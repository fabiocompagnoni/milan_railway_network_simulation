package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.micro.MicroIds;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.MicroNode.Station;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the neighbour a detailed station is reached through from a stop the
 * node does not declare, the way a train actually gets there: the shortest
 * rail path between the stop and the station's sidings, reachable from every
 * side, runs through one of the station's declared neighbours. An express
 * train's previous call may be three stations away; the station it runs
 * through last is what orients it.
 */
public final class RoutedSides implements PlatformPlanner.NeighbourResolver {

	private final Network network;
	private final LinkRouter router;
	private final Map<String, MicroNode> nodeByStation = new HashMap<>();
	private final Map<String, Optional<String>> cache = new HashMap<>();

	public RoutedSides(Network network, LinkRouter router, List<MicroNode> nodes) {
		this.network = network;
		this.router = router;
		for (MicroNode node : nodes) {
			for (Station station : node.stations()) {
				nodeByStation.put(station.id(), node);
			}
		}
	}

	@Override
	public Optional<String> neighbourTowards(String stationId, String otherStopId) {
		return cache.computeIfAbsent(stationId + "|" + otherStopId, key -> resolve(stationId, otherStopId));
	}

	private Optional<String> resolve(String stationId, String otherStopId) {
		MicroNode node = nodeByStation.get(stationId);
		Id<Link> sidings = MicroIds.sidings(node.station(stationId));
		Id<Link> other = linkOf(otherStopId);
		if (other == null || !network.getLinks().containsKey(sidings)) {
			return Optional.empty();
		}
		// the last link into the station names the neighbour the train runs through; the way out would name the same
		Pattern arriving = Pattern.compile("^([^_.]+)_" + Pattern.quote(stationId) + "(\\.|$)");
		try {
			List<Id<Link>> path = router.path(other, sidings);
			for (int i = path.size() - 1; i >= 0; i--) {
				Matcher matcher = arriving.matcher(path.get(i).toString());
				if (matcher.find()) {
					return Optional.of(matcher.group(1));
				}
			}
		} catch (IllegalArgumentException unreachable) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	/** Where a train stands at a stop: a meso station's stop link, a detailed station's sidings. */
	private Id<Link> linkOf(String stopId) {
		MicroNode node = nodeByStation.get(stopId);
		Id<Link> link = node != null ? MicroIds.sidings(node.station(stopId))
			: StationStopLinks.stopLinkId(Id.createNodeId(stopId));
		return network.getLinks().containsKey(link) ? link : null;
	}
}
