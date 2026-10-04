package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.StopVisit;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceIndicatorsTest {

	private static final double THRESHOLD = 300;

	private static StopVisit visit(String line, String trip, int sequence, double delay) {
		return new StopVisit("v", line, line + "_1", trip, sequence, "B", sequence == 0 ? "A" : "B",
			1000, 1000 + delay, 1060, 1060 + Math.max(delay, 0));
	}

	private static TripOutcome trip(String line, String id, TripStatus status, double delay) {
		return new TripOutcome(id, line, line + "_1", "v", "A", "B", 1000, 2000,
			status == TripStatus.COMPLETED ? 2000 + delay : Double.NaN, 2,
			status == TripStatus.COMPLETED ? 2 : status == TripStatus.INTERRUPTED ? 1 : 0, status);
	}

	@Test
	void regularityCountsOnlyCompletedTripsAndKeepsTheOthersApart() {
		ServiceIndicators indicators = ServiceIndicators.of(List.of(
			trip("S1", "a", TripStatus.COMPLETED, 0),
			trip("S1", "b", TripStatus.COMPLETED, 0),
			trip("S1", "c", TripStatus.INTERRUPTED, 0),
			trip("S1", "d", TripStatus.NEVER_DEPARTED, 0)), List.of(), THRESHOLD);

		assertEquals(4, indicators.tripsScheduled());
		assertEquals(2, indicators.tripsCompleted());
		assertEquals(1, indicators.tripsInterrupted());
		assertEquals(1, indicators.tripsNeverDeparted());
		assertEquals(50.0, indicators.regularityPercent());
		assertEquals(8, indicators.stopsPlanned());
		assertEquals(5, indicators.stopsServed());
	}

	@Test
	void punctualityAtDestinationIsMeasuredOnCompletedTrips() {
		// a train exactly 5 minutes late is on time; trips that did not arrive are not counted as late
		ServiceIndicators indicators = ServiceIndicators.of(List.of(
			trip("S1", "a", TripStatus.COMPLETED, 300),
			trip("S1", "b", TripStatus.COMPLETED, 301),
			trip("S1", "c", TripStatus.COMPLETED, -120),
			trip("S1", "d", TripStatus.INTERRUPTED, 0)), List.of(), THRESHOLD);

		assertEquals(100.0 * 2 / 3, indicators.punctualityAtDestinationPercent(), 1e-9);
	}

	@Test
	void anEarlyArrivalIsNoDelayButStillCountsInTheSignedDeviation() {
		ServiceIndicators indicators = ServiceIndicators.of(List.of(), List.of(
			visit("S1", "a", 0, -100),
			visit("S1", "a", 1, 100),
			visit("S1", "b", 0, 0),
			visit("S1", "b", 1, 600)), THRESHOLD);

		assertEquals(175.0, indicators.meanDelaySeconds(), "(0 + 100 + 0 + 600) / 4");
		assertEquals(150.0, indicators.meanDeviationSeconds(), "(-100 + 100 + 0 + 600) / 4");
		assertEquals(0.0, indicators.medianDelaySeconds());
		assertEquals(600.0, indicators.p95DelaySeconds());
		assertEquals(75.0, indicators.punctualityAtStopsPercent());
	}

	@Test
	void indicatorsAreAlsoGivenByLine() {
		Map<String, ServiceIndicators> byLine = ServiceIndicators.byLine(List.of(
			trip("S1", "a", TripStatus.COMPLETED, 0),
			trip("S2", "b", TripStatus.NEVER_DEPARTED, 0)), List.of(visit("S1", "a", 1, 60)), THRESHOLD);

		assertEquals(List.of("S1", "S2"), List.copyOf(byLine.keySet()));
		assertEquals(100.0, byLine.get("S1").regularityPercent());
		assertEquals(60.0, byLine.get("S1").meanDelaySeconds());
		assertEquals(0.0, byLine.get("S2").regularityPercent());
	}
}
