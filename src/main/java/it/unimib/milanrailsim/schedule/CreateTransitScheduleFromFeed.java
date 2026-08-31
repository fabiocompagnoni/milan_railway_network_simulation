package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Generates the transit schedule artifacts for a service date: the schedule,
 * one vehicle per departure, and the network enriched with station links.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.schedule.CreateTransitScheduleFromFeed}
 * with optional args {@code [gtfsDir] [networkFile] [serviceDate] [outputDir]}.
 */
public final class CreateTransitScheduleFromFeed {

	private static final Logger log = LogManager.getLogger(CreateTransitScheduleFromFeed.class);

	private static final String DEFAULT_GTFS_DIR = "orari_trenord";
	private static final String DEFAULT_NETWORK = "scenarios/milan/network.xml";
	private static final String DEFAULT_SERVICE_DATE = "2026-09-16";
	private static final String DEFAULT_OUTPUT_DIR = "scenarios/milan";

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
		Network network = NetworkUtils.readNetwork(networkFile.toString());
		GtfsFeed feed = GtfsFeed.load(gtfsDir);
		TransitScheduleBuilder.Result result = new TransitScheduleBuilder(
			feed, network, serviceDate, new RouteVehicleAssignment()).build();

		try {
			Files.createDirectories(outputDir);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output directory " + outputDir, e);
		}
		new TransitScheduleWriter(result.schedule())
			.writeFile(outputDir.resolve("transitSchedule.xml").toString());
		new MatsimVehicleWriter(result.vehicles())
			.writeFile(outputDir.resolve("transitVehicles.xml").toString());
		new NetworkWriter(network).write(outputDir.resolve("network-with-stations.xml").toString());

		int routes = result.schedule().getTransitLines().values().stream()
			.mapToInt(line -> line.getRoutes().size()).sum();
		int departures = result.schedule().getTransitLines().values().stream()
			.flatMap(line -> line.getRoutes().values().stream())
			.mapToInt(route -> route.getDepartures().size()).sum();
		log.info("Service date {}: {} lines, {} routes, {} departures, {} vehicles written to {}",
			serviceDate, result.schedule().getTransitLines().size(), routes, departures,
			result.vehicles().getVehicles().size(), outputDir);
	}
}
