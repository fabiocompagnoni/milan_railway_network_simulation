package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.StationTracks;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.railsim.RailsimSetup;
import it.unimib.milanrailsim.results.AnalyzeRun;
import it.unimib.milanrailsim.results.RunArchive;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.schedule.LineAssignments;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;
import it.unimib.milanrailsim.schedule.SchedulePipeline;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.SimulationServer.Emitter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One run end to end: timetable of the requested day, railsim simulation
 * streaming frames, then analysis into the run folder. The progress marker is
 * touched every simulated minute so a dead process is recognisable.
 */
public final class RailsimJob implements SimulationServer.Job {

	/** Simulated seconds between frames; the client extrapolates in between. */
	public static final double FRAME_INTERVAL_S = 5;
	private static final double PROGRESS_INTERVAL_S = 60;
	private static final String SPACE_TIME_LINE = "S1";

	/**
	 * Where the engine finds its inputs; the run folder holds {@code scenario.json}.
	 *
	 * @param stationTracksFile survey of terminal platform tracks, or null to size stations from their sections
	 */
	/**
	 * @param engineNetwork the network with the micro nodes spliced in
	 * @param microNodesDir the node declarations that were spliced, or null for a purely mesoscopic network
	 */
	public record Inputs(Path runDir, Path configTemplate, Path engineNetwork, Path gtfsDir, Path fleetFile,
			Path assignmentsFile, Path costsFile, Path stationTracksFile, Path microNodesDir) {
	}

	/** Thrown by the step listener to leave the mobsim when the client asked to stop. */
	static final class StoppedException extends RuntimeException {
		StoppedException() {
			super("Simulazione interrotta");
		}
	}

	private final Inputs inputs;

	public RailsimJob(Inputs inputs) {
		this.inputs = inputs;
	}

	@Override
	public void run(Pacer pacer, Emitter out) throws Exception {
		Path marker = inputs.runDir().resolve(RunLibrary.PROGRESS_MARKER);
		touch(marker);
		ScenarioSpec spec = ScenarioSpec.read(inputs.runDir().resolve("scenario.json"));

		out.send(Message.progress(0, 0, "Genero l'orario"));
		Path scenarioDir = inputs.runDir().resolve("scenario");
		generateTimetable(spec, scenarioDir);

		out.send(Message.progress(0, 0, "Simulazione"));
		Path output = inputs.runDir().resolve("output");
		FrameSampler sampler;
		try (FrameRecorder recorder = new FrameRecorder(inputs.runDir().resolve(FrameRecorder.FILE_NAME))) {
			sampler = new FrameSampler(FRAME_INTERVAL_S, frame -> {
				recorder.record(frame);
				out.send(Message.frame(frame));
			});
			simulate(scenarioDir, output, sampler, pacer, out, marker);
		}

		out.send(Message.progress(0, 0, "Analisi"));
		AnalyzeRun.analyze(output, RunArchive.at(inputs.runDir()), spec.type().name().toLowerCase(),
			inputs.costsFile(), SPACE_TIME_LINE);
		Files.deleteIfExists(marker);
		out.send(Message.done(inputs.runDir().toString()));
	}

	private void generateTimetable(ScenarioSpec spec, Path scenarioDir) {
		FleetConfig fleet = Files.exists(inputs.fleetFile()) ? FleetConfig.read(inputs.fleetFile()) : FleetConfig.defaults();
		LineAssignments assignments = Files.exists(inputs.assignmentsFile())
			? LineAssignments.read(inputs.assignmentsFile()) : LineAssignments.defaults();
		int start = spec.window() == null ? Integer.MIN_VALUE : spec.window().start().toSecondOfDay();
		int end = spec.window() == null ? Integer.MAX_VALUE : spec.window().end().toSecondOfDay();
		StationTracks tracks = inputs.stationTracksFile() != null && Files.exists(inputs.stationTracksFile())
			? StationTracks.read(inputs.stationTracksFile()) : StationTracks.empty();
		List<MicroNode> microNodes = inputs.microNodesDir() != null && Files.isDirectory(inputs.microNodesDir())
			? MicroNode.readAll(inputs.microNodesDir()) : List.of();
		new SchedulePipeline(GtfsFeed.load(inputs.gtfsDir()), inputs.engineNetwork(), fleet,
			new RouteVehicleAssignment(assignments), tracks, microNodes).generate(spec.serviceDate(), start, end, scenarioDir);
	}

	private void simulate(Path scenarioDir, Path output, FrameSampler sampler, Pacer pacer, Emitter out, Path marker) {
		Config config = ConfigUtils.loadConfig(inputs.configTemplate().toString());
		config.network().setInputFile(scenarioDir.resolve("network-with-stations.xml").toAbsolutePath().toString());
		config.transit().setTransitScheduleFile(scenarioDir.resolve("transitSchedule.xml").toAbsolutePath().toString());
		config.transit().setVehiclesFile(scenarioDir.resolve("transitVehicles.xml").toAbsolutePath().toString());
		config.controller().setOutputDirectory(output.toAbsolutePath().toString());
		config.controller().setRunId(inputs.runDir().getFileName().toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Controler controler = new Controler(scenario);
		RailsimSetup.install(controler);
		MobsimAfterSimStepListener step = new MobsimAfterSimStepListener() {
			private double nextProgress;

			@Override
			public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent event) {
				double time = event.getSimulationTime();
				sampler.onSimStep(time);
				if (time >= nextProgress) {
					nextProgress = time + PROGRESS_INTERVAL_S;
					out.send(Message.progress(time, sampler.activeTrains(), "Simulazione"));
					touch(marker);
				}
				if (out.stopRequested()) {
					throw new StoppedException();
				}
				try {
					pacer.afterSimStep(time);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new StoppedException();
				}
			}
		};
		IterationEndsListener mobsimDone = event -> out.send(Message.progress(0, 0, "Scrittura dei risultati"));
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(sampler);
				addMobsimListenerBinding().toInstance(step);
				addControllerListenerBinding().toInstance(mobsimDone);
			}
		});
		controler.run();
	}

	private static void touch(Path marker) {
		try {
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, String.valueOf(System.currentTimeMillis()));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot touch " + marker, e);
		}
	}
}
