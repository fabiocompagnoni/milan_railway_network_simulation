package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSkeletonBuilderTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 9, 16);

	private Network network;

	@BeforeEach
	void buildFromFixture() {
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		network = new NetworkSkeletonBuilder(feed, SERVICE_DATE).build();
	}

	@Test
	void createsOneNodePerServedStation() {
		// S3 is reached by active rail trip T1 (S1->S2->S3), so it must be present too;
		// only stops that no active rail trip ever visits would be absent.
		assertNotNull(network.getNodes().get(Id.createNodeId("S1")));
		assertNotNull(network.getNodes().get(Id.createNodeId("S2")));
		assertNotNull(network.getNodes().get(Id.createNodeId("S3")));
	}

	@Test
	void createsOnlyObservedDirections() {
		// Active rail trips (T1, TN) only ever run S1->S2->S3; reverse links must not exist.
		assertNotNull(network.getLinks().get(Id.createLinkId("S1_S2")));
		assertNull(network.getLinks().get(Id.createLinkId("S2_S1")));
	}

	@Test
	void excludesBusTripsAndInactiveServices() {
		// T3 (bus) would create S1_S3; T2 (inactive) would create S3_S2 and S2_S1.
		assertNull(network.getLinks().get(Id.createLinkId("S1_S3")));
		assertNull(network.getLinks().get(Id.createLinkId("S3_S2")));
	}

	@Test
	void aggregatesTripsIntoLinkAttributes() {
		Link link = network.getLinks().get(Id.createLinkId("S1_S2"));
		// T1 (08:01 -> 08:06 = 300 s) and TN (24:01 -> 24:05 = 240 s).
		assertEquals(2, link.getAttributes().getAttribute("gtfsDailyTrips"));
		assertEquals(240, link.getAttributes().getAttribute("gtfsMinTravelTimeSeconds"));
		assertEquals("SX", link.getAttributes().getAttribute("gtfsRoutes"));
	}

	@Test
	void marksEverythingProvisional() {
		Link link = network.getLinks().get(Id.createLinkId("S1_S2"));
		assertEquals("provisional", link.getAttributes().getAttribute("dataStatus"));
		assertEquals(1, link.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(Set.of("rail"), link.getAllowedModes());
	}

	@Test
	void derivesProvisionalFreespeedFromMinTravelTime() {
		Link link = network.getLinks().get(Id.createLinkId("S1_S2"));
		assertEquals(link.getLength() / 240.0, link.getFreespeed(), 1e-9);
		assertTrue(link.getLength() > 1000, "beeline S1-S2 must be kilometres, got " + link.getLength());
	}

	@Test
	void stationNameIsKeptOnNode() {
		assertEquals("Alfa",
			network.getNodes().get(Id.createNodeId("S1")).getAttributes().getAttribute("gtfsStopName"));
	}
}
