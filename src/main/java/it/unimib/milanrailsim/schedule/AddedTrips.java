package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.network.GtfsFeed.Trip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The trips a scenario adds to the published timetable. An added trip is a
 * shifted copy of a real one: same line, same days, same stops and the same
 * running and dwell times. Its id is that of the copied trip followed by
 * {@code +a} and the number of the copy.
 */
public final class AddedTrips {

	private static final String MARK = "+a";
	private static final Pattern ADDED_ID = Pattern.compile(".+\\+a\\d+");

	private final List<Trip> trips = new ArrayList<>();
	private final Map<String, List<StopTime>> calls = new LinkedHashMap<>();
	private final Map<String, Integer> copiesOfTrip = new HashMap<>();

	/** Whether a trip id is that of an added trip. */
	public static boolean isAdded(String tripId) {
		return ADDED_ID.matcher(tripId).matches();
	}

	/**
	 * Adds a copy of the calls {@code first..last} of a real trip, every time
	 * moved by {@code shiftSeconds}.
	 *
	 * @return the added trip
	 */
	DayTrip copy(DayTrip template, int first, int last, int shiftSeconds) {
		String id = template.tripId() + MARK + copiesOfTrip.merge(template.tripId(), 1, Integer::sum);
		List<StopTime> copy = new ArrayList<>();
		for (int i = first; i <= last; i++) {
			StopTime call = template.calls().get(i);
			copy.add(new StopTime(id, call.arrivalSeconds() + shiftSeconds, call.departureSeconds() + shiftSeconds,
				call.stopId(), copy.size() + 1));
		}
		trips.add(new Trip(id, template.routeId(), template.serviceId()));
		calls.put(id, List.copyOf(copy));
		return new DayTrip(id, template.routeId(), template.serviceId(), template.line(), List.copyOf(copy));
	}

	/** The published timetable with the trips added so far. */
	GtfsFeed addTo(GtfsFeed published) {
		return published.with(trips, calls);
	}
}
