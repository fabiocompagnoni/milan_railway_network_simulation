package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GtfsFeedTest {

	private static Path fixture() {
		return Path.of("src/test/resources/gtfs-minimal");
	}

	@Test
	void loadsStopsRoutesAndTrips() {
		GtfsFeed feed = GtfsFeed.load(fixture());
		assertEquals(3, feed.stopsById().size());
		assertEquals("Alfa", feed.stopsById().get("S1").name());
		assertEquals(45.50, feed.stopsById().get("S1").lat());
		assertEquals(2, feed.routesById().get("SX").type());
		assertEquals(3, feed.routesById().get("BUS").type());
		assertEquals("ffffff", feed.routesById().get("SX").color());
		assertEquals("Alfa-Gamma", feed.routesById().get("SX").longName());
		assertEquals("SVC_WEEKDAY", feed.tripsById().get("T1").serviceId());
	}

	@Test
	void stopTimesAreSortedByStopSequence() {
		GtfsFeed feed = GtfsFeed.load(fixture());
		List<GtfsFeed.StopTime> t1 = feed.stopTimesByTripId().get("T1");
		assertEquals(List.of("S1", "S2", "S3"),
			t1.stream().map(GtfsFeed.StopTime::stopId).toList());
		assertEquals(8 * 3600 + 60, t1.get(0).departureSeconds());
	}

	@Test
	void parsesAfterMidnightStopTimes() {
		GtfsFeed feed = GtfsFeed.load(fixture());
		assertEquals(24 * 3600, feed.stopTimesByTripId().get("TN").get(0).arrivalSeconds());
	}

	@Test
	void furtherTripsGiveANewFeedAndLeaveThisOneAsItWas() {
		GtfsFeed feed = GtfsFeed.load(fixture());
		GtfsFeed.Trip extra = new GtfsFeed.Trip("T1x", "SX", "SVC_WEEKDAY");
		List<GtfsFeed.StopTime> calls = List.of(new GtfsFeed.StopTime("T1x", 9 * 3600, 9 * 3600, "S1", 1),
			new GtfsFeed.StopTime("T1x", 9 * 3600 + 300, 9 * 3600 + 300, "S2", 2));

		GtfsFeed extended = feed.with(List.of(extra), java.util.Map.of("T1x", calls));

		assertEquals(feed.tripsById().size() + 1, extended.tripsById().size());
		assertEquals(calls, extended.stopTimesByTripId().get("T1x"));
		assertNull(feed.tripsById().get("T1x"));
	}

	@Test
	void aFurtherTripMustBeNewRunOnAKnownRouteAndCallSomewhere() {
		GtfsFeed feed = GtfsFeed.load(fixture());
		List<GtfsFeed.StopTime> calls = List.of(new GtfsFeed.StopTime("x", 0, 0, "S1", 1));

		assertThrows(IllegalArgumentException.class,
			() -> feed.with(List.of(new GtfsFeed.Trip("T1", "SX", "SVC_WEEKDAY")), java.util.Map.of("T1", calls)));
		assertThrows(IllegalArgumentException.class,
			() -> feed.with(List.of(new GtfsFeed.Trip("x", "NOWHERE", "SVC_WEEKDAY")), java.util.Map.of("x", calls)));
		assertThrows(IllegalArgumentException.class,
			() -> feed.with(List.of(new GtfsFeed.Trip("x", "SX", "SVC_WEEKDAY")), java.util.Map.of()));
	}

	@Test
	void failsOnMissingDirectory() {
		assertThrows(RuntimeException.class, () -> GtfsFeed.load(Path.of("does/not/exist")));
	}
}
