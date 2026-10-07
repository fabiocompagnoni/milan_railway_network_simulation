package it.unimib.milanrailsim.schedule;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Adds one loop link per station node: the mesoscopic "station as a link with
 * capacity" of the railsim network specification. Platform count is a declared
 * heuristic (max incident capacity, provisional mark) except for the terminals
 * with known track counts (docs/network/infrastruttura-nodo-milano.md).
 */
public final class StationStopLinks {

	private static final double LENGTH_M = 200.0;
	private static final double FREESPEED_MS = 13.9;

	/** Real platform tracks at documented terminals: Cadorna, Garibaldi surface, Garibaldi Passante. */
	private static final Map<String, Integer> TERMINAL_PLATFORMS =
		Map.of("S01066", 10, "S01645", 20, "S01647", 2);

	private StationStopLinks() {
	}

	public static Id<Link> stopLinkId(Id<Node> station) {
		return Id.createLinkId("stop_" + station);
	}

	public static void addStopLinks(Network network) {
		for (Node node : new ArrayList<>(network.getNodes().values())) {
			Id<Link> id = stopLinkId(node.getId());
			if (network.getLinks().containsKey(id)) {
				continue;
			}
			Integer documented = TERMINAL_PLATFORMS.get(node.getId().toString());
			int capacity = documented != null ? documented
				: Stream.concat(node.getInLinks().values().stream(), node.getOutLinks().values().stream())
					.map(l -> l.getAttributes().getAttribute("railsimTrainCapacity"))
					.map(v -> v instanceof Number n ? n.intValue() : 1)
					.max(Integer::compare)
					.orElse(1);
			Link stop = network.getFactory().createLink(id, node, node);
			stop.setLength(LENGTH_M);
			stop.setFreespeed(FREESPEED_MS);
			stop.setCapacity(3600.0);
			stop.setNumberOfLanes(1.0);
			stop.setAllowedModes(Set.of("rail"));
			stop.getAttributes().putAttribute("railsimTrainCapacity", capacity);
			stop.getAttributes().putAttribute("stationLink", true);
			if (documented == null) {
				stop.getAttributes().putAttribute("dataStatus", "provisional");
			}
			network.addLink(stop);
		}
	}
}
