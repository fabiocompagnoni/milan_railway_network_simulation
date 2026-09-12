package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes the track alignment of every network link as a CSV polyline
 * ({@code link_id,points} with {@code x y} pairs separated by {@code ;}, network CRS).
 * Consumed by the GUI map; regenerate whenever the network or the OSM snapshot changes.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.network.CreateLinkGeometry}
 * with optional args {@code [networkFile] [outputFile] [osmSnapshot...]}.
 */
public final class CreateLinkGeometry {

	private static final Logger log = LogManager.getLogger(CreateLinkGeometry.class);

	private static final String DEFAULT_NETWORK = "scenarios/milan/network.xml";
	private static final String DEFAULT_OUTPUT = "scenarios/milan/link-geometry.csv";
	private static final List<String> DEFAULT_SNAPSHOTS = List.of(
		"data/osm/2026-08-05-network-sweep/network_rail.json.gz",
		"data/osm/2026-09-09-gap-fill/gap_rail.json.gz");
	private static final String NETWORK_CRS = "EPSG:32632";

	private CreateLinkGeometry() {
	}

	public static void main(String[] args) {
		Path network = Path.of(args.length > 0 ? args[0] : DEFAULT_NETWORK);
		Path output = Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT);
		List<Path> snapshots = args.length > 2
			? Arrays.stream(args).skip(2).map(Path::of).toList()
			: DEFAULT_SNAPSHOTS.stream().map(Path::of).toList();
		run(network, snapshots, output);
	}

	static void run(Path networkFile, List<Path> snapshots, Path outputFile) {
		Network network = NetworkUtils.readNetwork(networkFile.toString());
		OsmRailWays osm = OsmRailWays.read(snapshots,
			TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, NETWORK_CRS));
		log.info("Loaded {} links and {} OSM ways", network.getLinks().size(), osm.ways().size());

		LinkGeometryBuilder builder = new LinkGeometryBuilder(osm);
		int straight = 0;
		try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
			writer.write("link_id,points\n");
			for (Link link : network.getLinks().values()) {
				List<Coord> polyline = builder.polyline(link);
				if (polyline.size() == 2) {
					straight++;
				}
				writer.write(link.getId() + "," + format(polyline) + "\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + outputFile, e);
		}
		log.info("Wrote {} ({} links drawn as straight segments)", outputFile, straight);
	}

	private static String format(List<Coord> polyline) {
		return polyline.stream()
			.map(point -> String.format(Locale.ROOT, "%.1f %.1f", point.getX(), point.getY()))
			.collect(Collectors.joining(";"));
	}
}
