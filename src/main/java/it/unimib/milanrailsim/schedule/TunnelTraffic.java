package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.schedule.DensificationPlan.Tunnel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Departures from the tunnel's reference stop, by the stop the trains head for
 * next: trains heading for the same stop run in the same direction.
 */
final class TunnelTraffic {

	/** Where and when a trip leaves the reference stop. */
	record Passage(String nextStop, int departure) {
	}

	private final String referenceStop;
	private final int minHeadwaySeconds;
	private final Map<String, TreeSet<Integer>> departuresByNextStop = new HashMap<>();

	TunnelTraffic(Tunnel tunnel, List<DayTrip> trips) {
		this.referenceStop = tunnel.referenceStop();
		this.minHeadwaySeconds = tunnel.minHeadwaySeconds();
		for (DayTrip trip : trips) {
			passageOf(trip, 0, trip.calls().size() - 1).ifPresent(this::record);
		}
	}

	void record(Passage passage) {
		departuresByNextStop.computeIfAbsent(passage.nextStop(), stop -> new TreeSet<>()).add(passage.departure());
	}

	/** Whether a train leaving at that time keeps the minimum headway from every train recorded in its direction. */
	boolean hasRoom(Passage passage) {
		return headwayOf(passage) >= minHeadwaySeconds;
	}

	/** Seconds to the nearest train recorded in the same direction, or {@link Integer#MAX_VALUE} when there is none. */
	int headwayOf(Passage passage) {
		TreeSet<Integer> departures = departuresByNextStop.getOrDefault(passage.nextStop(), new TreeSet<>());
		Integer before = departures.floor(passage.departure());
		Integer after = departures.ceiling(passage.departure());
		return Math.min(before == null ? Integer.MAX_VALUE : passage.departure() - before,
			after == null ? Integer.MAX_VALUE : after - passage.departure());
	}

	/** Where and when a trip leaves the reference stop between two of its calls, if it does. */
	Optional<Passage> passageOf(DayTrip trip, int first, int last) {
		List<StopTime> calls = trip.calls();
		for (int i = first; i < last; i++) {
			if (calls.get(i).stopId().equals(referenceStop)) {
				return Optional.of(new Passage(calls.get(i + 1).stopId(), calls.get(i).departureSeconds()));
			}
		}
		return Optional.empty();
	}
}
