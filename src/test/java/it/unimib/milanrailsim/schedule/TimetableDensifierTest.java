package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.schedule.DensificationPlan.Band;
import it.unimib.milanrailsim.schedule.DensificationPlan.DayKind;
import it.unimib.milanrailsim.schedule.DensificationPlan.Intensity;
import it.unimib.milanrailsim.schedule.DensificationPlan.Level;
import it.unimib.milanrailsim.schedule.DensificationPlan.Relation;
import it.unimib.milanrailsim.schedule.DensificationPlan.Service;
import it.unimib.milanrailsim.schedule.DensificationPlan.Tunnel;
import it.unimib.milanrailsim.schedule.TimetableDensifier.Added;
import it.unimib.milanrailsim.schedule.TimetableDensifier.Densified;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TimetableDensifierTest {

	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 23);
	private static final GtfsFeed REAL = GtfsFeed.load(Path.of("src/test/resources/gtfs-dense"));

	/** S1 trips between A and D, measured on the trains running from A to B and back. */
	private static final Relation LINE = new Relation("a-d", List.of("A", "B"), List.of(new Service("S1", "A", "D")),
		Intensity.FULL, false);
	private static final Relation LINE_THROUGH_TUNNEL = new Relation("a-d", List.of("A", "B"),
		List.of(new Service("S1", "A", "D")), Intensity.FULL, true);

	private static int at(int hours, int minutes) {
		return hours * 3600 + minutes * 60;
	}

	/** Peak from 06:30 to 08:00, off peak until noon, on weekdays only; five minutes of minimum spacing. */
	private static DensificationPlan plan(int tunnelHeadwaySeconds, Relation... relations) {
		return new DensificationPlan(List.of(relations), new Tunnel("T", tunnelHeadwaySeconds), 3600, 300, Map.of(
			DayKind.WEEKDAY, List.of(new Band(at(6, 30), at(8, 0), Level.PEAK, Optional.empty()),
				new Band(at(8, 0), at(12, 0), Level.OFF_PEAK, Optional.empty())),
			DayKind.SATURDAY, List.of(),
			DayKind.HOLIDAY, List.of()));
	}

	private static Relation shared(Intensity intensity) {
		return new Relation("p-q", List.of("P", "Q"), List.of(new Service("S8", "P", "Q"), new Service("S11", "P", "Q")),
			intensity, false);
	}

	private static List<String> stopsOf(GtfsFeed feed, String tripId) {
		return feed.stopTimesByTripId().get(tripId).stream().map(StopTime::stopId).toList();
	}

	private static int departure(GtfsFeed feed, String tripId) {
		return feed.stopTimesByTripId().get(tripId).getFirst().departureSeconds();
	}

	@Test
	void anAddedTripIsACopyCutToItsServiceAndShiftedIntoTheGap() {
		Densified densified = new TimetableDensifier(plan(0, LINE)).densify(REAL, WEDNESDAY, 15);
		GtfsFeed feed = densified.feed();

		assertEquals(List.of("A", "B", "T", "C", "D"), stopsOf(feed, "F1+a1"), "cut at D: the real trip goes on to E");
		List<StopTime> copy = feed.stopTimesByTripId().get("F1+a1");
		assertEquals(at(7, 15), copy.getFirst().departureSeconds());
		assertEquals(at(7, 19), copy.get(1).arrivalSeconds());
		assertEquals(at(7, 20), copy.get(1).departureSeconds());
		assertEquals(at(7, 34), copy.getLast().arrivalSeconds());
		assertEquals(List.of(1, 2, 3, 4, 5), copy.stream().map(StopTime::stopSequence).toList());
		assertEquals("SX", feed.tripsById().get("F1+a1").routeId());
		assertEquals("WEDNESDAY", feed.tripsById().get("F1+a1").serviceId(), "active on the days of the trip it copies");
		assertEquals(List.of("A", "B", "T", "C", "D", "E"), stopsOf(feed, "F1"), "the real trip is untouched");
	}

	@Test
	void theOppositeDirectionIsMeasuredAndFilledOnItsOwn() {
		Densified densified = new TimetableDensifier(plan(0, LINE)).densify(REAL, WEDNESDAY, 15);

		assertEquals(List.of("D", "C", "T", "B", "A"), stopsOf(densified.feed(), "R1+a1"));
		assertEquals(at(7, 35), departure(densified.feed(), "R1+a1"),
			"it leaves B at 07:50, half way between the trains of 07:35 and 08:05");
	}

	@Test
	void theChosenTargetHoldsAtPeakAndOneStepLongerOffPeak() {
		TimetableDensifier densifier = new TimetableDensifier(plan(0, LINE));

		GtfsFeed ten = densifier.densify(REAL, WEDNESDAY, 10).feed();
		assertEquals(at(7, 10), departure(ten, "F1+a1"));
		assertEquals(at(7, 20), departure(ten, "F1+a2"));
		assertEquals(at(8, 15), departure(ten, "F3+a1"), "off peak from 08:00: fifteen minutes instead of ten");
		assertNull(ten.tripsById().get("F3+a2"));

		GtfsFeed fifteen = densifier.densify(REAL, WEDNESDAY, 15).feed();
		assertEquals(at(7, 45), departure(fifteen, "F2+a1"));
		assertNull(fifteen.tripsById().get("F3+a1"), "one step longer than fifteen minutes adds nothing");
	}

	@Test
	void aBreakInServiceAndAnotherDaysTripsAreLeftAlone() {
		Densified densified = new TimetableDensifier(plan(0, LINE)).densify(REAL, WEDNESDAY, 10);

		assertNull(densified.feed().tripsById().get("F5+a1"), "two hours between 09:00 and 11:00 are a break, not a headway");
		assertEquals(at(7, 10), departure(densified.feed(), "F1+a1"), "the Thursday trip of 07:15 does not shorten the gap");
		assertNull(densified.feed().tripsById().get("F9+a1"));
	}

	@Test
	void theHeadwayIsThatOfEveryTrainOfTheFlowWhateverItsLine() {
		// S8 and S11 leave P every twenty minutes between them: measured alone, each would show forty
		Densified densified = new TimetableDensifier(plan(0, shared(Intensity.FULL))).densify(REAL, WEDNESDAY, 10);

		assertEquals(List.of(at(7, 10), at(7, 30), at(7, 50), at(8, 10), at(8, 30)),
			densified.report().added().stream().map(Added::departureSeconds).toList());
	}

	@Test
	void theServicesOfARelationTakeTurnsAndAReducedOneServesEveryOtherGap() {
		Densified densified = new TimetableDensifier(plan(0, shared(Intensity.REDUCED))).densify(REAL, WEDNESDAY, 10);

		assertEquals(List.of("G1+a1", "H1+a1", "G3+a1"), densified.report().added().stream().map(Added::tripId).toList());
		assertEquals(at(7, 10), departure(densified.feed(), "G1+a1"));
		assertEquals(at(7, 50), departure(densified.feed(), "H1+a1"), "the 07:20 of the S11 copied into the gap after the 07:40");
		assertEquals(at(8, 30), departure(densified.feed(), "G3+a1"));
	}

	@Test
	void aRelationSeesTheTripsAddedByThoseBeforeIt() {
		Relation again = new Relation("a-d-again", List.of("A", "B"), List.of(new Service("S1", "A", "D")), Intensity.FULL, false);

		Densified densified = new TimetableDensifier(plan(0, LINE, again)).densify(REAL, WEDNESDAY, 15);

		assertEquals(Map.of("a-d", 3L), densified.report().addedByRelation(),
			"after the first relation no train of the flow is more than fifteen minutes from the next");
	}

	@Test
	void aTripThroughTheTunnelMovesWithinItsGapToKeepTheMinimumHeadway() {
		Densified densified = new TimetableDensifier(plan(180, LINE_THROUGH_TUNNEL)).densify(REAL, WEDNESDAY, 15);

		// planned at 07:15 it would leave the tunnel stop at 07:25, a minute before the S2 of 07:26
		assertEquals(at(7, 13), departure(densified.feed(), "F1+a1"));
		assertEquals(at(7, 45), departure(densified.feed(), "F2+a1"), "no train nearby: the middle of the gap");
	}

	@Test
	void aTripWithNoRoomInTheTunnelIsGivenUpAndReported() {
		Densified densified = new TimetableDensifier(plan(1800, LINE_THROUGH_TUNNEL)).densify(REAL, WEDNESDAY, 15);

		assertTrue(densified.report().added().isEmpty());
		assertEquals(3, densified.report().skipped().size(), "two gaps one way and one the other, all at peak");
		TimetableDensifier.Skipped first = densified.report().skipped().getFirst();
		assertEquals("a-d", first.relation());
		assertEquals("S1", first.line());
		assertEquals(at(7, 15), first.plannedSeconds());
		assertEquals(REAL.tripsById().size(), densified.feed().tripsById().size());
	}

	@Test
	void aLineOutOfServiceYieldsItsTurnToTheNext() {
		// no S1 runs between P and Q: every added trip is a copy of the S8
		Relation withIdleLine = new Relation("p-q", List.of("P", "Q"),
			List.of(new Service("S1", "P", "Q"), new Service("S8", "P", "Q")), Intensity.FULL, false);

		Densified densified = new TimetableDensifier(plan(0, withIdleLine)).densify(REAL, WEDNESDAY, 10);

		assertEquals(5, densified.report().added().size());
		assertTrue(densified.report().added().stream().allMatch(added -> added.line().equals("S8")));
		assertTrue(densified.report().skipped().isEmpty());
	}

	@Test
	void aServiceNoRealTripRunsIsReported() {
		Relation unserved = new Relation("a-q", List.of("A", "B"), List.of(new Service("S8", "A", "D")), Intensity.FULL, false);

		Densified densified = new TimetableDensifier(plan(0, unserved)).densify(REAL, WEDNESDAY, 15);

		assertTrue(densified.report().added().isEmpty());
		assertEquals(3, densified.report().skipped().size());
	}

	@Test
	void theReportCountsWhatWasAddedByRelation() {
		Densified densified = new TimetableDensifier(plan(0, LINE, shared(Intensity.FULL))).densify(REAL, WEDNESDAY, 10);

		assertEquals(Map.of("a-d", 8L, "p-q", 5L), densified.report().addedByRelation());
		assertTrue(densified.report().added().stream().allMatch(added -> TimetableDensifier.isAdded(added.tripId())));
		assertFalse(TimetableDensifier.isAdded("F1"));
	}
}
