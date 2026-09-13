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
import org.matsim.core.network.NetworkUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * redirected to the station's side junction and flagged as rerouting entry or
 * exit, so railsim can divert a train to a free platform of its stop area.
 * Turn restrictions keep every train on the groups its side is connected to
 * and force it through a platform track.
 * <p>
 * Ids: junctions {@code <station>.<side>}, platform tracks {@code <station>.p<ref>}
 * (or {@code <station>.<group>.<n>} for anonymous capacity), platform link
 * ends {@code .a} (south end) and {@code .b} (north end), approach links
 * {@code <track>.<side>.in|out}, sections {@code <from>_<to>.<bundle>.<north|south>}
 * with {@code .exit} and {@code .entry} stubs.
 */
public final class MicroNodeBuilder {

	private static final Logger LOG = LogManager.getLogger(MicroNodeBuilder.class);

	static final double PLATFORM_LENGTH_DEFAULT_M = 200.0;
	static final double APPROACH_LENGTH_DEFAULT_M = 100.0;
	static final double APPROACH_SPEED_DEFAULT_KMH = 30.0;
	static final double STUB_LENGTH_M = 100.0;
	static final double MIN_SECTION_LENGTH_M = 100.0;
	private static final double PLATFORM_SPEED_MS = 13.9;
	private static final double SECTION_SPEED_DEFAULT_MS = 25.0;
	private static final double TRACK_SPACING_M = 5.0;
	private static final double JUNCTION_OFFSET_M = 300.0;
	private static final String MODE = "rail";
	private static final String PROVISIONAL = "provisional";

	private final Network network;

	private record Approach(Station station, Group group, Direction side) {
	}

