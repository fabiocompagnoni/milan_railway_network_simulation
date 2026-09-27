package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.Route;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.network.GtfsFeed.Trip;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.schedule.DensificationPlan.Intensity;
import it.unimib.milanrailsim.schedule.DensificationPlan.Relation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Adds trips to the published timetable on the relations of a
 * {@link DensificationPlan}. An added trip is a copy of a real trip of its
 * line, cut to the two ends of the relation and shifted into a gap between
 * two real departures: same stops, same running and dwell times. Real trips
 * are never moved, and a trip is added only if the headway it produces does
 * not fall below the cadence.
 * <p>
 * Whether the network carries the denser timetable is not decided here: the
 * only constraint applied is the minimum headway of the Passante tunnel,
 * below which a trip is moved within its gap or given up.
 */
public final class TimetableDensifier {

	private static final int RAIL = 2;
	private static final int SECONDS_PER_DAY = 24 * 3600;
	private static final int SHIFT_STEP_SECONDS = 60;
	private static final String ADDED_MARK = "+a";
	private static final Pattern ADDED_ID = Pattern.compile(".+\\+a\\d+");
	private static final String NO_ROOM_IN_TUNNEL = "intervallo minimo nel tunnel";

	/** @param templateTripId the real trip the added one copies */
	public record Added(String relation, String line, String tripId, String templateTripId, String from, String to,
			int departureSeconds) {
	}

	/** @param plannedDepartureSeconds where the trip would have left from, had there been room */
	public record Skipped(String relation, String line, String from, String to, int plannedDepartureSeconds, String reason) {
	}

	public record Report(List<Added> added, List<Skipped> skipped) {

		public Map<String, Long> addedByRelation() {
			return added.stream().collect(Collectors.groupingBy(Added::relation, TreeMap::new, Collectors.counting()));
		}
	}

	public record Densified(GtfsFeed feed, Report report) {
	}

	/** A real trip of the service day with its line. */
	private record Run(Trip trip, String line, List<StopTime> calls) {

		int indexOf(String stopId) {
			for (int i = 0; i < calls.size(); i++) {
				if (calls.get(i).stopId().equals(stopId)) {
					return i;
				}
			}
			return -1;
		}
	}

	/** The part of a real trip between the two ends of a relation, in its direction of travel. */
	private record Segment(Run run, int first, int last) {

		int departure() {
			return run.calls().get(first).departureSeconds();
		}
	}

	/** Departures from the tunnel's reference stop, by the stop the trains head for next. */
	private static final class TunnelTraffic {

		private final String referenceStop;
		private final int minHeadwaySeconds;
		private final Map<String, TreeSet<Integer>> departuresByNextStop = new HashMap<>();

		TunnelTraffic(DensificationPlan.Tunnel tunnel, List<Run> runs) {
			this.referenceStop = tunnel.referenceStop();
			this.minHeadwaySeconds = tunnel.minHeadwaySeconds();
			for (Run run : runs) {
				int index = run.indexOf(referenceStop);
				if (index >= 0 && index + 1 < run.calls().size()) {
					record(run.calls().get(index + 1).stopId(), run.calls().get(index).departureSeconds());
				}
			}
		}

		void record(String nextStop, int departure) {
			departuresByNextStop.computeIfAbsent(nextStop, stop -> new TreeSet<>()).add(departure);
		}

		boolean hasRoom(String nextStop, int departure) {
			TreeSet<Integer> departures = departuresByNextStop.getOrDefault(nextStop, new TreeSet<>());
			Integer before = departures.floor(departure);
			Integer after = departures.ceiling(departure);
			return (before == null || departure - before >= minHeadwaySeconds)
				&& (after == null || after - departure >= minHeadwaySeconds);
		}
	}

	private final DensificationPlan plan;

	public TimetableDensifier(DensificationPlan plan) {
		this.plan = plan;
	}

