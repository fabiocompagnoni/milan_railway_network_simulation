package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.schedule.DensificationPlan.Tunnel;
import it.unimib.milanrailsim.schedule.LineUpgrade.Added;
import it.unimib.milanrailsim.schedule.LineUpgrade.Upgraded;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineUpgradeTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 23);
	private static final GtfsFeed REAL = GtfsFeed.load(Path.of("src/test/resources/gtfs-dense"));

	private static int at(int hours, int minutes) {
		return hours * 3600 + minutes * 60;
	}

	private static LineUpgrade upgrade(int tunnelHeadwaySeconds) {
		return new LineUpgrade(new LineUpgradePlan(3600, 8, 4, 3, 30, 15, new Tunnel("T", tunnelHeadwaySeconds)));
	}

	private static List<Integer> departuresFrom(Upgraded upgraded, String stop) {
		return upgraded.report().added().stream().filter(added -> added.from().equals(stop)).map(Added::departureSeconds).toList();
	}

	@Test
	void theGapsBetweenTwoTripsOfTheRouteTakeEvenlySpacedCopies() {
		// S5 leaves U at 07:00, 07:30 and 08:00: a ten-minute wait needs two trips in each gap
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S5", "U", "V", 10)));

		assertEquals(List.of(at(7, 10), at(7, 20), at(7, 40), at(7, 50)), departuresFrom(upgraded, "U"));
	}

	@Test
	void theWayBackIsMeasuredAtItsOwnTerminus() {
		// S5 leaves V at 07:13, 07:43 and 08:13
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S5", "V", "U", 10)));

		assertEquals(List.of(at(7, 23), at(7, 33), at(7, 53), at(8, 3)), departuresFrom(upgraded, "V"),
			"the route is the same whichever terminus is named first");
	}

	@Test
	void aTargetThatDoesNotDivideTheGapRoundsEachDepartureToTheMinute() {
		// eight minutes over thirty take three trips, every seven and a half minutes
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S5", "U", "V", 8)));

		assertEquals(List.of(at(7, 8), at(7, 15), at(7, 23), at(7, 38), at(7, 45), at(7, 53)), departuresFrom(upgraded, "U"));
	}

	@Test
	void theCopyRunsTheWholeTripThatOpensTheGap() {
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S1", "A", "E", 15)));
		List<StopTime> copy = upgraded.feed().stopTimesByTripId().get("F1+a1");

		assertEquals(List.of("A", "B", "T", "C", "D", "E"), copy.stream().map(StopTime::stopId).toList());
		assertEquals(at(7, 15), copy.getFirst().departureSeconds());
		assertEquals(at(7, 45), copy.getLast().arrivalSeconds());
		assertEquals("SX", upgraded.feed().tripsById().get("F1+a1").routeId());
		assertEquals(List.of("A", "B", "T", "C", "D", "E"), upgraded.feed().stopTimesByTripId().get("F1").stream()
			.map(StopTime::stopId).toList(), "the real trip is untouched");
	}

	@Test
	void aBreakInServiceIsNotFilled() {
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S1", "A", "E", 15)));

		assertTrue(departuresFrom(upgraded, "A").stream().noneMatch(departure -> departure > at(9, 0)),
			"two hours between 09:00 and 11:00 are a break, not a headway");
		assertNull(upgraded.feed().tripsById().get("F9+a1"), "the Thursday trip does not run");
	}

	@Test
	void tripsOfOtherRoutesOfTheLineAreNeitherMeasuredNorCopied() {
		// R1 and R2 run E to A, the other direction: they do not belong to the A to E route
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S1", "A", "E", 15)));

		assertTrue(upgraded.report().added().stream().allMatch(added -> added.from().equals("A") || added.from().equals("E")));
		assertTrue(upgraded.report().added().stream().noneMatch(added -> added.templateTripId().startsWith("X")));
	}

	@Test
	void aPassageTooCloseInTheTunnelIsReportedButTheTripIsKept() {
		// the copy of 07:15 leaves T at 07:25, a minute before the S2 of 07:26
		Upgraded upgraded = upgrade(180).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S1", "A", "E", 15)));

		assertTrue(upgraded.feed().tripsById().containsKey("F1+a1"));
		LineUpgrade.TunnelWarning warning = upgraded.report().tunnelWarnings().getFirst();
		assertEquals("F1+a1", warning.tripId());
		assertEquals(at(7, 25), warning.passageSeconds());
		assertEquals(60, warning.headwaySeconds());
	}

	@Test
	void everyRouteTakesItsOwnTarget() {
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY,
			List.of(new RouteTarget("S5", "U", "V", 15), new RouteTarget("S6", "U", "V", 10)));

		assertEquals(4, upgraded.report().added().stream().filter(added -> added.line().equals("S5")).count());
		assertEquals(8, upgraded.report().added().stream().filter(added -> added.line().equals("S6")).count());
	}

	@Test
	void theSameRouteTwiceIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> upgrade(0).upgrade(REAL, WEDNESDAY,
			List.of(new RouteTarget("S5", "U", "V", 15), new RouteTarget("S5", "V", "U", 10))));
	}

	@Test
	void everyAddedTripIsMarked() {
		Upgraded upgraded = upgrade(0).upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S5", "U", "V", 10)));

		assertTrue(upgraded.report().added().stream().allMatch(added -> AddedTrips.isAdded(added.tripId())));
		assertEquals(REAL.tripsById().size() + 8, upgraded.feed().tripsById().size());
	}
}
