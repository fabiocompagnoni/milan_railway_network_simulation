package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.runs.ScenarioSpec.SimulationType;
import it.unimib.milanrailsim.runs.ScenarioSpec.TimeWindow;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioSummaryTest {

	private static final GtfsFeed FEED = GtfsFeed.load(Path.of("src/test/resources/gtfs-minimal"));
	private static final LocalDate WEEKDAY = LocalDate.of(2026, 9, 16);
	private static final Set<String> ALL_STOPS = Set.of("S1", "S2", "S3");

	private static ScenarioSpec spec(SimulationType type, TimeWindow window, Integer dynamicPercent) {
		return new ScenarioSpec("test", type, WEEKDAY, window, null, null, null, null, dynamicPercent);
	}

	@Test
	void countsRailTripsOfTheServiceDayPerLine() {
		// T1 and TN run on the weekday; T3 is a bus and T2 runs on another day
		ScenarioSummary summary = ScenarioSummary.of(FEED, RouteVehicleAssignment.defaults(),
			spec(SimulationType.REAL, null, null), ALL_STOPS);

		assertEquals(2, summary.trips());
		assertEquals(List.of("S1"), summary.lines());
	}

	@Test
	void timeWindowKeepsTripsByFirstDeparture() {
		// TN departs at 24:01, outside 07:00–09:00
		ScenarioSummary summary = ScenarioSummary.of(FEED, RouteVehicleAssignment.defaults(),
			spec(SimulationType.REAL, new TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 0)), null), ALL_STOPS);

		assertEquals(1, summary.trips());
	}

	@Test
	void fleetSharesFollowTheAssignment() {
		// S1 runs 70/30 tsr/taf spread through the day: the second departure is the taf
		ScenarioSummary summary = ScenarioSummary.of(FEED, RouteVehicleAssignment.defaults(),
			spec(SimulationType.REAL, null, null), ALL_STOPS);

		assertEquals(Map.of("tsr", 1, "taf", 1), summary.tripsByVehicleType());
	}

	@Test
	void dynamicReductionEstimatesExtraTrips() {
		// 50% shorter headways double the service: 2 trips become 4
		ScenarioSummary summary = ScenarioSummary.of(FEED, RouteVehicleAssignment.defaults(),
			spec(SimulationType.DYNAMIC, null, 50), ALL_STOPS);

		assertEquals(4, summary.trips());
		assertEquals(2, summary.extraTrips());
	}

	@Test
	void tripsCallingOutsideTheNetworkAreCountedNotScheduled() {
		// without S3, T1 (S1-S2-S3) is dropped and TN (S1-S2) stays
		ScenarioSummary summary = ScenarioSummary.of(FEED, RouteVehicleAssignment.defaults(),
			spec(SimulationType.REAL, null, null), Set.of("S1", "S2"));

		assertEquals(1, summary.trips());
		assertEquals(1, summary.offNetworkTrips());
	}
}
