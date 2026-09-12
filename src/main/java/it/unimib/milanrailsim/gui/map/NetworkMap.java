package it.unimib.milanrailsim.gui.map;

import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.network.GtfsFeed;
import javafx.scene.paint.Color;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the map draws that does not move: stations, track alignments and
 * the lines running over them, in Web Mercator metres so raster tiles align. Every line carries its GTFS colour; tracks take
 * the colour of the suburban lines serving them and stay muted elsewhere, so
 * the S network is the only vivid element while regional trains still show.
 */
public final class NetworkMap {

	private static final String MERCATOR_CRS = "EPSG:3857";

	/** {@code lineCount} is the number of lines calling at the station, a proxy of its importance. */
	public record Station(String id, String name, double x, double y, int lineCount) {
	}

	/** {@code lineIds} lists every line over the track in id order. */
	public record Track(String linkId, Polyline polyline, List<String> lineIds) {
	}

	/** {@code dailyDepartures} counts the timetabled runs of the loaded service day. */
	public record Line(String id, String name, Color color, boolean suburban, int dailyDepartures,
			int stationCount, Set<String> linkIds) {
	}

	private final List<Station> stations;
	private final List<Track> tracks;
	private final Map<String, Line> lines;
	private final Bounds bounds;

	private NetworkMap(List<Station> stations, List<Track> tracks, List<Line> lines, Bounds bounds) {
		this.stations = stations;
		this.tracks = tracks;
		this.lines = new LinkedHashMap<>();
		lines.forEach(line -> this.lines.put(line.id(), line));
		this.bounds = bounds;
	}

	public List<Station> stations() {
		return stations;
	}

	/** Regional-only tracks first, so suburban tracks are painted on top. */
	public List<Track> tracks() {
		return tracks;
	}

	public Line line(String id) {
		return lines.get(id);
	}

	public List<Line> lines() {
		return List.copyOf(lines.values());
	}

	public Bounds bounds() {
		return bounds;
	}

	/**
	 * @param networkCrs the CRS of the network and geometry files, e.g. {@code EPSG:32632}
	 * @throws IllegalStateException if a link lacks geometry or a line lacks a GTFS colour
	 */
	public static NetworkMap load(Path networkFile, Path scheduleFile, Path geometryCsv, Path gtfsDir, String networkCrs) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(networkFile.toString());
		new TransitScheduleReader(scenario).readFile(scheduleFile.toString());
		Network network = scenario.getNetwork();
		CoordinateTransformation toMercator = TransformationFactory.getCoordinateTransformation(networkCrs, MERCATOR_CRS);
		network.getNodes().values().forEach(node -> node.setCoord(toMercator.transform(node.getCoord())));

		List<Line> lines = lines(scenario.getTransitSchedule(), GtfsFeed.load(gtfsDir));
		return new NetworkMap(stations(network, lines), tracks(network, readGeometry(geometryCsv, toMercator), lines),
			lines, bounds(network));
	}

	private static Map<String, Polyline> readGeometry(Path csv, CoordinateTransformation toMercator) {
		Map<String, Polyline> geometry = new HashMap<>();
		for (Map<String, String> row : CsvTable.read(csv)) {
			Polyline polyline = Polyline.parse(row.get("points")).transform(point -> {
				Coord mapped = toMercator.transform(new Coord(point[0], point[1]));
				return new double[] { mapped.getX(), mapped.getY() };
			});
			geometry.put(row.get("link_id"), polyline);
		}
		return geometry;
	}

	private static List<Line> lines(TransitSchedule schedule, GtfsFeed feed) {
		Map<String, GtfsFeed.Route> routes = new HashMap<>();
		feed.routesById().values().forEach(route -> routes.put(route.shortName(), route));
		return schedule.getTransitLines().values().stream()
			.map(line -> line.getId().toString())
			.sorted()
			.map(id -> {
				GtfsFeed.Route route = routes.get(id);
				if (route == null) {
					throw new IllegalStateException("No GTFS route for line " + id);
				}
				TransitLine line = schedule.getTransitLines().get(Id.create(id, TransitLine.class));
				int departures = line.getRoutes().values().stream().mapToInt(r -> r.getDepartures().size()).sum();
				Set<String> linkIds = linkIds(line);
				int stations = (int) linkIds.stream().filter(linkId -> linkId.startsWith("stop_")).count();
				return new Line(id, route.longName(), Color.web("#" + route.color()), id.startsWith("S"),
					departures, stations, linkIds);
			})
			.toList();
	}

	private static Set<String> linkIds(TransitLine line) {
		Set<String> linkIds = new LinkedHashSet<>();
		for (TransitRoute route : line.getRoutes().values()) {
			linkIds.add(route.getRoute().getStartLinkId().toString());
			route.getRoute().getLinkIds().forEach(id -> linkIds.add(id.toString()));
			linkIds.add(route.getRoute().getEndLinkId().toString());
		}
		return Set.copyOf(linkIds);
	}

	private static List<Track> tracks(Network network, Map<String, Polyline> geometry, List<Line> lines) {
		Map<String, List<String>> linesByLink = new HashMap<>();
		lines.forEach(line -> line.linkIds()
			.forEach(id -> linesByLink.computeIfAbsent(id, key -> new ArrayList<>()).add(line.id())));
		Set<String> suburbanLinks = lines.stream().filter(Line::suburban)
			.flatMap(line -> line.linkIds().stream()).collect(Collectors.toSet());

		List<Track> tracks = new ArrayList<>();
		for (Link link : network.getLinks().values()) {
			if (link.getFromNode() == link.getToNode()) {
				continue;
			}
			String id = link.getId().toString();
			Polyline polyline = geometry.get(id);
			if (polyline == null) {
				throw new IllegalStateException("No geometry for link " + id);
			}
			tracks.add(new Track(id, polyline, List.copyOf(linesByLink.getOrDefault(id, List.of()))));
		}
		tracks.sort(Comparator.comparing(track -> suburbanLinks.contains(track.linkId())));
		return List.copyOf(tracks);
	}

	private static List<Station> stations(Network network, List<Line> lines) {
		List<Station> stations = new ArrayList<>();
		for (Node node : network.getNodes().values()) {
			Object name = node.getAttributes().getAttribute("gtfsStopName");
			if (name != null) {
				String stopLink = "stop_" + node.getId();
				int lineCount = (int) lines.stream().filter(line -> line.linkIds().contains(stopLink)).count();
				stations.add(new Station(node.getId().toString(), name.toString(),
					node.getCoord().getX(), node.getCoord().getY(), lineCount));
			}
		}
		return List.copyOf(stations);
	}

	private static Bounds bounds(Network network) {
		Bounds bounds = null;
		for (Node node : network.getNodes().values()) {
			double x = node.getCoord().getX();
			double y = node.getCoord().getY();
			bounds = bounds == null ? Bounds.around(x, y) : bounds.including(x, y);
		}
		if (bounds == null) {
			throw new IllegalStateException("Empty network");
		}
		return bounds;
	}
}
