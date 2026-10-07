package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteVehicleAssignmentTest {

	private final RouteVehicleAssignment assignment = RouteVehicleAssignment.defaults();

	private Map<String, Integer> countOverDepartures(String line, int departures) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < departures; i++) {
			counts.merge(assignment.vehicleTypeId(line, i), 1, Integer::sum);
		}
		return counts;
	}

	@Test
	void suburbanRoutesRunSeventyThirtyOverTenDepartures() {
		assertEquals(Map.of("tsr", 7, "taf", 3), countOverDepartures("S1", 10));
		assertEquals(Map.of("tsr", 70, "taf", 30), countOverDepartures("S19", 100));
	}

	@Test
	void minorityStockIsSpreadThroughTheDay() {
		assertEquals("tsr", assignment.vehicleTypeId("S1", 0));
		assertEquals("taf", assignment.vehicleTypeId("S1", 1));
		assertEquals("tsr", assignment.vehicleTypeId("S1", 2));
	}

	@Test
	void dedicatedAssignments() {
		assertEquals("atr125", assignment.vehicleTypeId("S7", 0));
		assertEquals("atr125", assignment.vehicleTypeId("R18", 3));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("S11", 0));
		assertEquals("caravaggio_421", assignment.vehicleTypeId("RE54", 5));
		assertEquals("donizetti", assignment.vehicleTypeId("R34", 0));
		assertEquals("tilo_flirt_tsi", assignment.vehicleTypeId("RE80", 0));
	}

	@Test
	void regionalDefaultIsDeclared() {
		assertEquals("caravaggio_521", assignment.vehicleTypeId("R38", 0));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("RE2", 0));
	}

	@Test
	void tiloLinesAreExcluded() {
		assertTrue(assignment.isExcluded("S10"));
		assertTrue(assignment.isExcluded("Trenord GP"));
		assertFalse(assignment.isExcluded("RE80"));
		assertThrows(IllegalArgumentException.class, () -> assignment.vehicleTypeId("S10", 0));
	}

	@Test
	void unknownRouteFailsFast() {
		assertThrows(IllegalArgumentException.class, () -> assignment.vehicleTypeId("X99", 0));
	}

	@Test
	void configuredSharesOverrideTheDefaults() {
		LineAssignments custom = LineAssignments.defaults()
			.with("S1", List.of(new LineAssignments.Share("caravaggio_421", 50), new LineAssignments.Share("tsr", 50)));

		RouteVehicleAssignment assignment = new RouteVehicleAssignment(custom);

		assertEquals("caravaggio_421", assignment.vehicleTypeId("S1", 0));
		assertEquals("tsr", assignment.vehicleTypeId("S1", 1));
	}

	@Test
	void sharesMustSumToOneHundred() {
		assertThrows(IllegalArgumentException.class, () -> LineAssignments.defaults()
			.with("S1", List.of(new LineAssignments.Share("tsr", 60))));
	}

	@Test
	void assignmentsRoundTripThroughJson(@TempDir Path dir) {
		Path file = dir.resolve("assignments.json");
		LineAssignments.defaults().write(file);

		assertEquals(LineAssignments.defaults(), LineAssignments.read(file));
	}
}
