package it.unimib.milanrailsim.network.micro;

import it.unimib.milanrailsim.network.micro.MicroNode.Bundle;
import it.unimib.milanrailsim.network.micro.MicroNode.Connection;
import it.unimib.milanrailsim.network.micro.MicroNode.ConnectionKind;
import it.unimib.milanrailsim.network.micro.MicroNode.Direction;
import it.unimib.milanrailsim.network.micro.MicroNode.Group;
import it.unimib.milanrailsim.network.micro.MicroNode.Segment;
import it.unimib.milanrailsim.network.micro.MicroNode.SpeedStep;
import it.unimib.milanrailsim.network.micro.MicroNode.Station;
import it.unimib.milanrailsim.network.micro.MicroNode.Throat;
import it.unimib.milanrailsim.network.micro.MicroNode.Track;
import it.unimib.milanrailsim.network.micro.MicroNode.TrackWays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Splices {@link MicroNode}s into the mesoscopic network, following the
 * railsim micro station pattern: one link per platform track (capacity 1, its
 * own resource), throat links sharing one conflict resource per bundle and
 * marked as non-blocking area, and per-track section links between the
 * stations of a node. Mesoscopic links adjacent to a micro station are
 * redirected to the station and flagged as rerouting entry or exit, so railsim
 * can divert a train to a free platform of its stop area.
 * <p>
 * Each connection of a station side (a meso neighbour or a section bundle)
 * gets its own pair of junction nodes, one where trains enter and one where
 * they leave, joined only to the platforms of the groups declaring that
 * connection. Where a train may go is thus fixed by the topology alone: a
 * railsim detour ends at the node the exit link starts from without checking
 * turn restrictions, so restrictions could not have enforced it.
 * <p>
 * Ids: junctions {@code <station>.<side>.<connection>.in|out}, platform tracks
 * {@code <station>.p<ref>} (or {@code <station>.<group>.<n>} for anonymous
 * capacity), platform link ends {@code .a} (south end) and {@code .b} (north
 * end), approach links {@code <track>.<side>.<connection>.in|out}, sections
 * {@code <from>_<to>.<bundle>.<north|south>} with {@code .exit} and
 * {@code .entry} stubs. Internal nodes are laid out along the direction of the
 * station's northern neighbours, one track every {@value #TRACK_SPACING_M} m.
 */
public final class MicroNodeBuilder {

	private static final Logger LOG = LogManager.getLogger(MicroNodeBuilder.class);

	static final double PLATFORM_LENGTH_DEFAULT_M = 200.0;
	static final double APPROACH_LENGTH_DEFAULT_M = 100.0;
	static final double APPROACH_SPEED_DEFAULT_KMH = 30.0;
	static final double STUB_LENGTH_M = 100.0;
	static final double MIN_SECTION_LENGTH_M = 100.0;
	static final double TRACK_SPACING_M = 5.0;
	static final int MIN_THROAT_CAPACITY = 2;
	private static final double PLATFORM_SPEED_MS = 13.9;
	private static final double SECTION_SPEED_DEFAULT_MS = 25.0;
	private static final double JUNCTION_OFFSET_M = 300.0;
	private static final double JUNCTION_SPACING_M = 10.0;
	private static final String MODE = "rail";
	private static final String PROVISIONAL = "provisional";

	private final Network network;

	/** Entry and exit node of one connection on one side of a station. */
	private record Junction(Node in, Node out) {
	}

	private record Frame(Coord origin, double[] north, double[] left) {

		Coord at(double along, double across) {
			return new Coord(origin.getX() + north[0] * along + left[0] * across,
				origin.getY() + north[1] * along + left[1] * across);
		}
	}

	private final Map<String, Junction> junctions = new HashMap<>();

	public MicroNodeBuilder(Network network) {
		this.network = network;
	}

	public void splice(List<MicroNode> nodes) {
		for (MicroNode node : nodes) {
			for (Station station : node.stations()) {
				buildStation(node, station);
			}
			for (Segment segment : node.segments()) {
				buildSegment(node, segment);
			}
		}
	}

	/**
	 * A platform track with the nodes trains enter it by and leave it from,
	 * per side. Entrances and exits are distinct nodes even at the same end,
	 * so an approach cannot chain into an exit and skip the platform: with one
	 * node per end a train could enter from the south and leave southwards
	 * without touching the track, and the router took such U-turns.
	 */
	private record TrackEnds(String id, Group group, Track track, Map<Direction, Node> entrances, Map<Direction, Node> exits) {
	}

