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

	public record Call(String stopId, int arrivalSeconds, int departureSeconds) {
	}

	public record TripCalls(String tripId, String line, List<Call> calls) {
	}

	/** The planned platform of every call at a micro station, by trip and call index. */
	public record Plan(Map<String, Map<Integer, Id<Link>>> platforms, int conflicts) {

		public Optional<Id<Link>> platform(String tripId, int callIndex) {
			return Optional.ofNullable(platforms.getOrDefault(tripId, Map.of()).get(callIndex));
		}
	}

	private record Candidate(Group group, Track track, String trackId) {
	}

	private final Map<String, MicroNode> nodeByStation = new HashMap<>();
	private final int turnaroundSeconds;
	private final java.util.Set<String> warnedFallbacks = new java.util.HashSet<>();

	public PlatformPlanner(List<MicroNode> nodes, int turnaroundSeconds) {
		this.turnaroundSeconds = turnaroundSeconds;
		for (MicroNode node : nodes) {
			for (Station station : node.stations()) {
				nodeByStation.put(station.id(), node);
			}
		}
	}

	public boolean isMicro(String stopId) {
		return nodeByStation.containsKey(stopId);
	}

	public Optional<MicroNode> nodeOf(String stopId) {
		return Optional.ofNullable(nodeByStation.get(stopId));
	}

	/** Travel direction of a trip through a station, from where it comes or, at a first call, where it goes. */
	public Optional<Direction> travelAt(TripCalls trip, int callIndex) {
		return travelAt(trip.calls().get(callIndex).stopId(),
			callIndex > 0 ? trip.calls().get(callIndex - 1).stopId() : null,
			callIndex + 1 < trip.calls().size() ? trip.calls().get(callIndex + 1).stopId() : null);
	}

	/**
	 * Travel direction through {@code stopId} of a train coming from
	 * {@code previousStop} or, when it starts there, going to {@code nextStop};
	 * either may be null. Empty when the station is not a micro node.
	 */
	public Optional<Direction> travelAt(String stopId, String previousStop, String nextStop) {
		MicroNode node = nodeByStation.get(stopId);
		if (node == null) {
			return Optional.empty();
		}
		if (previousStop != null) {
			return node.sideOf(stopId, previousStop).map(PlatformPlanner::opposite);
		}
		if (nextStop != null) {
			return node.sideOf(stopId, nextStop);
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
				inherited = next != null && next.calls().getFirst().stopId().equals(trip.calls().get(last).stopId())
					? planned.get(last) : null;
			}
		}
		if (conflicts > 0) {
			LOG.warn("{} platform calls could not be placed on a free track of their preferred groups: {}", conflicts,
				conflictsByStation);
		}
		return new Plan(platforms, conflicts);
	}

	/** Occupation of the platform: from arrival (or just before a first departure) to departure, or to the next trip's departure at a terminus. */
	private double[] window(TripCalls trip, int callIndex, TripCalls next) {
		Call call = trip.calls().get(callIndex);
		boolean last = callIndex == trip.calls().size() - 1;
		double start = callIndex == 0 ? call.departureSeconds() - BUFFER_SECONDS : call.arrivalSeconds();
		double end;
		if (last && next != null && next.calls().getFirst().stopId().equals(call.stopId())) {
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
		Candidate fallback = null;
		for (String groupId : preferred) {
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
