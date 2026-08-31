package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CreateNetworkSkeletonFromGtfsTest {

	@TempDir
	Path outputDir;

	@Test
	void writesNetworkReadableByMatsimWithAllAttributes() {
		Path output = outputDir.resolve("nested/dir/skeleton.xml");

		CreateNetworkSkeletonFromGtfs.run(
			Path.of("src/test/resources/gtfs-minimal"), LocalDate.of(2026, 9, 16), output);

		assertTrue(Files.exists(output));
		Network reread = NetworkUtils.readNetwork(output.toString());
		// Active rail trips: T1 (S1->S2->S3) and TN (S1->S2) => 3 stations, links S1_S2 and S2_S3.
		assertEquals(3, reread.getNodes().size());
		assertEquals(2, reread.getLinks().size());
		Link link = reread.getLinks().get(Id.createLinkId("S1_S2"));
		assertEquals("provisional", link.getAttributes().getAttribute("dataStatus"));
		assertEquals(1, link.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("S1", link.getAttributes().getAttribute("gtfsRoutes"));
		assertEquals(2, link.getAttributes().getAttribute("gtfsDailyTrips"));
		assertEquals(240, link.getAttributes().getAttribute("gtfsMinTravelTimeSeconds"));
	}
}
