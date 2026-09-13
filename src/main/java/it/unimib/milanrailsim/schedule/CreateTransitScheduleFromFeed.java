package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.StationTracks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Generates the transit schedule artifacts for a service date with the default
 * fleet and line assignments: the schedule, one vehicle per circulation, and
 * the network enriched with station links.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.schedule.CreateTransitScheduleFromFeed}
 * with optional args {@code [gtfsDir] [networkFile] [serviceDate] [outputDir]}.
 */
public final class CreateTransitScheduleFromFeed {

	private static final String DEFAULT_GTFS_DIR = "orari_trenord";
	private static final String DEFAULT_NETWORK = "scenarios/milan/network.xml";
	private static final String DEFAULT_SERVICE_DATE = "2026-09-16";
	private static final String DEFAULT_OUTPUT_DIR = "scenarios/milan";
	/** Local survey of terminal platform tracks; the generated network carries its values. */
	public static final Path STATION_TRACKS = Path.of("docs", "network", "misure", "capolinea-binari.csv");

	private CreateTransitScheduleFromFeed() {
	}

	public static void main(String[] args) {
		Path gtfsDir = Path.of(args.length > 0 ? args[0] : DEFAULT_GTFS_DIR);
		Path networkFile = Path.of(args.length > 1 ? args[1] : DEFAULT_NETWORK);
		LocalDate serviceDate = LocalDate.parse(args.length > 2 ? args[2] : DEFAULT_SERVICE_DATE);
		Path outputDir = Path.of(args.length > 3 ? args[3] : DEFAULT_OUTPUT_DIR);
		run(gtfsDir, networkFile, serviceDate, outputDir);
	}

	static void run(Path gtfsDir, Path networkFile, LocalDate serviceDate, Path outputDir) {
		StationTracks tracks = Files.exists(STATION_TRACKS) ? StationTracks.read(STATION_TRACKS) : StationTracks.empty();
		new SchedulePipeline(GtfsFeed.load(gtfsDir), networkFile, FleetConfig.defaults(), RouteVehicleAssignment.defaults(), tracks)
			.generate(serviceDate, Integer.MIN_VALUE, Integer.MAX_VALUE, outputDir);
	}
}
