package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StationStopLinksTest {

	@Test
	void addsOneLoopLinkPerStationWithInheritedCapacity() {
		Network network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("S1"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("S2"), new Coord(1000, 0));
		network.addNode(a);
		network.addNode(b);
		Link ab = network.getFactory().createLink(Id.createLinkId("S1_S2"), a, b);
		ab.setLength(1000);
		ab.setAllowedModes(Set.of("rail"));
		ab.getAttributes().putAttribute("railsimTrainCapacity", 2);
		network.addLink(ab);

		StationStopLinks.addStopLinks(network);
		StationStopLinks.addStopLinks(network); // idempotent

		Link stopA = network.getLinks().get(StationStopLinks.stopLinkId(a.getId()));
		assertNotNull(stopA);
		assertEquals(a.getId(), stopA.getFromNode().getId());
		assertEquals(a.getId(), stopA.getToNode().getId());
		assertEquals(2, stopA.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("provisional", stopA.getAttributes().getAttribute("dataStatus"));
		assertEquals(Boolean.TRUE, stopA.getAttributes().getAttribute("stationLink"));

		Link stopB = network.getLinks().get(StationStopLinks.stopLinkId(b.getId()));
		// S2's only incident link is S1_S2 (capacity 2), inherited via the max rule
		assertEquals(2, stopB.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(3, network.getLinks().size()); // S1_S2 + 2 stop links, no duplicates
	}
}
