package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinkGeometryBuilderTest {

	private static final Map<Long, Coord> NODES = Map.of(
		1L, new Coord(0, 0),
		2L, new Coord(100, 50),
		3L, new Coord(200, 50),
		4L, new Coord(300, 0),
		9L, new Coord(150, 500));

	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		network.addNode(network.getFactory().createNode(Id.createNodeId("A"), new Coord(5, -5)));
		network.addNode(network.getFactory().createNode(Id.createNodeId("B"), new Coord(305, 5)));
		return network;
	}

	private static Link link(Network network, String from, String to, String wayIds) {
		Link link = network.getFactory().createLink(Id.createLinkId(from + "_" + to),
			network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
		if (wayIds != null) {
			link.getAttributes().putAttribute("osmWayIds", wayIds);
		}
		network.addLink(link);
		return link;
	}

	private static List<Coord> polyline(OsmRailWays ways, String wayIds) {
		Network network = network();
		Link link = link(network, "A", "B", wayIds);
		return new LinkGeometryBuilder(ways, network).polyline(link);
	}

	@Test
	void chainsSplitWaysIntoOneOrderedPolyline() {
		// way 20 is stored in the opposite direction of travel: chaining must not depend on it
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L),
			20L, List.of(3L, 2L),
			30L, List.of(3L, 4L)));

		List<Coord> polyline = polyline(ways, "10 20 30");

		assertEquals(List.of(NODES.get(1L), NODES.get(2L), NODES.get(3L), NODES.get(4L)), polyline);
	}

	@Test
	void ignoresDeadEndBranchesOfListedWays() {
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L, 3L, 4L),
			40L, List.of(2L, 9L)));

		List<Coord> polyline = polyline(ways, "10 40");

		assertFalse(polyline.contains(NODES.get(9L)));
		assertEquals(4, polyline.size());
	}

	@Test
	void fallsBackToStraightSegmentWithoutWays() {
		Network network = network();
		Link link = link(network, "A", "B", null);

		List<Coord> polyline = new LinkGeometryBuilder(new OsmRailWays(NODES, Map.of()), network).polyline(link);

		assertEquals(List.of(link.getFromNode().getCoord(), link.getToNode().getCoord()), polyline);
	}

	@Test
	void fallsBackToStraightSegmentWhenWaysAreDisconnected() {
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L),
			30L, List.of(3L, 4L)));
		Network network = network();
		Link link = link(network, "A", "B", "10 30");

		List<Coord> polyline = new LinkGeometryBuilder(ways, network).polyline(link);

		assertEquals(List.of(link.getFromNode().getCoord(), link.getToNode().getCoord()), polyline);
	}

	@Test
	void rejectsUnknownWay() {
		Network network = network();
		link(network, "A", "B", "77");
		OsmRailWays ways = new OsmRailWays(NODES, Map.of());

		assertThrows(IllegalArgumentException.class, () -> new LinkGeometryBuilder(ways, network));
	}

	@Test
	void linksMeetingAtAStationShareOneEndpoint() {
		// A-B runs on one track (node 2), B-C on a parallel one (node 5); station B is nearest node 5
		Map<Long, Coord> nodes = Map.of(
			1L, new Coord(0, 0), 2L, new Coord(100, 50),
			5L, new Coord(110, 60), 6L, new Coord(300, 60));
		OsmRailWays ways = new OsmRailWays(nodes, Map.of(10L, List.of(1L, 2L), 50L, List.of(5L, 6L)));
		Network network = network();
		network.getNodes().get(Id.createNodeId("B")).setCoord(new Coord(105, 58));
		network.addNode(network.getFactory().createNode(Id.createNodeId("C"), new Coord(305, 65)));
		Link ab = link(network, "A", "B", "10");
		Link bc = link(network, "B", "C", "50");
		LinkGeometryBuilder builder = new LinkGeometryBuilder(ways, network);

		List<Coord> first = builder.polyline(ab);
		List<Coord> second = builder.polyline(bc);

		assertEquals(nodes.get(5L), first.getLast());
		assertEquals(nodes.get(5L), second.getFirst());
		assertEquals(List.of(nodes.get(1L), nodes.get(2L), nodes.get(5L)), first);
	}
}
