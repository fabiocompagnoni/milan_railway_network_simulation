package it.unimib.milanrailsim.network;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Traces each network link along the OSM ways recorded in its {@code osmWayIds}
 * attribute, yielding the real track alignment for map rendering.
 * <p>
 * The ways of a link are fragments of the shortest rail path found by the OSM sweep,
 * stored in arbitrary direction and possibly touching sidings. The polyline is the
 * shortest path over those fragments between the two station anchors. A station's
 * anchor is the OSM node within {@value #ANCHOR_RADIUS_M} m shared by the most
 * incident links (nearest to the station among ties), so all links meeting there
 * end at the same point on the through tracks; a link whose own track misses the anchor is joined to it by a short connector. Links without ways, or whose fragments do
 * not connect, fall back to the straight segment between their nodes.
 */
public final class LinkGeometryBuilder {

	private static final String WAY_IDS_ATTRIBUTE = "osmWayIds";
	/** Station points lie up to ~150 m off the tracks; beyond this an OSM node is not the station. */
	private static final double ANCHOR_RADIUS_M = 300;

	private final OsmRailWays osm;
	private final Map<Node, Long> anchors = new HashMap<>();

	public LinkGeometryBuilder(OsmRailWays osm, Network network) {
		this.osm = osm;
		network.getNodes().values().forEach(node -> anchor(node).ifPresent(anchor -> anchors.put(node, anchor)));
	}

	/** @throws IllegalArgumentException if the link references a way absent from the snapshot */
	public List<Coord> polyline(Link link) {
		List<Coord> straight = List.of(link.getFromNode().getCoord(), link.getToNode().getCoord());
		Map<Long, Set<Long>> adjacency = adjacency(wayIds(link));
		if (adjacency.isEmpty()) {
			return straight;
		}
		long start = endpoint(adjacency, link.getFromNode());
		long end = endpoint(adjacency, link.getToNode());
		List<Coord> traced = shortestPath(adjacency, start, end);
		if (traced.isEmpty()) {
			return straight;
		}
		List<Coord> joined = new ArrayList<>();
		Long fromAnchor = anchors.get(link.getFromNode());
		Long toAnchor = anchors.get(link.getToNode());
		if (fromAnchor != null && fromAnchor != start) {
			joined.add(coord(fromAnchor));
		}
		joined.addAll(traced);
		if (toAnchor != null && toAnchor != end) {
			joined.add(coord(toAnchor));
		}
		return List.copyOf(joined);
	}

	private Optional<Long> anchor(Node node) {
		Map<Long, Long> linksThrough = Stream.concat(node.getInLinks().values().stream(), node.getOutLinks().values().stream())
			.flatMap(link -> adjacency(wayIds(link)).keySet().stream())
			.collect(Collectors.groupingBy(osmNode -> osmNode, Collectors.counting()));
		return linksThrough.entrySet().stream()
			.filter(entry -> CoordUtils.calcEuclideanDistance(coord(entry.getKey()), node.getCoord()) <= ANCHOR_RADIUS_M)
			.max(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue)
				.thenComparing(entry -> -CoordUtils.calcEuclideanDistance(coord(entry.getKey()), node.getCoord())))
			.map(Map.Entry::getKey);
	}

	/** The station anchor when the link's own track reaches it, else the track node nearest to the station. */
	private long endpoint(Map<Long, Set<Long>> adjacency, Node station) {
		Long anchor = anchors.get(station);
		return anchor != null && adjacency.containsKey(anchor) ? anchor : nearest(adjacency.keySet(), station.getCoord());
	}

	private List<Long> wayIds(Link link) {
		Object attribute = link.getAttributes().getAttribute(WAY_IDS_ATTRIBUTE);
		if (attribute == null || attribute.toString().isBlank()) {
			return List.of();
		}
		List<Long> ids = new ArrayList<>();
		for (String token : attribute.toString().trim().split("\\s+")) {
			long id = Long.parseLong(token);
			if (!osm.ways().containsKey(id)) {
				throw new IllegalArgumentException("Link " + link.getId() + " references unknown OSM way " + token);
			}
			ids.add(id);
		}
		return ids;
	}

	private Map<Long, Set<Long>> adjacency(List<Long> wayIds) {
		Map<Long, Set<Long>> adjacency = new HashMap<>();
		for (long wayId : wayIds) {
			List<Long> refs = osm.ways().get(wayId);
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
