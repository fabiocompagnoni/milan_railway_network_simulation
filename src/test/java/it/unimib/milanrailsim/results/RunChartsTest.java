package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunChartsTest {

	@TempDir
	Path dir;

	private static PunctualityAnalysis.StopVisit visit(String line, double time, double delay) {
		return new PunctualityAnalysis.StopVisit("v", line, line + "_1", "t", 0, "B", "A",
			time, time + delay, time + 30, time + 30 + delay);
	}

	private void assertPngWritten(Path png) throws IOException {
		assertTrue(Files.exists(png), png + " missing");
		assertTrue(Files.size(png) > 1000, png + " suspiciously small");
	}

	@Test
	void delaysAreCountedByMinuteWithOneClassForEachTail() {
		Map<String, Integer> classes = RunCharts.delayClasses(List.of(
			visit("S1", 8 * 3600, -400), visit("S1", 8 * 3600, -30), visit("S1", 8 * 3600, 0),
			visit("S1", 8 * 3600, 59), visit("S1", 8 * 3600, 60), visit("S1", 8 * 3600, 1799), visit("S1", 8 * 3600, 1800)));

		assertEquals(37, classes.size(), "below -5, the 35 minutes from -5 to 29, 30 and over");
		assertEquals(1, classes.get("< −5"));
		assertEquals(1, classes.get("−1"), "30 s early is in the minute from -1 to 0");
		assertEquals(2, classes.get("0"));
		assertEquals(1, classes.get("1"));
		assertEquals(1, classes.get("29"));
		assertEquals(1, classes.get("≥ 30"));
	}

	@Test
	void theMeanDelayByHourTakesThePlannedHourAndCountsEarlyArrivalsAsOnTime() {
		Map<Integer, Double> minutes = RunCharts.meanDelayMinutesByPlannedHour(List.of(
			visit("S1", 8 * 3600 + 3500, 240), visit("S1", 8 * 3600, -120), visit("S1", 25 * 3600, 60)));

		assertEquals(2.0, minutes.get(8), 1e-9, "240 s and 0 s over two arrivals planned in the hour of 8");
		assertEquals(1.0, minutes.get(25), 1e-9, "an hour past midnight of the service day keeps its number");
	}

	@Test
	void tripsInProgressAreCountedMinuteByMinute() {
		int[] running = RunCharts.tripsRunningByMinute(List.of(
			trip(8 * 3600, 8 * 3600 + 600, PunctualityAnalysis.TripStatus.COMPLETED),
			trip(8 * 3600 + 300, 8 * 3600 + 900, PunctualityAnalysis.TripStatus.COMPLETED),
			trip(8 * 3600, Double.NaN, PunctualityAnalysis.TripStatus.NEVER_DEPARTED)));

		assertEquals(1, running[8 * 60]);
		assertEquals(2, running[8 * 60 + 7]);
		assertEquals(1, running[8 * 60 + 12]);
		assertEquals(0, running[8 * 60 + 16]);
	}

	private static PunctualityAnalysis.TripOutcome trip(double departure, double arrival, PunctualityAnalysis.TripStatus status) {
		return new PunctualityAnalysis.TripOutcome("t", "S1", "S1_1", "v", "A", "B", departure, departure + 600, arrival, 2, 2, status);
	}

	@Test
	void writesAllFourChartTypes() throws IOException {
		List<PunctualityAnalysis.StopVisit> visits = List.of(
			visit("S1", 8 * 3600, 30), visit("S1", 9 * 3600, 90),
			visit("S5", 8 * 3600 + 600, 10), visit("S5", 17 * 3600, 240));

		Path histogram = dir.resolve("delay_histogram.png");
		RunCharts.delayHistogram(visits, histogram);
		assertPngWritten(histogram);

		Path byHour = dir.resolve("delay_by_hour.png");
		RunCharts.delayByHour(visits, byHour);
		assertPngWritten(byHour);

		Path spaceTime = dir.resolve("space_time.png");
		RunCharts.spaceTime(Map.of(
			"t1", List.of(new double[] { 8 * 3600, 0 }, new double[] { 8 * 3600 + 600, 10_000 }),
			"t2", List.of(new double[] { 8 * 3600 + 300, 0 }, new double[] { 8 * 3600 + 900, 10_000 })),
			spaceTime);
		assertPngWritten(spaceTime);

		Path costs = dir.resolve("cost_breakdown.png");
		RunCharts.costBreakdown(new CostModel.Breakdown("EUR",
			Map.of("staff", 1200.0, "electricity", 800.0), 400, 8, 3), costs);
		assertPngWritten(costs);
	}
}
