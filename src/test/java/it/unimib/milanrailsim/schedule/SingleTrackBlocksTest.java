package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SingleTrackBlocksTest {

	private Network network;

	private void addPair(String from, String to, int capacity, boolean singleTrackResource) {
		for (String[] direction : new String[][] { { from, to }, { to, from } }) {
			Link link = network.getFactory().createLink(Id.createLinkId(direction[0] + "_" + direction[1]),
				network.getNodes().get(Id.createNodeId(direction[0])),
				network.getNodes().get(Id.createNodeId(direction[1])));
			link.setAllowedModes(Set.of("rail"));
			link.getAttributes().putAttribute("railsimTrainCapacity", capacity);
			if (singleTrackResource) {
				link.getAttributes().putAttribute("railsimResourceId",
					from.compareTo(to) < 0 ? from + "_" + to : to + "_" + from);
			}
			network.addLink(link);
		}
	}

	private void addStation(String id, int stopCapacity) {
		network.addNode(network.getFactory().createNode(Id.createNodeId(id), new Coord(0, 0)));
		Link stop = network.getFactory().createLink(
			StationStopLinks.stopLinkId(Id.createNodeId(id)),
			network.getNodes().get(Id.createNodeId(id)), network.getNodes().get(Id.createNodeId(id)));
		stop.setAllowedModes(Set.of("rail"));
		stop.getAttributes().putAttribute("railsimTrainCapacity", stopCapacity);
		stop.getAttributes().putAttribute("stationLink", true);
		network.addLink(stop);
	}

	@BeforeEach
	void singleTrackLineWithOneCrossingStation() {
		network = NetworkUtils.createNetwork();
		// A -1- B -1- C -1- D single track; B has one station track (no crossing),
		// C has two (crossing point); D_E is double track
		for (String[] station : new String[][] { { "A", "2" }, { "B", "1" }, { "C", "2" },
				{ "D", "1" }, { "E", "2" } }) {
			addStation(station[0], Integer.parseInt(station[1]));
		}
		addPair("A", "B", 1, true);
		addPair("B", "C", 1, true);
		addPair("C", "D", 1, true);
		addPair("D", "E", 2, false);
	}

	private String resource(String linkId) {
		return (String) network.getLinks().get(Id.createLinkId(linkId))
			.getAttributes().getAttribute("railsimResourceId");
	}

	@Test
	void chainsSingleTrackLinksBetweenCrossingPoints() {
		SingleTrackBlocks.apply(network);

		// A..C is one block (B cannot host a meet), C..D another
		assertEquals(resource("A_B"), resource("B_C"));
		assertEquals(resource("A_B"), resource("C_B"));
		assertNotEquals(resource("A_B"), resource("C_D"));
		assertEquals(resource("C_D"), resource("D_C"));
	}

	@Test
	void doubleTrackLinksAreUntouched() {
		SingleTrackBlocks.apply(network);

		assertNull(resource("D_E"));
		assertNull(resource("stop_C"));
	}

	@Test
	void isIdempotent() {
		SingleTrackBlocks.apply(network);
		String first = resource("A_B");
		SingleTrackBlocks.apply(network);

		assertEquals(first, resource("A_B"));
		assertEquals(List.of(), network.getLinks().values().stream()
			.filter(l -> l.getAttributes().getAttribute("railsimResourceId") == null
				&& Integer.valueOf(1).equals(l.getAttributes().getAttribute("railsimTrainCapacity"))
				&& l.getAttributes().getAttribute("stationLink") == null
				&& !l.getFromNode().getId().equals(l.getToNode().getId()))
			.map(l -> l.getId().toString()).toList());
	}
}
