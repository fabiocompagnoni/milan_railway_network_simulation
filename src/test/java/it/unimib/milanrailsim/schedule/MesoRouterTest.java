package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MesoRouterTest {

	private Network network;

	private void addLink(String id, String from, String to, double length) {
		Link link = network.getFactory().createLink(Id.createLinkId(id),
			network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
		link.setLength(length);
		link.setFreespeed(30);
		link.setCapacity(3600);
		link.setAllowedModes(Set.of("rail"));
		network.addLink(link);
	}

	@BeforeEach
	void diamondNetwork() {
		network = NetworkUtils.createNetwork();
		// A -> B -> C (short) and A -> D -> C (long); plus a loop link at A
		for (String n : List.of("A", "B", "C", "D")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(n), new Coord(0, 0)));
		}
		addLink("A_B", "A", "B", 1000);
		addLink("B_C", "B", "C", 1000);
		addLink("A_D", "A", "D", 1500);
		addLink("D_C", "D", "C", 1500);
		addLink("loop_A", "A", "A", 200);
	}

	@Test
	void picksTheShortestChain() {
		List<Id<Link>> path = new MesoRouter(network).shortestPath(Id.createNodeId("A"), Id.createNodeId("C"));
		assertEquals(List.of(Id.createLinkId("A_B"), Id.createLinkId("B_C")), path);
	}

	@Test
	void rejectsStationToItselfRouting() {
		MesoRouter router = new MesoRouter(network);
		assertThrows(IllegalArgumentException.class,
			() -> router.shortestPath(Id.createNodeId("A"), Id.createNodeId("A")));
	}

	@Test
	void failsWhenNoPathExists() {
		MesoRouter router = new MesoRouter(network);
		assertThrows(IllegalArgumentException.class,
			() -> router.shortestPath(Id.createNodeId("C"), Id.createNodeId("A")));
	}
}
