package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.RailVehicleTypes;
import it.unimib.milanrailsim.network.StationTracks;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.network.micro.MicroIds;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.MicroNode.Direction;
import it.unimib.milanrailsim.network.micro.MicroNode.Group;
import it.unimib.milanrailsim.network.micro.MicroNode.Station;
import it.unimib.milanrailsim.network.micro.MicroNode.Track;
import it.unimib.milanrailsim.schedule.PlatformPlanner.Call;
import it.unimib.milanrailsim.schedule.PlatformPlanner.TripCalls;
import it.unimib.milanrailsim.schedule.TripChains.Journey;
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
import org.matsim.pt.transitSchedule.api.TransitStopArea;
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
import java.util.function.ToDoubleFunction;

/**
 * Turns the GTFS trips active on a given service date into a MATSim
 * {@link TransitSchedule} and matching {@link Vehicles} container. Trips are
 * first chained into circulations, so that at a micro station a train keeps
 * the platform it arrived on until it leaves again; each call at a micro
 * station gets a stop facility on its planned platform link, grouped in a
 * stop area per line so railsim can divert the train to another platform
 * the line may use. Routes are cheapest link chains that keep a line on its
 * declared bundle.
 */
public final class TransitScheduleBuilder {

	private static final Logger LOG = LogManager.getLogger(TransitScheduleBuilder.class);
	private static final int RAIL_ROUTE_TYPE = 2;
	private static final String MODE = "rail";
	/** Makes a link chain over the wrong bundle or platform group lose against any detour on the right one. */
	private static final double OFF_ROUTE_PENALTY_M = 1_000_000.0;

	/** @param chains trip ids of each circulation, in service order */
	public record Result(TransitSchedule schedule, Vehicles vehicles, List<List<String>> chains) {
	}

	private final GtfsFeed feed;
	private final Network network;
	private final LocalDate serviceDate;
	private final RouteVehicleAssignment assignment;
	private final List<VehicleType> vehicleTypes;
	private int windowStartSeconds = Integer.MIN_VALUE;
	private int windowEndSeconds = Integer.MAX_VALUE;
	private StationTracks stationTracks = StationTracks.empty();
	private List<MicroNode> microNodes = List.of();
	private int turnaroundSeconds = SchedulePipeline.TURNAROUND_SECONDS;
	private ToDoubleFunction<String> maxLayoverSeconds = stop -> Double.POSITIVE_INFINITY;

	private final TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
	private final Map<String, TransitStopFacility> facilities = new HashMap<>();
	private final Map<String, String> patternToRouteId = new HashMap<>();
	private final Map<String, Integer> routeCounters = new HashMap<>();
	private final Map<String, ToDoubleFunction<Link>> lineCosts = new HashMap<>();
	private final Map<String, TripCalls> movements = new HashMap<>();
	private PlatformPlanner planner;
	private PlatformPlanner.Plan plan;
	private LinkRouter router;

	public TransitScheduleBuilder(GtfsFeed feed, Network network, LocalDate serviceDate,
			RouteVehicleAssignment assignment) {
		this(feed, network, serviceDate, assignment, RailVehicleTypes.all());
	}

	public TransitScheduleBuilder(GtfsFeed feed, Network network, LocalDate serviceDate,
			RouteVehicleAssignment assignment, List<VehicleType> vehicleTypes) {
		this.feed = feed;
		this.network = network;
		this.serviceDate = serviceDate;
		this.assignment = assignment;
		this.vehicleTypes = vehicleTypes;
	}

	/** Platform counts that size the station loop links of the mesoscopic stations. */
	public TransitScheduleBuilder withStationTracks(StationTracks tracks) {
		this.stationTracks = tracks;
		return this;
	}

	/** Micro nodes already spliced into the network: their stations get platform facilities. */
	public TransitScheduleBuilder withMicroNodes(List<MicroNode> nodes) {
		this.microNodes = List.copyOf(nodes);
		return this;
	}

	/** Chaining parameters: turnaround and the longest layover a terminus allows before a train leaves for the sidings. */
	public TransitScheduleBuilder withCirculations(int turnaroundSeconds, ToDoubleFunction<String> maxLayoverSeconds) {
		this.turnaroundSeconds = turnaroundSeconds;
		this.maxLayoverSeconds = maxLayoverSeconds;
		return this;
	}

	/** Keeps only trips whose first departure falls within the window, in seconds since midnight. */
	public TransitScheduleBuilder withWindow(int startSeconds, int endSeconds) {
		this.windowStartSeconds = startSeconds;
		this.windowEndSeconds = endSeconds;
		return this;
	}