	/** Approach links from a junction into a platform, by link id. */
	private final Map<Id<Link>, Approach> approachesIn = new HashMap<>();
	/** Links a train may take after leaving a platform towards a junction, by approach-out link id. */
	private final Map<Id<Link>, Set<Id<Link>>> exitsAfter = new HashMap<>();
	private final Set<Id<Node>> junctions = new java.util.LinkedHashSet<>();

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
		applyTurnRestrictions();
	}

	private record TrackEnds(String id, Group group, Track track, Node a, Node b) {
	}

	private void buildStation(MicroNode node, Station station) {
		Node hub = network.getNodes().get(Id.createNodeId(station.id()));
		if (hub == null) {
			throw new IllegalArgumentException("Micro station " + station.id() + " is not a node of the meso network");
		}
		hub.getAttributes().putAttribute("microNode", node.id());

		Map<Direction, Node> sides = new EnumMap<>(Direction.class);
		for (Direction side : Direction.values()) {
			if (station.groups().stream().anyMatch(group -> group.connections().containsKey(side))) {
				double dy = side == Direction.NORTH ? JUNCTION_OFFSET_M : -JUNCTION_OFFSET_M;
				Node junction = addNode(station.id() + "." + name(side), hub.getCoord().getX(), hub.getCoord().getY() + dy);
				sides.put(side, junction);
				junctions.add(junction.getId());
			}
		}

		int trackIndex = 0;
		for (Group group : station.groups()) {
			for (TrackEnds ends : platformTracks(node, station, group, hub, trackIndex)) {
				trackIndex++;
				for (Direction side : group.connections().keySet()) {
					addApproachLinks(node, station, group, ends, side, sides.get(side));
				}
			}
		}
		redirectMesoLinks(station, sides);
	}

	private List<TrackEnds> platformTracks(MicroNode node, Station station, Group group, Node hub, int firstIndex) {
		double length = station.platformLengthM().orElse(PLATFORM_LENGTH_DEFAULT_M);
		boolean provisional = station.platformLengthM().isEmpty();
		boolean oneSided = group.connections().size() == 1;
		List<TrackEnds> result = new ArrayList<>();
		List<Track> tracks = group.hasNamedTracks() ? group.tracks() : anonymousTracks(group);
		int index = firstIndex;
		for (Track track : tracks) {
			String id = group.hasNamedTracks() ? station.id() + ".p" + track.ref() : station.id() + "." + group.id() + "." + track.ref();
			double x = hub.getCoord().getX() + index++ * TRACK_SPACING_M;
			Node a = addNode(id + ".a", x, hub.getCoord().getY() - length / 2);
			Node b = addNode(id + ".b", x, hub.getCoord().getY() + length / 2);
			if (track.direction() == Direction.NORTH) {
				addPlatformLink(id, id, a, b, length, provisional, node, station, group, track);
			} else if (track.direction() == Direction.SOUTH) {
				addPlatformLink(id, id, b, a, length, provisional, node, station, group, track);
			} else if (oneSided) {
				Direction side = group.connections().keySet().iterator().next();
				Node entrance = side == Direction.NORTH ? b : a;
				Node buffer = side == Direction.NORTH ? a : b;
				addPlatformLink(id + ".in", id, entrance, buffer, length, provisional, node, station, group, track);
				addPlatformLink(id + ".out", id, buffer, entrance, length, provisional, node, station, group, track);
			} else {
				addPlatformLink(id + ".north", id, a, b, length, provisional, node, station, group, track);
				addPlatformLink(id + ".south", id, b, a, length, provisional, node, station, group, track);
			}
			result.add(new TrackEnds(id, group, track, a, b));
		}
		return result;
	}

	private static List<Track> anonymousTracks(Group group) {
		List<Track> tracks = new ArrayList<>();
		for (int i = 1; i <= group.trackCount(); i++) {
			tracks.add(new Track(Integer.toString(i), null));
		}
		return tracks;
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
		if (provisional) {
			link.getAttributes().putAttribute("dataStatus", PROVISIONAL);
		}
	}

	private void addApproachLinks(MicroNode node, Station station, Group group, TrackEnds ends, Direction side, Node junction) {
		Node end = side == Direction.NORTH ? ends.b() : ends.a();
		Direction trackDirection = ends.track().direction();
		boolean inbound = trackDirection == null || (trackDirection == Direction.NORTH) == (side == Direction.SOUTH);
		boolean outbound = trackDirection == null || (trackDirection == Direction.NORTH) == (side == Direction.NORTH);
		Optional<Throat> throat = station.throats().stream()
			.filter(candidate -> candidate.side() == side && candidate.groups().contains(group.id()))
			.findFirst();
		String prefix = ends.id() + "." + name(side);
		if (inbound) {
			Link in = addThroatLink(prefix + ".in", junction, end, throat, node, station);
			approachesIn.put(in.getId(), new Approach(station, group, side));
		}
		if (outbound) {
			Link out = addThroatLink(prefix + ".out", end, junction, throat, node, station);
			exitsAfter.put(out.getId(), exitsOf(station, group, side));
		}
	}

	private Link addThroatLink(String id, Node from, Node to, Optional<Throat> throat, MicroNode node, Station station) {
		double length = throat.flatMap(t -> t.lengthM().stream().boxed().findFirst()).orElse(APPROACH_LENGTH_DEFAULT_M);
		double speedKmh = throat.flatMap(t -> t.speedKmh().stream().boxed().findFirst()).orElse(APPROACH_SPEED_DEFAULT_KMH);
		Link link = addLink(id, from, to, length, speedKmh / 3.6);
		link.getAttributes().putAttribute("railsimTrainCapacity", 1);
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

	/** The links a train may take when it leaves a group's tracks towards a side: its declared connections only. */
	private static Set<Id<Link>> exitsOf(Station station, Group group, Direction side) {
		return group.connections().get(side).stream()
			.map(connection -> exitLinkId(station, connection, side))
			.collect(Collectors.toSet());
	}

	private static Id<Link> exitLinkId(Station station, Connection connection, Direction side) {
		if (connection.kind() == ConnectionKind.MESO) {
			return Id.createLinkId(station.id() + "_" + connection.target());
		}
		Direction travel = side == Direction.NORTH ? Direction.NORTH : Direction.SOUTH;
		return Id.createLinkId(connection.target() + "." + connection.bundle() + "." + name(travel) + ".exit");
	}

	private static Id<Link> entryLinkId(Station station, Connection connection, Direction side) {
		if (connection.kind() == ConnectionKind.MESO) {
			return Id.createLinkId(connection.target() + "_" + station.id());
		}
		Direction travel = side == Direction.NORTH ? Direction.SOUTH : Direction.NORTH;
		return Id.createLinkId(connection.target() + "." + connection.bundle() + "." + name(travel) + ".entry");
	}

	private void redirectMesoLinks(Station station, Map<Direction, Node> junctions) {
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
			Node junction = junctions.get(side);
			Link incoming = network.getLinks().get(Id.createLinkId(neighbour + "_" + station.id()));
			if (incoming != null) {
				Link redirected = replaceEnd(incoming, station.id(), junction);
				redirected.getAttributes().putAttribute("railsimEntry", true);
			}
			Link outgoing = network.getLinks().get(Id.createLinkId(station.id() + "_" + neighbour));
			if (outgoing != null) {
				Link redirected = replaceEnd(outgoing, station.id(), junction);
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
		Node fromJunction = network.getNodes().get(Id.createNodeId(from.id() + ".north"));
		Node toJunction = network.getNodes().get(Id.createNodeId(to.id() + ".south"));
		if (fromJunction == null || toJunction == null) {
			throw new IllegalArgumentException("Segment " + segment.id() + " needs a north side at " + from.id()
				+ " and a south side at " + to.id());
		}
		removeMesoLink(from.id() + "_" + to.id());
		removeMesoLink(to.id() + "_" + from.id());
		int bundleIndex = 0;
		for (Map.Entry<String, Bundle> entry : segment.bundles().entrySet()) {
			String bundleId = entry.getKey();
			Bundle bundle = entry.getValue();
			double fromApproach = approachLength(from, Direction.NORTH);
			double toApproach = approachLength(to, Direction.SOUTH);
			double speed = equivalentSpeed(bundle, from);
			double offset = bundleIndex++ * TRACK_SPACING_M;
			addSectionTrack(node, segment, bundleId, Direction.NORTH, bundle.north(), fromJunction, toJunction,
				fromApproach + toApproach, speed, offset);
			addSectionTrack(node, segment, bundleId, Direction.SOUTH, bundle.south(), toJunction, fromJunction,
				fromApproach + toApproach, speed, offset);
		}
	}

	private void addSectionTrack(MicroNode node, Segment segment, String bundleId, Direction travel, TrackWays track,
			Node start, Node end, double approaches, double speed, double offset) {
		String id = segment.id() + "." + bundleId + "." + name(travel);
		double length = Math.max(MIN_SECTION_LENGTH_M, track.lengthM() - approaches - 2 * STUB_LENGTH_M);
		Node a = addNode(id + ".a", lerp(start, end, 0.1) + offset, lerpY(start, end, 0.1));
		Node b = addNode(id + ".b", lerp(start, end, 0.9) + offset, lerpY(start, end, 0.9));
		Link exit = addLink(id + ".exit", start, a, STUB_LENGTH_M, speed);
		exit.getAttributes().putAttribute("railsimExit", true);
		Link main = addLink(id, a, b, length, speed);
		main.getAttributes().putAttribute("railsimResourceId", id);
		main.getAttributes().putAttribute("microSegment", segment.id());
		main.getAttributes().putAttribute("microBundle", bundleId);
		if (!track.wayIds().isEmpty()) {
			main.getAttributes().putAttribute("osmWayIds",
				track.wayIds().stream().map(String::valueOf).collect(Collectors.joining(" ")));
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

	/**
	 * At each junction an incoming link may continue only onto the approach
	 * links of the groups connected to it, and a train leaving a platform only
	 * onto that group's declared exits: no bypass of the station, no hopping
	 * between platforms.
	 */
	private void applyTurnRestrictions() {
		for (Id<Node> junctionId : junctions) {
			Node junction = network.getNodes().get(junctionId);
			for (Link incoming : junction.getInLinks().values()) {
				Set<Id<Link>> allowed = exitsAfter.containsKey(incoming.getId())
					? exitsAfter.get(incoming.getId())
					: approachesReachableFrom(junction, incoming);
				List<Id<Link>> disallowed = junction.getOutLinks().keySet().stream()
					.filter(next -> !allowed.contains(next))
					.toList();
				for (Id<Link> next : disallowed) {
					NetworkUtils.addDisallowedNextLinks(incoming, MODE, List.of(next));
				}
			}
		}
	}

	/** Approach links of the groups that declare the incoming link's origin as a connection on this side. */
	private Set<Id<Link>> approachesReachableFrom(Node junction, Link incoming) {
		return junction.getOutLinks().keySet().stream()
			.filter(approachesIn::containsKey)
			.filter(candidate -> {
				Approach approach = approachesIn.get(candidate);
				return approach.group().connections().getOrDefault(approach.side(), List.of()).stream()
					.anyMatch(connection -> entryLinkId(approach.station(), connection, approach.side()).equals(incoming.getId()));
			})
			.collect(Collectors.toSet());
	}

	private Node addNode(String id, double x, double y) {
		Node node = network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y));
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

	private static String name(Direction direction) {
		return direction.name().toLowerCase(Locale.ROOT);
	}

	private static double lerp(Node start, Node end, double fraction) {
		return start.getCoord().getX() + (end.getCoord().getX() - start.getCoord().getX()) * fraction;
	}

	private static double lerpY(Node start, Node end, double fraction) {
		return start.getCoord().getY() + (end.getCoord().getY() - start.getCoord().getY()) * fraction;
	}
}
