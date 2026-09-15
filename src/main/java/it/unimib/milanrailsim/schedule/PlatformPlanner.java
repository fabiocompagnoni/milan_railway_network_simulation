package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.micro.MicroIds;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.MicroNode.Direction;
import it.unimib.milanrailsim.network.micro.MicroNode.Group;
import it.unimib.milanrailsim.network.micro.MicroNode.Station;
import it.unimib.milanrailsim.network.micro.MicroNode.Track;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans the platform track every trip uses at the micro stations: the first
 * free track of the line's preferred groups, rotating within a group so the
 * load spreads, with the same track kept from a trip's arrival to the next
 * departure of its circulation (railsim requires the next leg to start on the
 * link where the previous one ended). Tracks whose direction contradicts the
 * train's travel are skipped. When no preferred track is free the first
 * group's least-used track is taken and the conflict counted: railsim may
 * still divert the train to a free platform of its stop area at run time.
 */
public final class PlatformPlanner {

	private static final Logger LOG = LogManager.getLogger(PlatformPlanner.class);

	/** Clearance between two trains on the same platform track (domain estimate). */
	static final int BUFFER_SECONDS = 60;
	/** How long before departure a train coming from the sidings reaches its platform (domain estimate). */
	public static final int POSITIONING_SECONDS = 4 * 60;

	public record Call(String stopId, int arrivalSeconds, int departureSeconds) {
	}

	/**
	 * The calls of one trip; {@code fromSidings} and {@code toSidings} say whether
	 * the train comes onto its first platform from the sidings and leaves its
	 * last one for them, instead of standing there between trips.
	 */
	public record TripCalls(String tripId, String line, List<Call> calls, boolean fromSidings, boolean toSidings) {

		public TripCalls(String tripId, String line, List<Call> calls) {
			this(tripId, line, calls, false, false);
		}

		public TripCalls via(boolean fromSidings, boolean toSidings) {
			return new TripCalls(tripId, line, calls, fromSidings, toSidings);
		}
	}

	/** The planned platform of every call at a micro station, by trip and call index. */
	public record Plan(Map<String, Map<Integer, Id<Link>>> platforms, int conflicts) {

		public Optional<Id<Link>> platform(String tripId, int callIndex) {
			return Optional.ofNullable(platforms.getOrDefault(tripId, Map.of()).get(callIndex));
		}
	}

	private record Candidate(Group group, Track track, String trackId) {
	}

	/**
	 * Names the declared neighbour of a station that trains run through on
	 * their way from or to a stop the node does not declare itself.
	 */
	public interface NeighbourResolver {
		Optional<String> neighbourTowards(String stationId, String otherStopId);
	}

	/** How a trip reaches or leaves a station: through which declared neighbour, on which side. */
	private record Approach(String neighbour, Direction side) {
	}

	private final Map<String, MicroNode> nodeByStation = new HashMap<>();
	private final int turnaroundSeconds;
	private final NeighbourResolver transit;
	private final java.util.Set<String> warnedFallbacks = new java.util.HashSet<>();

	public PlatformPlanner(List<MicroNode> nodes, int turnaroundSeconds) {
		this(nodes, turnaroundSeconds, (station, other) -> Optional.empty());
	}

	/** @param transit consulted when a node does not declare the stop a trip comes from or goes to */
	public PlatformPlanner(List<MicroNode> nodes, int turnaroundSeconds, NeighbourResolver transit) {
		this.turnaroundSeconds = turnaroundSeconds;
		this.transit = transit;
		for (MicroNode node : nodes) {
			for (Station station : node.stations()) {
				nodeByStation.put(station.id(), node);
			}
		}
	}

	/**
	 * The neighbour and side through which a trip reaches a station from
	 * another stop: the stop itself when the node declares it, otherwise the
	 * declared neighbour the train runs through on the way.
	 */
	private Optional<Approach> approach(MicroNode node, String stationId, String otherStopId) {
		return node.sideOf(stationId, otherStopId)
			.map(side -> new Approach(otherStopId, side))
			.or(() -> transit.neighbourTowards(stationId, otherStopId)
				.flatMap(neighbour -> node.sideOf(stationId, neighbour).map(side -> new Approach(neighbour, side))));
	}

	public boolean isMicro(String stopId) {
		return nodeByStation.containsKey(stopId);
	}

	public Optional<MicroNode> nodeOf(String stopId) {
		return Optional.ofNullable(nodeByStation.get(stopId));
	}

