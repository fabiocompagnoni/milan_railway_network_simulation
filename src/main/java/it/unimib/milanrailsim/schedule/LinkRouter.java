package it.unimib.milanrailsim.schedule;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.ToDoubleFunction;

/**
 * Cheapest link chain from one link to another, honouring the network's turn
 * restrictions. A train may reverse onto the opposite link only as its first
 * move, which is how it leaves a terminal platform; loop links other than the
 * target are never traversed, so a train is not routed through another
 * station's stop link.
 */
public final class LinkRouter {

	private static final String MODE = "rail";

	private final Network network;
	private final Map<String, List<Id<Link>>> cache = new HashMap<>();

	public LinkRouter(Network network) {
		this.network = network;
	}

	/** Length-shortest chain after {@code from} up to and including {@code to}. */
	public List<Id<Link>> path(Id<Link> from, Id<Link> to) {
		return path(from, to, "length", Link::getLength);
	}

	/**
	 * @param costKey identifies the cost function for caching; equal keys must mean equal costs
	 */
	public List<Id<Link>> path(Id<Link> from, Id<Link> to, String costKey, ToDoubleFunction<Link> cost) {
		return cache.computeIfAbsent(from + ">" + to + "@" + costKey, key -> compute(from, to, cost));
	}

	private record Step(Link link, double cost) {
	}

	private List<Id<Link>> compute(Id<Link> fromId, Id<Link> toId, ToDoubleFunction<Link> cost) {
		Link from = network.getLinks().get(fromId);
		Link to = network.getLinks().get(toId);
		if (from == null || to == null) {
			throw new IllegalArgumentException("Unknown link: " + (from == null ? fromId : toId));
		}
		if (fromId.equals(toId)) {
			throw new IllegalArgumentException("Routing from a link to itself requested for " + fromId);
		}
		Map<Id<Link>, Double> best = new HashMap<>();
		Map<Id<Link>, Link> arrivedFrom = new HashMap<>();
		PriorityQueue<Step> queue = new PriorityQueue<>((a, b) -> Double.compare(a.cost(), b.cost()));
		for (Link next : successors(from, true)) {
			enqueue(queue, best, arrivedFrom, from, next, cost.applyAsDouble(next), toId);
		}
		while (!queue.isEmpty()) {
			Step current = queue.poll();
			if (current.cost() > best.getOrDefault(current.link().getId(), Double.POSITIVE_INFINITY)) {
				continue;
			}
			if (current.link().getId().equals(toId)) {
				return chain(from, to, arrivedFrom);
			}
			for (Link next : successors(current.link(), false)) {
				enqueue(queue, best, arrivedFrom, current.link(), next, current.cost() + cost.applyAsDouble(next), toId);
			}
		}
		throw new IllegalArgumentException("No rail path from " + fromId + " to " + toId);
	}

	private void enqueue(PriorityQueue<Step> queue, Map<Id<Link>, Double> best, Map<Id<Link>, Link> arrivedFrom,
			Link previous, Link next, double cost, Id<Link> target) {
		if (isLoop(next) && !next.getId().equals(target)) {
			return;
		}
		if (cost < best.getOrDefault(next.getId(), Double.POSITIVE_INFINITY)) {
			best.put(next.getId(), cost);
			arrivedFrom.put(next.getId(), previous);
			queue.add(new Step(next, cost));
		}
	}

	private List<Link> successors(Link link, boolean mayReverse) {
		DisallowedNextLinks restrictions = NetworkUtils.getDisallowedNextLinks(link);
		List<Link> result = new ArrayList<>();
		for (Link next : link.getToNode().getOutLinks().values()) {
			if (!next.getAllowedModes().contains(MODE)) {
				continue;
			}
			if (!mayReverse && isOpposite(link, next)) {
				continue;
			}
			if (restrictions != null && restrictions.getDisallowedLinkSequences(MODE).stream()
					.anyMatch(sequence -> sequence.size() == 1 && sequence.getFirst().equals(next.getId()))) {
				continue;
			}
			result.add(next);
		}
		return result;
	}

	/**
	 * The two directions of a section: the same two nodes swapped, or the two
	 * mesoscopic links {@code A_B} and {@code B_A} between the same stations,
	 * whose ends at a detailed station sit on separate entry and exit nodes.
	 */
	private static boolean isOpposite(Link link, Link next) {
		if (next.getToNode().equals(link.getFromNode()) && next.getFromNode().equals(link.getToNode())) {
			return true;
		}
		String[] stations = link.getId().toString().split("_");
		return stations.length == 2 && !link.getId().toString().contains(".")
			&& next.getId().toString().equals(stations[1] + "_" + stations[0]);
	}

	private static boolean isLoop(Link link) {
		return link.getFromNode().equals(link.getToNode());
	}

	private static List<Id<Link>> chain(Link from, Link to, Map<Id<Link>, Link> arrivedFrom) {
		List<Id<Link>> path = new ArrayList<>();
		Link cursor = to;
		while (!cursor.getId().equals(from.getId())) {
			path.add(cursor.getId());
			cursor = arrivedFrom.get(cursor.getId());
		}
		Collections.reverse(path);
		return List.copyOf(path);
	}
}