	/** Whether a trip id is that of an added trip. */
	public static boolean isAdded(String tripId) {
		return ADDED_ID.matcher(tripId).matches();
	}

	/**
	 * @param peakCadenceMinutes the headway to reach at peak hours, one of {@link DensificationPlan#CADENCES_MINUTES}
	 * @return the timetable of the day with the added trips, and what was added or given up
	 */
	public Densified densify(GtfsFeed real, LocalDate day, int peakCadenceMinutes) {
		List<Run> runs = railRunsOf(real, day);
		Filling filling = new Filling(day, peakCadenceMinutes, new TunnelTraffic(plan.tunnel(), runs));
		for (Relation relation : plan.relations()) {
			filling.fill(relation, relation.from(), relation.to(), runs);
			filling.fill(relation, relation.to(), relation.from(), runs);
		}
		return new Densified(real.with(filling.trips, filling.calls), new Report(List.copyOf(filling.added), List.copyOf(filling.skipped)));
	}

	private static List<Run> railRunsOf(GtfsFeed feed, LocalDate day) {
		Set<String> active = ServiceCalendar.activeServiceIds(feed.calendarDateRows(), day);
		List<Run> runs = new ArrayList<>();
		for (Trip trip : feed.tripsById().values()) {
			Route route = feed.routesById().get(trip.routeId());
			List<StopTime> calls = feed.stopTimesByTripId().get(trip.id());
			if (active.contains(trip.serviceId()) && route != null && route.type() == RAIL && calls != null) {
				runs.add(new Run(trip, route.shortName(), calls));
			}
		}
		return runs;
	}

	/** The added trips of one day, accumulated relation by relation. */
	private final class Filling {

		private final LocalDate day;
		private final int peakCadenceMinutes;
		private final TunnelTraffic tunnel;
		private final List<Trip> trips = new ArrayList<>();
		private final Map<String, List<StopTime>> calls = new LinkedHashMap<>();
		private final List<Added> added = new ArrayList<>();
		private final List<Skipped> skipped = new ArrayList<>();
		private final Map<String, Integer> copiesOfTrip = new HashMap<>();

		Filling(LocalDate day, int peakCadenceMinutes, TunnelTraffic tunnel) {
			this.day = day;
			this.peakCadenceMinutes = peakCadenceMinutes;
			this.tunnel = tunnel;
		}

		/**
		 * Fills the gaps between the trains of the relation's lines leaving
		 * {@code origin} for {@code destination}. Several lines share the
		 * headway of the relation and lend their trips in turn.
		 */
		void fill(Relation relation, String origin, String destination, List<Run> runs) {
			List<Segment> trains = runs.stream()
				.filter(run -> relation.lines().contains(run.line()))
				.map(run -> new Segment(run, run.indexOf(origin), run.indexOf(destination)))
				.filter(segment -> segment.first() >= 0 && segment.first() < segment.last())
				.sorted(Comparator.comparingInt(Segment::departure).thenComparing(segment -> segment.run().trip().id()))
				.toList();
			int served = 0;
			for (int i = 0; i + 1 < trains.size(); i++) {
				int start = trains.get(i).departure();
				int gap = trains.get(i + 1).departure() - start;
				int tripsToAdd = tripsFitting(relation, start, gap);
				if (tripsToAdd == 0) {
					continue;
				}
				int turn = served++;
				if (relation.intensity() == Intensity.REDUCED && turn % 2 == 1) {
					continue;
				}
				String line = relation.lines().get((relation.intensity() == Intensity.REDUCED ? turn / 2 : turn) % relation.lines().size());
				Segment template = templateOf(line, trains, i);
				for (int n = 1; n <= tripsToAdd; n++) {
					int planned = wholeMinute(start + (double) n * gap / (tripsToAdd + 1));
					add(relation, template, planned, start, start + gap);
				}
			}
		}

