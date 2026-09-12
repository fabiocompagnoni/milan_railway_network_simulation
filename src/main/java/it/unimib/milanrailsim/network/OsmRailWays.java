package it.unimib.milanrailsim.network;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Rail ways of the archived Overpass snapshot: node coordinates (already in the
 * network CRS) and the ordered node references of every way.
 */
public record OsmRailWays(Map<Long, Coord> nodes, Map<Long, List<Long>> ways) {

	/**
	 * Streams the snapshots (optionally gzipped) in order; a later snapshot
	 * overrides elements of an earlier one, so gap fills win over the base sweep.
	 */
	public static OsmRailWays read(List<Path> snapshots, CoordinateTransformation toNetworkCrs) {
		Map<Long, Coord> nodes = new HashMap<>();
		Map<Long, List<Long>> ways = new HashMap<>();
		for (Path snapshot : snapshots) {
			try (InputStream input = open(snapshot); JsonParser parser = new JsonFactory().createParser(input)) {
				while (parser.nextToken() != null) {
					if (parser.currentToken() == JsonToken.FIELD_NAME && "elements".equals(parser.currentName())) {
						parser.nextToken();
						while (parser.nextToken() == JsonToken.START_OBJECT) {
							readElement(parser, toNetworkCrs, nodes, ways);
						}
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot read OSM snapshot " + snapshot, e);
			}
		}
		return new OsmRailWays(Map.copyOf(nodes), Map.copyOf(ways));
	}

	private static InputStream open(Path snapshot) throws IOException {
		InputStream input = Files.newInputStream(snapshot);
		return snapshot.toString().endsWith(".gz") ? new GZIPInputStream(input) : input;
	}

	private static void readElement(JsonParser parser, CoordinateTransformation toNetworkCrs,
			Map<Long, Coord> nodes, Map<Long, List<Long>> ways) throws IOException {
		String type = null;
		long id = 0;
		double lat = Double.NaN;
		double lon = Double.NaN;
		List<Long> nodeRefs = null;
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			String field = parser.currentName();
			parser.nextToken();
			switch (field) {
				case "type" -> type = parser.getText();
				case "id" -> id = parser.getLongValue();
				case "lat" -> lat = parser.getDoubleValue();
				case "lon" -> lon = parser.getDoubleValue();
				case "nodes" -> nodeRefs = readLongArray(parser);
				default -> parser.skipChildren();
			}
		}
		if ("node".equals(type)) {
			nodes.put(id, toNetworkCrs.transform(new Coord(lon, lat)));
		} else if ("way".equals(type) && nodeRefs != null) {
			ways.put(id, nodeRefs);
		}
	}

	private static List<Long> readLongArray(JsonParser parser) throws IOException {
		List<Long> values = new ArrayList<>();
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			values.add(parser.getLongValue());
		}
		return List.copyOf(values);
	}
}
