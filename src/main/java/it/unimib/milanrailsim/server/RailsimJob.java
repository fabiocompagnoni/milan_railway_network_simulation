package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.StationTracks;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.Sidings;
import it.unimib.milanrailsim.railsim.RailsimSetup;
import it.unimib.milanrailsim.results.AnalyzeRun;
import it.unimib.milanrailsim.results.EnergyMeter;
import it.unimib.milanrailsim.results.EnergyModel;
import it.unimib.milanrailsim.results.PunctualityAnalysis;
import it.unimib.milanrailsim.results.RunArchive;
import it.unimib.milanrailsim.results.RunData;
import it.unimib.milanrailsim.results.RunOutcome;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.schedule.CreateTransitScheduleFromFeed;
import it.unimib.milanrailsim.schedule.AddedTrips;
import it.unimib.milanrailsim.schedule.DensificationPlan;
import it.unimib.milanrailsim.schedule.LineAssignments;
import it.unimib.milanrailsim.schedule.LineUpgrade;
import it.unimib.milanrailsim.schedule.LineUpgradePlan;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;
import it.unimib.milanrailsim.schedule.SchedulePipeline;
import it.unimib.milanrailsim.schedule.TimetableDensifier;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.Protocol.Summary;
import it.unimib.milanrailsim.server.SimulationServer.Emitter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One run end to end: timetable of the requested day, railsim simulation
 * streaming frames, then analysis into the run folder. The progress marker is
 * touched every simulated minute so a dead process is recognisable.
 */
public final class RailsimJob implements SimulationServer.Job {

	/** Simulated seconds between frames; the client extrapolates in between. */
	public static final double FRAME_INTERVAL_S = 5;
	private static final double PROGRESS_INTERVAL_S = 60;
	/** Grace after the last planned arrival for late trains to finish before the run is cut. */
	static final double END_MARGIN_S = 3 * 3600;
	private static final String SPACE_TIME_LINE = "S1";
	/** Phase name of the simulated day itself; the client tells it from the preparation and wrap-up phases. */
	public static final String SIMULATING = "Simulazione";
	/** The plan of a high-frequency run, copied into its folder when the run is launched. */
	public static final String DENSIFICATION_PLAN = "densification.json";
	/** The plan of a line upgrade run, copied into its folder when the run is launched. */
	public static final String LINE_UPGRADE_PLAN = "line-upgrade.json";
	/** The copy of the energy model the run's consumption is computed with. */
	public static final String ENERGY_MODEL = "energy-model.json";
	public static final String ADDED_TRIPS = "added_trips.csv";
	public static final String SKIPPED_TRIPS = "skipped_trips.csv";
	public static final String TUNNEL_WARNINGS = "tunnel_warnings.csv";
	public static final String UNSCHEDULED_ADDED_TRIPS = "unscheduled_added_trips.csv";

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
		Instant started = Instant.now();
		Path marker = inputs.runDir().resolve(RunLibrary.PROGRESS_MARKER);
		touch(marker);
		ScenarioSpec spec = ScenarioSpec.read(inputs.runDir().resolve("scenario.json"));

		out.send(Message.progress(0, 0, "Genero l'orario"));
		Path scenarioDir = inputs.runDir().resolve("scenario");
		TransitSchedule timetable = generateTimetable(spec, scenarioDir);

		out.send(Message.progress(0, 0, "Carico la rete e l'orario"));
		Path output = inputs.runDir().resolve("output");
		FrameSampler sampler;
		RunData data;
		try (FrameRecorder recorder = new FrameRecorder(inputs.runDir().resolve(FrameRecorder.FILE_NAME))) {
			sampler = new FrameSampler(FRAME_INTERVAL_S, tripsPerVehicle(timetable), frame -> {
				recorder.record(frame);
				out.send(Message.frame(frame));
			});
			data = simulate(scenarioDir, output, sampler, pacer, out, marker);
		}

