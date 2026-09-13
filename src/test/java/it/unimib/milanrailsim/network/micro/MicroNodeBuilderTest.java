package it.unimib.milanrailsim.network.micro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MicroNodeBuilderTest {

	private static final String NODE = """
		{
			"node": "test",
			"title": "Test node",
			"stations": [
				{
					"id": "A", "name": "A", "kind": "terminal", "platformLengthM": null,
					"groups": [
						{"id": "a_main", "kind": "terminal", "tracks": [{"ref": "1", "direction": null}, {"ref": "2", "direction": null}],
							"connections": {"north": ["segment:A_B:f1"]}},
						{"id": "a_shared", "kind": "terminal", "capacity": 3, "otherOperatorsShare": 1,
							"connections": {"north": ["segment:A_B:f1", "meso:X"]}}
					],
					"throats": [
						{"id": "a_throat", "side": "north", "resource": "a_throat", "lengthM": 300, "speedKmh": 30, "switches": 4,
							"groups": ["a_main", "a_shared"]}
					]
				},
				{
					"id": "B", "name": "B", "kind": "through", "platformLengthM": 250,
					"groups": [
						{"id": "b_f1", "kind": "through", "tracks": [{"ref": "1", "direction": "north"}, {"ref": "2", "direction": "south"}],
							"connections": {"south": ["segment:A_B:f1"], "north": ["meso:C"]}}
					],
					"throats": []
				}
			],
			"segments": [
				{"from": "A", "to": "B", "bundles": {"f1": {
					"north": {"wayIds": [11, 12], "lengthM": 1500},
					"south": {"wayIds": [21], "lengthM": 1500},
					"speedProfile": [{"kmh": 30, "lengthM": 300}, {"kmh": 90, "lengthM": 1200}]}}}
			],
			"lines": {
				"S1": {"bundle": "f1", "stations": {"A": ["a_main", "a_shared"], "B": ["b_f1"]}}
			}
		}
		""";

	private Network network;
	private MicroNode node;

	@BeforeEach
	void build(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("test.json");
		Files.writeString(file, NODE);
		node = MicroNode.read(file);
		network = NetworkUtils.createNetwork();
		Node x = station("X", 0, 0);
		Node a = station("A", 1000, 0);
		Node b = station("B", 2500, 0);
		Node c = station("C", 4000, 0);
		meso(x, a);
		meso(a, x);
		meso(a, b);
		meso(b, a);
		meso(b, c);
		meso(c, b);
		new MicroNodeBuilder(network).splice(List.of(node));
	}

	@Test
	void replacesTheMesoSectionWithOneLinkPerBundleTrack() {
		assertFalse(network.getLinks().containsKey(Id.createLinkId("A_B")));
		assertFalse(network.getLinks().containsKey(Id.createLinkId("B_A")));

		Link north = link("A_B.f1.north");
		assertEquals("A_B.f1.north", north.getAttributes().getAttribute("railsimResourceId"));
		assertEquals(1, north.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("11 12", north.getAttributes().getAttribute("osmWayIds"));
		assertEquals("test", north.getAttributes().getAttribute("microNode"));
		// bundle 1500 m minus the 300 m throat at A, the provisional 100 m approach at B and the two 100 m stubs
		assertEquals(900, north.getLength(), 1e-9);
		// the profile beyond the 30 km/h throat runs 1200 m in 48 s
		assertEquals(25, north.getFreespeed(), 1e-9);
		assertEquals(Boolean.TRUE, link("A_B.f1.north.exit").getAttributes().getAttribute("railsimExit"));
		assertEquals(Boolean.TRUE, link("A_B.f1.north.entry").getAttributes().getAttribute("railsimEntry"));
		assertEquals("A.north", link("A_B.f1.north.exit").getFromNode().getId().toString());
		assertEquals("B.south", link("A_B.f1.north.entry").getToNode().getId().toString());
		assertEquals("B.south", link("A_B.f1.south.exit").getFromNode().getId().toString());
		assertEquals("A.north", link("A_B.f1.south.entry").getToNode().getId().toString());
	}

	@Test
	void terminalTracksAreInAndOutLinksSharingOneResource() {
		Link in = link("A.p1.in");
		Link out = link("A.p1.out");
		assertEquals("A.p1", in.getAttributes().getAttribute("railsimResourceId"));
		assertEquals("A.p1", out.getAttributes().getAttribute("railsimResourceId"));
		assertEquals(1, in.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(Boolean.TRUE, in.getAttributes().getAttribute("stationLink"));
		assertEquals("A", in.getAttributes().getAttribute("microStation"));
		assertEquals("a_main", in.getAttributes().getAttribute("microGroup"));
		assertEquals("1", in.getAttributes().getAttribute("microTrack"));
		assertEquals(in.getToNode(), out.getFromNode());
		assertEquals(200, in.getLength(), 1e-9);
		assertEquals("provisional", in.getAttributes().getAttribute("dataStatus"));
	}

	@Test
	void capacityGroupsGetAnonymousTracksNetOfOtherOperators() {
		assertTrue(network.getLinks().containsKey(Id.createLinkId("A.a_shared.1.in")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("A.a_shared.2.in")));
		assertFalse(network.getLinks().containsKey(Id.createLinkId("A.a_shared.3.in")));
	}

	@Test
	void throughTracksAreDirectional() {
		Link p1 = link("B.p1");
		Link p2 = link("B.p2");
		assertEquals("B.p1.a", p1.getFromNode().getId().toString());
		assertEquals("B.p1.b", p1.getToNode().getId().toString());
		assertEquals("B.p2.b", p2.getFromNode().getId().toString());
		assertEquals("B.p2.a", p2.getToNode().getId().toString());
		assertEquals(250, p1.getLength(), 1e-9);
		assertNull(p1.getAttributes().getAttribute("dataStatus"));
		assertFalse(network.getLinks().containsKey(Id.createLinkId("B.p1.in")));
	}

	@Test
	void throatLinksShareTheThroatResourceAndAreNonBlocking() {
		Link approach = link("A.p1.north.in");
		assertEquals("A.north", approach.getFromNode().getId().toString());
		assertEquals("A.p1.b", approach.getToNode().getId().toString());
		assertEquals("a_throat", approach.getAttributes().getAttribute("railsimResourceId"));
		assertEquals(Boolean.TRUE, approach.getAttributes().getAttribute("railsimNonBlockingArea"));
		assertEquals(300, approach.getLength(), 1e-9);
		assertEquals(30 / 3.6, approach.getFreespeed(), 1e-9);
		assertEquals("A.north", link("A.p1.north.out").getToNode().getId().toString());

		Link plain = link("B.p1.south.in");
		assertNull(plain.getAttributes().getAttribute("railsimResourceId"));
		assertEquals(100, plain.getLength(), 1e-9);
		assertEquals("provisional", plain.getAttributes().getAttribute("dataStatus"));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("B.p1.north.out")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("B.p2.north.in")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("B.p2.south.out")));
		assertFalse(network.getLinks().containsKey(Id.createLinkId("B.p1.north.in")));
	}

	@Test
	void mesoLinksAreRedirectedToTheJunctionAndFlaggedEntryOrExit() {
		Link entry = link("X_A");
		assertEquals("A.north", entry.getToNode().getId().toString());
		assertEquals(Boolean.TRUE, entry.getAttributes().getAttribute("railsimEntry"));
		assertEquals(1000, entry.getLength(), 1e-9);
		Link exit = link("A_X");
		assertEquals("A.north", exit.getFromNode().getId().toString());
		assertEquals(Boolean.TRUE, exit.getAttributes().getAttribute("railsimExit"));
		assertEquals("B.north", link("C_B").getToNode().getId().toString());
		assertEquals("B.north", link("B_C").getFromNode().getId().toString());
		assertTrue(network.getNodes().get(Id.createNodeId("A")).getInLinks().isEmpty());
		assertEquals("test", network.getNodes().get(Id.createNodeId("A")).getAttributes().getAttribute("microNode"));
	}

	@Test
	void turnRestrictionsKeepTrainsOnConnectedGroupsAndThroughAPlatform() {
		Set<Id<Link>> fromX = disallowedAfter("X_A");
		assertTrue(fromX.contains(Id.createLinkId("A.p1.north.in")), "a_main is not reachable from X");
		assertTrue(fromX.contains(Id.createLinkId("A.p2.north.in")));
		assertFalse(fromX.contains(Id.createLinkId("A.a_shared.1.north.in")));
		assertTrue(fromX.contains(Id.createLinkId("A_B.f1.north.exit")), "no bypass of the station");

		Set<Id<Link>> fromSection = disallowedAfter("A_B.f1.south.entry");
		assertFalse(fromSection.contains(Id.createLinkId("A.p1.north.in")));
		assertTrue(fromSection.contains(Id.createLinkId("A_X")), "no bypass of the station");

		Set<Id<Link>> leavingP1 = disallowedAfter("A.p1.north.out");
		assertTrue(leavingP1.contains(Id.createLinkId("A_X")), "a_main does not lead to X");
		assertFalse(leavingP1.contains(Id.createLinkId("A_B.f1.north.exit")));
		assertTrue(leavingP1.contains(Id.createLinkId("A.p2.north.in")), "no hopping between platforms");
	}

	@Test
	void projectNodesSpliceIntoTheMesoNetwork() {
		Path nodes = Path.of("data", "nodes");
		Path meso = Path.of("scenarios", "milan", "network.xml");
		assumeTrue(Files.isDirectory(nodes) && Files.exists(meso));
		Network milan = NetworkUtils.readNetwork(meso.toString());
		int linksBefore = milan.getLinks().size();

		new MicroNodeBuilder(milan).splice(MicroNode.readAll(nodes));

		assertTrue(milan.getLinks().containsKey(Id.createLinkId("S01066.p10.in")));
		assertTrue(milan.getLinks().containsKey(Id.createLinkId("S01066_S01067.f1.north")));
		assertFalse(milan.getLinks().containsKey(Id.createLinkId("S01066_S01067")));
		assertEquals("S01642.south", milan.getLinks().get(Id.createLinkId("S01067_S01642.f1.north.entry")).getToNode().getId().toString());
		assertEquals(Boolean.TRUE, milan.getLinks().get(Id.createLinkId("S01643_S01642")).getAttributes().getAttribute("railsimEntry"));
		assertTrue(milan.getLinks().size() > linksBefore);
	}

	private Set<Id<Link>> disallowedAfter(String linkId) {
		DisallowedNextLinks restrictions = NetworkUtils.getDisallowedNextLinks(link(linkId));
		assertNotNull(restrictions, "no restrictions on " + linkId);
		return Set.copyOf(restrictions.getDisallowedLinkSequences("rail").stream().map(List::getFirst).toList());
	}

	private Link link(String id) {
		Link link = network.getLinks().get(Id.createLinkId(id));
		assertNotNull(link, "missing link " + id);
		return link;
	}

	private Node station(String id, double x, double y) {
		Node node = network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y));
		node.getAttributes().putAttribute("gtfsStopName", id);
		network.addNode(node);
		return node;
	}

	private void meso(Node from, Node to) {
		Link link = network.getFactory().createLink(Id.createLinkId(from.getId() + "_" + to.getId()), from, to);
		link.setLength(NetworkUtils.getEuclideanDistance(from.getCoord(), to.getCoord()));
		link.setFreespeed(30);
		link.setCapacity(3600);
		link.setNumberOfLanes(1);
		link.setAllowedModes(Set.of("rail"));
		link.getAttributes().putAttribute("railsimTrainCapacity", 2);
		link.getAttributes().putAttribute("tracksTotal", 4);
		network.addLink(link);
	}
}