	/** Travel direction of a trip through a station, from where it comes or, at a first call, where it goes. */
	public Optional<Direction> travelAt(TripCalls trip, int callIndex) {
		String stopId = trip.calls().get(callIndex).stopId();
		MicroNode node = nodeByStation.get(stopId);
		if (node == null) {
			return Optional.empty();
		}
		if (callIndex > 0) {
			return approach(node, stopId, trip.calls().get(callIndex - 1).stopId()).map(Approach::side)
				.map(PlatformPlanner::opposite);
		}
		if (callIndex + 1 < trip.calls().size()) {
			return approach(node, stopId, trip.calls().get(callIndex + 1).stopId()).map(Approach::side);
		}
		return Optional.empty();
	}

	public Plan plan(List<List<TripCalls>> chains) {
		Map<String, List<double[]>> windowsByTrack = new HashMap<>();
		Map<String, Integer> rotation = new HashMap<>();
		Map<String, Map<Integer, Id<Link>>> platforms = new HashMap<>();
		Map<String, Integer> conflictsByStation = new java.util.TreeMap<>();
		int conflicts = 0;
		for (List<TripCalls> chain : chains) {
			Id<Link> inherited = null;
			for (int t = 0; t < chain.size(); t++) {
				TripCalls trip = chain.get(t);
				TripCalls next = t + 1 < chain.size() ? chain.get(t + 1) : null;
				Map<Integer, Id<Link>> planned = new HashMap<>();
				for (int i = 0; i < trip.calls().size(); i++) {
					Call call = trip.calls().get(i);
					if (!nodeByStation.containsKey(call.stopId())) {
						continue;
					}
					if (i == 0 && inherited != null) {
						planned.put(i, inherited);
						continue;
					}
					double[] window = window(trip, i, next);
					Optional<Candidate> chosen = choose(trip, i, window, windowsByTrack, rotation);
					if (chosen.isEmpty()) {
						continue;
					}
					Candidate candidate = chosen.get();
					if (!isFree(windowsByTrack, candidate.trackId(), window)) {
						conflicts++;
						conflictsByStation.merge(call.stopId(), 1, Integer::sum);
					}
					windowsByTrack.computeIfAbsent(candidate.trackId(), key -> new ArrayList<>()).add(window);
					Id<Link> link = MicroIds.platformLink(candidate.trackId(), candidate.group(), candidate.track(),
						travelAt(trip, i).orElse(null));
					planned.put(i, link);
				}
				platforms.put(trip.tripId(), planned);
				int last = trip.calls().size() - 1;
				inherited = next != null && !trip.toSidings()
					&& next.calls().getFirst().stopId().equals(trip.calls().get(last).stopId())
					? planned.get(last) : null;
			}
		}
		if (conflicts > 0) {
			LOG.warn("{} platform calls could not be placed on a free track of their preferred groups: {}", conflicts,
				conflictsByStation);
		}
		return new Plan(platforms, conflicts);
	}

	/**
	 * Occupation of the platform: from arrival (or the positioning before a first
	 * departure) to departure, or to the next trip's departure at a terminus
	 * where the train waits on the platform.
	 */
	private double[] window(TripCalls trip, int callIndex, TripCalls next) {
		Call call = trip.calls().get(callIndex);
		boolean last = callIndex == trip.calls().size() - 1;
		double start = callIndex == 0
			? call.departureSeconds() - BUFFER_SECONDS - (trip.fromSidings() ? POSITIONING_SECONDS : 0)
			: call.arrivalSeconds();
		double end;
		if (last && trip.toSidings()) {
			end = call.departureSeconds();
		} else if (last && next != null && next.calls().getFirst().stopId().equals(call.stopId())) {
			end = next.calls().getFirst().departureSeconds();
		} else if (last) {
			end = call.arrivalSeconds() + turnaroundSeconds;
		} else {
			end = call.departureSeconds();
		}
		return new double[] { start - BUFFER_SECONDS, end + BUFFER_SECONDS };
	}

