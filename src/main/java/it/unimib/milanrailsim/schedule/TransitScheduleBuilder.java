package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.RailVehicleTypes;
import it.unimib.milanrailsim.network.ServiceCalendar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehicleUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the GTFS trips active on a given service date into a MATSim
 * {@link TransitSchedule} and matching {@link Vehicles} container, routed on
 * the mesoscopic rail network via {@link MesoRouter}.
 */
public final class TransitScheduleBuilder {

	private static final Logger LOG = LogManager.getLogger(TransitScheduleBuilder.class);
	private static final int RAIL_ROUTE_TYPE = 2;
	private static final String MODE = "rail";

	public record Result(TransitSchedule schedule, Vehicles vehicles) {
	}

	private final GtfsFeed feed;
	private final Network network;
	private final LocalDate serviceDate;
	private final RouteVehicleAssignment assignment;

	private final TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
	private final Map<String, TransitStopFacility> stopFacilities = new HashMap<>();
	private final Map<String, String> patternToRouteId = new HashMap<>();
	private final Map<String, Integer> routeCounters = new HashMap<>();

	public TransitScheduleBuilder(GtfsFeed feed, Network network, LocalDate serviceDate,
			RouteVehicleAssignment assignment) {
		this.feed = feed;
		this.network = network;
		this.serviceDate = serviceDate;
		this.assignment = assignment;
	}

