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
	void failsOnMissingDirectory() {
		assertThrows(RuntimeException.class, () -> GtfsFeed.load(Path.of("does/not/exist")));
	}
}