	private void buildStation(MicroNode node, Station station) {
		Node hub = network.getNodes().get(Id.createNodeId(station.id()));
		if (hub == null) {
			throw new IllegalArgumentException("Micro station " + station.id() + " is not a node of the meso network");
		}
		hub.getAttributes().putAttribute("microNode", node.id());
		Frame frame = frameOf(station, hub);

		for (Direction side : Direction.values()) {
			int index = 0;
			for (Connection connection : connectionsOf(station, side)) {
				double along = side == Direction.NORTH ? JUNCTION_OFFSET_M : -JUNCTION_OFFSET_M;
				double across = index++ * JUNCTION_SPACING_M;
				String base = MicroIds.junction(station, side, key(connection));
				Junction junction = new Junction(
					addNode(base + ".in", frame.at(along, across)),
					addNode(base + ".out", frame.at(along, across + JUNCTION_SPACING_M / 2)));
				junctions.put(base, junction);
			}
		}

		int trackIndex = 0;
		for (Group group : station.groups()) {
			for (TrackEnds ends : platformTracks(node, station, group, frame, trackIndex)) {
				trackIndex++;
				group.connections().forEach((side, connections) -> connections.forEach(connection ->
					addApproachLinks(node, station, group, ends, side, connection)));
			}
		}
		redirectMesoLinks(station);
	}

	/** Every distinct connection any group of the station declares on a side, in declaration order. */
	private static List<Connection> connectionsOf(Station station, Direction side) {
		Map<String, Connection> distinct = new LinkedHashMap<>();
		for (Group group : station.groups()) {
			for (Connection connection : group.connections().getOrDefault(side, List.of())) {
				distinct.putIfAbsent(key(connection), connection);
			}
		}
		return List.copyOf(distinct.values());
	}

	static String key(Connection connection) {
		return connection.kind() == ConnectionKind.MESO
			? connection.target()
			: connection.target() + "." + connection.bundle();
	}

	/**
	 * Local axes of the station: "north" points at the mean of the meso
	 * neighbours declared on the north side (or away from the southern ones),
	 * "left" is perpendicular, where tracks are spread.
	 */
	private Frame frameOf(Station station, Node hub) {
		double[] north = meanDirection(station, hub, Direction.NORTH);
		if (north == null) {
			double[] south = meanDirection(station, hub, Direction.SOUTH);
			north = south == null ? new double[] { 0, 1 } : new double[] { -south[0], -south[1] };
		}
		return new Frame(hub.getCoord(), north, new double[] { -north[1], north[0] });
	}

	private double[] meanDirection(Station station, Node hub, Direction side) {
		double dx = 0;
		double dy = 0;
		int count = 0;
		for (Connection connection : connectionsOf(station, side)) {
			if (connection.kind() != ConnectionKind.MESO) {
				continue;
			}
			Node neighbour = network.getNodes().get(Id.createNodeId(connection.target()));
			if (neighbour == null) {
				continue;
			}
			dx += neighbour.getCoord().getX() - hub.getCoord().getX();
			dy += neighbour.getCoord().getY() - hub.getCoord().getY();
			count++;
		}
		double length = Math.hypot(dx, dy);
		return count == 0 || length == 0 ? null : new double[] { dx / length, dy / length };
	}

