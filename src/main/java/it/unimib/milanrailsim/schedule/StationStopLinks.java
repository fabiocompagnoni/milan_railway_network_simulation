package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.StationTracks;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
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

	/** Platform tracks documented in docs/network/infrastruttura-nodo-milano.md: Cadorna, Garibaldi surface, Garibaldi Passante. */
	private static final Map<String, Integer> DOCUMENTED_PLATFORMS =
		Map.of("S01066", 10, "S01645", 20, "S01647", 2);

	private StationStopLinks() {
	}

	public static Id<Link> stopLinkId(Id<Node> station) {
		return Id.createLinkId("stop_" + station);
	}

	public static void addStopLinks(Network network) {
		addStopLinks(network, StationTracks.empty());
	}

	/**
	 * One loop link per station, holding every train that dwells there in either
	 * direction. Capacity, in order of trust: the surveyed platform count, the
	 * documented one, the OSM platform count (provisional), else the total tracks
	 * of the busiest incident section, which a double-track line makes two.
	 */
	public static void addStopLinks(Network network, StationTracks stationTracks) {
		for (Node node : new ArrayList<>(network.getNodes().values())) {
			Id<Link> id = stopLinkId(node.getId());
			if (network.getLinks().containsKey(id)) {
				continue;
			}
			Optional<StationTracks.Tracks> surveyed = stationTracks.of(node.getId().toString());
			Integer documented = DOCUMENTED_PLATFORMS.get(node.getId().toString());
			boolean provisional;
			int capacity;
			if (surveyed.isPresent() && !surveyed.get().provisional()) {
				capacity = surveyed.get().count();
				provisional = false;
			} else if (documented != null) {
				capacity = documented;
				provisional = false;
			} else if (surveyed.isPresent()) {
				capacity = surveyed.get().count();
				provisional = true;
			} else {
				capacity = incidentTracks(node);
				provisional = true;
			}
			Link stop = network.getFactory().createLink(id, node, node);
			stop.setLength(LENGTH_M);
			stop.setFreespeed(FREESPEED_MS);
			stop.setCapacity(3600.0);
			stop.setNumberOfLanes(1.0);
			stop.setAllowedModes(Set.of("rail"));
			stop.getAttributes().putAttribute("railsimTrainCapacity", capacity);
			stop.getAttributes().putAttribute("stationLink", true);
			if (provisional) {
				stop.getAttributes().putAttribute("dataStatus", "provisional");
			}
			network.addLink(stop);
		}
	}

	private static int incidentTracks(Node node) {
		return Stream.concat(node.getInLinks().values().stream(), node.getOutLinks().values().stream())
			.map(link -> {
				Object total = link.getAttributes().getAttribute("tracksTotal");
				Object perDirection = link.getAttributes().getAttribute("railsimTrainCapacity");
				return total instanceof Number n ? n.intValue() : perDirection instanceof Number n ? n.intValue() : 1;
			})
			.max(Integer::compare)
			.orElse(1);
	}
}
