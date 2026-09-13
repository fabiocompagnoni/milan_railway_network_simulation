package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
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

	private TransitScheduleBuilder.Result result;

	@BeforeEach
	void scheduleFromFixture() {
		// fixture trips: T1 (S1->S2->S3, dep 08:01) and TN (S1->S2, dep 24:01), line S1
		Network network = TestNetworks.threeStationLine();
		GtfsFeed feed = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
		result = new TransitScheduleBuilder(feed, network, DATE, RouteVehicleAssignment.defaults()).build();
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
	void builderChainsAreOneVehicleEach() {
		// T1 ends at S3, TN starts at S1: no chain possible -> one vehicle each
		assertEquals(List.of(List.of("T1"), List.of("TN")), result.chains());

		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(), result.chains(),
			RouteVehicleAssignment.defaults());

		assertEquals(2, vehicleIds(line()).size());
		assertEquals(2, circulated.getVehicles().size());
	}

	@Test
	void aChainPutsAllItsTripsOnOneVehicle() {
		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(),
			List.of(List.of("T1", "TN")), RouteVehicleAssignment.defaults());

		assertEquals(1, vehicleIds(line()).size());
		Vehicle vehicle = circulated.getVehicles().values().iterator().next();
		assertEquals("S1_circ_1", vehicle.getId().toString());
		assertEquals("T1,TN", vehicle.getAttributes().getAttribute("servedTrips"));
		for (Departure departure : line().getRoutes().values().stream()
				.flatMap(route -> route.getDepartures().values().stream()).toList()) {
			assertEquals(vehicle.getId(), departure.getVehicleId());
		}
	}

	@Test
	void circulationVehiclesCarryTheLineType() {
		Vehicles circulated = VehicleCirculations.apply(result.schedule(), result.vehicles(), result.chains(),
			RouteVehicleAssignment.defaults());

		// two circulations on a 70/30 line: the first takes the majority type, the second the minority
		for (Vehicle vehicle : circulated.getVehicles().values()) {
			assertTrue(Set.of("tsr", "taf").contains(vehicle.getType().getId().toString()));
		}
	}

	@Test
	void rejectsChainsWithUnknownTrips() {
		assertThrows(IllegalArgumentException.class, () -> VehicleCirculations.apply(result.schedule(),
			result.vehicles(), List.of(List.of("nope")), RouteVehicleAssignment.defaults()));
	}
}
