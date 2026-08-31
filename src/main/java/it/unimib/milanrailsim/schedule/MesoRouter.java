package it.unimib.milanrailsim.schedule;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Length-shortest paths between station nodes on the meso network. */
public final class MesoRouter {

	private final Network network;
	private final Map<String, List<Id<Link>>> cache = new HashMap<>();

	public MesoRouter(Network network) {
		this.network = network;
	}

	public List<Id<Link>> shortestPath(Id<Node> from, Id<Node> to) {
		return cache.computeIfAbsent(from + ">" + to, key -> compute(from, to));
	}

	private List<Id<Link>> compute(Id<Node> fromId, Id<Node> toId) {
		Node from = network.getNodes().get(fromId);
		Node to = network.getNodes().get(toId);
		if (from == null || to == null) {
			throw new IllegalArgumentException("Unknown node: " + (from == null ? fromId : toId));
		}
		Map<Id<Node>, Double> dist = new HashMap<>();
		Map<Id<Node>, Link> arrivedBy = new HashMap<>();
		PriorityQueue<Id<Node>> queue =
			new PriorityQueue<>((a, b) -> Double.compare(dist.get(a), dist.get(b)));
		dist.put(fromId, 0.0);
		queue.add(fromId);
		while (!queue.isEmpty()) {
			Id<Node> current = queue.poll();
			if (current.equals(toId)) {
				break;
			}
			for (Link link : network.getNodes().get(current).getOutLinks().values()) {
				if (link.getToNode().getId().equals(link.getFromNode().getId())) {
					continue;
				}
				double candidate = dist.get(current) + link.getLength();
				Id<Node> next = link.getToNode().getId();
				if (candidate < dist.getOrDefault(next, Double.POSITIVE_INFINITY)) {
					dist.put(next, candidate);
					arrivedBy.put(next, link);
					queue.remove(next);
					queue.add(next);
				}
			}
		}
		if (!arrivedBy.containsKey(toId)) {
			throw new IllegalArgumentException("No rail path from " + fromId + " to " + toId);
		}
		List<Id<Link>> path = new ArrayList<>();
		Id<Node> cursor = toId;
		while (!cursor.equals(fromId)) {
			Link link = arrivedBy.get(cursor);
			path.add(link.getId());
			cursor = link.getFromNode().getId();
		}
		java.util.Collections.reverse(path);
		return List.copyOf(path);
	}
}