	private List<TrackEnds> platformTracks(MicroNode node, Station station, Group group, Frame frame, int firstIndex) {
		double length = station.platformLengthM().orElse(PLATFORM_LENGTH_DEFAULT_M);
		boolean provisional = station.platformLengthM().isEmpty();
		boolean oneSided = group.connections().size() == 1;
		List<TrackEnds> result = new ArrayList<>();
		int index = firstIndex;
		for (Track track : group.effectiveTracks()) {
			String id = MicroIds.trackId(station, group, track);
			double across = index++ * TRACK_SPACING_M;
			Coord a = frame.at(-length / 2, across);
			Coord b = frame.at(length / 2, across);
			Map<Direction, Node> entrances = new EnumMap<>(Direction.class);
			Map<Direction, Node> exits = new EnumMap<>(Direction.class);
			if (track.direction() == Direction.NORTH) {
				entrances.put(Direction.SOUTH, addNode(id + ".a.in", a));
				exits.put(Direction.NORTH, addNode(id + ".b.out", b));
				addPlatformLink(id, id, entrances.get(Direction.SOUTH), exits.get(Direction.NORTH), length, provisional, node, station, group, track);
			} else if (track.direction() == Direction.SOUTH) {
				entrances.put(Direction.NORTH, addNode(id + ".b.in", b));
				exits.put(Direction.SOUTH, addNode(id + ".a.out", a));
				addPlatformLink(id, id, entrances.get(Direction.NORTH), exits.get(Direction.SOUTH), length, provisional, node, station, group, track);
			} else if (oneSided) {
				Direction side = group.connections().keySet().iterator().next();
				String entranceEnd = side == Direction.NORTH ? ".b" : ".a";
				Coord entranceCoord = side == Direction.NORTH ? b : a;
				Node buffer = addNode(id + (side == Direction.NORTH ? ".a" : ".b"), side == Direction.NORTH ? a : b);
				entrances.put(side, addNode(id + entranceEnd + ".in", entranceCoord));
				exits.put(side, addNode(id + entranceEnd + ".out", entranceCoord));
				addPlatformLink(id + ".in", id, entrances.get(side), buffer, length, provisional, node, station, group, track);
				addPlatformLink(id + ".out", id, buffer, exits.get(side), length, provisional, node, station, group, track);
			} else {
				entrances.put(Direction.SOUTH, addNode(id + ".a.in", a));
				entrances.put(Direction.NORTH, addNode(id + ".b.in", b));
				exits.put(Direction.NORTH, addNode(id + ".b.out", b));
				exits.put(Direction.SOUTH, addNode(id + ".a.out", a));
				addPlatformLink(id + ".north", id, entrances.get(Direction.SOUTH), exits.get(Direction.NORTH), length, provisional, node, station, group, track);
				addPlatformLink(id + ".south", id, entrances.get(Direction.NORTH), exits.get(Direction.SOUTH), length, provisional, node, station, group, track);
			}
			result.add(new TrackEnds(id, group, track, entrances, exits));
		}
		return result;
	}

	private void addPlatformLink(String linkId, String resource, Node from, Node to, double length, boolean provisional,
			MicroNode node, Station station, Group group, Track track) {
		Link link = addLink(linkId, from, to, length, PLATFORM_SPEED_MS);
		link.getAttributes().putAttribute("railsimTrainCapacity", 1);
		link.getAttributes().putAttribute("railsimResourceId", resource);
		link.getAttributes().putAttribute("stationLink", true);
		link.getAttributes().putAttribute("microNode", node.id());
		link.getAttributes().putAttribute("microStation", station.id());
		link.getAttributes().putAttribute("microGroup", group.id());
		link.getAttributes().putAttribute("microTrack", track.ref());
		if (!track.wayIds().isEmpty()) {
			link.getAttributes().putAttribute("osmWayIds", joinWays(track.wayIds()));
		}
		if (provisional) {
			link.getAttributes().putAttribute("dataStatus", PROVISIONAL);
		}
	}

	private void addApproachLinks(MicroNode node, Station station, Group group, TrackEnds ends, Direction side,
			Connection connection) {
		Optional<Throat> throat = station.throats().stream()
			.filter(candidate -> candidate.side() == side && candidate.groups().contains(group.id()))
			.findFirst();
		Junction junction = junctions.get(MicroIds.junction(station, side, key(connection)));
		String prefix = ends.id() + "." + MicroIds.name(side) + "." + key(connection);
		int capacity = throatCapacity(station, side);
		if (ends.entrances().containsKey(side)) {
			addThroatLink(prefix + ".in", junction.in(), ends.entrances().get(side), throat, capacity, node, station);
		}
		if (ends.exits().containsKey(side)) {
			addThroatLink(prefix + ".out", ends.exits().get(side), junction.out(), throat, capacity, node, station);
		}
	}

	/**
	 * Simultaneous movements a throat admits: one per connection, since routes
	 * to different neighbours run over parallel tracks, and never fewer than
	 * {@value #MIN_THROAT_CAPACITY}. A throat of capacity 1 would be a "conflict
	 * point" for railsim's deadlock avoidance, which reserves it for a train
	 * still approaching on the section; that train then waits for an occupied
	 * platform while the train on it cannot leave through the reserved throat.
	 * Above 1 the avoidance ignores the throat and the non-blocking area alone
	 * governs entry, which admits a train only once its platform is free.
	 */
	private static int throatCapacity(Station station, Direction side) {
		return Math.max(MIN_THROAT_CAPACITY, connectionsOf(station, side).size());
	}

