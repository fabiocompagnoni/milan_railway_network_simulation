package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The routes of each line on a service day and the waits between their
 * trains. A route is a pair of termini: its trips are those of the line
 * leaving one terminus and ending at the other, in either direction.
 */
public final class RegularRoutes {

	/**
	 * @param one   stop id of the terminus the route's earliest trip leaves from
	 * @param other stop id of the other terminus
	 * @param trips trips of the day in both directions
	 * @param stops the stops the earliest trip calls at, from {@code one} to {@code other}
	 */
	public record Route(String line, String one, String other, int trips, List<String> stops) {

		public Route {
			stops = List.copyOf(stops);
		}
	}

	private final List<DayTrip> trips;
	private final int serviceGapSeconds;

	/** @param serviceGapSeconds a gap longer than this is a break in service, not a wait */
	public RegularRoutes(GtfsFeed feed, LocalDate day, int serviceGapSeconds) {
		this.trips = DayTrip.of(feed, day).stream().sorted(Comparator.comparingInt(DayTrip::departure)).toList();
		this.serviceGapSeconds = serviceGapSeconds;
	}

	/**
	 * The routes run often enough to be worth upgrading, ordered by line and
	 * then by trips, most first.
	 */
	public List<Route> offered(int minTrips, int minTripsPerDirection) {
		Map<List<String>, List<DayTrip>> byRoute = new LinkedHashMap<>();
		for (DayTrip trip : trips) {
			byRoute.computeIfAbsent(key(trip.line(), trip.firstStop(), trip.lastStop()), route -> new ArrayList<>()).add(trip);
		}
		List<Route> offered = new ArrayList<>();
		for (List<DayTrip> route : byRoute.values()) {
			DayTrip earliest = route.getFirst();
			long oneWay = route.stream().filter(trip -> trip.firstStop().equals(earliest.firstStop())).count();
			long back = route.size() - oneWay;
			if (route.size() >= minTrips && oneWay >= minTripsPerDirection && back >= minTripsPerDirection) {
				offered.add(new Route(earliest.line(), earliest.firstStop(), earliest.lastStop(), route.size(),
					earliest.calls().stream().map(GtfsFeed.StopTime::stopId).toList()));
			}
		}
		offered.sort(Comparator.comparing(Route::line).thenComparing(Comparator.comparingInt(Route::trips).reversed()));
		return List.copyOf(offered);
	}

	/**
	 * The widest gap between two consecutive trips of the route in either
	 * direction, both leaving within {@code fromSeconds..toSeconds}; breaks in
	 * service are left out.
	 *
	 * @return empty when no direction has two trips in the window
	 */
	public OptionalInt longestWaitSeconds(Route route, int fromSeconds, int toSeconds) {
		OptionalInt oneWay = longestWait(route.line(), route.one(), route.other(), fromSeconds, toSeconds);
		OptionalInt back = longestWait(route.line(), route.other(), route.one(), fromSeconds, toSeconds);
		if (oneWay.isEmpty()) {
			return back;
		}
		return back.isEmpty() ? oneWay : OptionalInt.of(Math.max(oneWay.getAsInt(), back.getAsInt()));
	}

	private OptionalInt longestWait(String line, String from, String to, int fromSeconds, int toSeconds) {
		List<Integer> departures = trips.stream()
			.filter(trip -> trip.line().equals(line) && trip.firstStop().equals(from) && trip.lastStop().equals(to))
			.map(DayTrip::departure)
			.filter(departure -> departure >= fromSeconds && departure <= toSeconds)
			.toList();
		OptionalInt longest = OptionalInt.empty();
		for (int i = 0; i + 1 < departures.size(); i++) {
			int gap = departures.get(i + 1) - departures.get(i);
			if (gap <= serviceGapSeconds && (longest.isEmpty() || gap > longest.getAsInt())) {
				longest = OptionalInt.of(gap);
			}
		}
		return longest;
	}

	/** The same key whichever terminus is named first. */
	private static List<String> key(String line, String one, String other) {
		Set<String> termini = Set.of(one, other);
		return List.of(line, termini.stream().sorted().collect(Collectors.joining("|")));
	}
}
