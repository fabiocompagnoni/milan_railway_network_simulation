package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitScheduleBuilderTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

	private Network network;

	@BeforeEach
	void networkForFixture() {
		network = TestNetworks.threeStationLine();
	}

	private TransitScheduleBuilder.Result build() {
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		return new TransitScheduleBuilder(feed, network, DATE, RouteVehicleAssignment.defaults()).build();
	}

	@Test
	void buildsLineRoutesAndDepartures() {
		TransitScheduleBuilder.Result result = build();
		TransitLine line = result.schedule().getTransitLines().get(Id.create("S1", TransitLine.class));
		assertNotNull(line);
		int departures = line.getRoutes().values().stream()
			.mapToInt(r -> r.getDepartures().size()).sum();
		assertEquals(2, departures); // T1 and TN; T2 inactive, T3 bus
	}

	@Test
	void offsetsComeFromGtfsTimes() {
		TransitLine line = build().schedule().getTransitLines().get(Id.create("S1", TransitLine.class));
		TransitRoute t1Route = line.getRoutes().values().stream()
			.filter(r -> r.getStops().size() == 3).findFirst().orElseThrow();
		// T1: departs S1 08:01; S2 arrival 08:06 (+300 s), departure 08:07 (+360 s)
		assertEquals(300.0, t1Route.getStops().get(1).getArrivalOffset().seconds());
		assertEquals(360.0, t1Route.getStops().get(1).getDepartureOffset().seconds());
		assertTrue(t1Route.getStops().stream().allMatch(
			org.matsim.pt.transitSchedule.api.TransitRouteStop::isAwaitDepartureTime));
		Departure dep = t1Route.getDepartures().values().iterator().next();
		assertEquals(8 * 3600 + 60, dep.getDepartureTime());
	}

	@Test
	void routeChainIncludesStopAndTrackLinks() {
		TransitLine line = build().schedule().getTransitLines().get(Id.create("S1", TransitLine.class));
		TransitRoute t1Route = line.getRoutes().values().stream()
			.filter(r -> r.getStops().size() == 3).findFirst().orElseThrow();
		List<Id<Link>> chain = new java.util.ArrayList<>();
		chain.add(t1Route.getRoute().getStartLinkId());
		chain.addAll(t1Route.getRoute().getLinkIds());
		chain.add(t1Route.getRoute().getEndLinkId());
		assertEquals(List.of(Id.createLinkId("stop_S1"), Id.createLinkId("S1_S2"),
			Id.createLinkId("stop_S2"), Id.createLinkId("S2_S3"), Id.createLinkId("stop_S3")), chain);
	}

	@Test
	void oneVehiclePerTripWithAssignedType() {
		TransitScheduleBuilder.Result result = build();
		assertNotNull(result.vehicles().getVehicles().get(Id.createVehicleId("T1")));
		assertNotNull(result.vehicles().getVehicles().get(Id.createVehicleId("TN")));
		assertEquals(8, result.vehicles().getVehicleTypes().size());
	}
}
