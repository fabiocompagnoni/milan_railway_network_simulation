package it.unimib.milanrailsim.network.micro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;

/**
 * Declarative description of a microscopically modelled node of the network,
 * read from {@code data/nodes/<node>.json} (schema in {@code data/nodes/README.md}):
 * stations with their platform groups and throats, the per-track sections
 * between them, and which groups each line may use in order of preference.
 * <p>
 * Every reference is validated on read, so the builders can trust the ids.
 */
public record MicroNode(String id, String title, List<Station> stations, List<Segment> segments, Map<String, Line> lines) {

	public enum StationKind { TERMINAL, THROUGH, MIXED }

	public enum GroupKind { TERMINAL, THROUGH }

	public enum Direction { NORTH, SOUTH }

	/** {@code SIDINGS} is never declared: the builder adds it to every station for its sidings link. */
	public enum ConnectionKind { SEGMENT, MESO, SIDINGS }

	private static final String TERMINAL_RULE = "*terminal";
	private static final List<String> THROUGH_RULES = List.of("*through", "*transit");

	/** Where a group leads on one side: a bundle of a section of this node, or a mesoscopic neighbour station. */
	public record Connection(ConnectionKind kind, String target, String bundle) {

		public static final Connection SIDINGS = new Connection(ConnectionKind.SIDINGS, "sidings", null);
	}

	/** A named platform track; {@code direction} is null on bidirectional (terminal) tracks; {@code wayIds} are its OSM ways, if surveyed. */
	public record Track(String ref, Direction direction, List<Long> wayIds) {

		public Track(String ref, Direction direction) {
			this(ref, direction, List.of());
		}
	}

	public record Group(String id, GroupKind kind, List<Track> tracks, Integer capacity, int otherOperatorsShare,
			Map<Direction, List<Connection>> connections, String notes) {

		/** Tracks usable by timetabled trains: named tracks, or the declared capacity net of other operators. */
		public int trackCount() {
			return tracks.isEmpty() ? capacity - otherOperatorsShare : tracks.size();
		}

		public boolean hasNamedTracks() {
			return !tracks.isEmpty();
		}

		/** The named tracks, or numbered bidirectional ones for a group declared by capacity. */
		public List<Track> effectiveTracks() {
			if (hasNamedTracks()) {
				return tracks;
			}
			List<Track> anonymous = new ArrayList<>();
			for (int i = 1; i <= trackCount(); i++) {
				anonymous.add(new Track(Integer.toString(i), null));
			}
			return anonymous;
		}
	}

	public record Throat(String id, Direction side, String resource, OptionalDouble lengthM, OptionalDouble speedKmh,
			List<String> groups) {
	}

	public record Sidings(Optional<Integer> tracks) {
	}