	private Link addThroatLink(String id, Node from, Node to, Optional<Throat> throat, int capacity, MicroNode node,
			Station station) {
		double length = throat.flatMap(t -> t.lengthM().stream().boxed().findFirst()).orElse(APPROACH_LENGTH_DEFAULT_M);
		double speedKmh = throat.flatMap(t -> t.speedKmh().stream().boxed().findFirst()).orElse(APPROACH_SPEED_DEFAULT_KMH);
		Link link = addLink(id, from, to, length, speedKmh / 3.6);
		link.getAttributes().putAttribute("railsimTrainCapacity", capacity);
		link.getAttributes().putAttribute("railsimNonBlockingArea", true);
		link.getAttributes().putAttribute("microNode", node.id());
		link.getAttributes().putAttribute("microStation", station.id());
		throat.ifPresent(t -> {
			link.getAttributes().putAttribute("railsimResourceId", t.resource());
			link.getAttributes().putAttribute("microThroat", t.id());
		});
		if (throat.isEmpty() || throat.get().lengthM().isEmpty() || throat.get().speedKmh().isEmpty()) {
			link.getAttributes().putAttribute("dataStatus", PROVISIONAL);
		}
		return link;
	}

	private void redirectMesoLinks(Station station) {
		Map<String, Direction> sideOfNeighbour = new LinkedHashMap<>();
		for (Group group : station.groups()) {
			group.connections().forEach((side, connections) -> connections.stream()
				.filter(connection -> connection.kind() == ConnectionKind.MESO)
				.forEach(connection -> {
					Direction previous = sideOfNeighbour.put(connection.target(), side);
					if (previous != null && previous != side) {
						throw new IllegalArgumentException("Neighbour " + connection.target() + " of " + station.id()
							+ " is declared on both sides");
					}
				}));
		}
		sideOfNeighbour.forEach((neighbour, side) -> {
			Junction junction = junctions.get(MicroIds.junction(station, side, neighbour));
			Link incoming = network.getLinks().get(Id.createLinkId(neighbour + "_" + station.id()));
			if (incoming != null) {
				Link redirected = replaceEnd(incoming, station.id(), junction.in());
				redirected.getAttributes().putAttribute("railsimEntry", true);
			}
			Link outgoing = network.getLinks().get(Id.createLinkId(station.id() + "_" + neighbour));
			if (outgoing != null) {
				Link redirected = replaceEnd(outgoing, station.id(), junction.out());
				redirected.getAttributes().putAttribute("railsimExit", true);
			}
			if (incoming == null && outgoing == null) {
				LOG.warn("Station {} declares neighbour {} but the meso network has no link between them", station.id(), neighbour);
			}
		});
	}

	/** Recreates a meso link with the end at {@code stationId} moved to the junction; attributes are kept. */
	private Link replaceEnd(Link link, String stationId, Node junction) {
		boolean atFrom = link.getFromNode().getId().toString().equals(stationId);
		Node from = atFrom ? junction : link.getFromNode();
		Node to = atFrom ? link.getToNode() : junction;
		network.removeLink(link.getId());
		Link replacement = network.getFactory().createLink(link.getId(), from, to);
		replacement.setLength(link.getLength());
		replacement.setFreespeed(link.getFreespeed());
		replacement.setCapacity(link.getCapacity());
		replacement.setNumberOfLanes(link.getNumberOfLanes());
		replacement.setAllowedModes(link.getAllowedModes());
		link.getAttributes().getAsMap().forEach((key, value) -> replacement.getAttributes().putAttribute(key, value));
		network.addLink(replacement);
		return replacement;
	}

	private void buildSegment(MicroNode node, Segment segment) {
		Station from = node.station(segment.from());
		Station to = node.station(segment.to());
		removeMesoLink(from.id() + "_" + to.id());
		removeMesoLink(to.id() + "_" + from.id());
		int bundleIndex = 0;
		for (Map.Entry<String, Bundle> entry : segment.bundles().entrySet()) {
			String bundleId = entry.getKey();
			Bundle bundle = entry.getValue();
			String connection = segment.id() + "." + bundleId;
			Junction atFrom = junctions.get(MicroIds.junction(from, Direction.NORTH, connection));
			Junction atTo = junctions.get(MicroIds.junction(to, Direction.SOUTH, connection));
			if (atFrom == null || atTo == null) {
				throw new IllegalArgumentException("Bundle " + bundleId + " of segment " + segment.id()
					+ " must be a north connection at " + from.id() + " and a south connection at " + to.id());
			}
			double approaches = approachLength(from, Direction.NORTH) + approachLength(to, Direction.SOUTH);
			double speed = equivalentSpeed(bundle, from);
			double offset = bundleIndex++ * TRACK_SPACING_M;
			addSectionTrack(node, segment, bundleId, Direction.NORTH, bundle.north(), atFrom.out(), atTo.in(),
				approaches, speed, offset);
			addSectionTrack(node, segment, bundleId, Direction.SOUTH, bundle.south(), atTo.out(), atFrom.in(),
				approaches, speed, offset);
		}
	}

