package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.runs.ScenarioSpec.TimeWindow;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.schedule.AddedTrips;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What a scenario will simulate, derived from its timetable before any run:
 * lines and trips of the service day, fleet mix from the assignment rules and
 * the trips the scenario adds, told from the real ones by their id.
 *
 * @param trips runs the engine will schedule, added ones included
 * @param extraTrips runs among them that the scenario adds to the published timetable
 * @param offNetworkTrips runs dropped because they call at stations outside the modelled network
 * @param offNetworkExtraTrips added runs among the dropped ones
 */
public record ScenarioSummary(List<String> lines, int trips, int extraTrips, int offNetworkTrips, int offNetworkExtraTrips,
		Map<String, Integer> tripsByVehicleType) {

	private static final int RAIL_ROUTE_TYPE = 2;

	/**
	 * @param feed         the timetable the run starts from, with the trips its scenario adds
	 * @param networkStops ids of the stations the network models; trips calling elsewhere are counted, not scheduled
	 */
	public static ScenarioSummary of(GtfsFeed feed, RouteVehicleAssignment assignment, ScenarioSpec spec,
			Set<String> networkStops) {
		Set<String> services = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), spec.serviceDate());
		Map<String, List<GtfsFeed.Trip>> tripsByLine = new TreeMap<>();
		int offNetwork = 0;
		int offNetworkExtra = 0;
		int extra = 0;
		for (GtfsFeed.Trip trip : feed.tripsById().values()) {
			GtfsFeed.Route route = feed.routesById().get(trip.routeId());
			if (!services.contains(trip.serviceId()) || route.type() != RAIL_ROUTE_TYPE
					|| assignment.isExcluded(route.shortName()) || !inWindow(feed, trip, spec.window())) {
				continue;
			}
			boolean added = AddedTrips.isAdded(trip.id());
			if (feed.stopTimesByTripId().get(trip.id()).stream().anyMatch(stop -> !networkStops.contains(stop.stopId()))) {
				offNetwork++;
				offNetworkExtra += added ? 1 : 0;
				continue;
			}
			extra += added ? 1 : 0;
			tripsByLine.computeIfAbsent(route.shortName(), key -> new ArrayList<>()).add(trip);
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
		return new ScenarioSummary(List.copyOf(tripsByLine.keySet()), timetabled, extra, offNetwork, offNetworkExtra,
			byVehicleType);
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