	public record Station(String id, String name, StationKind kind, OptionalDouble platformLengthM, List<Group> groups,
			List<Throat> throats, Optional<Sidings> sidings) {

		public Group group(String groupId) {
			return groups.stream().filter(group -> group.id().equals(groupId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown group " + groupId + " in station " + id));
		}
	}

	/** The OSM ways and measured length of one physical track of a bundle in one direction. */
	public record TrackWays(List<Long> wayIds, double lengthM) {
	}

	public record SpeedStep(double kmh, double lengthM) {
	}

	public record Bundle(TrackWays north, TrackWays south, List<SpeedStep> speedProfile) {
	}

	public record Segment(String from, String to, Map<String, Bundle> bundles) {

		public String id() {
			return from + "_" + to;
		}
	}

	public record Line(Optional<String> bundle, Map<String, List<String>> stations) {
	}

	public MicroNode(String id, String title, List<Station> stations, List<Segment> segments, Map<String, Line> lines) {
		this.id = id;
		this.title = title;
		this.stations = List.copyOf(stations);
		this.segments = List.copyOf(segments);
		this.lines = Map.copyOf(lines);
		validate(this);
	}

	public Station station(String stationId) {
		return stations.stream().filter(station -> station.id().equals(stationId)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown station " + stationId + " in node " + id));
	}

	public boolean hasStation(String stationId) {
		return stations.stream().anyMatch(station -> station.id().equals(stationId));
	}

	public Line line(String lineId) {
		Line line = lines.get(lineId);
		if (line == null) {
			throw new IllegalArgumentException("Line " + lineId + " is not declared in node " + id);
		}
		return line;
	}

	public Optional<Segment> segment(String from, String to) {
		return segments.stream().filter(segment -> segment.from().equals(from) && segment.to().equals(to)).findFirst();
	}

	/**
	 * Groups a line may use at a station, first choice first. A line named in
	 * the file wins; otherwise the wildcard rule for terminating or passing
	 * trains applies; an empty list means the line is not covered here.
	 */
	public List<String> preferredGroups(String lineId, String stationId, boolean terminating) {
		Line named = lines.get(lineId);
		if (named != null && named.stations().containsKey(stationId)) {
			return named.stations().get(stationId);
		}
		List<String> rules = terminating ? List.of(TERMINAL_RULE) : THROUGH_RULES;
		for (String rule : rules) {
			Line fallback = lines.get(rule);
			if (fallback != null && fallback.stations().containsKey(stationId)) {
				return fallback.stations().get(stationId);
			}
		}
		return List.of();
	}

	/**
	 * The side of a station on which another stop lies: a declared meso
	 * neighbour or segment end, or a station of this node reached through the
	 * chain of segments (sections run from south to north).
	 */
	/** Whether trains can run between the group and {@code otherStopId} through the given side of the station. */
	public static boolean connects(Group group, Direction side, String stationId, String otherStopId) {
		for (Connection connection : group.connections().getOrDefault(side, List.of())) {
			if (connection.kind() == ConnectionKind.MESO && connection.target().equals(otherStopId)) {
				return true;
			}
			if (connection.kind() == ConnectionKind.SEGMENT
					&& (connection.target().equals(stationId + "_" + otherStopId)
						|| connection.target().equals(otherStopId + "_" + stationId))) {
				return true;
			}
		}
		return false;
	}

	public Optional<Direction> sideOf(String stationId, String otherStopId) {
		Station station = station(stationId);
		for (Group group : station.groups()) {
			for (Map.Entry<Direction, List<Connection>> side : group.connections().entrySet()) {
				for (Connection connection : side.getValue()) {
					if (connection.kind() == ConnectionKind.MESO && connection.target().equals(otherStopId)) {
						return Optional.of(side.getKey());
					}
					if (connection.kind() == ConnectionKind.SEGMENT
							&& (connection.target().equals(stationId + "_" + otherStopId)
								|| connection.target().equals(otherStopId + "_" + stationId))) {
						return Optional.of(side.getKey());
					}
				}
			}
		}
		if (!hasStation(otherStopId)) {
			return Optional.empty();
		}
		if (reachesNorthward(stationId, otherStopId)) {
			return Optional.of(Direction.NORTH);
		}
		if (reachesNorthward(otherStopId, stationId)) {
			return Optional.of(Direction.SOUTH);
		}
		return Optional.empty();
	}

	private boolean reachesNorthward(String from, String to) {
		return segments.stream()
			.filter(segment -> segment.from().equals(from))
			.anyMatch(segment -> segment.to().equals(to) || reachesNorthward(segment.to(), to));
	}

	public static MicroNode read(Path file) {
		try {
			return parse(new ObjectMapper().readTree(file.toFile()), file);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read micro node " + file, e);
		}
	}

	/** Every {@code *.json} of the directory that declares a {@code node} field, sorted by node id. */
	public static List<MicroNode> readAll(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().endsWith(".json"))
				.filter(MicroNode::declaresNode)
				.map(MicroNode::read)
				.sorted(Comparator.comparing(MicroNode::id))
				.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list micro nodes in " + directory, e);
		}
	}

	private static boolean declaresNode(Path file) {
		try {
			return new ObjectMapper().readTree(file.toFile()).hasNonNull("node");
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
	}

	private static MicroNode parse(JsonNode root, Path file) {
		List<Station> stations = new ArrayList<>();
		for (JsonNode station : required(root, "stations", file)) {
			stations.add(parseStation(station));
		}
		List<Segment> segments = new ArrayList<>();
		for (JsonNode segment : required(root, "segments", file)) {
			segments.add(parseSegment(segment));
		}
		Map<String, Line> lines = new LinkedHashMap<>();
		required(root, "lines", file).properties().forEach(entry ->
			lines.put(entry.getKey(), parseLine(entry.getValue())));
		return new MicroNode(text(root, "node"), text(root, "title"), stations, segments, lines);
	}

	private static Station parseStation(JsonNode node) {
		List<Group> groups = new ArrayList<>();
		for (JsonNode group : node.path("groups")) {
			groups.add(parseGroup(group));
		}
		List<Throat> throats = new ArrayList<>();
		for (JsonNode throat : node.path("throats")) {
			List<String> throatGroups = new ArrayList<>();
			throat.path("groups").forEach(group -> throatGroups.add(group.asText()));
			throats.add(new Throat(text(throat, "id"), Direction.valueOf(text(throat, "side").toUpperCase(Locale.ROOT)),
				text(throat, "resource"), optionalDouble(throat, "lengthM"), optionalDouble(throat, "speedKmh"), throatGroups));
		}
		Optional<Sidings> sidings = node.hasNonNull("sidings")
			? Optional.of(new Sidings(node.get("sidings").hasNonNull("tracks")
				? Optional.of(node.get("sidings").get("tracks").asInt()) : Optional.empty()))
			: Optional.empty();
		return new Station(text(node, "id"), text(node, "name"),
			StationKind.valueOf(text(node, "kind").toUpperCase(Locale.ROOT)),
			optionalDouble(node, "platformLengthM"), groups, throats, sidings);
	}

	private static Group parseGroup(JsonNode node) {
		List<Track> tracks = new ArrayList<>();
		for (JsonNode track : node.path("tracks")) {
			Direction direction = track.hasNonNull("direction")
				? Direction.valueOf(track.get("direction").asText().toUpperCase(Locale.ROOT)) : null;
			List<Long> wayIds = new ArrayList<>();
			track.path("wayIds").forEach(way -> wayIds.add(way.asLong()));
			tracks.add(new Track(text(track, "ref"), direction, List.copyOf(wayIds)));
		}
		Map<Direction, List<Connection>> connections = new EnumMap<>(Direction.class);
		node.path("connections").properties().forEach(entry -> {
			List<Connection> sides = new ArrayList<>();
			entry.getValue().forEach(connection -> sides.add(parseConnection(connection.asText())));
			connections.put(Direction.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), sides);
		});
		Integer capacity = node.hasNonNull("capacity") ? node.get("capacity").asInt() : null;
		return new Group(text(node, "id"), GroupKind.valueOf(text(node, "kind").toUpperCase(Locale.ROOT)), tracks,
			capacity, node.path("otherOperatorsShare").asInt(0), connections, node.path("notes").asText(""));
	}

	private static Connection parseConnection(String spec) {
		String[] parts = spec.split(":");
		if (parts.length == 3 && parts[0].equals("segment")) {
			return new Connection(ConnectionKind.SEGMENT, parts[1], parts[2]);
		}
		if (parts.length == 2 && parts[0].equals("meso")) {
			return new Connection(ConnectionKind.MESO, parts[1], null);
		}
		throw new IllegalArgumentException("Connection must be segment:<from>_<to>:<bundle> or meso:<stop>: " + spec);
	}

	private static Segment parseSegment(JsonNode node) {
		Map<String, Bundle> bundles = new LinkedHashMap<>();
		node.path("bundles").properties().forEach(entry -> {
			JsonNode bundle = entry.getValue();
			List<SpeedStep> profile = new ArrayList<>();
			bundle.path("speedProfile").forEach(step ->
				profile.add(new SpeedStep(step.get("kmh").asDouble(), step.get("lengthM").asDouble())));
			bundles.put(entry.getKey(), new Bundle(parseTrackWays(bundle.get("north")), parseTrackWays(bundle.get("south")), profile));
		});
		return new Segment(text(node, "from"), text(node, "to"), bundles);
	}

	private static TrackWays parseTrackWays(JsonNode node) {
		if (node == null) {
			throw new IllegalArgumentException("A bundle needs both north and south tracks");
		}
		List<Long> wayIds = new ArrayList<>();
		node.path("wayIds").forEach(way -> wayIds.add(way.asLong()));
		return new TrackWays(wayIds, node.get("lengthM").asDouble());
	}

	private static Line parseLine(JsonNode node) {
		Map<String, List<String>> stations = new LinkedHashMap<>();
		node.path("stations").properties().forEach(entry -> {
			List<String> groups = new ArrayList<>();
			entry.getValue().forEach(group -> groups.add(group.asText()));
			stations.put(entry.getKey(), List.copyOf(groups));
		});
		Optional<String> bundle = node.hasNonNull("bundle") ? Optional.of(node.get("bundle").asText()) : Optional.empty();
		return new Line(bundle, stations);
	}

	private static JsonNode required(JsonNode root, String field, Path file) {
		JsonNode node = root.get(field);
		if (node == null) {
			throw new IllegalArgumentException("Micro node " + file + " lacks '" + field + "'");
		}
		return node;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw new IllegalArgumentException("Missing field '" + field + "' in " + node);
		}
		return value.asText();
	}