	private Optional<Candidate> choose(TripCalls trip, int callIndex, double[] window,
			Map<String, List<double[]>> windowsByTrack, Map<String, Integer> rotation) {
		Call call = trip.calls().get(callIndex);
		MicroNode node = nodeByStation.get(call.stopId());
		Station station = node.station(call.stopId());
		boolean terminating = callIndex == 0 || callIndex == trip.calls().size() - 1;
		List<String> preferred = node.preferredGroups(trip.line(), call.stopId(), terminating);
		if (preferred.isEmpty()) {
			preferred = unassignedFallback(node, station, trip.line());
		}
		Direction travel = travelAt(trip, callIndex).orElse(null);
		List<String> reachable = preferred.stream()
			.filter(groupId -> reaches(node, station.group(groupId), trip, callIndex))
			.toList();
		if (reachable.isEmpty()) {
			reachable = station.groups().stream().map(Group::id)
				.filter(groupId -> reaches(node, station.group(groupId), trip, callIndex))
				.toList();
			if (warnedFallbacks.add(trip.line() + "@" + station.id() + "@reach")) {
				LOG.warn("No preferred group of line {} at {} connects to the trip's neighbours: using {}", trip.line(),
					station.id(), reachable.isEmpty() ? preferred : reachable);
			}
			if (reachable.isEmpty()) {
				reachable = preferred;
			}
		}
		Candidate fallback = null;
		for (String groupId : reachable) {
			Group group = station.group(groupId);
			List<Candidate> candidates = new ArrayList<>();
			for (Track track : group.effectiveTracks()) {
				if (track.direction() == null || travel == null || track.direction() == travel) {
					candidates.add(new Candidate(group, track, MicroIds.trackId(station, group, track)));
				}
			}
			if (candidates.isEmpty()) {
				continue;
			}
			String rotationKey = station.id() + "|" + group.id();
			int start = rotation.getOrDefault(rotationKey, 0);
			for (int k = 0; k < candidates.size(); k++) {
				Candidate candidate = candidates.get((start + k) % candidates.size());
				if (isFree(windowsByTrack, candidate.trackId(), window)) {
					rotation.put(rotationKey, (start + k + 1) % candidates.size());
					return Optional.of(candidate);
				}
			}
			if (fallback == null) {
				fallback = candidates.stream()
					.min((a, b) -> Integer.compare(windowsByTrack.getOrDefault(a.trackId(), List.of()).size(),
						windowsByTrack.getOrDefault(b.trackId(), List.of()).size()))
					.orElseThrow();
			}
		}
		return Optional.ofNullable(fallback);
	}

	/**
	 * Whether the group's connections lead to the neighbour the trip comes in
	 * through and to the one it leaves through, on the sides they lie. A group
	 * that fails this would force the route to leave the station and bounce
	 * back. A stop no resolver can place leaves the side undecided and is not
	 * held against the group.
	 */
	private boolean reaches(MicroNode node, Group group, TripCalls trip, int callIndex) {
		String stopId = trip.calls().get(callIndex).stopId();
		if (callIndex > 0) {
			Optional<Approach> from = approach(node, stopId, trip.calls().get(callIndex - 1).stopId());
			if (from.isPresent() && !MicroNode.connects(group, from.get().side(), stopId, from.get().neighbour())) {
				return false;
			}
		}
		if (callIndex + 1 < trip.calls().size()) {
			Optional<Approach> to = approach(node, stopId, trip.calls().get(callIndex + 1).stopId());
			if (to.isPresent() && !MicroNode.connects(group, to.get().side(), stopId, to.get().neighbour())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A line without a group at this station takes the groups connected to its
	 * declared bundle, so the route stays on the right tracks, or any group when
	 * no bundle is declared either. Warned once per line and station.
	 */
	private List<String> unassignedFallback(MicroNode node, Station station, String line) {
		MicroNode.Line declared = node.lines().get(line);
		List<String> groups = station.groups().stream()
			.filter(group -> declared == null || declared.bundle().isEmpty() || group.connections().values().stream()
				.flatMap(List::stream)
				.anyMatch(connection -> declared.bundle().get().equals(connection.bundle())))
			.map(Group::id)
			.toList();
		if (groups.isEmpty()) {
			groups = station.groups().stream().map(Group::id).toList();
		}
		if (warnedFallbacks.add(line + "@" + station.id())) {
			LOG.warn("Line {} is not assigned to any platform group at {}: using {}", line, station.id(), groups);
		}
		return groups;
	}

	private static boolean isFree(Map<String, List<double[]>> windowsByTrack, String trackId, double[] window) {
		return windowsByTrack.getOrDefault(trackId, List.of()).stream()
			.noneMatch(other -> other[0] < window[1] && window[0] < other[1]);
	}

	private static Direction opposite(Direction side) {
		return side == Direction.NORTH ? Direction.SOUTH : Direction.NORTH;
	}
}
