package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineSummariesTest {

	private static PunctualityAnalysis.StopVisit visit(String line, double arrivalDelay) {
		return new PunctualityAnalysis.StopVisit("v", line, line + "_1", "A",
			1000, 1000 + arrivalDelay, 1060, 1060);
	}

	@Test
	void aggregatesArrivalDelaysPerLine() {
		List<PunctualityAnalysis.StopVisit> visits = List.of(
			visit("S1", 10), visit("S1", 20), visit("S1", 90),
			visit("S5", 0));

		List<LineSummaries.LineSummary> summaries = LineSummaries.of(visits);

		assertEquals(2, summaries.size());
		LineSummaries.LineSummary s1 = summaries.get(0);
		assertEquals("S1", s1.line());
		assertEquals(3, s1.observations());
		assertEquals(40.0, s1.meanDelaySeconds(), 1e-9);
		assertEquals(20.0, s1.medianDelaySeconds(), 1e-9);
		assertEquals(90.0, s1.maxDelaySeconds(), 1e-9);
	}

	@Test
	void percentileUsesNearestRank() {
		List<PunctualityAnalysis.StopVisit> visits = java.util.stream.IntStream.rangeClosed(1, 100)
			.mapToObj(i -> visit("S1", i))
			.toList();

		LineSummaries.LineSummary s1 = LineSummaries.of(visits).get(0);

		assertEquals(95.0, s1.p95DelaySeconds(), 1e-9);
	}

	@Test
	void emptyInputYieldsNoSummaries() {
		assertTrue(LineSummaries.of(List.of()).isEmpty());
	}
}