	private static OptionalDouble optionalDouble(JsonNode node, String field) {
		return node.hasNonNull(field) ? OptionalDouble.of(node.get(field).asDouble()) : OptionalDouble.empty();
	}

	private static void validate(MicroNode node) {
		for (Station station : node.stations()) {
			for (Group group : station.groups()) {
				if (group.tracks().isEmpty() && group.capacity() == null) {
					throw new IllegalArgumentException("Group " + group.id() + " of " + station.id() + " needs tracks or capacity");
				}
				group.connections().values().stream().flatMap(List::stream).forEach(connection -> validateConnection(node, station, connection));
			}
			for (Throat throat : station.throats()) {
				throat.groups().forEach(station::group);
			}
		}
		for (Segment segment : node.segments()) {
			if (!node.hasStation(segment.from()) || !node.hasStation(segment.to())) {
				throw new IllegalArgumentException("Segment " + segment.id() + " links stations outside node " + node.id());
			}
		}
		node.lines().forEach((lineId, line) -> {
			line.stations().forEach((stationId, groups) -> groups.forEach(node.station(stationId)::group));
			line.bundle().ifPresent(bundle -> {
				if (node.segments().stream().noneMatch(segment -> segment.bundles().containsKey(bundle))) {
					throw new IllegalArgumentException("Line " + lineId + " uses unknown bundle " + bundle + " in node " + node.id());
				}
			});
		});
	}

	private static void validateConnection(MicroNode node, Station station, Connection connection) {
		if (connection.kind() != ConnectionKind.SEGMENT) {
			return;
		}
		Segment segment = node.segments().stream().filter(candidate -> candidate.id().equals(connection.target())).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Station " + station.id() + " connects to unknown segment " + connection.target()));
		if (!segment.bundles().containsKey(connection.bundle())) {
			throw new IllegalArgumentException("Station " + station.id() + " connects to unknown bundle " + connection.bundle() + " of " + segment.id());
		}
	}
}
