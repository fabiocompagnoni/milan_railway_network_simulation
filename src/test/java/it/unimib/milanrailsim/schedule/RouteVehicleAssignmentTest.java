package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteVehicleAssignmentTest {

	private final RouteVehicleAssignment assignment = new RouteVehicleAssignment();

	@Test
	void suburbanRoutesAlternateSeventyThirty() {
		for (int i = 0; i <= 6; i++) {
			assertEquals("tsr", assignment.vehicleTypeId("S1", i));
		}
		for (int i = 7; i <= 9; i++) {
			assertEquals("taf", assignment.vehicleTypeId("S1", i));
		}
		assertEquals("tsr", assignment.vehicleTypeId("S19", 10));
	}

	@Test
	void dedicatedAssignments() {
		assertEquals("atr125", assignment.vehicleTypeId("S7", 0));
		assertEquals("atr125", assignment.vehicleTypeId("R18", 3));
		assertEquals("atr125", assignment.vehicleTypeId("R3", 0));
		assertEquals("atr125", assignment.vehicleTypeId("RE3", 0));
		assertEquals("atr125", assignment.vehicleTypeId("R9", 0));
		assertEquals("atr125", assignment.vehicleTypeId("S31", 0));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("S11", 0));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("RE1", 0));
		assertEquals("caravaggio_421", assignment.vehicleTypeId("RE54", 5));
		assertEquals("caravaggio_421", assignment.vehicleTypeId("RE51", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("RE13", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("R34", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("R35", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("R36", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("R37", 0));
		assertEquals("tilo_flirt_tsi", assignment.vehicleTypeId("RE80", 0));
	}

	@Test
	void alternationRestartsEveryTenDepartures() {
		assertEquals("taf", assignment.vehicleTypeId("S1", 69));
		assertEquals("tsr", assignment.vehicleTypeId("S1", 70));
	}

	@Test
	void regionalDefaultIsDeclared() {
		assertEquals("caravaggio_521", assignment.vehicleTypeId("R38", 0));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("RE2", 0));
	}

	@Test
	void tiloLinesAreExcluded() {
		assertTrue(assignment.isExcluded("S10"));
		assertTrue(assignment.isExcluded("S40"));
		assertTrue(assignment.isExcluded("Trenord GP"));
		assertFalse(assignment.isExcluded("RE80"));
		assertFalse(assignment.isExcluded("S1"));
	}

	@Test
	void unknownRouteFailsFast() {
		assertThrows(IllegalArgumentException.class, () -> assignment.vehicleTypeId("X99", 0));
	}
}
