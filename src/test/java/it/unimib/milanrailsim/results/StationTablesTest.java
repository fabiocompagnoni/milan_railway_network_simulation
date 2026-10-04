package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.PlannedCall;
import it.unimib.milanrailsim.results.PunctualityAnalysis.StopVisit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StationTablesTest {

	private static final double THRESHOLD = 300;

	private static StopVisit visit(String vehicle, String trip, String stop, String destination, double planned, double delay) {
		return new StopVisit(vehicle, "S1", "S1_1", trip, 1, destination, stop, planned, planned + delay,
			planned + 60, planned + 60 + Math.max(delay, 0));
	}

	private static final List<StopVisit> VISITS = List.of(
		visit("S1_circ_1", "t1", "A", "C", 100, -30),
		visit("S1_circ_1", "t1", "B.p1|B|S1|through", "C", 400, 400),
		visit("S1_circ_1", "t1", "C", "C", 700, 120),
		visit("S1_circ_2", "t2", "A", "C", 200, 30),
		visit("S1_circ_2", "t2", "B.p2|B|S1|through", "C", 500, 60));

	@Test
	void groupsPlatformsOfAStationTogetherWorstFirst() {
		List<StationTables.StationRow> rows = StationTables.byStation(VISITS, THRESHOLD);

		assertEquals(List.of("B", "C", "A"), rows.stream().map(StationTables.StationRow::station).toList());
		StationTables.StationRow b = rows.getFirst();
		assertEquals(2, b.observations());
		assertEquals(230, b.meanDelaySeconds());
		assertEquals(400, b.maxDelaySeconds());
		assertEquals(400, b.p95DelaySeconds());
		assertEquals(50, b.latePercent());
		assertEquals(15, rows.getLast().meanDelaySeconds(), "the early arrival at A counts as no delay: (0 + 30) / 2");
	}

	@Test
	void ranksTrainsByTheirWorstArrival() {
		List<StationTables.TrainRow> rows = StationTables.byTrain(VISITS);

		assertEquals("S1_circ_1", rows.getFirst().vehicle());
		assertEquals(3, rows.getFirst().stops());
		assertEquals(400, rows.getFirst().maxDelaySeconds());
		assertEquals(120, rows.getFirst().finalDelaySeconds());
		assertEquals(60, rows.get(1).maxDelaySeconds());
	}

	@Test
	void countsPlannedAndActualCallsPerStationDirectionAndPlannedHour() {
		// three trains are planned at B towards C between 8 and 9; one never gets there, one is 6 minutes late
		// and passes after 9, yet stays in the hour it was planned in
		List<PlannedCall> planned = List.of(
			new PlannedCall("S1", "t1", "B.p1|B|S1|through", "C", 8 * 3600 + 600),
			new PlannedCall("S1", "t2", "B.p1|B|S1|through", "C", 8 * 3600 + 3500),
			new PlannedCall("S1", "t3", "B.p1|B|S1|through", "C", 8 * 3600 + 1800),
			new PlannedCall("S1", "t4", "B.p2|B|S1|through", "A", 8 * 3600 + 900),
			new PlannedCall("S1", "t5", "B.p1|B|S1|through", "C", 9 * 3600 + 60));
		List<StopVisit> visits = List.of(
			visit("v1", "t1", "B.p1|B|S1|through", "C", 8 * 3600 + 600, 60),
			visit("v2", "t2", "B.p2|B|S1|through", "C", 8 * 3600 + 3500, 360),
			visit("v4", "t4", "B.p2|B|S1|through", "A", 8 * 3600 + 900, -20));

		List<StationTables.HourRow> rows = StationTables.hourly(planned, visits, THRESHOLD);

		assertEquals(3, rows.size(), "B towards A at 8, B towards C at 8, B towards C at 9");
		StationTables.HourRow towardsC = rows.get(1);
		assertEquals("B", towardsC.station());
		assertEquals("C", towardsC.direction());
		assertEquals(8, towardsC.hour());
		assertEquals(3, towardsC.trainsPlanned());
		assertEquals(2, towardsC.trainsCalled());
		assertEquals(210, towardsC.meanDelaySeconds(), "(60 + 360) / 2");
		assertEquals(50, towardsC.punctualityPercent());
		assertEquals(0, rows.get(0).meanDelaySeconds(), "the early train towards A");
		assertEquals(0, rows.get(2).trainsCalled());
	}
}
