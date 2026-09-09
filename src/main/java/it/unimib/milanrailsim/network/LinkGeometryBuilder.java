package it.unimib.milanrailsim.network;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Traces each network link along the OSM ways recorded in its {@code osmWayIds}
 * attribute, yielding the real track alignment for map rendering.
 * <p>
 * The ways of a link are fragments of the shortest rail path found by the OSM sweep,
 * stored in arbitrary direction and possibly touching sidings. The polyline is the
 * shortest path over those fragments between the OSM nodes nearest to the link ends.
 * Links without ways, or whose fragments do not connect, fall back to the straight
 * segment between their nodes.
 */
public final class LinkGeometryBuilder {

	private static final String WAY_IDS_ATTRIBUTE = "osmWayIds";

	private final OsmRailWays osm;

	public LinkGeometryBuilder(OsmRailWays osm) {
		this.osm = osm;
	}

	/** @throws IllegalArgumentException if the link references a way absent from the snapshot */
	public List<Coord> polyline(Link link) {
		List<Coord> straight = List.of(link.getFromNode().getCoord(), link.getToNode().getCoord());
		Object attribute = link.getAttributes().getAttribute(WAY_IDS_ATTRIBUTE);
		if (attribute == null || attribute.toString().isBlank()) {
			return straight;
		}
		Map<Long, Set<Long>> adjacency = adjacency(attribute.toString(), link);
		long start = nearest(adjacency.keySet(), link.getFromNode().getCoord());
		long end = nearest(adjacency.keySet(), link.getToNode().getCoord());
		List<Coord> traced = shortestPath(adjacency, start, end);
		return traced.isEmpty() ? straight : traced;
	}

	private Map<Long, Set<Long>> adjacency(String wayIds, Link link) {
		Map<Long, Set<Long>> adjacency = new HashMap<>();
		for (String token : wayIds.trim().split("\\s+")) {
			List<Long> refs = osm.ways().get(Long.parseLong(token));
			if (refs == null) {
				throw new IllegalArgumentException("Link " + link.getId() + " references unknown OSM way " + token);
			}
			for (int i = 1; i < refs.size(); i++) {
				adjacency.computeIfAbsent(refs.get(i - 1), key -> new HashSet<>()).add(refs.get(i));
				adjacency.computeIfAbsent(refs.get(i), key -> new HashSet<>()).add(refs.get(i - 1));
			}
		}
		return adjacency;
	}

	private long nearest(Set<Long> candidates, Coord target) {
		return candidates.stream()
			.min(Comparator.comparingDouble(node -> CoordUtils.calcEuclideanDistance(coord(node), target)))
			.orElseThrow();
	}

	private List<Coord> shortestPath(Map<Long, Set<Long>> adjacency, long start, long end) {
		Map<Long, Double> distance = new HashMap<>(Map.of(start, 0.0));
		Map<Long, Long> previous = new HashMap<>();
		PriorityQueue<Long> queue = new PriorityQueue<>(Comparator.comparingDouble(distance::get));
		queue.add(start);
		while (!queue.isEmpty()) {
			long current = queue.poll();
			if (current == end) {
				return backtrack(previous, end);
			}
			for (long next : adjacency.get(current)) {
				double candidate = distance.get(current) + CoordUtils.calcEuclideanDistance(coord(current), coord(next));
				if (candidate < distance.getOrDefault(next, Double.POSITIVE_INFINITY)) {
					distance.put(next, candidate);
					previous.put(next, current);
					queue.remove(next);
					queue.add(next);
				}
			}
		}
		return List.of();
	}

	private List<Coord> backtrack(Map<Long, Long> previous, long end) {
		List<Coord> path = new ArrayList<>();
		for (Long node = end; node != null; node = previous.get(node)) {
			path.add(coord(node));
		}
		return List.copyOf(path.reversed());
	}

	private Coord coord(long node) {
		Coord coord = osm.nodes().get(node);
		if (coord == null) {
			throw new IllegalStateException("OSM node " + node + " referenced by a way is missing from the snapshot");
		}
		return coord;
	}
}
