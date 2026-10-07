package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VehicleCirculationsTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 16);
	private static final int TURNAROUND_SECONDS = 15 * 60;

	private TransitScheduleBuilder.Result result;

	@BeforeEach
	void scheduleFromFixture() {
		// fixture trips: T1 (S1->S2->S3, dep 08:01) and TN (S1->S2, dep 24:01), line S1
		Network network = TestNetworks.threeStationLine();
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		result = new TransitScheduleBuilder(feed, network, DATE, new RouteVehicleAssignment()).build();
	}

	private TransitLine line() {
		return result.schedule().getTransitLines().get(Id.create("S1", TransitLine.class));
	}

	private static Set<Id<Vehicle>> vehicleIds(TransitLine line) {
		Set<Id<Vehicle>> ids = new HashSet<>();
		line.getRoutes().values().forEach(route ->
			route.getDepartures().values().forEach(dep -> ids.add(dep.getVehicleId())));
		return ids;
	}

	@Test
	void chainsReturnTripOntoTheSameVehicle() {
		// add a return trip S3->S1 leaving after T1 arrives (08:13) plus turnaround
		TransitSchedule schedule = result.schedule();
		var factory = schedule.getFactory();
		var back = factory.createTransitRoute(Id.create("S1_back", org.matsim.pt.transitSchedule.api.TransitRoute.class),
			null,
			List.of(factory.createTransitRouteStop(
					schedule.getFacilities().get(Id.create("S3", org.matsim.pt.transitSchedule.api.TransitStopFacility.class)), 0, 0),
				factory.createTransitRouteStop(
					schedule.getFacilities().get(Id.create("S1", org.matsim.pt.transitSchedule.api.TransitStopFacility.class)), 600, 600)),
			"rail");
		Departure ret = factory.createDeparture(Id.create("TR", Departure.class), 8 * 3600 + 13 * 60 + TURNAROUND_SECONDS);
		ret.setVehicleId(Id.create("TR", Vehicle.class));
		back.addDeparture(ret);
		line().addRoute(back);
		result.vehicles().addVehicle(org.matsim.vehicles.VehicleUtils.createVehicle(
			Id.create("TR", Vehicle.class),
			result.vehicles().getVehicleTypes().values().iterator().next()));

		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(),
			TURNAROUND_SECONDS, new RouteVehicleAssignment());

		// full chain: T1 ends S3 08:13, TR starts S3 08:28, ends S1 08:38,
		// and the after-midnight TN from S1 continues on the same vehicle
		assertEquals(1, vehicleIds(line()).size());
		Vehicle vehicle = circulated.getVehicles().values().iterator().next();
		assertEquals("T1,TR,TN", vehicle.getAttributes().getAttribute("servedTrips"));
	}

	@Test
	void tooShortTurnaroundStartsANewVehicle() {
		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(),
			TURNAROUND_SECONDS, new RouteVehicleAssignment());

		// T1 ends at S3, TN starts at S1: no chain possible -> one vehicle each
		assertEquals(2, vehicleIds(line()).size());
		assertEquals(2, circulated.getVehicles().size());
	}

	@Test
	void circulationVehiclesCarryServedTripsAndLineType() {
		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(),
			TURNAROUND_SECONDS, new RouteVehicleAssignment());

		for (Vehicle vehicle : circulated.getVehicles().values()) {
			assertEquals("tsr", vehicle.getType().getId().toString());
			assertNotNull(vehicle.getAttributes().getAttribute("servedTrips"));
		}
	}
}
