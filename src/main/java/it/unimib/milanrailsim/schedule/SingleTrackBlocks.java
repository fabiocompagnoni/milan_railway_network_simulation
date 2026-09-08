package it.unimib.milanrailsim.schedule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Merges consecutive single-track links into one block resource between
 * crossing points, so opposing trains serialize over the whole section and
 * meets happen at stations — as on the real network (e.g. Malnate-Varese
 * Nord, single track with meets at the two end stations). A crossing point
 * is a station with at least two platform tracks or any junction.
 */
public final class SingleTrackBlocks {

	private static final Logger log = LogManager.getLogger(SingleTrackBlocks.class);

	private SingleTrackBlocks() {
	}

	public static void apply(Network network) {
		Set<Node> visited = new HashSet<>();
		int blocks = 0;
		for (Link link : network.getLinks().values()) {
			Node from = link.getFromNode();
			if (isSingleTrack(link) && !visited.contains(from) && isBlockBoundary(network, from)) {
				blocks += growBlocksFrom(network, from, visited);
			}
		}
		log.info("Merged single-track links into {} block resources", blocks);
	}

	private static int growBlocksFrom(Network network, Node boundary, Set<Node> visited) {
		int blocks = 0;
		for (Link start : boundary.getOutLinks().values()) {
			if (!isSingleTrack(start)) {
				continue;
			}
			Deque<Link> chain = new ArrayDeque<>();
			chain.add(start);
			Node cursor = start.getToNode();
			while (!isBlockBoundary(network, cursor)) {
				visited.add(cursor);
				Link next = cursor.getOutLinks().values().stream()
					.filter(SingleTrackBlocks::isSingleTrack)
					.filter(l -> !l.getToNode().equals(chain.getLast().getFromNode()))
					.findFirst().orElse(null);
				if (next == null) {
					break;
				}
				chain.add(next);
				cursor = next.getToNode();
			}
			String blockId = "block_" + start.getFromNode().getId() + "_" + cursor.getId();
			boolean renamed = false;
			for (Link segment : chain) {
				renamed |= rename(network, segment, blockId);
			}
			if (renamed) {
				blocks++;
			}
		}
		return blocks;
	}

	/** Renames the resource of both directions of a segment; false if already this block. */
	private static boolean rename(Network network, Link segment, String blockId) {
		if (blockId.equals(segment.getAttributes().getAttribute("railsimResourceId"))) {
			return false;
		}
		segment.getAttributes().putAttribute("railsimResourceId", blockId);
		Link opposite = findOpposite(network, segment);
		if (opposite != null) {
			opposite.getAttributes().putAttribute("railsimResourceId", blockId);
		}
		return true;
	}

	private static Link findOpposite(Network network, Link link) {
		return link.getToNode().getOutLinks().values().stream()
			.filter(l -> l.getToNode().equals(link.getFromNode()) && isSingleTrack(l))
			.findFirst().orElse(null);
	}

	private static boolean isSingleTrack(Link link) {
		return !link.getFromNode().equals(link.getToNode())
			&& link.getAttributes().getAttribute("stationLink") == null
			&& Integer.valueOf(1).equals(link.getAttributes().getAttribute("railsimTrainCapacity"));
	}

	/** Crossing point: a station able to host a meet, a junction, or the end of single track. */
	private static boolean isBlockBoundary(Network network, Node node) {
		Link stop = network.getLinks().get(StationStopLinks.stopLinkId(node.getId()));
		if (stop != null && ((Number) stop.getAttributes()
				.getAttribute("railsimTrainCapacity")).intValue() >= 2) {
			return true;
		}
		long singleTrackNeighbours = node.getOutLinks().values().stream()
			.filter(SingleTrackBlocks::isSingleTrack)
			.map(l -> l.getToNode().getId())
			.distinct().count();
		long doubleTrackNeighbours = node.getOutLinks().values().stream()
			.filter(l -> !isSingleTrack(l) && !l.getFromNode().equals(l.getToNode())
				&& l.getAttributes().getAttribute("stationLink") == null)
			.count();
		return singleTrackNeighbours != 2 || doubleTrackNeighbours > 0;
	}
}
