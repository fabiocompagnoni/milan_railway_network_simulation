package it.unimib.milanrailsim.network;

import it.unimib.milanrailsim.network.RouteTracks.Length;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RouteTracksTest {

	/**
	 * A - B double track (2000 m), B - C single track (3000 m), C - D not
	 * measured (1000 m); a detour A - X - B longer than the direct section.
	 */
	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		for (String id : List.of("A", "B", "C", "D", "X")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(id), new Coord(0, 0)));
		}
		section(network, "A", "B", 2000, 4);
		section(network, "B", "C", 3000, 1);
		section(network, "C", "D", 1000, null);
		section(network, "A", "X", 1500, 2);
		section(network, "X", "B", 1500, 2);
		return network;
	}

	private static void section(Network network, String from, String to, double length, Integer tracks) {
		Node one = network.getNodes().get(Id.createNodeId(from));
		Node other = network.getNodes().get(Id.createNodeId(to));
		for (Node[] ends : List.of(new Node[] { one, other }, new Node[] { other, one })) {
			Link link = network.getFactory().createLink(Id.createLinkId(ends[0].getId() + "_" + ends[1].getId()), ends[0], ends[1]);
			link.setLength(length);
			if (tracks != null) {
				link.getAttributes().putAttribute("tracksTotal", tracks);
			}
			network.addLink(link);
		}
	}

	@Test
	void sumsTheSectionsBetweenConsecutiveStopsAlongTheShortestPath() {
		Optional<Length> length = new RouteTracks(network()).of(List.of("A", "C"));

		assertEquals(Optional.of(new Length(5000, 3000, 0)), length, "A to C runs A - B - C, not through X");
	}

	@Test
	void sectionsWithoutAMeasuredTrackCountAreCountedApart() {
		Optional<Length> length = new RouteTracks(network()).of(List.of("A", "B", "D"));

		assertEquals(Optional.of(new Length(6000, 3000, 1000)), length);
	}

	@Test
	void aStopOutsideTheNetworkLeavesTheLengthUnknown() {
		assertEquals(Optional.empty(), new RouteTracks(network()).of(List.of("A", "Z")));
	}
}
