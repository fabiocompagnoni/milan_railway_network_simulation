package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.schedule.DensificationPlan.Tunnel;
import it.unimib.milanrailsim.schedule.RegularRoutes.Route;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class RegularRoutesTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 23);
	private static final GtfsFeed REAL = GtfsFeed.load(Path.of("src/test/resources/gtfs-dense"));
	private static final int WHOLE_DAY_START = 0;
	private static final int WHOLE_DAY_END = Integer.MAX_VALUE;

	/** S1 from A to E, with the stops of its earliest trip. */
	private static final Route S1 = new Route("S1", "A", "E", 8, List.of("A", "B", "T", "C", "D", "E"));

	private static int at(int hours, int minutes) {
		return hours * 3600 + minutes * 60;
	}

	@Test
	void aRouteIsOfferedWithEnoughTripsInEachDirection() {
		// S1 runs A to E six times and back twice; S5 and S6 run U to V and back three times each
		RegularRoutes routes = new RegularRoutes(REAL, WEDNESDAY, 3600);

		assertEquals(List.of(S1), routes.offered(8, 2));
		assertEquals(List.of(new Route("S5", "U", "V", 6, List.of("U", "V")), new Route("S6", "U", "V", 6, List.of("U", "V"))),
			routes.offered(6, 3), "S1 has only two trips back; S8 runs one way only");
	}

	@Test
	void theFirstTerminusIsWhereTheEarliestTripLeaves() {
		Route offered = new RegularRoutes(REAL, WEDNESDAY, 3600).offered(8, 2).getFirst();

		assertEquals("A", offered.one(), "the first trip leaves A at 07:00, the first back leaves E at 07:10");
	}

	@Test
	void theLongestWaitIsTheWidestGapOfEitherDirectionBreaksExcluded() {
		RegularRoutes routes = new RegularRoutes(REAL, WEDNESDAY, 3600);
		assertEquals(OptionalInt.of(1800), routes.longestWaitSeconds(S1, WHOLE_DAY_START, WHOLE_DAY_END),
			"thirty minutes both ways; the two hours between 09:00 and 11:00 are a break");
	}

	@Test
	void onlyDeparturesInsideTheWindowCount() {
		RegularRoutes routes = new RegularRoutes(REAL, WEDNESDAY, 3600);

		assertEquals(OptionalInt.empty(), routes.longestWaitSeconds(S1, at(7, 0), at(7, 20)),
			"one trip each way leaves between 07:00 and 07:20: there is no wait to measure");
	}

	@Test
	void theWaitAfterAnUpgradeIsMeasuredOnTheUpgradedTimetable() {
		LineUpgrade upgrade = new LineUpgrade(new LineUpgradePlan(3600, 8, 4, 3, 30, 15, new Tunnel("T", 0)));
		GtfsFeed upgraded = upgrade.upgrade(REAL, WEDNESDAY, List.of(new RouteTarget("S1", "A", "E", 15))).feed();

		assertEquals(OptionalInt.of(900), new RegularRoutes(upgraded, WEDNESDAY, 3600)
			.longestWaitSeconds(S1, WHOLE_DAY_START, WHOLE_DAY_END));
	}
}