	private void addSectionTrack(MicroNode node, Segment segment, String bundleId, Direction travel, TrackWays track,
			Node start, Node end, double approaches, double speed, double offset) {
		String id = segment.id() + "." + bundleId + "." + MicroIds.name(travel);
		double length = Math.max(MIN_SECTION_LENGTH_M, track.lengthM() - approaches - 2 * STUB_LENGTH_M);
		Node a = addNode(id + ".a", between(start, end, 0.1, offset));
		Node b = addNode(id + ".b", between(start, end, 0.9, offset));
		Link exit = addLink(id + ".exit", start, a, STUB_LENGTH_M, speed);
		exit.getAttributes().putAttribute("railsimExit", true);
		Link main = addLink(id, a, b, length, speed);
		main.getAttributes().putAttribute("railsimResourceId", id);
		main.getAttributes().putAttribute("microSegment", segment.id());
		main.getAttributes().putAttribute("microBundle", bundleId);
		if (!track.wayIds().isEmpty()) {
			main.getAttributes().putAttribute("osmWayIds", joinWays(track.wayIds()));
		}
		Link entry = addLink(id + ".entry", b, end, STUB_LENGTH_M, speed);
		entry.getAttributes().putAttribute("railsimEntry", true);
		for (Link link : List.of(exit, main, entry)) {
			link.getAttributes().putAttribute("railsimTrainCapacity", 1);
			link.getAttributes().putAttribute("microNode", node.id());
		}
	}

	/** The mesoscopic link a per-track section replaces; absent when the GTFS served only one direction. */
	private void removeMesoLink(String linkId) {
		Id<Link> id = Id.createLinkId(linkId);
		if (network.getLinks().containsKey(id)) {
			network.removeLink(id);
		} else {
			LOG.info("No meso link {} to replace", linkId);
		}
	}

	private static double approachLength(Station station, Direction side) {
		return station.throats().stream()
			.filter(throat -> throat.side() == side)
			.flatMap(throat -> throat.lengthM().stream().boxed())
			.findFirst()
			.orElse(APPROACH_LENGTH_DEFAULT_M);
	}

	/**
	 * Speed of the section beyond the departure throat: the profile's running
	 * time minus the time spent in the declared throat, over the remaining length.
	 */
	private static double equivalentSpeed(Bundle bundle, Station from) {
		if (bundle.speedProfile().isEmpty()) {
			return SECTION_SPEED_DEFAULT_MS;
		}
		double time = 0;
		double length = 0;
		for (SpeedStep step : bundle.speedProfile()) {
			time += step.lengthM() / (step.kmh() / 3.6);
			length += step.lengthM();
		}
		Optional<Throat> throat = from.throats().stream().filter(candidate -> candidate.side() == Direction.NORTH).findFirst();
		if (throat.isPresent() && throat.get().lengthM().isPresent() && throat.get().speedKmh().isPresent()) {
			double throatLength = throat.get().lengthM().getAsDouble();
			time -= throatLength / (throat.get().speedKmh().getAsDouble() / 3.6);
			length -= throatLength;
		}
		return time > 0 && length > 0 ? length / time : SECTION_SPEED_DEFAULT_MS;
	}

	private static String joinWays(List<Long> wayIds) {
		return wayIds.stream().map(String::valueOf).collect(Collectors.joining(" "));
	}

	private Node addNode(String id, Coord coord) {
		Node node = network.getFactory().createNode(Id.createNodeId(id), coord);
		network.addNode(node);
		return node;
	}

	private Link addLink(String id, Node from, Node to, double length, double freespeed) {
		Link link = network.getFactory().createLink(Id.createLinkId(id), from, to);
		link.setLength(length);
		link.setFreespeed(freespeed);
		link.setCapacity(3600.0);
		link.setNumberOfLanes(1.0);
		link.setAllowedModes(Set.of(MODE));
		network.addLink(link);
		return link;
	}

	/** A point along the line between two nodes, shifted sideways by {@code offset}. */
	private static Coord between(Node start, Node end, double fraction, double offset) {
		double dx = end.getCoord().getX() - start.getCoord().getX();
		double dy = end.getCoord().getY() - start.getCoord().getY();
		double length = Math.hypot(dx, dy);
		double leftX = length == 0 ? 0 : -dy / length;
		double leftY = length == 0 ? 0 : dx / length;
		return new Coord(start.getCoord().getX() + dx * fraction + leftX * offset,
			start.getCoord().getY() + dy * fraction + leftY * offset);
	}
}