		/** How many trips a gap takes without the headway falling below the cadence of that hour; none in a break of service. */
		private int tripsFitting(Relation relation, int start, int gap) {
			if (gap <= 0 || gap > plan.serviceGapSeconds()) {
				return 0;
			}
			OptionalInt cadence = plan.cadenceSeconds(day, start % SECONDS_PER_DAY, peakCadenceMinutes, relation.intensity());
			return cadence.isEmpty() ? 0 : Math.max(0, gap / cadence.getAsInt() - 1);
		}

		/** The last train of the line before the gap, or its first one after when the line has not run yet. */
		private Segment templateOf(String line, List<Segment> trains, int beforeGap) {
			for (int i = beforeGap; i >= 0; i--) {
				if (trains.get(i).run().line().equals(line)) {
					return trains.get(i);
				}
			}
			return trains.stream().filter(train -> train.run().line().equals(line)).findFirst().orElseThrow();
		}

		private void add(Relation relation, Segment template, int planned, int gapStart, int gapEnd) {
			Run run = template.run();
			String origin = run.calls().get(template.first()).stopId();
			String destination = run.calls().get(template.last()).stopId();
			Optional<Integer> departure = relation.throughTunnel()
				? departureWithRoom(template, planned, gapStart, gapEnd) : Optional.of(planned);
			if (departure.isEmpty()) {
				skipped.add(new Skipped(relation.id(), run.line(), origin, destination, planned, NO_ROOM_IN_TUNNEL));
				return;
			}
			int shift = departure.get() - template.departure();
			String id = run.trip().id() + ADDED_MARK + copiesOfTrip.merge(run.trip().id(), 1, Integer::sum);
			List<StopTime> copy = new ArrayList<>();
			for (int i = template.first(); i <= template.last(); i++) {
				StopTime call = run.calls().get(i);
				copy.add(new StopTime(id, call.arrivalSeconds() + shift, call.departureSeconds() + shift, call.stopId(),
					i - template.first() + 1));
			}
			trips.add(new Trip(id, run.trip().routeId(), run.trip().serviceId()));
			calls.put(id, copy);
			added.add(new Added(relation.id(), run.line(), id, run.trip().id(), origin, destination, departure.get()));
			tunnelPassage(template).ifPresent(passage -> tunnel.record(passage.nextStop(), passage.departure() + shift));
		}

		private record Passage(String nextStop, int departure) {
		}

		/** Where and when the template leaves the tunnel's reference stop, if its segment runs through it. */
		private Optional<Passage> tunnelPassage(Segment template) {
			List<StopTime> calls = template.run().calls();
			for (int i = template.first(); i < template.last(); i++) {
				if (calls.get(i).stopId().equals(tunnel.referenceStop)) {
					return Optional.of(new Passage(calls.get(i + 1).stopId(), calls.get(i).departureSeconds()));
				}
			}
			return Optional.empty();
		}

		/**
		 * The departure nearest to the planned one, within the gap, that keeps
		 * the minimum headway of the tunnel from every other train: tried a
		 * minute later, a minute earlier, two minutes later and so on.
		 */
		private Optional<Integer> departureWithRoom(Segment template, int planned, int gapStart, int gapEnd) {
			Optional<Passage> passage = tunnelPassage(template);
			if (passage.isEmpty()) {
				return Optional.of(planned);
			}
			for (int step = 0; planned + step < gapEnd || planned - step > gapStart; step += SHIFT_STEP_SECONDS) {
				for (int departure : step == 0 ? new int[] { planned } : new int[] { planned + step, planned - step }) {
					int shift = departure - template.departure();
					if (departure > gapStart && departure < gapEnd
							&& tunnel.hasRoom(passage.get().nextStop(), passage.get().departure() + shift)) {
						return Optional.of(departure);
					}
				}
			}
			return Optional.empty();
		}

		private static int wholeMinute(double seconds) {
			return (int) Math.round(seconds / 60) * 60;
		}
	}
}
