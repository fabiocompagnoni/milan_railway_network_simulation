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
	void oneTrackStationsInsideABlockBelongToIt() {
		SingleTrackBlocks.apply(network);

		assertEquals(resource("A_B"), resource("stop_B"));
		assertNull(resource("stop_A"), "crossing points keep their own stop resources");
		assertNull(resource("stop_C"));
		assertNull(resource("stop_D"), "a boundary at the end of single track too");
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

	@Test
	void aSingleTrackJunctionStationGetsASecondStopTrack() {
		for (String[] station : new String[][] { { "J", "1" }, { "F", "2" }, { "G", "2" }, { "H", "2" } }) {
			addStation(station[0], Integer.parseInt(station[1]));
		}
		addPair("J", "F", 1, true);
		addPair("J", "G", 1, true);
		addPair("J", "H", 1, true);

		SingleTrackBlocks.apply(network);

		Link junction = network.getLinks().get(Id.createLinkId("stop_J"));
		assertEquals(2, junction.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("provisional", junction.getAttributes().getAttribute("dataStatus"));
		assertEquals(1, network.getLinks().get(Id.createLinkId("stop_D")).getAttributes().getAttribute("railsimTrainCapacity"),
			"an end of single track with two neighbours is no junction");
	}

	@Test
	void doubleTrackWithOneTrainPerDirectionIsNotSingleTrack() {
		// two tracks give one train per direction, with no shared resource: never a block
		addStation("P", 2);
		addStation("Q", 2);
		addPair("P", "Q", 1, false);

		SingleTrackBlocks.apply(network);

		assertNull(resource("P_Q"));
		assertNull(resource("Q_P"));
	}

	@Test
	void bothDirectionsBetweenDetailedStationsShareOneBlock() {
		// between two detailed stations each direction enters and leaves through its own junction
		// node, so the two links share no node: the opposite must still join the same block
		for (String id : List.of("M.north.N.out", "N.south.M.in", "N.south.M.out", "M.north.N.in", "M.p1", "N.p1")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(id), new Coord(0, 0)));
		}
		Link towardsN = singleTrack("M_N", "M.north.N.out", "N.south.M.in");
		Link towardsM = singleTrack("N_M", "N.south.M.out", "M.north.N.in");
		for (String[] throat : new String[][] { { "M.p1.north.out", "M.p1", "M.north.N.out" }, { "M.p1.north.in", "M.north.N.in", "M.p1" },
				{ "N.p1.south.out", "N.p1", "N.south.M.out" }, { "N.p1.south.in", "N.south.M.in", "N.p1" } }) {
			Link link = network.getFactory().createLink(Id.createLinkId(throat[0]),
				network.getNodes().get(Id.createNodeId(throat[1])), network.getNodes().get(Id.createNodeId(throat[2])));
			link.setAllowedModes(Set.of("rail"));
			link.getAttributes().putAttribute("railsimTrainCapacity", 1);
			link.getAttributes().putAttribute("microNode", "mn");
			network.addLink(link);
		}

		SingleTrackBlocks.apply(network);

		assertEquals(resource("M_N"), resource("N_M"));
		assertTrue(resource("M_N").startsWith("block_"));
		assertNotEquals(towardsN.getToNode(), towardsM.getFromNode(), "the fixture really keeps the ends apart");
	}

	private Link singleTrack(String id, String from, String to) {
		Link link = network.getFactory().createLink(Id.createLinkId(id),
			network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
		link.setAllowedModes(Set.of("rail"));
		link.getAttributes().putAttribute("railsimTrainCapacity", 1);
		link.getAttributes().putAttribute("railsimResourceId", "M_N");
		network.addLink(link);
		return link;
	}

	@Test
	void microNodeLinksKeepTheirOwnResources() {
		// a throat link of a micro station carries the shared throat resource, never a block
		network.addNode(network.getFactory().createNode(Id.createNodeId("E.north"), new Coord(0, 0)));
		network.addNode(network.getFactory().createNode(Id.createNodeId("E.p1.b"), new Coord(0, 0)));
		Link throat = network.getFactory().createLink(Id.createLinkId("E.p1.north.in"),
			network.getNodes().get(Id.createNodeId("E.north")), network.getNodes().get(Id.createNodeId("E.p1.b")));
		throat.setAllowedModes(Set.of("rail"));
		throat.getAttributes().putAttribute("railsimTrainCapacity", 1);
		throat.getAttributes().putAttribute("railsimResourceId", "e_throat");
		throat.getAttributes().putAttribute("microNode", "e");
		network.addLink(throat);

		SingleTrackBlocks.apply(network);

		assertEquals("e_throat", resource("E.p1.north.in"));
	}
}
