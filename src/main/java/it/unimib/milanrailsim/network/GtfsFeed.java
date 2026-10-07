package it.unimib.milanrailsim.network;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Immutable in-memory view of the GTFS files this project needs. Loading is
 * fail-fast: structural problems in the feed abort with an exception instead
 * of producing a partially wrong network.
 */
public final class GtfsFeed {

	public record Stop(String id, String name, double lat, double lon) {
	}

	/** {@code color} is the GTFS {@code route_color} hex string without {@code #}. */
	public record Route(String id, String shortName, String longName, int type, String color) {
	}

	public record Trip(String id, String routeId, String serviceId) {
	}

	public record StopTime(String tripId, int arrivalSeconds, int departureSeconds, String stopId, int stopSequence) {
	}

	private final Map<String, Stop> stopsById;
	private final Map<String, Route> routesById;
	private final Map<String, Trip> tripsById;
	private final Map<String, List<StopTime>> stopTimesByTripId;
	private final List<Map<String, String>> calendarDateRows;

	private GtfsFeed(Map<String, Stop> stopsById, Map<String, Route> routesById, Map<String, Trip> tripsById,
			Map<String, List<StopTime>> stopTimesByTripId, List<Map<String, String>> calendarDateRows) {
		this.stopsById = stopsById;
		this.routesById = routesById;
		this.tripsById = tripsById;
		this.stopTimesByTripId = stopTimesByTripId;
		this.calendarDateRows = calendarDateRows;
	}

	public static GtfsFeed load(Path directory) {
		Map<String, Stop> stops = CsvTable.read(directory.resolve("stops.txt")).stream()
			.map(row -> new Stop(row.get("stop_id"), row.get("stop_name"),
				Double.parseDouble(row.get("stop_lat")), Double.parseDouble(row.get("stop_lon"))))
			.collect(toMapById(Stop::id));
		Map<String, Route> routes = CsvTable.read(directory.resolve("routes.txt")).stream()
			.map(row -> new Route(row.get("route_id"), row.get("route_short_name"),
				row.get("route_long_name"), Integer.parseInt(row.get("route_type")), row.get("route_color")))
			.collect(toMapById(Route::id));
		Map<String, Trip> trips = CsvTable.read(directory.resolve("trips.txt")).stream()
			.map(row -> new Trip(row.get("trip_id"), row.get("route_id"), row.get("service_id")))
			.collect(toMapById(Trip::id));
		Map<String, List<StopTime>> stopTimes = CsvTable.read(directory.resolve("stop_times.txt")).stream()
			.map(row -> new StopTime(row.get("trip_id"),
				GtfsTime.parseSeconds(row.get("arrival_time")),
				GtfsTime.parseSeconds(row.get("departure_time")),
				row.get("stop_id"),
				Integer.parseInt(row.get("stop_sequence"))))
			.collect(Collectors.groupingBy(StopTime::tripId,
				Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
					.sorted(Comparator.comparingInt(StopTime::stopSequence))
					.toList())));
		List<Map<String, String>> calendarDates = CsvTable.read(directory.resolve("calendar_dates.txt"));
		return new GtfsFeed(stops, routes, trips, stopTimes, calendarDates);
	}

	private static <T> Collector<T, ?, Map<String, T>> toMapById(Function<T, String> idExtractor) {
		return Collectors.toMap(idExtractor, Function.identity(),
			(a, b) -> {
				throw new IllegalArgumentException("Duplicate id: " + idExtractor.apply(a));
			},
			LinkedHashMap::new);
	}

	public Map<String, Stop> stopsById() {
		return stopsById;
	}

	public Map<String, Route> routesById() {
		return routesById;
	}

	public Map<String, Trip> tripsById() {
		return tripsById;
	}

	public Map<String, List<StopTime>> stopTimesByTripId() {
		return stopTimesByTripId;
	}

	public List<Map<String, String>> calendarDateRows() {
		return calendarDateRows;
	}
}
