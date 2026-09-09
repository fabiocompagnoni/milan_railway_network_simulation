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

	private static Link link(String wayIds) {
		Network network = NetworkUtils.createNetwork();
		Node from = network.getFactory().createNode(Id.createNodeId("A"), new Coord(5, -5));
		Node to = network.getFactory().createNode(Id.createNodeId("B"), new Coord(305, 5));
		network.addNode(from);
		network.addNode(to);
		Link link = network.getFactory().createLink(Id.createLinkId("A_B"), from, to);
		if (wayIds != null) {
			link.getAttributes().putAttribute("osmWayIds", wayIds);
		}
		network.addLink(link);
		return link;
	}

	@Test
	void chainsSplitWaysIntoOneOrderedPolyline() {
		// way 20 is stored in the opposite direction of travel: chaining must not depend on it
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L),
			20L, List.of(3L, 2L),
			30L, List.of(3L, 4L)));

		List<Coord> polyline = new LinkGeometryBuilder(ways).polyline(link("10 20 30"));

		assertEquals(List.of(NODES.get(1L), NODES.get(2L), NODES.get(3L), NODES.get(4L)), polyline);
	}

	@Test
	void ignoresDeadEndBranchesOfListedWays() {
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L, 3L, 4L),
			40L, List.of(2L, 9L)));

		List<Coord> polyline = new LinkGeometryBuilder(ways).polyline(link("10 40"));

		assertFalse(polyline.contains(NODES.get(9L)));
		assertEquals(4, polyline.size());
	}

	@Test
	void fallsBackToStraightSegmentWithoutWays() {
		Link link = link(null);

		List<Coord> polyline = new LinkGeometryBuilder(new OsmRailWays(NODES, Map.of())).polyline(link);

		assertEquals(List.of(link.getFromNode().getCoord(), link.getToNode().getCoord()), polyline);
	}

	@Test
	void fallsBackToStraightSegmentWhenWaysAreDisconnected() {
		OsmRailWays ways = new OsmRailWays(NODES, Map.of(
			10L, List.of(1L, 2L),
			30L, List.of(3L, 4L)));
		Link link = link("10 30");

		List<Coord> polyline = new LinkGeometryBuilder(ways).polyline(link);

		assertEquals(List.of(link.getFromNode().getCoord(), link.getToNode().getCoord()), polyline);
	}

	@Test
	void rejectsUnknownWay() {
		LinkGeometryBuilder builder = new LinkGeometryBuilder(new OsmRailWays(NODES, Map.of()));

		assertThrows(IllegalArgumentException.class, () -> builder.polyline(link("77")));
	}
}