	public Result build() {
		StationStopLinks.addStopLinks(network, stationTracks);
		router = new LinkRouter(network);
		planner = new PlatformPlanner(microNodes, turnaroundSeconds);

		TransitSchedule schedule = factory.createTransitSchedule();
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		vehicleTypes.forEach(vehicles::addVehicleType);

		Set<String> activeServiceIds = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), serviceDate);
		Map<String, List<GtfsFeed.Trip>> includedTripsByRoute = includedTripsByRoute(activeServiceIds);

		List<List<TripCalls>> chains = viaSidings(chains(includedTripsByRoute));
		plan = planner.plan(chains);
		chains.forEach(chain -> chain.forEach(trip -> movements.put(trip.tripId(), trip)));

		for (Map.Entry<String, List<GtfsFeed.Trip>> entry : includedTripsByRoute.entrySet()) {
			buildLine(entry.getKey(), entry.getValue(), schedule, vehicles);
		}

		List<List<String>> chainIds = chains.stream()
			.map(chain -> chain.stream().map(TripCalls::tripId).toList())
			.toList();
		return new Result(schedule, vehicles, chainIds);
	}

	private Map<String, List<GtfsFeed.Trip>> includedTripsByRoute(Set<String> activeServiceIds) {
		Map<String, List<GtfsFeed.Trip>> tripsByRoute = new LinkedHashMap<>();
		Map<String, Integer> excludedCounts = new HashMap<>();
		Map<String, Integer> offNetworkCounts = new HashMap<>();
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
			int firstDeparture = stopTimes.getFirst().departureSeconds();
			if (firstDeparture < windowStartSeconds || firstDeparture > windowEndSeconds) {
				continue;
			}
			if (stopTimes.stream().anyMatch(stopTime -> !network.getNodes().containsKey(Id.createNodeId(stopTime.stopId())))) {
				offNetworkCounts.merge(route.shortName(), 1, Integer::sum);
				continue;
			}
			tripsByRoute.computeIfAbsent(route.shortName(), key -> new ArrayList<>()).add(trip);
		}
		excludedCounts.forEach((routeShortName, count) ->
			LOG.info("Excluded route {}: {} trips skipped", routeShortName, count));
		offNetworkCounts.forEach((routeShortName, count) ->
			LOG.warn("Route {}: {} trips skipped, they call at stations outside the modelled network", routeShortName, count));
		tripsByRoute.forEach((routeShortName, trips) ->
			LOG.info("Included route {}: {} trips", routeShortName, trips.size()));
		return tripsByRoute;
	}

	private List<List<TripCalls>> chains(Map<String, List<GtfsFeed.Trip>> tripsByRoute) {
		List<Journey> journeys = new ArrayList<>();
		Map<String, TripCalls> callsByTrip = new HashMap<>();
		tripsByRoute.forEach((line, trips) -> trips.forEach(trip -> {
			List<GtfsFeed.StopTime> stopTimes = feed.stopTimesByTripId().get(trip.id());
			List<Call> calls = stopTimes.stream()
				.map(stopTime -> new Call(stopTime.stopId(), stopTime.arrivalSeconds(), stopTime.departureSeconds()))
				.toList();
			callsByTrip.put(trip.id(), new TripCalls(trip.id(), line, calls));
			journeys.add(new Journey(trip.id(), line, calls.getFirst().stopId(), calls.getLast().stopId(),
				calls.getFirst().departureSeconds(), calls.getLast().arrivalSeconds()));
		}));
		// a detailed station has sidings: a long layover there is spent in them, not by a new vehicle
		ToDoubleFunction<String> chainLimit = stop -> planner.isMicro(stop) ? Double.POSITIVE_INFINITY
			: maxLayoverSeconds.applyAsDouble(stop);
		return TripChains.chain(journeys, turnaroundSeconds, chainLimit).stream()
			.map(chain -> chain.stream().map(journey -> callsByTrip.get(journey.tripId())).toList())
			.toList();
	}

	/**
	 * Marks the trips that start from or end in the sidings of a detailed
	 * station: the first and last of a chain there, and both sides of a layover
	 * longer than the station allows on a platform.
	 */
	private List<List<TripCalls>> viaSidings(List<List<TripCalls>> chains) {
		List<List<TripCalls>> result = new ArrayList<>();
		for (List<TripCalls> chain : chains) {
			List<TripCalls> marked = new ArrayList<>();
			for (int i = 0; i < chain.size(); i++) {
				TripCalls trip = chain.get(i);
				String first = trip.calls().getFirst().stopId();
				String last = trip.calls().getLast().stopId();
				boolean from = planner.isMicro(first) && (i == 0 || longLayover(chain.get(i - 1), trip));
				boolean to = planner.isMicro(last) && (i == chain.size() - 1 || longLayover(trip, chain.get(i + 1)));
				marked.add(trip.via(from, to));
			}
			result.add(List.copyOf(marked));
		}
		return List.copyOf(result);
	}

	private boolean longLayover(TripCalls before, TripCalls after) {
		String terminus = before.calls().getLast().stopId();
		double wait = after.calls().getFirst().departureSeconds() - before.calls().getLast().arrivalSeconds();
		return wait > maxLayoverSeconds.applyAsDouble(terminus);
	}

	private void buildLine(String routeShortName, List<GtfsFeed.Trip> trips, TransitSchedule schedule, Vehicles vehicles) {
		List<GtfsFeed.Trip> orderedTrips = trips.stream()
			.sorted(Comparator.comparingInt(trip -> firstDeparture(trip.id())))
			.toList();

		TransitLine line = factory.createTransitLine(Id.create(routeShortName, TransitLine.class));
		schedule.addTransitLine(line);

		int departureIndex = 0;
		for (GtfsFeed.Trip trip : orderedTrips) {
			TransitRoute transitRoute = routeForTrip(routeShortName, trip, schedule, line);
			addDepartureAndVehicle(trip, routeShortName, departureIndex, transitRoute, vehicles);
			departureIndex++;
		}
	}

	private TransitRoute routeForTrip(String routeShortName, GtfsFeed.Trip trip, TransitSchedule schedule, TransitLine line) {
		List<GtfsFeed.StopTime> stopTimes = feed.stopTimesByTripId().get(trip.id());
		TripCalls movement = movements.get(trip.id());
		int positioning = movement.fromSidings() ? PlatformPlanner.POSITIONING_SECONDS : 0;
		int firstDeparture = stopTimes.get(0).departureSeconds();

		List<TransitStopFacility> stopFacilities = new ArrayList<>();
		for (int i = 0; i < stopTimes.size(); i++) {
			stopFacilities.add(facilityFor(schedule, routeShortName, trip.id(), stopTimes, i));
		}
		String pattern = pattern(trip.routeId(), stopTimes, stopFacilities, firstDeparture)
			+ (movement.fromSidings() ? "|from-sidings" : "") + (movement.toSidings() ? "|to-sidings" : "");
		String existingRouteId = patternToRouteId.get(pattern);
		if (existingRouteId != null) {
			return line.getRoutes().get(Id.create(existingRouteId, TransitRoute.class));
		}

		String costKey = routeShortName + "|" + pattern.hashCode();
		ToDoubleFunction<Link> cost = tripCost(routeShortName, stopFacilities);
		List<TransitRouteStop> routeStops = new ArrayList<>();
		List<Id<Link>> chain = new ArrayList<>();
		for (int i = 0; i < stopTimes.size(); i++) {
			GtfsFeed.StopTime stopTime = stopTimes.get(i);
			TransitStopFacility facility = stopFacilities.get(i);
			if (i == 0 && movement.fromSidings()) {
				Id<Link> sidings = sidingsOf(stopTime.stopId());
				chain.add(sidings);
				chain.addAll(router.path(sidings, facility.getLinkId(), costKey, cost));
			} else if (i == 0) {
				chain.add(facility.getLinkId());
			} else {
				chain.addAll(router.path(stopFacilities.get(i - 1).getLinkId(), facility.getLinkId(), costKey, cost));
			}
			double arrivalOffset = stopTime.arrivalSeconds() - firstDeparture + positioning;
			double departureOffset = stopTime.departureSeconds() - firstDeparture + positioning;
			TransitRouteStop routeStop = factory.createTransitRouteStop(facility, arrivalOffset, departureOffset);
			// without passengers a driver would skip stops and run early;
			// holding to the timetable keeps the simulation on the GTFS times
			routeStop.setAwaitDepartureTime(true);
			routeStops.add(routeStop);
		}

		if (movement.toSidings()) {
			chain.addAll(router.path(stopFacilities.getLast().getLinkId(), sidingsOf(stopTimes.getLast().stopId()), costKey, cost));
		}
		if (new java.util.HashSet<>(chain).size() < chain.size()) {
			// railsim tracks a train's head and tail by the links of its route and cannot follow a repeated one
			throw new IllegalStateException("Route of trip " + trip.id() + " (" + routeShortName + ") runs a link twice: " + chain);
		}
		NetworkRoute networkRoute = RouteUtils.createNetworkRoute(chain, network);
		String routeId = routeShortName + "_" + routeCounters.merge(routeShortName, 1, Integer::sum);
		TransitRoute transitRoute = factory.createTransitRoute(Id.create(routeId, TransitRoute.class),
			networkRoute, routeStops, MODE);
		line.addRoute(transitRoute);
		patternToRouteId.put(pattern, routeId);
		return transitRoute;
	}

	private TransitStopFacility facilityFor(TransitSchedule schedule, String line, String tripId,
			List<GtfsFeed.StopTime> stopTimes, int index) {
		String stopId = stopTimes.get(index).stopId();
		if (!planner.isMicro(stopId)) {
			return stopFacility(schedule, stopId);
		}
		Id<Link> platform = plan.platform(tripId, index)
			.orElseThrow(() -> new IllegalStateException("No platform planned for trip " + tripId + " at " + stopId));
		boolean terminating = index == 0 || index == stopTimes.size() - 1;
		Station station = planner.nodeOf(stopId).orElseThrow().station(stopId);
		return platformFacility(schedule, new PlatformCall(stopId, line, terminating, directionOf(platform, station)), platform);
	}

	/**
	 * The direction the planned platform link is run in: its own on a
	 * directional track, the variant's on a two-sided bidirectional track, none
	 * on a one-sided terminal track. Taken from the link rather than from the
	 * trip, since a train departing after a reversal stands on the link of its
	 * arrival direction.
	 */
	private static Direction directionOf(Id<Link> platform, Station station) {
		String id = platform.toString();
		for (Direction direction : Direction.values()) {
			if (id.endsWith("." + MicroIds.name(direction))) {
				return direction;
			}
		}
		for (Group group : station.groups()) {
			for (Track track : group.effectiveTracks()) {
				if (MicroIds.trackId(station, group, track).equals(id)) {
					return track.direction();
				}
			}
		}
		return null;
	}

	/** One call of a line at a micro station; {@code travel} is null when the platform has no direction. */
	private record PlatformCall(String stopId, String line, boolean terminating, Direction travel) {

		String area() {
			return stopId + "|" + line + "|" + (terminating ? "terminal" : "through")
				+ (travel == null ? "" : "|" + MicroIds.name(travel));
		}
	}

	/**
	 * Every platform the line may use at the station in that travel direction
	 * becomes a facility of one stop area, so railsim can remap a diverted
	 * train to the platform it actually reaches; the call itself refers to the
	 * planned platform's facility. Platforms of the other direction stay out:
	 * a detour onto one would have to run the platform, turn back and run it
	 * again, and railsim cannot follow a route that repeats a link.
	 */
	private TransitStopFacility platformFacility(TransitSchedule schedule, PlatformCall call, Id<Link> platform) {
		MicroNode node = planner.nodeOf(call.stopId()).orElseThrow();
		Station station = node.station(call.stopId());
		List<String> groups = node.preferredGroups(call.line(), call.stopId(), call.terminating());
		if (groups.isEmpty()) {
			groups = station.groups().stream().map(Group::id).toList();
		}
		String area = call.area();
		for (String groupId : groups) {
			Group group = station.group(groupId);
			for (Track track : group.effectiveTracks()) {
				if (track.direction() != null && call.travel() != null && track.direction() != call.travel()) {
					continue;
				}
				facility(schedule, MicroIds.platformLink(MicroIds.trackId(station, group, track), group, track, call.travel()),
					area, call.stopId());
			}
		}
		// the planner may have had to leave the preferred groups for one that connects to the trip's neighbours
		return facility(schedule, platform, area, call.stopId());
	}

	private TransitStopFacility facility(TransitSchedule schedule, Id<Link> link, String area, String stopId) {
		return facilities.computeIfAbsent(link + "|" + area, id -> {
			Node hub = network.getNodes().get(Id.createNodeId(stopId));
			TransitStopFacility facility = factory.createTransitStopFacility(
				Id.create(id, TransitStopFacility.class), hub.getCoord(), false);
			facility.setLinkId(link);
			facility.setStopAreaId(Id.create(area, TransitStopArea.class));
			facility.setName(stopId);
			schedule.addStopFacility(facility);
			return facility;
		});
	}

	/**
	 * Length, with a prohibitive penalty on links of another bundle than the
	 * line's in a node, and on platforms of groups the line may not pass through.
	 */
	/**
	 * The line's costs, except on the tracks this trip is planned to stand on:
	 * the planner may have put the train on a group the line does not prefer,
	 * and reversing there runs the track's other platform link, which must not
	 * cost more than a detour around the network.
	 */
	private ToDoubleFunction<Link> tripCost(String line, List<TransitStopFacility> stopFacilities) {
		Set<String> ownTracks = new java.util.HashSet<>();
		for (TransitStopFacility facility : stopFacilities) {
			ownTracks.add(trackOf(facility.getLinkId()));
		}
		ToDoubleFunction<Link> lineCost = lineCost(line);
		return link -> link.getAttributes().getAttribute("microTrack") != null && ownTracks.contains(trackOf(link.getId()))
			? link.getLength() : lineCost.applyAsDouble(link);
	}

	/** The track a platform link belongs to: its id without the {@code .in}, {@code .out}, {@code .north} or {@code .south} variant. */
	private static String trackOf(Id<Link> platformLink) {
		String id = platformLink.toString();
		for (String suffix : List.of(".in", ".out", ".north", ".south")) {
			if (id.endsWith(suffix)) {
				return id.substring(0, id.length() - suffix.length());
			}
		}
		return id;
	}

	private ToDoubleFunction<Link> lineCost(String line) {
		return lineCosts.computeIfAbsent(line, key -> link -> {
			double cost = link.getLength();
			Object nodeId = link.getAttributes().getAttribute("microNode");
			if (nodeId == null) {
				return cost;
			}
			MicroNode node = microNodes.stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
			if (node == null) {
				return cost;
			}
			Object bundle = link.getAttributes().getAttribute("microBundle");
			MicroNode.Line declared = node.lines().get(line);
			if (bundle != null && declared != null && declared.bundle().isPresent() && !declared.bundle().get().equals(bundle)) {
				cost += OFF_ROUTE_PENALTY_M;
			}
			Object group = link.getAttributes().getAttribute("microGroup");
			Object station = link.getAttributes().getAttribute("microStation");
			if (group != null && station != null) {
				// the groups the line may stand on when passing or when terminating: a train reversing
				// on its terminal track runs that track's other platform link, which must stay cheap
				List<String> allowed = new ArrayList<>(node.preferredGroups(line, station.toString(), false));
				allowed.addAll(node.preferredGroups(line, station.toString(), true));
				if (!allowed.isEmpty() && !allowed.contains(group.toString())) {
					cost += OFF_ROUTE_PENALTY_M;
				}
			}
			if (link.getAttributes().getAttribute("microSidings") != null) {
				// only a trip that starts or ends in the sidings should run them, never one changing platform
				cost += OFF_ROUTE_PENALTY_M;
			}
			return cost;
		});
	}

	private Id<Link> sidingsOf(String stopId) {
		return MicroIds.sidings(planner.nodeOf(stopId).orElseThrow().station(stopId));
	}

	/** A trip from the sidings leaves them early enough to be on its platform at the timetable departure. */
	private void addDepartureAndVehicle(GtfsFeed.Trip trip, String routeShortName, int departureIndex,
			TransitRoute transitRoute, Vehicles vehicles) {
		int positioning = movements.get(trip.id()).fromSidings() ? PlatformPlanner.POSITIONING_SECONDS : 0;
		Departure departure = factory.createDeparture(Id.create(trip.id(), Departure.class),
			firstDeparture(trip.id()) - positioning);
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
		return facilities.computeIfAbsent(stopId, id -> {
			Node node = network.getNodes().get(Id.createNodeId(id));
			if (node == null) {
				throw new IllegalArgumentException("Unknown station node for GTFS stop: " + id);
			}
			TransitStopFacility facility = factory.createTransitStopFacility(
				Id.create(id, TransitStopFacility.class), node.getCoord(), false);
			facility.setLinkId(StationStopLinks.stopLinkId(node.getId()));
			facility.setName(stopId);
			schedule.addStopFacility(facility);
			return facility;
		});
	}

	private String pattern(String routeId, List<GtfsFeed.StopTime> stopTimes, List<TransitStopFacility> stopFacilities,
			int firstDeparture) {
		StringBuilder key = new StringBuilder(routeId);
		for (int i = 0; i < stopTimes.size(); i++) {
			GtfsFeed.StopTime stopTime = stopTimes.get(i);
			key.append('|').append(stopFacilities.get(i).getId())
				.append(':').append(stopTime.arrivalSeconds() - firstDeparture)
				.append(':').append(stopTime.departureSeconds() - firstDeparture);
		}
		return key.toString();
	}

	private int firstDeparture(String tripId) {
		return feed.stopTimesByTripId().get(tripId).get(0).departureSeconds();
	}
}
