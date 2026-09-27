package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.GtfsFeed.Route;
import it.unimib.milanrailsim.network.GtfsFeed.StopTime;
import it.unimib.milanrailsim.network.GtfsFeed.Trip;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.schedule.DensificationPlan.Intensity;
import it.unimib.milanrailsim.schedule.DensificationPlan.Relation;
import it.unimib.milanrailsim.schedule.DensificationPlan.Service;

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
 * {@link DensificationPlan}. The headway of a relation is that of its flow:
 * every train, of any line, calling at two given stops in the direction of
 * travel. Where two trains of the flow leave a wait above the target, trips
 * are added between them. An added trip is a copy of a real trip of one of
 * the relation's services, cut to the ends of the service and shifted: same
 * stops, same running and dwell times. Real trips are never moved.
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
	private static final String NO_TRIP_TO_COPY = "nessuna corsa reale da copiare";

	/**
	 * @param templateTripId   the real trip the added one copies
	 * @param departureSeconds when the added trip leaves its first stop
	 */
	public record Added(String relation, String line, String tripId, String templateTripId, String from, String to,
			int departureSeconds) {
	}

	/** @param plannedSeconds when the trip would have left the first stop of the flow, had it been added */
	public record Skipped(String relation, String line, int plannedSeconds, String reason) {
	}

	public record Report(List<Added> added, List<Skipped> skipped) {

		public Map<String, Long> addedByRelation() {
			return added.stream().collect(Collectors.groupingBy(Added::relation, TreeMap::new, Collectors.counting()));
		}
	}

	public record Densified(GtfsFeed feed, Report report) {
	}

	/** A trip of the service day with its line. */
	private record Run(String tripId, String routeId, String serviceId, String line, List<StopTime> calls) {

		int indexOf(String stopId) {
			for (int i = 0; i < calls.size(); i++) {
				if (calls.get(i).stopId().equals(stopId)) {
					return i;
				}
			}
			return -1;
		}

		/** Whether the trip calls at the two stops in this order. */
		boolean runs(String stop, String next) {
			int index = indexOf(stop);
			return index >= 0 && index < indexOf(next);
		}

		int departureFrom(String stopId) {
			return calls.get(indexOf(stopId)).departureSeconds();
		}
	}

	/** The part of a real trip between the two ends of a service. */
	private record Segment(Run run, int first, int last) {
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
	 * @param peakCadenceMinutes the longest wait to allow at peak hours, one of {@link DensificationPlan#CADENCES_MINUTES}
	 * @return the timetable of the day with the added trips, and what was added or given up
	 */
	public Densified densify(GtfsFeed real, LocalDate day, int peakCadenceMinutes) {
		Filling filling = new Filling(day, peakCadenceMinutes, railRunsOf(real, day));
		for (Relation relation : plan.relations()) {
			filling.fill(relation, relation.flow().getFirst(), relation.flow().getLast());
			filling.fill(relation, relation.flow().getLast(), relation.flow().getFirst());
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
				runs.add(new Run(trip.id(), trip.routeId(), trip.serviceId(), route.shortName(), calls));
			}
		}
		return runs;
	}

	/** The added trips of one day, accumulated relation by relation. */
	private final class Filling {

		private final LocalDate day;
		private final int peakCadenceMinutes;
		private final List<Run> realRuns;
		private final List<Run> traffic;
		private final TunnelTraffic tunnel;
		private final List<Trip> trips = new ArrayList<>();
		private final Map<String, List<StopTime>> calls = new LinkedHashMap<>();
		private final List<Added> added = new ArrayList<>();
		private final List<Skipped> skipped = new ArrayList<>();
		private final Map<String, Integer> copiesOfTrip = new HashMap<>();

		Filling(LocalDate day, int peakCadenceMinutes, List<Run> realRuns) {
			this.day = day;
			this.peakCadenceMinutes = peakCadenceMinutes;
			this.realRuns = realRuns;
			this.traffic = new ArrayList<>(realRuns);
			this.tunnel = new TunnelTraffic(plan.tunnel(), realRuns);
		}

		/**
		 * Fills the gaps between the trains running from {@code stop} to
		 * {@code next}, real or added by an earlier relation. The gaps are
		 * those found on entry: the trips added here do not open new ones.
		 */
		void fill(Relation relation, String stop, String next) {
			List<Integer> departures = traffic.stream().filter(run -> run.runs(stop, next))
				.map(run -> run.departureFrom(stop)).sorted().toList();
			int served = 0;
			int turn = 0;
			for (int i = 0; i + 1 < departures.size(); i++) {
				int start = departures.get(i);
				int gap = departures.get(i + 1) - start;
				int tripsToAdd = tripsFitting(relation, start, gap);
				if (tripsToAdd == 0 || (relation.intensity() == Intensity.REDUCED && served++ % 2 == 1)) {
					continue;
				}
				Gap between = new Gap(stop, next, start, start + gap);
				for (int n = 1; n <= tripsToAdd; n++) {
					int planned = wholeMinute(start + (double) n * gap / (tripsToAdd + 1));
					Optional<Turn> taken = nextInTurn(relation, turn, between);
					if (taken.isEmpty()) {
						skipped.add(new Skipped(relation.id(), relation.services().get(turn % relation.services().size()).line(),
							planned, NO_TRIP_TO_COPY));
						continue;
					}
					turn = taken.get().position() + 1;
					add(relation, taken.get().template(), between, planned);
				}
			}
		}

		/** Two consecutive trains of a flow, by their departures from its first stop. */
		private record Gap(String stop, String next, int start, int end) {
		}

		/** The service a trip is copied from, by its position in the rotation of the relation. */
		private record Turn(int position, Segment template) {
		}

		/** The next service of the rotation that runs on the day: a line out of service yields its turn. */
		private Optional<Turn> nextInTurn(Relation relation, int from, Gap gap) {
			int services = relation.services().size();
			for (int position = from; position < from + services; position++) {
				Optional<Segment> template = templateOf(relation.services().get(position % services), gap);
				if (template.isPresent()) {
					return Optional.of(new Turn(position, template.get()));
				}
			}
			return Optional.empty();
		}

		private int tripsFitting(Relation relation, int start, int gap) {
			if (gap <= 0 || gap > plan.serviceGapSeconds()) {
				return 0;
			}
			OptionalInt cadence = plan.cadenceSeconds(day, start % SECONDS_PER_DAY, peakCadenceMinutes, relation.intensity());
			return cadence.isEmpty() ? 0 : plan.tripsFitting(gap, cadence.getAsInt());
		}

		private void add(Relation relation, Segment template, Gap gap, int planned) {
			Run run = template.run();
			Optional<Integer> leaving = relation.throughTunnel() ? departureWithRoom(template, gap, planned) : Optional.of(planned);
			if (leaving.isEmpty()) {
				skipped.add(new Skipped(relation.id(), run.line(), planned, NO_ROOM_IN_TUNNEL));
				return;
			}
			int shift = leaving.get() - run.departureFrom(gap.stop());
			String id = run.tripId() + ADDED_MARK + copiesOfTrip.merge(run.tripId(), 1, Integer::sum);
			List<StopTime> copy = new ArrayList<>();
			for (int i = template.first(); i <= template.last(); i++) {
				StopTime call = run.calls().get(i);
				copy.add(new StopTime(id, call.arrivalSeconds() + shift, call.departureSeconds() + shift, call.stopId(),
					copy.size() + 1));
			}
			trips.add(new Trip(id, run.routeId(), run.serviceId()));
			calls.put(id, copy);
			traffic.add(new Run(id, run.routeId(), run.serviceId(), run.line(), copy));
			added.add(new Added(relation.id(), run.line(), id, run.tripId(), copy.getFirst().stopId(), copy.getLast().stopId(),
				copy.getFirst().departureSeconds()));
			tunnelPassage(template).ifPresent(passage -> tunnel.record(passage.nextStop(), passage.departure() + shift));
		}

		/**
		 * The real trip to copy: of the line of the service, calling at both
		 * its ends with the flow in between, the last one to leave before the
		 * gap or, when the line has not run yet, its first one after.
		 */
		private Optional<Segment> templateOf(Service service, Gap gap) {
			List<Segment> candidates = realRuns.stream()
				.filter(run -> run.line().equals(service.line()) && run.runs(gap.stop(), gap.next()))
				.map(run -> segmentOf(run, service))
				.filter(segment -> segment.first() >= 0 && segment.first() <= segment.run().indexOf(gap.stop())
					&& segment.run().indexOf(gap.next()) <= segment.last())
				.sorted(Comparator.comparingInt((Segment segment) -> segment.run().departureFrom(gap.stop()))
					.thenComparing(segment -> segment.run().tripId()))
				.toList();
			Optional<Segment> lastBefore = candidates.stream()
				.filter(segment -> segment.run().departureFrom(gap.stop()) <= gap.start())
				.reduce((earlier, later) -> later);
			return lastBefore.or(() -> candidates.stream().findFirst());
		}

		/** The calls of a trip between the two ends of a service, in the order the trip makes them; first is -1 if it misses one. */
		private static Segment segmentOf(Run run, Service service) {
			int one = run.indexOf(service.from());
			int other = run.indexOf(service.to());
			return new Segment(run, one < 0 || other < 0 ? -1 : Math.min(one, other), Math.max(one, other));
		}

		private record Passage(String nextStop, int departure) {
		}

		/** Where and when the template leaves the tunnel's reference stop, if its segment runs through it. */
		private Optional<Passage> tunnelPassage(Segment template) {
			List<StopTime> templateCalls = template.run().calls();
			for (int i = template.first(); i < template.last(); i++) {
				if (templateCalls.get(i).stopId().equals(tunnel.referenceStop)) {
					return Optional.of(new Passage(templateCalls.get(i + 1).stopId(), templateCalls.get(i).departureSeconds()));
				}
			}
			return Optional.empty();
		}

		/**
		 * The departure from the first stop of the flow nearest to the planned
		 * one, within the gap, that keeps the minimum headway of the tunnel
		 * from every other train: tried a minute later, a minute earlier, two
		 * minutes later and so on.
		 */
		private Optional<Integer> departureWithRoom(Segment template, Gap gap, int planned) {
			Optional<Passage> passage = tunnelPassage(template);
			if (passage.isEmpty()) {
				return Optional.of(planned);
			}
			int templateDeparture = template.run().departureFrom(gap.stop());
			for (int step = 0; planned + step < gap.end() || planned - step > gap.start(); step += SHIFT_STEP_SECONDS) {
				for (int leaving : step == 0 ? new int[] { planned } : new int[] { planned + step, planned - step }) {
					if (leaving > gap.start() && leaving < gap.end()
							&& tunnel.hasRoom(passage.get().nextStop(), passage.get().departure() + leaving - templateDeparture)) {
						return Optional.of(leaving);
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
