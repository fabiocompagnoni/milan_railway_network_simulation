package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TerminalCapacitiesTest {

	@Test
	void raisesStopCapacityToThePeakOfParkedCirculations() {
		Network network = TestNetworks.threeStationLine();
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		TransitScheduleBuilder.Result result = new TransitScheduleBuilder(
			feed, network, LocalDate.of(2026, 9, 16), new RouteVehicleAssignment()).build();
		VehicleCirculations.apply(result.schedule(), result.vehicles(), 15 * 60,
			new RouteVehicleAssignment());

		TerminalCapacities.apply(result.schedule(), network);

		// T1's circulation parks at S3 after 08:13 for the rest of the day,
		// TN's parks at S2: both stop links must fit the parked train plus one operating
		Link stopS3 = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("S3")));
		Link stopS2 = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("S2")));
		assertEquals(2, stopS3.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(2, stopS2.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals("provisional", stopS3.getAttributes().getAttribute("dataStatus"));
	}
}
