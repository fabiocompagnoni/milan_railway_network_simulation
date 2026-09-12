package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.gui.config.ScenarioSpec;
import it.unimib.milanrailsim.gui.config.ScenarioSpec.SimulationType;
import it.unimib.milanrailsim.gui.config.ScenarioSpec.TimeWindow;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What a scenario will simulate, derived from the timetable before any run:
 * lines and trips of the service day, fleet mix from the assignment rules and,
 * for compressed scenarios, the estimated extra service.
 *
 * @param trips runs the engine will schedule (estimated for compressed scenarios)
 * @param extraTrips runs added on top of the timetable by the scenario's compression
 */
public record ScenarioSummary(List<String> lines, int trips, int extraTrips, Map<String, Integer> tripsByVehicleType) {

	private static final int RAIL_ROUTE_TYPE = 2;

	public static ScenarioSummary of(GtfsFeed feed, RouteVehicleAssignment assignment, ScenarioSpec spec) {
		Set<String> services = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), spec.serviceDate());
		Map<String, List<GtfsFeed.Trip>> tripsByLine = new TreeMap<>();
		for (GtfsFeed.Trip trip : feed.tripsById().values()) {
			GtfsFeed.Route route = feed.routesById().get(trip.routeId());
			if (services.contains(trip.serviceId()) && route.type() == RAIL_ROUTE_TYPE
					&& !assignment.isExcluded(route.shortName()) && inWindow(feed, trip, spec.window())) {
				tripsByLine.computeIfAbsent(route.shortName(), key -> new ArrayList<>()).add(trip);
			}
		}

		Map<String, Integer> byVehicleType = new TreeMap<>();
		int timetabled = 0;
		for (Map.Entry<String, List<GtfsFeed.Trip>> entry : tripsByLine.entrySet()) {
			List<GtfsFeed.Trip> trips = entry.getValue().stream()
				.sorted(Comparator.comparingInt(trip -> firstDeparture(feed, trip)))
				.toList();
			for (int i = 0; i < trips.size(); i++) {
				byVehicleType.merge(assignment.vehicleTypeId(entry.getKey(), i), 1, Integer::sum);
			}
			timetabled += trips.size();
		}
		int extra = extraTrips(spec, timetabled);
		return new ScenarioSummary(List.copyOf(tripsByLine.keySet()), timetabled + extra, extra, byVehicleType);
	}

	/** Shorter headways scale service by 1 / (1 - reduction); collapse and metro-like are sized at run time. */
	private static int extraTrips(ScenarioSpec spec, int timetabled) {
		if (spec.type() != SimulationType.DYNAMIC || spec.dynamicReductionPercent() == null) {
			return 0;
		}
		double factor = 1 / (1 - spec.dynamicReductionPercent() / 100.0);
		return (int) Math.round(timetabled * factor) - timetabled;
	}

	private static boolean inWindow(GtfsFeed feed, GtfsFeed.Trip trip, TimeWindow window) {
		if (window == null) {
			return true;
		}
		int departure = firstDeparture(feed, trip);
		return departure >= window.start().toSecondOfDay() && departure <= window.end().toSecondOfDay();
	}

	private static int firstDeparture(GtfsFeed feed, GtfsFeed.Trip trip) {
		return feed.stopTimesByTripId().get(trip.id()).getFirst().departureSeconds();
	}

	/** Shares in percent by vehicle type, largest first. */
	public Map<String, Integer> fleetSharesPercent() {
		int total = tripsByVehicleType.values().stream().mapToInt(Integer::intValue).sum();
		return tripsByVehicleType.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.collect(Collectors.toMap(Map.Entry::getKey,
				entry -> total == 0 ? 0 : (int) Math.round(100.0 * entry.getValue() / total),
				(a, b) -> a, java.util.LinkedHashMap::new));
	}
}
