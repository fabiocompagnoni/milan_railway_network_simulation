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
	void suburbanRoutesRunMostlyTsrWithTheOtherStockMixedIn() {
		assertEquals(Map.of("tsr", 6, "caravaggio_521", 2, "etr245", 1, "taf", 1), countOverDepartures("S1", 10));
		assertEquals(Map.of("tsr", 60, "caravaggio_521", 20, "etr245", 10, "taf", 10), countOverDepartures("S19", 100));
	}

	@Test
	void theTwoSuburbanLinesOfTheAssoAndSaronnoBranchesRunMostlyCaravaggio() {
		assertEquals(Map.of("caravaggio_521", 7, "tsr", 3), countOverDepartures("S3", 10));
		assertEquals(Map.of("caravaggio_521", 70, "tsr", 30), countOverDepartures("S4", 100));
	}

	@Test
	void minorityStockIsSpreadThroughTheDay() {
		assertEquals("tsr", assignment.vehicleTypeId("S1", 0));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("S1", 1));
		assertEquals("tsr", assignment.vehicleTypeId("S1", 2));
	}

	@Test
	void dedicatedAssignments() {
		assertEquals("atr125", assignment.vehicleTypeId("S7", 0));
		assertEquals("atr125", assignment.vehicleTypeId("R18", 3));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("S11", 0));
		assertEquals("caravaggio_421", assignment.vehicleTypeId("RE54", 5));
		assertEquals("donizetti", assignment.vehicleTypeId("R34", 0));
		assertEquals("donizetti", assignment.vehicleTypeId("RE8", 4));
		assertEquals("donizetti", assignment.vehicleTypeId("R6", 0));
		assertEquals("tilo_flirt_tsi", assignment.vehicleTypeId("RE80", 0));
	}

	@Test
	void regionalLinesWithNoRuleOfTheirOwnShareTheRegionalMix() {
		assertEquals(Map.of("caravaggio_521", 60, "tsr", 30, "etr425", 10), countOverDepartures("R38", 100));
		assertEquals("caravaggio_521", assignment.vehicleTypeId("RE2", 0));
	}

	@Test
	void onlySpecialServicesAreExcluded() {
		assertTrue(assignment.isExcluded("Trenord GP"));
		assertFalse(assignment.isExcluded("S10"));
		assertFalse(assignment.isExcluded("RE80"));
		assertThrows(IllegalArgumentException.class, () -> assignment.vehicleTypeId("Trenord GP", 0));
	}

	@Test
	void tiloLinesRankTheirTrainsByPeakLoad() {
		assertTrue(assignment.ranksByPeakLoad("S10"));
		assertTrue(assignment.ranksByPeakLoad("S50"));
		assertFalse(assignment.ranksByPeakLoad("RE80"));
		assertFalse(assignment.ranksByPeakLoad("S1"));
	}

	@Test
	void theBusiestTiloTrainsAreSixCarSets() {
		assertEquals(List.of("tilo_flirt_6", "tilo_flirt_6", "tilo_flirt_6", "tilo_flirt_6",
			"tilo_flirt_4", "tilo_flirt_4", "tilo_flirt_4"), assignment.vehicleTypeIdsByRank("S10", 7));
		assertEquals(List.of("tilo_flirt_6"), assignment.vehicleTypeIdsByRank("S30", 1));
		assertEquals(List.of(), assignment.vehicleTypeIdsByRank("S40", 0));
	}

	@Test
	void sharesAreKeptWhenRankingByPeakLoad() {
		List<String> ranked = assignment.vehicleTypeIdsByRank("S50", 100);
		assertEquals(57, ranked.stream().filter("tilo_flirt_6"::equals).count());
		assertEquals(43, ranked.stream().filter("tilo_flirt_4"::equals).count());
		assertEquals("tilo_flirt_6", ranked.getFirst());
		assertEquals("tilo_flirt_4", ranked.getLast());
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
