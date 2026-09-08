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
		return new PunctualityAnalysis.StopVisit("v", line, line + "_1", "A",
			time, time + delay, time + 30, time + 30 + delay);
	}

	private void assertPngWritten(Path png) throws IOException {
		assertTrue(Files.exists(png), png + " missing");
		assertTrue(Files.size(png) > 1000, png + " suspiciously small");
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
