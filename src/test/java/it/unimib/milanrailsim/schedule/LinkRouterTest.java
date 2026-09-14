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

class LinkRouterTest {

	private Network network;

	private Link addLink(String id, String from, String to, double length) {
		Link link = network.getFactory().createLink(Id.createLinkId(id),
			network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
		link.setLength(length);
		link.setFreespeed(30);
		link.setCapacity(3600);
		link.setAllowedModes(Set.of("rail"));
		network.addLink(link);
		return link;
	}

	private static List<Id<Link>> ids(String... linkIds) {
		return java.util.Arrays.stream(linkIds).map(Id::createLinkId).toList();
	}

	@BeforeEach
	void diamondWithATerminalStub() {
		network = NetworkUtils.createNetwork();
		// stop_A -> A_B -> B_C (short) or A_D -> D_C (long) -> stop_C; T is a stub platform off B
		for (String n : List.of("A", "B", "C", "D", "T")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(n), new Coord(0, 0)));
		}
		addLink("stop_A", "A", "A", 200);
		addLink("A_B", "A", "B", 1000);
		addLink("B_C", "B", "C", 1000);
		addLink("A_D", "A", "D", 1500);
		addLink("D_C", "D", "C", 1500);
		addLink("stop_C", "C", "C", 200);
		addLink("stop_B", "B", "B", 200);
		addLink("B_T", "B", "T", 100);
		addLink("T_B", "T", "B", 100);
	}

	@Test
	void picksTheCheapestChainAndSkipsOtherStopLoops() {
		List<Id<Link>> path = new LinkRouter(network).path(Id.createLinkId("stop_A"), Id.createLinkId("stop_C"));

		assertEquals(ids("A_B", "B_C", "stop_C"), path);
	}

	@Test
	void costFunctionCanPreferTheLongerTracks() {
		List<Id<Link>> path = new LinkRouter(network).path(Id.createLinkId("stop_A"), Id.createLinkId("stop_C"),
			"avoid-B", link -> link.getId().toString().contains("B") ? 1e6 : link.getLength());

		assertEquals(ids("A_D", "D_C", "stop_C"), path);
	}

	@Test
	void doesNotBounceBetweenTheTwoDirectionsOfASectionAtAStation() {
		// S_M leaves a detailed station from its exit node, M_S returns to its entry node: distinct nodes,
		// yet the same section, so M_S right after S_M would be a reversal on the open line
		for (String n : List.of("P", "Sout", "Sin", "M", "Y")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(n), new Coord(0, 0)));
		}
		addLink("P_S", "P", "Sout", 500);
		addLink("S_M", "Sout", "M", 3000);
		addLink("M_S", "M", "Sin", 3000);
		addLink("M_Y", "M", "Y", 1000);
		addLink("stop_Y", "Y", "Y", 200);
		LinkRouter router = new LinkRouter(network);

		assertEquals(ids("S_M", "M_Y", "stop_Y"), router.path(Id.createLinkId("P_S"), Id.createLinkId("stop_Y")));
		assertThrows(IllegalArgumentException.class, () -> router.path(Id.createLinkId("P_S"), Id.createLinkId("M_S")));
	}

	@Test
	void reversesOnlyAsTheFirstMoveOutOfATerminalPlatform() {
		LinkRouter router = new LinkRouter(network);

		assertEquals(ids("T_B", "B_C", "stop_C"), router.path(Id.createLinkId("B_T"), Id.createLinkId("stop_C")));
		// A_B -> B_T -> T_B would reverse mid-route: not allowed, so T is unreachable as a through point
		assertThrows(IllegalArgumentException.class,
			() -> router.path(Id.createLinkId("A_B"), Id.createLinkId("T_B")));
	}

	@Test
	void honoursTurnRestrictions() {
		NetworkUtils.addDisallowedNextLinks(network.getLinks().get(Id.createLinkId("A_B")), "rail", ids("B_C"));

		List<Id<Link>> path = new LinkRouter(network).path(Id.createLinkId("stop_A"), Id.createLinkId("stop_C"));

		assertEquals(ids("A_D", "D_C", "stop_C"), path);
	}

	@Test
	void rejectsRoutingToTheSameLinkAndUnknownLinks() {
		LinkRouter router = new LinkRouter(network);

		assertThrows(IllegalArgumentException.class, () -> router.path(Id.createLinkId("A_B"), Id.createLinkId("A_B")));
		assertThrows(IllegalArgumentException.class, () -> router.path(Id.createLinkId("A_B"), Id.createLinkId("nope")));
	}
}
