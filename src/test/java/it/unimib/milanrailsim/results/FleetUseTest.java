package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FleetUseTest {

	private static final int HOUR = 3600;
	private static final Map<String, String> TYPES = Map.of("S1_circ_1", "tsr", "S1_circ_2", "tsr", "R1_circ_1", "atr125");

	private static TripOutcome completed(String trip, String line, String vehicle, double departure, double arrival) {
		return new TripOutcome(trip, line, line + "_1", vehicle, "A", "B", departure, arrival, arrival, 2, 2, TripStatus.COMPLETED);
	}

	@Test
	void trainsAreCountedOnceByTypeAndUnderTheLineOfTheirFirstTrip() {
		FleetUse fleet = FleetUse.of(List.of(
			completed("t1", "S1", "S1_circ_1", 8 * HOUR, 8 * HOUR + 600),
			completed("t2", "S1", "S1_circ_1", 9 * HOUR, 9 * HOUR + 600),
			completed("t3", "S1", "S1_circ_2", 8 * HOUR, 8 * HOUR + 600),
			// a train shared by two lines leaves first as R1, then runs an S1 trip
			completed("t4", "R1", "R1_circ_1", 7 * HOUR, 7 * HOUR + 600),
			completed("t5", "S1", "R1_circ_1", 10 * HOUR, 10 * HOUR + 600)), TYPES);

		assertEquals(3, fleet.trains().size());
		assertEquals(Map.of("tsr", 2, "atr125", 1), fleet.byType());
		assertEquals(Map.of("S1", Map.of("tsr", 2), "R1", Map.of("atr125", 1)), fleet.byLineAndType());
		assertEquals(new FleetUse.Train("R1_circ_1", "atr125", "R1", 2), fleet.trains().getFirst());
	}

	@Test
	void anHourHoldsTheMostTrainsRunningTogetherAndEveryTrainThatRanInIt() {
		FleetUse fleet = FleetUse.of(List.of(
			// 8:00-8:20 and 8:10-8:30 overlap; the diesel runs alone at 8:40
			completed("t1", "S1", "S1_circ_1", 8 * HOUR, 8 * HOUR + 1200),
			completed("t2", "S1", "S1_circ_2", 8 * HOUR + 600, 8 * HOUR + 1800),
			completed("t3", "R1", "R1_circ_1", 8 * HOUR + 2400, 8 * HOUR + 3000)), TYPES);

		FleetUse.Hour eight = fleet.hours().getFirst();
		assertEquals(8, eight.hour());
		assertEquals(2, eight.peak());
		assertEquals(Map.of("tsr", 2), eight.peakByType(), "the trains running at the busiest instant of the hour");
		assertEquals(3, eight.active());
		assertEquals(Map.of("tsr", 2, "atr125", 1), eight.activeByType());
		assertEquals(eight, fleet.busiestHour().orElseThrow());
	}

	@Test
	void aTripAcrossTwoHoursCountsInBothAndATrainWithTwoTripsInAnHourIsActiveOnce() {
		FleetUse fleet = FleetUse.of(List.of(
			completed("t1", "S1", "S1_circ_1", 8 * HOUR + 3000, 9 * HOUR + 600),
			completed("t2", "S1", "S1_circ_1", 9 * HOUR + 1200, 9 * HOUR + 1800)), TYPES);

		assertEquals(List.of(8, 9), fleet.hours().stream().map(FleetUse.Hour::hour).toList());
		assertEquals(1, fleet.hours().get(1).active(), "two trips of the same train");
		assertEquals(1, fleet.hours().get(1).peak());
	}

	@Test
	void aTripThatDidNotCompleteCountsOverItsPlannedTimes() {
		TripOutcome interrupted = new TripOutcome("t1", "S1", "S1_1", "S1_circ_1", "A", "B", 8 * HOUR, 8 * HOUR + 600,
			Double.NaN, 2, 1, TripStatus.INTERRUPTED);

		FleetUse fleet = FleetUse.of(List.of(interrupted), TYPES);

		assertEquals(1, fleet.trains().size());
		assertEquals(List.of(8), fleet.hours().stream().map(FleetUse.Hour::hour).toList());
	}

	@Test
	void aLateTrainWhoseNextTripNeverLeavesIsStillOneTrain() {
		TripOutcome late = completed("t1", "S1", "S1_circ_1", 8 * HOUR, 8 * HOUR + 2400);
		TripOutcome neverLeft = new TripOutcome("t2", "S1", "S1_1", "S1_circ_1", "B", "A", 8 * HOUR + 1200, 8 * HOUR + 1800,
			Double.NaN, 2, 0, TripStatus.NEVER_DEPARTED);

		FleetUse.Hour eight = FleetUse.of(List.of(late, neverLeft), TYPES).hours().getFirst();

		assertEquals(1, eight.peak());
		assertEquals(1, eight.active());
	}

	@Test
	void aTrainWithNoKnownTypeIsRefused() {
		assertThrows(IllegalArgumentException.class,
			() -> FleetUse.of(List.of(completed("t1", "S9", "S9_circ_1", 0, 600)), TYPES));
	}
}