	public Result build() {
		StationStopLinks.addStopLinks(network);
		MesoRouter router = new MesoRouter(network);

		TransitSchedule schedule = factory.createTransitSchedule();
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		RailVehicleTypes.all().forEach(vehicles::addVehicleType);

		Set<String> activeServiceIds = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), serviceDate);
		Map<String, List<GtfsFeed.Trip>> includedTripsByRoute = includedTripsByRoute(activeServiceIds);

		for (Map.Entry<String, List<GtfsFeed.Trip>> entry : includedTripsByRoute.entrySet()) {
			buildLine(entry.getKey(), entry.getValue(), schedule, vehicles, router);
		}

		return new Result(schedule, vehicles);
	}

	private Map<String, List<GtfsFeed.Trip>> includedTripsByRoute(Set<String> activeServiceIds) {
		Map<String, List<GtfsFeed.Trip>> tripsByRoute = new LinkedHashMap<>();
		Map<String, Integer> excludedCounts = new HashMap<>();
		for (GtfsFeed.Trip trip : feed.tripsById().values()) {
			if (!activeServiceIds.contains(trip.serviceId())) {
				continue;
			}
			GtfsFeed.Route route = feed.routesById().get(trip.routeId());
			if (route.type() != RAIL_ROUTE_TYPE) {
				continue;
			}
			if (assignment.isExcluded(route.shortName())) {
				excludedCounts.merge(route.shortName(), 1, Integer::sum);
				continue;
			}
			List<GtfsFeed.StopTime> stopTimes = feed.stopTimesByTripId().get(trip.id());
			if (stopTimes == null || stopTimes.isEmpty()) {
				throw new IllegalArgumentException("Trip without stop times: " + trip.id());
			}
			tripsByRoute.computeIfAbsent(route.shortName(), key -> new ArrayList<>()).add(trip);
		}
		excludedCounts.forEach((routeShortName, count) ->
			LOG.info("Excluded route {}: {} trips skipped", routeShortName, count));
		tripsByRoute.forEach((routeShortName, trips) ->
			LOG.info("Included route {}: {} trips", routeShortName, trips.size()));
		return tripsByRoute;
	}

	private void buildLine(String routeShortName, List<GtfsFeed.Trip> trips, TransitSchedule schedule,
			Vehicles vehicles, MesoRouter router) {
		List<GtfsFeed.Trip> orderedTrips = trips.stream()
			.sorted(Comparator.comparingInt(trip -> firstDeparture(trip.id())))
			.toList();

		TransitLine line = factory.createTransitLine(Id.create(routeShortName, TransitLine.class));
		schedule.addTransitLine(line);

		int departureIndex = 0;
		for (GtfsFeed.Trip trip : orderedTrips) {
			TransitRoute transitRoute = routeForTrip(routeShortName, trip, schedule, router, line);
			addDepartureAndVehicle(trip, routeShortName, departureIndex, transitRoute, vehicles);
			departureIndex++;
		}
	}

	private TransitRoute routeForTrip(String routeShortName, GtfsFeed.Trip trip, TransitSchedule schedule,
			MesoRouter router, TransitLine line) {
		List<GtfsFeed.StopTime> stopTimes = feed.stopTimesByTripId().get(trip.id());
		int firstDeparture = stopTimes.get(0).departureSeconds();

		String pattern = pattern(trip.routeId(), stopTimes, firstDeparture);
		String existingRouteId = patternToRouteId.get(pattern);
		if (existingRouteId != null) {
			return line.getRoutes().get(Id.create(existingRouteId, TransitRoute.class));
		}

		List<TransitRouteStop> routeStops = new ArrayList<>();
		List<Id<Link>> chain = new ArrayList<>();
		for (int i = 0; i < stopTimes.size(); i++) {
			GtfsFeed.StopTime stopTime = stopTimes.get(i);
			TransitStopFacility facility = stopFacility(schedule, stopTime.stopId());
			if (i > 0) {
				Id<Node> from = Id.createNodeId(stopTimes.get(i - 1).stopId());
				chain.addAll(router.shortestPath(from, Id.createNodeId(stopTime.stopId())));
			}
			chain.add(StationStopLinks.stopLinkId(Id.createNodeId(stopTime.stopId())));
			double arrivalOffset = stopTime.arrivalSeconds() - firstDeparture;
			double departureOffset = stopTime.departureSeconds() - firstDeparture;
			TransitRouteStop routeStop =
				factory.createTransitRouteStop(facility, arrivalOffset, departureOffset);
			// without passengers a driver would skip stops and run early;
			// holding to the timetable keeps the simulation on the GTFS times
			routeStop.setAwaitDepartureTime(true);
			routeStops.add(routeStop);
		}

		NetworkRoute networkRoute = RouteUtils.createNetworkRoute(chain, network);
		String routeId = routeShortName + "_" + routeCounters.merge(routeShortName, 1, Integer::sum);
		TransitRoute transitRoute = factory.createTransitRoute(Id.create(routeId, TransitRoute.class),
			networkRoute, routeStops, MODE);
		line.addRoute(transitRoute);
		patternToRouteId.put(pattern, routeId);
		return transitRoute;
	}

	private void addDepartureAndVehicle(GtfsFeed.Trip trip, String routeShortName, int departureIndex,
			TransitRoute transitRoute, Vehicles vehicles) {
		Departure departure = factory.createDeparture(Id.create(trip.id(), Departure.class),
			firstDeparture(trip.id()));
		Id<Vehicle> vehicleId = Id.create(trip.id(), Vehicle.class);
		departure.setVehicleId(vehicleId);
		transitRoute.addDeparture(departure);

		String vehicleTypeId = assignment.vehicleTypeId(routeShortName, departureIndex);
		VehicleType vehicleType = vehicles.getVehicleTypes().get(Id.create(vehicleTypeId, VehicleType.class));
		if (vehicleType == null) {
			throw new IllegalArgumentException("Unknown vehicle type: " + vehicleTypeId);
		}
		vehicles.addVehicle(VehicleUtils.createVehicle(vehicleId, vehicleType));
	}

	private TransitStopFacility stopFacility(TransitSchedule schedule, String stopId) {
		return stopFacilities.computeIfAbsent(stopId, id -> {
			Node node = network.getNodes().get(Id.createNodeId(id));
			if (node == null) {
				throw new IllegalArgumentException("Unknown station node for GTFS stop: " + id);
			}
			TransitStopFacility facility = factory.createTransitStopFacility(
				Id.create(id, TransitStopFacility.class), node.getCoord(), false);
			facility.setLinkId(StationStopLinks.stopLinkId(node.getId()));
			schedule.addStopFacility(facility);
			return facility;
		});
	}

	private String pattern(String routeId, List<GtfsFeed.StopTime> stopTimes, int firstDeparture) {
		StringBuilder key = new StringBuilder(routeId);
		for (GtfsFeed.StopTime stopTime : stopTimes) {
			key.append('|').append(stopTime.stopId())
				.append(':').append(stopTime.arrivalSeconds() - firstDeparture)
				.append(':').append(stopTime.departureSeconds() - firstDeparture);
		}
		return key.toString();
	}

	private int firstDeparture(String tripId) {
		return feed.stopTimesByTripId().get(tripId).get(0).departureSeconds();
	}
}
