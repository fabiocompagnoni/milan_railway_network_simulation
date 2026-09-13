package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.StationTracks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

	@Test
	void inheritsTheMaximumAcrossIncidentLinks() {
		Network network = NetworkUtils.createNetwork();
		Node hub = network.getFactory().createNode(Id.createNodeId("H"), new Coord(0, 0));
		Node east = network.getFactory().createNode(Id.createNodeId("E"), new Coord(1000, 0));
		Node west = network.getFactory().createNode(Id.createNodeId("W"), new Coord(-1000, 0));
		network.addNode(hub);
		network.addNode(east);
		network.addNode(west);
		Link singleTrack = network.getFactory().createLink(Id.createLinkId("W_H"), west, hub);
		singleTrack.getAttributes().putAttribute("railsimTrainCapacity", 1);
		network.addLink(singleTrack);
		Link doubleTrack = network.getFactory().createLink(Id.createLinkId("H_E"), hub, east);
		doubleTrack.getAttributes().putAttribute("railsimTrainCapacity", 2);
		network.addLink(doubleTrack);

		StationStopLinks.addStopLinks(network);

		Link stopHub = network.getLinks().get(StationStopLinks.stopLinkId(hub.getId()));
		assertEquals(2, stopHub.getAttributes().getAttribute("railsimTrainCapacity"));
	}

	@Test
	void isolatedStationFallsBackToSingleTrackCapacity() {
		Network network = NetworkUtils.createNetwork();
		network.addNode(network.getFactory().createNode(Id.createNodeId("X"), new Coord(0, 0)));

		StationStopLinks.addStopLinks(network);

		Link stop = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("X")));
		assertEquals(1, stop.getAttributes().getAttribute("railsimTrainCapacity"));
	}

	@Test
	void doubleTrackStationHoldsOneTrainPerDirection() {
		Network network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("S1"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("S2"), new Coord(1000, 0));
		network.addNode(a);
		network.addNode(b);
		Link ab = network.getFactory().createLink(Id.createLinkId("S1_S2"), a, b);
		ab.getAttributes().putAttribute("railsimTrainCapacity", 1);
		ab.getAttributes().putAttribute("tracksTotal", 2);
		network.addLink(ab);

		StationStopLinks.addStopLinks(network);

		assertEquals(2, network.getLinks().get(StationStopLinks.stopLinkId(a.getId()))
			.getAttributes().getAttribute("railsimTrainCapacity"));
	}

	@Test
	void surveyedPlatformsOverrideEverythingAndAreNotProvisional(@TempDir Path dir) throws IOException {
		Network network = NetworkUtils.createNetwork();
		network.addNode(network.getFactory().createNode(Id.createNodeId("S09999"), new Coord(0, 0)));
		network.addNode(network.getFactory().createNode(Id.createNodeId("S01087"), new Coord(0, 0)));
		StationTracks tracks = StationTracks.read(Files.writeString(dir.resolve("t.csv"), """
			stop_id,nome,corse_giorno,banchine_OSM_da_correggere,linee,binari_reali
			S09999,Brescia,208,8,"R1,R3",12
			S01087,Meda,6,2,S2,
			"""));

		StationStopLinks.addStopLinks(network, tracks);

		Link brescia = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("S09999")));
		assertEquals(12, brescia.getAttributes().getAttribute("railsimTrainCapacity"));
		assertNull(brescia.getAttributes().getAttribute("dataStatus"));
		Link meda = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("S01087")));
		assertEquals(2, meda.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("provisional", meda.getAttributes().getAttribute("dataStatus"));
	}

	@Test
	void microStationsAndTheirInternalNodesGetNoLoopLink() {
		Network network = NetworkUtils.createNetwork();
		Node hub = network.getFactory().createNode(Id.createNodeId("S1"), new Coord(0, 0));
		hub.getAttributes().putAttribute("microNode", "node");
		Node junction = network.getFactory().createNode(Id.createNodeId("S1.north"), new Coord(0, 300));
		Node meso = network.getFactory().createNode(Id.createNodeId("S2"), new Coord(1000, 0));
		network.addNode(hub);
		network.addNode(junction);
		network.addNode(meso);

		StationStopLinks.addStopLinks(network);

		assertFalse(network.getLinks().containsKey(StationStopLinks.stopLinkId(hub.getId())));
		assertFalse(network.getLinks().containsKey(StationStopLinks.stopLinkId(junction.getId())));
		assertTrue(network.getLinks().containsKey(StationStopLinks.stopLinkId(meso.getId())));
	}
}
