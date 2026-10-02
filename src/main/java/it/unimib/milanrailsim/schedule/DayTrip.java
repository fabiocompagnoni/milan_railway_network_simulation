package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.Route;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.network.GtfsFeed.Trip;
import it.unimib.milanrailsim.network.ServiceCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A rail trip running on a service day, with the line it belongs to. */
record DayTrip(String tripId, String routeId, String serviceId, String line, List<StopTime> calls) {

	private static final int RAIL = 2;

	/** The rail trips of a feed active on the day. */
	static List<DayTrip> of(GtfsFeed feed, LocalDate day) {
		Set<String> active = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), day);
		List<DayTrip> trips = new ArrayList<>();
		for (Trip trip : feed.tripsById().values()) {
			Route route = feed.routesById().get(trip.routeId());
			List<StopTime> calls = feed.stopTimesByTripId().get(trip.id());
			if (active.contains(trip.serviceId()) && route != null && route.type() == RAIL && calls != null) {
				trips.add(new DayTrip(trip.id(), trip.routeId(), trip.serviceId(), route.shortName(), calls));
			}
		}
		return trips;
	}

	int indexOf(String stopId) {
		for (int i = 0; i < calls.size(); i++) {
			if (calls.get(i).stopId().equals(stopId)) {
				return i;
			}
		}
		return -1;
	}

	/** Whether the trip calls at the two stops in this order. */
	boolean runs(String stop, String next) {
		int index = indexOf(stop);
		return index >= 0 && index < indexOf(next);
	}

	int departureFrom(String stopId) {
		return calls.get(indexOf(stopId)).departureSeconds();
	}

	String firstStop() {
		return calls.getFirst().stopId();
	}

	String lastStop() {
		return calls.getLast().stopId();
	}

	int departure() {
		return calls.getFirst().departureSeconds();
	}
}