		double simulatedEnd = sampler.simulatedTime();
		Summary summary = new Summary(sampler.arrivedTrains(), sampler.abortedTrains(), sampler.activeTrains(), simulatedEnd);
		RunOutcome outcome = new RunOutcome(summary.arrived(), summary.aborted(), summary.stalled(), simulatedEnd,
			Duration.between(started, Instant.now()));
		AnalyzeRun.analyze(new AnalyzeRun.Request(data, RunArchive.at(inputs.runDir()),
			spec.type().name().toLowerCase(), inputs.costsFile(), SPACE_TIME_LINE, outcome, phase -> {
				out.send(Message.progress(0, 0, phase));
				touch(marker);
			}));
		Files.deleteIfExists(marker);
		out.send(Message.done(inputs.runDir().toString(), summary));
	}

	private static Map<String, Integer> tripsPerVehicle(TransitSchedule timetable) {
		Map<String, Integer> trips = new HashMap<>();
		for (TransitLine line : timetable.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					trips.merge(departure.getVehicleId().toString(), 1, Integer::sum);
				}
			}
		}
		return trips;
	}

	private TransitSchedule generateTimetable(ScenarioSpec spec, Path scenarioDir) {
		FleetConfig fleet = Files.exists(inputs.fleetFile()) ? FleetConfig.read(inputs.fleetFile()) : FleetConfig.defaults();
		LineAssignments assignments = Files.exists(inputs.assignmentsFile())
			? LineAssignments.read(inputs.assignmentsFile()) : LineAssignments.defaults();
		int start = spec.window() == null ? Integer.MIN_VALUE : spec.window().start().toSecondOfDay();
		int end = spec.window() == null ? Integer.MAX_VALUE : spec.window().end().toSecondOfDay();
		StationTracks tracks = inputs.stationTracksFile() != null && Files.exists(inputs.stationTracksFile())
			? StationTracks.read(inputs.stationTracksFile()) : StationTracks.empty();
		boolean hasNodes = inputs.microNodesDir() != null && Files.isDirectory(inputs.microNodesDir());
		List<MicroNode> microNodes = hasNodes ? MicroNode.readAll(inputs.microNodesDir()) : List.of();
		Path sidingsFile = hasNodes ? inputs.microNodesDir().resolve(CreateTransitScheduleFromFeed.SIDINGS_FILE) : null;
		Sidings sidings = sidingsFile != null && Files.exists(sidingsFile) ? Sidings.read(sidingsFile) : Sidings.none();
		GtfsFeed feed = timetableOf(spec, scenarioDir);
		TransitSchedule schedule = new SchedulePipeline(feed, inputs.engineNetwork(), fleet,
			new RouteVehicleAssignment(assignments), tracks, microNodes, sidings).generate(spec.serviceDate(), start, end, scenarioDir);
		if (spec.type() != ScenarioSpec.SimulationType.REAL) {
			write(scenarioDir.resolve(UNSCHEDULED_ADDED_TRIPS), unscheduledAddedTrips(feed, schedule, start, end));
		}
		return schedule;
	}

	/**
	 * The published timetable with the trips of the scenario added to it. The
	 * plan is the copy the application left in the run folder, so the run
	 * records what it was generated from; what the generator did is written
	 * next to the generated scenario.
	 */
	private GtfsFeed timetableOf(ScenarioSpec spec, Path scenarioDir) {
		GtfsFeed published = GtfsFeed.load(inputs.gtfsDir());
		return switch (spec.type()) {
			case REAL -> published;
			case METRO_LIKE -> densified(published, spec, scenarioDir);
			case LINE_UPGRADE -> upgraded(published, spec, scenarioDir);
		};
	}

	private GtfsFeed densified(GtfsFeed published, ScenarioSpec spec, Path scenarioDir) {
		DensificationPlan plan = DensificationPlan.read(inputs.runDir().resolve(DENSIFICATION_PLAN));
		TimetableDensifier.Densified densified = new TimetableDensifier(plan)
			.densify(published, spec.serviceDate(), spec.metroHeadwayMinutes());
		write(scenarioDir.resolve(ADDED_TRIPS), densified.report().addedCsv());
		write(scenarioDir.resolve(SKIPPED_TRIPS), densified.report().skippedCsv());
		return densified.feed();
	}

	private GtfsFeed upgraded(GtfsFeed published, ScenarioSpec spec, Path scenarioDir) {
		LineUpgradePlan plan = LineUpgradePlan.read(inputs.runDir().resolve(LINE_UPGRADE_PLAN));
		LineUpgrade.Upgraded upgraded = new LineUpgrade(plan).upgrade(published, spec.serviceDate(), spec.routeTargets());
		write(scenarioDir.resolve(ADDED_TRIPS), upgraded.report().addedCsv());
		write(scenarioDir.resolve(TUNNEL_WARNINGS), upgraded.report().tunnelWarningsCsv());
		return upgraded.feed();
	}

	/**
	 * Added trips of the simulated window that the timetable left out, as it
	 * does with every trip calling at a station the network does not model:
	 * the scenario runs without them, and the file says so.
	 */
	private static List<String> unscheduledAddedTrips(GtfsFeed feed, TransitSchedule schedule, int start, int end) {
		Set<String> departures = new HashSet<>();
		schedule.getTransitLines().values().forEach(line -> line.getRoutes().values()
			.forEach(route -> route.getDepartures().keySet().forEach(id -> departures.add(id.toString()))));
		List<String> lines = new ArrayList<>();
		lines.add("trip");
		feed.stopTimesByTripId().forEach((trip, calls) -> {
			int departure = calls.getFirst().departureSeconds();
			if (AddedTrips.isAdded(trip) && departure >= start && departure <= end && !departures.contains(trip)) {
				lines.add(trip);
			}
		});
		return lines;
	}

	private static void write(Path file, List<String> lines) {
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + file, e);
		}
	}

	/**
	 * Runs the mobsim until every train has finished (see
	 * {@link FinishedTrainRetirement}), or at the latest {@link #END_MARGIN_S}
	 * after the last planned arrival of the timetable: the day is over when the
	 * timetable is, and what is still moving then is late, not scheduled.
	 *
	 * @return the simulated scenario with the punctuality measured on its events
	 */
	private RunData simulate(Path scenarioDir, Path output, FrameSampler sampler, Pacer pacer, Emitter out, Path marker) {
		Config config = ConfigUtils.loadConfig(inputs.configTemplate().toString());
		config.network().setInputFile(scenarioDir.resolve("network-with-stations.xml").toAbsolutePath().toString());
		config.transit().setTransitScheduleFile(scenarioDir.resolve("transitSchedule.xml").toAbsolutePath().toString());
		config.transit().setVehiclesFile(scenarioDir.resolve("transitVehicles.xml").toAbsolutePath().toString());
		config.controller().setOutputDirectory(output.toAbsolutePath().toString());
		config.controller().setRunId(inputs.runDir().getFileName().toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		// everything the analysis needs is measured in memory while the day runs: the events file
		// (hundreds of MB, written twice) and the end-of-run dump of the inputs would go unread
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setDumpDataAtEnd(false);
		config.controller().setCreateGraphsInterval(0);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		double endTime = lastPlannedArrival(scenario.getTransitSchedule()) + END_MARGIN_S;
		config.qsim().setEndTime(endTime);
		Controler controler = new Controler(scenario);
		RailsimSetup.install(controler);
		PunctualityAnalysis punctuality = new PunctualityAnalysis(scenario.getTransitSchedule());
		Optional<EnergyMeter> energy = energyMeter(scenario);
		MobsimAfterSimStepListener step = new MobsimAfterSimStepListener() {
			private double nextProgress;

			@Override
			public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent event) {
				double time = event.getSimulationTime();
				sampler.onSimStep(time);
				energy.ifPresent(meter -> meter.onSimStep(time));
				if (time >= nextProgress) {
					nextProgress = time + PROGRESS_INTERVAL_S;
					out.send(Message.progress(time, sampler.activeTrains(), SIMULATING));
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
		MobsimInitializedListener mobsimReady = event -> out.send(Message.progress(0, 0, SIMULATING));
		AfterMobsimListener mobsimDone = event -> out.send(Message.progress(0, 0, "Scrittura dei risultati"));
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(sampler);
				addEventHandlerBinding().toInstance(punctuality);
				energy.ifPresent(meter -> addEventHandlerBinding().toInstance(meter));
				addMobsimListenerBinding().toInstance(mobsimReady);
				addMobsimListenerBinding().toInstance(step);
				addMobsimListenerBinding().toInstance(new FinishedTrainRetirement(sampler));
				addControllerListenerBinding().toInstance(mobsimDone);
			}
		});
		controler.run();
		return new RunData(scenario.getTransitSchedule(), scenario.getTransitVehicles(), scenario.getNetwork(), punctuality,
			energy.map(EnergyMeter::use), output);
	}

	/**
	 * The meter of the run's energy, with the model the application left in
	 * the run folder; empty for a run started without one.
	 */
	private Optional<EnergyMeter> energyMeter(Scenario scenario) {
		Path modelFile = inputs.runDir().resolve(ENERGY_MODEL);
		if (!Files.exists(modelFile)) {
			return Optional.empty();
		}
		FleetConfig fleet = Files.exists(inputs.fleetFile()) ? FleetConfig.read(inputs.fleetFile()) : FleetConfig.defaults();
		Set<String> diesel = new HashSet<>();
		fleet.types().stream().filter(type -> type.traction() == FleetConfig.Traction.DIESEL)
			.forEach(type -> diesel.add(type.id()));
		LocalDate day = ScenarioSpec.read(inputs.runDir().resolve("scenario.json")).serviceDate();
		return Optional.of(new EnergyMeter(EnergyModel.read(modelFile), day, scenario.getTransitSchedule(),
			scenario.getTransitVehicles(), scenario.getNetwork(), diesel::contains, FRAME_INTERVAL_S));
	}

	/** Departure time plus the last stop's arrival offset, over every departure of the schedule. */
	static double lastPlannedArrival(TransitSchedule schedule) {
		double last = 0;
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				double lastOffset = route.getStops().getLast().getArrivalOffset().orElse(0);
				for (Departure departure : route.getDepartures().values()) {
					last = Math.max(last, departure.getDepartureTime() + lastOffset);
				}
			}
		}
		return last;
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
