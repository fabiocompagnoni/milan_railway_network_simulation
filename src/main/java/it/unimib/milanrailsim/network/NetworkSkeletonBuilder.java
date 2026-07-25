package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the provisional railsim network skeleton from the GTFS feed: one node
 * per served station, one directed link per observed consecutive-stop pair.
 * <p>
 * Every generated link is marked {@code dataStatus="provisional"}: length is a
 * beeline, freespeed is derived from it, capacity is the railsim default. These
 * placeholders make the skeleton simulatable but are NOT real data; they are
 * meant to be replaced manually with OSM measurements (correctness rule of the
 * project: no invented value may pass as real).
 */
final class NetworkSkeletonBuilder {

	private static final Logger log = LogManager.getLogger(NetworkSkeletonBuilder.class);

	private static final String TARGET_CRS = "EPSG:32632";
	private static final int RAIL_ROUTE_TYPE = 2;
	/** Placeholder when the GTFS minimum travel time is 0 (50 km/h). */
	private static final double FALLBACK_FREESPEED = 13.9;
	private static final double MINIMUM_LINK_LENGTH = 1.0;

	private final GtfsFeed feed;
	private final LocalDate serviceDate;

	NetworkSkeletonBuilder(GtfsFeed feed, LocalDate serviceDate) {
		this.feed = feed;
		this.serviceDate = serviceDate;
	}

	private record Segment(Set<String> routeShortNames, int tripCount, int minTravelSeconds) {

		Segment merge(String routeShortName, int travelSeconds) {
			Set<String> routes = new TreeSet<>(routeShortNames);
			routes.add(routeShortName);
			return new Segment(routes, tripCount + 1, Math.min(minTravelSeconds, travelSeconds));
		}

		static Segment first(String routeShortName, int travelSeconds) {
			return new Segment(new TreeSet<>(Set.of(routeShortName)), 1, travelSeconds);
		}
	}

	Network build() {
		Map<String, Segment> segments = aggregateSegments();
		return toNetwork(segments);
	}

	private Map<String, Segment> aggregateSegments() {
		Set<String> activeServices = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), serviceDate);
		// LinkedHashMap keeps link order stable across runs (reproducible XML diffs).
		Map<String, Segment> segments = new LinkedHashMap<>();
		for (GtfsFeed.Trip trip : feed.tripsById().values()) {
			GtfsFeed.Route route = feed.routesById().get(trip.routeId());
			if (route == null) {
				throw new IllegalArgumentException("Trip " + trip.id() + " references unknown route " + trip.routeId());
			}
			if (route.type() != RAIL_ROUTE_TYPE || !activeServices.contains(trip.serviceId())) {
				continue;
			}
			List<GtfsFeed.StopTime> stopTimes = feed.stopTimesByTripId().get(trip.id());
			if (stopTimes == null) {
				continue;
			}
			for (int i = 0; i < stopTimes.size() - 1; i++) {
				GtfsFeed.StopTime from = stopTimes.get(i);
				GtfsFeed.StopTime to = stopTimes.get(i + 1);
				if (from.stopId().equals(to.stopId())) {
					log.warn("Trip {} visits stop {} twice in a row; pair skipped", trip.id(), from.stopId());
					continue;
				}
				int travelSeconds = to.arrivalSeconds() - from.departureSeconds();
				if (travelSeconds < 0) {
					throw new IllegalArgumentException("Negative travel time on trip " + trip.id()
						+ " between " + from.stopId() + " and " + to.stopId());
				}
				String key = from.stopId() + "_" + to.stopId();
				segments.merge(key, Segment.first(route.shortName(), travelSeconds),
					(existing, ignored) -> existing.merge(route.shortName(), travelSeconds));
			}
		}
		return segments;
	}

	private Network toNetwork(Map<String, Segment> segments) {
		Network network = NetworkUtils.createNetwork();
		network.getAttributes().putAttribute("coordinateReferenceSystem", TARGET_CRS);
		CoordinateTransformation toUtm =
			TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, TARGET_CRS);
		Map<String, Node> nodesByStopId = new HashMap<>();
		for (Map.Entry<String, Segment> entry : segments.entrySet()) {
			String[] stopIds = entry.getKey().split("_");
			Node fromNode = nodesByStopId.computeIfAbsent(stopIds[0], id -> createStationNode(network, toUtm, id));
			Node toNode = nodesByStopId.computeIfAbsent(stopIds[1], id -> createStationNode(network, toUtm, id));
			addSkeletonLink(network, fromNode, toNode, entry.getValue());
		}
		return network;
	}

	private Node createStationNode(Network network, CoordinateTransformation toUtm, String stopId) {
		GtfsFeed.Stop stop = feed.stopsById().get(stopId);
		if (stop == null) {
			throw new IllegalArgumentException("stop_times references unknown stop " + stopId);
		}
		Coord utm = toUtm.transform(new Coord(stop.lon(), stop.lat()));
		Node node = network.getFactory().createNode(Id.createNodeId(stop.id()), utm);
		node.getAttributes().putAttribute("gtfsStopName", stop.name());
		network.addNode(node);
		return node;
	}

	private void addSkeletonLink(Network network, Node fromNode, Node toNode, Segment segment) {
		Link link = network.getFactory().createLink(
			Id.createLinkId(fromNode.getId() + "_" + toNode.getId()), fromNode, toNode);
		double beeline = CoordUtils.calcEuclideanDistance(fromNode.getCoord(), toNode.getCoord());
		if (beeline < MINIMUM_LINK_LENGTH) {
			log.warn("Stops {} and {} share coordinates; length floored to {} m",
				fromNode.getId(), toNode.getId(), MINIMUM_LINK_LENGTH);
			beeline = MINIMUM_LINK_LENGTH;
		}
		double freespeed;
		if (segment.minTravelSeconds() > 0) {
			freespeed = beeline / segment.minTravelSeconds();
		} else {
			log.warn("Zero minimum travel time on {}; placeholder freespeed {} m/s", link.getId(), FALLBACK_FREESPEED);
			freespeed = FALLBACK_FREESPEED;
		}
		link.setLength(beeline);
		link.setFreespeed(freespeed);
		link.setCapacity(3600.0);
		link.setNumberOfLanes(1.0);
		link.setAllowedModes(Set.of("rail"));
		link.getAttributes().putAttribute("railsimTrainCapacity", 1);
		link.getAttributes().putAttribute("dataStatus", "provisional");
		link.getAttributes().putAttribute("gtfsRoutes", String.join(",", segment.routeShortNames()));
		link.getAttributes().putAttribute("gtfsDailyTrips", segment.tripCount());
		link.getAttributes().putAttribute("gtfsMinTravelTimeSeconds", segment.minTravelSeconds());
		network.addLink(link);
	}
}
