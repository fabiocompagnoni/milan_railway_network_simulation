package it.unimib.milanrailsim.network;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * How long a route is and how much of it runs on single track, measured on
 * the mesoscopic network the simulation is built from: nodes are the stops,
 * each section carries the {@code tracksTotal} of its OSM measure. Between two
 * consecutive stops the route follows the shortest path, as the timetable
 * builder routes trains.
 */
public final class RouteTracks {

	private static final String TRACKS_TOTAL = "tracksTotal";

	/**
	 * @param unmeasuredMetres length of the sections whose track count is not measured, neither single nor double
	 */
	public record Length(double totalMetres, double singleTrackMetres, double unmeasuredMetres) {
	}

	private final Network network;
	private final Map<List<String>, Optional<List<Link>>> paths = new HashMap<>();

	public RouteTracks(Network network) {
		this.network = network;
	}

	/**
	 * @param stops the stop ids of a trip of the route, in the order it calls at them
	 * @return empty when a stop is not in the network or two stops are not connected
	 */
	public Optional<Length> of(List<String> stops) {
		double total = 0;
		double single = 0;
		double unmeasured = 0;
		for (int i = 0; i + 1 < stops.size(); i++) {
			Optional<List<Link>> path = paths.computeIfAbsent(List.of(stops.get(i), stops.get(i + 1)),
				pair -> shortestPath(pair.get(0), pair.get(1)));
			if (path.isEmpty()) {
				return Optional.empty();
			}
			for (Link link : path.get()) {
				total += link.getLength();
				Object tracks = link.getAttributes().getAttribute(TRACKS_TOTAL);
				if (tracks == null) {
					unmeasured += link.getLength();
				} else if (((Number) tracks).intValue() == 1) {
					single += link.getLength();
				}
			}
		}
		return Optional.of(new Length(total, single, unmeasured));
	}

	private record Reached(Node node, double metres) {
	}

	/** Dijkstra on section lengths. */
	private Optional<List<Link>> shortestPath(String from, String to) {
		Node start = network.getNodes().get(Id.createNodeId(from));
		Node end = network.getNodes().get(Id.createNodeId(to));
		if (start == null || end == null) {
			return Optional.empty();
		}
		Map<Node, Double> distance = new HashMap<>(Map.of(start, 0.0));
		Map<Node, Link> reachedBy = new HashMap<>();
		Set<Node> settled = new HashSet<>();
		PriorityQueue<Reached> queue = new PriorityQueue<>((one, other) -> Double.compare(one.metres(), other.metres()));
		queue.add(new Reached(start, 0));
		while (!queue.isEmpty()) {
			Reached current = queue.poll();
			if (!settled.add(current.node())) {
				continue;
			}
			if (current.node().equals(end)) {
				return Optional.of(pathTo(end, reachedBy));
			}
			for (Link link : current.node().getOutLinks().values()) {
				double metres = current.metres() + link.getLength();
				if (metres < distance.getOrDefault(link.getToNode(), Double.POSITIVE_INFINITY)) {
					distance.put(link.getToNode(), metres);
					reachedBy.put(link.getToNode(), link);
					queue.add(new Reached(link.getToNode(), metres));
				}
			}
		}
		return Optional.empty();
	}

	private static List<Link> pathTo(Node end, Map<Node, Link> reachedBy) {
		LinkedList<Link> path = new LinkedList<>();
		for (Link link = reachedBy.get(end); link != null; link = reachedBy.get(link.getFromNode())) {
			path.addFirst(link);
		}
		return List.copyOf(path);
	}
}
