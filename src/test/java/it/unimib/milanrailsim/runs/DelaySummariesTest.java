package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.runs.RunResults.VisitRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelaySummariesTest {

	private static VisitRow visit(String vehicle, String line, String stop, double plannedArrival, double delay) {
		return new VisitRow(vehicle, line, line + "_1", stop, plannedArrival, plannedArrival + delay, delay,
			plannedArrival + 60, plannedArrival + 60 + delay, delay);
	}

	private static final List<VisitRow> VISITS = List.of(
		visit("S1_circ_1", "S1", "A", 100, 0),
		visit("S1_circ_1", "S1", "B.p1|B|S1|through", 400, 400),
		visit("S1_circ_1", "S1", "C", 700, 120),
		visit("S1_circ_2", "S1", "A", 200, 30),
		visit("S1_circ_2", "S1", "B.p2|B|S1|through", 500, 60),
		new VisitRow("S1_circ_2", "S1", "S1_1", "C", 800, Double.NaN, Double.NaN, 860, Double.NaN, Double.NaN));

	@Test
	void groupsPlatformsOfAStationTogetherWorstFirst() {
		List<DelaySummaries.StationRow> rows = DelaySummaries.byStation(VISITS, 300);

		assertEquals(List.of("B", "C", "A"), rows.stream().map(DelaySummaries.StationRow::station).toList());
		DelaySummaries.StationRow b = rows.getFirst();
		assertEquals(2, b.observations());
		assertEquals(230, b.meanDelay());
		assertEquals(400, b.maxDelay());
		assertEquals(400, b.p95Delay());
		assertEquals(0.5, b.lateShare());
		assertEquals(1, rows.get(1).observations(), "a visit without a measured arrival is not counted");
	}

	@Test
	void ranksTrainsByTheirWorstArrival() {
		List<DelaySummaries.TrainRow> rows = DelaySummaries.byTrain(VISITS);

		assertEquals("S1_circ_1", rows.getFirst().vehicle());
		assertEquals(3, rows.getFirst().stops());
		assertEquals(400, rows.getFirst().maxDelay());
		assertEquals(120, rows.getFirst().finalDelay());
		assertEquals(60, rows.get(1).maxDelay());
	}

	@Test
	void stationOfAPlatformFacilityIsItsSecondField() {
		assertEquals("S01326", DelaySummaries.stationOf("S01326.p2|S01326|R38|terminal"));
		assertEquals("S01700", DelaySummaries.stationOf("S01700"));
	}
}
