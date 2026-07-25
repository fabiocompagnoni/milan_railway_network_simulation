package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.io.NetworkWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Extracts the provisional railsim network skeleton from the Trenord GTFS feed.
 * See the network specification (docs/docs_matsim/network-specification.md) for
 * the railsim link attributes and the design spec for what "provisional" means.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.network.CreateNetworkSkeletonFromGtfs}
 * with optional args {@code [gtfsDir] [serviceDate] [outputFile]}.
 */
public final class CreateNetworkSkeletonFromGtfs {

	private static final Logger log = LogManager.getLogger(CreateNetworkSkeletonFromGtfs.class);

	private static final String DEFAULT_GTFS_DIR = "orari_trenord";
	private static final String DEFAULT_SERVICE_DATE = "2026-09-16";
	private static final String DEFAULT_OUTPUT = "scenarios/milan/network-skeleton.xml";

	private CreateNetworkSkeletonFromGtfs() {
	}

	public static void main(String[] args) {
		Path gtfsDir = Path.of(args.length > 0 ? args[0] : DEFAULT_GTFS_DIR);
		LocalDate serviceDate = LocalDate.parse(args.length > 1 ? args[1] : DEFAULT_SERVICE_DATE);
		Path outputFile = Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT);
		run(gtfsDir, serviceDate, outputFile);
	}

	static void run(Path gtfsDir, LocalDate serviceDate, Path outputFile) {
		GtfsFeed feed = GtfsFeed.load(gtfsDir);
		log.info("Loaded GTFS feed from {}: {} stops, {} routes, {} trips",
			gtfsDir, feed.stopsById().size(), feed.routesById().size(), feed.tripsById().size());

		Network network = new NetworkSkeletonBuilder(feed, serviceDate).build();

		try {
			Files.createDirectories(outputFile.toAbsolutePath().getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output directory for " + outputFile, e);
		}
		new NetworkWriter(network).write(outputFile.toString());
		log.info("Service date {}: wrote {} stations and {} provisional links to {}",
			serviceDate, network.getNodes().size(), network.getLinks().size(), outputFile);
	}
}
