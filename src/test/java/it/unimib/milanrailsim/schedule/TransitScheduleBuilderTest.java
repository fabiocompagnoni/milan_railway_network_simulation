package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.MicroNodeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.network.NetworkUtils;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.IOException;
import java.nio.file.Files;
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

	@Test
	void timeWindowKeepsTripsByFirstDeparture() {
		// TN departs at 24:01 and falls outside 07:00–09:00; T1 at 08:01 stays
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		TransitScheduleBuilder.Result result = new TransitScheduleBuilder(feed, network, DATE,
			RouteVehicleAssignment.defaults()).withWindow(7 * 3600, 9 * 3600).build();

		int departures = result.schedule().getTransitLines().values().stream()
			.flatMap(line -> line.getRoutes().values().stream())
			.mapToInt(route -> route.getDepartures().size()).sum();
		assertEquals(1, departures);
	}

	private static final String MICRO_NODE = """
		{
			"node": "fixture", "title": "Fixture node",
			"stations": [
				{"id": "S1", "name": "S1", "kind": "terminal", "platformLengthM": null,
					"groups": [{"id": "s1_main", "kind": "terminal", "tracks": [{"ref": "1", "direction": null}, {"ref": "2", "direction": null}],
						"connections": {"north": ["segment:S1_S2:f1"]}}], "throats": []},
				{"id": "S2", "name": "S2", "kind": "through", "platformLengthM": null,
					"groups": [{"id": "s2_f1", "kind": "through", "tracks": [{"ref": "1", "direction": "north"}, {"ref": "2", "direction": "south"}],
						"connections": {"south": ["segment:S1_S2:f1"], "north": ["meso:S3"]}}], "throats": []}
			],
			"segments": [{"from": "S1", "to": "S2", "bundles": {"f1": {
				"north": {"wayIds": [], "lengthM": 2000}, "south": {"wayIds": [], "lengthM": 2000}, "speedProfile": []}}}],
			"lines": {"S1": {"bundle": "f1", "stations": {"S1": ["s1_main"], "S2": ["s2_f1"]}}}
		}
		""";

	@Test
	void microStationsGetPlatformFacilitiesAndRoutesThroughTheirTracks(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("fixture.json");
		Files.writeString(file, MICRO_NODE);
		List<MicroNode> nodes = List.of(MicroNode.read(file));
		new MicroNodeBuilder(network).splice(nodes);
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));

		TransitScheduleBuilder.Result result = new TransitScheduleBuilder(feed, network, DATE, RouteVehicleAssignment.defaults())
			.withMicroNodes(nodes).build();

		TransitLine line = result.schedule().getTransitLines().get(Id.create("S1", TransitLine.class));
		TransitRoute t1Route = line.getRoutes().values().stream()
			.filter(r -> r.getStops().size() == 3).findFirst().orElseThrow();
		List<Id<Link>> chain = new java.util.ArrayList<>();
		chain.add(t1Route.getRoute().getStartLinkId());
		chain.addAll(t1Route.getRoute().getLinkIds());
		chain.add(t1Route.getRoute().getEndLinkId());
		assertEquals(List.of("S1.p1.in", "S1.p1.out", "S1.p1.north.S1_S2.f1.out", "S1_S2.f1.north.exit", "S1_S2.f1.north",
				"S1_S2.f1.north.entry", "S2.p1.south.S1_S2.f1.in", "S2.p1", "S2.p1.north.S3.out", "S2_S3", "stop_S3"),
			chain.stream().map(Id::toString).toList());

		TransitStopFacility first = t1Route.getStops().getFirst().getStopFacility();
		assertEquals("S1.p1.in|S1|S1|terminal", first.getId().toString());
		assertEquals("S1|S1|terminal", first.getStopAreaId().toString());
		assertEquals("S1", first.getName());
		assertNotNull(result.schedule().getFacilities().get(Id.create("S1.p2.in|S1|S1|terminal", TransitStopFacility.class)),
			"every platform of the area is a facility");
		TransitStopFacility second = t1Route.getStops().get(1).getStopFacility();
		assertEquals("S2|S1|through", second.getStopAreaId().toString());
		assertEquals("S3", t1Route.getStops().getLast().getStopFacility().getId().toString());
	}

	@Test
	void tripsCallingOutsideTheNetworkAreSkipped() {
		// a network without S3: T1 (S1-S2-S3) is dropped, TN (S1-S2) survives
		Network twoStations = NetworkUtils.createNetwork();
		for (String station : List.of("S1", "S2")) {
			twoStations.addNode(twoStations.getFactory().createNode(Id.createNodeId(station), new Coord(0, 0)));
		}
		Link link = twoStations.getFactory().createLink(Id.createLinkId("S1_S2"),
			twoStations.getNodes().get(Id.createNodeId("S1")), twoStations.getNodes().get(Id.createNodeId("S2")));
		link.setLength(1000);
		link.setFreespeed(20);
		link.setAllowedModes(java.util.Set.of("rail"));
		twoStations.addLink(link);
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));

		TransitScheduleBuilder.Result result = new TransitScheduleBuilder(feed, twoStations, DATE,
			RouteVehicleAssignment.defaults()).build();

		int departures = result.schedule().getTransitLines().values().stream()
			.flatMap(line -> line.getRoutes().values().stream())
			.mapToInt(route -> route.getDepartures().size()).sum();
		assertEquals(1, departures);
	}
}
