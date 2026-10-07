package it.unimib.milanrailsim.results;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What the analysis of a run works on: the simulated scenario and the
 * punctuality measured on its events. The application driving a run has all
 * of it in memory when the mobsim ends, so the run leaves no events file
 * behind; a MATSim output folder produced elsewhere is read back instead.
 *
 * @param energy    the energy measured while the run was simulated; empty for a run read back from its output
 * @param outputDir the MATSim output folder, holding the railsim time-distance file
 */
public record RunData(TransitSchedule schedule, Vehicles vehicles, Network network, PunctualityAnalysis punctuality,
		Optional<EnergyLedger.Use> energy, Path outputDir) {

	/** Reads the output files of a completed run and replays its events through the punctuality analysis. */
	public static RunData fromOutput(Path outputDir) {
		Scenario matsim = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(matsim).readFile(locate(outputDir, "*.output_transitSchedule.xml*").toString());
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(locate(outputDir, "*.output_transitVehicles.xml*").toString());
		Network network = NetworkUtils.readNetwork(locate(outputDir, "*.output_network.xml*").toString());
		PunctualityAnalysis punctuality = new PunctualityAnalysis(matsim.getTransitSchedule());
		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler(punctuality);
		new MatsimEventsReader(events).readFile(locate(iterationDir(outputDir), "*.events.xml*").toString());
		return new RunData(matsim.getTransitSchedule(), vehicles, network, punctuality, Optional.empty(), outputDir);
	}

	/** The time-distance trajectories railsim writes at the end of the iteration. */
	public Path timeDistanceCsv() {
		return locate(iterationDir(outputDir), "*railsimTimeDistance.csv*");
	}

	private static Path iterationDir(Path outputDir) {
		return outputDir.resolve("ITERS").resolve("it.0");
	}

	private static Path locate(Path dir, String glob) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(file -> dir.getFileSystem().getPathMatcher("glob:" + glob).matches(file.getFileName()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No file matching " + glob + " in " + dir));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list " + dir, e);
		}
	}
}