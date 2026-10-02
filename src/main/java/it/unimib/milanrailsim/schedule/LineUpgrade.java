package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.schedule.TunnelTraffic.Passage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Adds trips to the published timetable so that, on each chosen route of a
 * line, no wait between two trains exceeds the target. A route is a pair of
 * termini; each direction is measured on the departures from its own
 * terminus, counting only the trips of the line that run the whole route in
 * that direction. Between two such trips the gap takes
 * {@code ceil(gap / target) - 1} copies of the trip that opens it, evenly
 * spaced and rounded to the minute; a gap longer than the plan's service gap
 * is a break in service and stays empty. Real trips are never moved.
 * <p>
 * Nothing is refused: a copy that passes the Passante tunnel closer than its
 * minimum headway to another train is kept and reported, since whether the
 * network carries it is what the simulation measures.
 */
public final class LineUpgrade {

	private static final int SECONDS_PER_MINUTE = 60;

	/**
	 * @param templateTripId   the real trip the added one copies
	 * @param departureSeconds when the added trip leaves its first stop
	 */
	public record Added(String line, String tripId, String templateTripId, String from, String to, int departureSeconds) {
	}

	/**
	 * An added trip leaving the tunnel's reference stop closer than the minimum
	 * headway to another train in its direction.
	 *
	 * @param headwaySeconds seconds to the nearest train, real or added before it
	 */
	public record TunnelWarning(String line, String tripId, int passageSeconds, int headwaySeconds) {
	}

	public record Report(List<Added> added, List<TunnelWarning> tunnelWarnings) {

		/** One line per added trip under a header; times in seconds since midnight. */
		public List<String> addedCsv() {
			List<String> lines = new ArrayList<>();
			lines.add("line,trip,copied_trip,from,to,departure_s");
			added.forEach(trip -> lines.add(String.join(",", trip.line(), trip.tripId(), trip.templateTripId(), trip.from(),
				trip.to(), Integer.toString(trip.departureSeconds()))));
			return lines;
		}

		/** One line per close passage in the tunnel under a header; times in seconds since midnight. */
		public List<String> tunnelWarningsCsv() {
			List<String> lines = new ArrayList<>();
			lines.add("line,trip,passage_s,headway_s");
			tunnelWarnings.forEach(warning -> lines.add(String.join(",", warning.line(), warning.tripId(),
				Integer.toString(warning.passageSeconds()), Integer.toString(warning.headwaySeconds()))));
			return lines;
		}
	}

	public record Upgraded(GtfsFeed feed, Report report) {
	}

	private final LineUpgradePlan plan;

	public LineUpgrade(LineUpgradePlan plan) {
		this.plan = plan;
	}

	/**
	 * @param targets the routes to upgrade, each at most once, filled in this order
	 * @return the timetable of the day with the added trips, and what was added
	 * @throws IllegalArgumentException when a route is named twice
	 */
	public Upgraded upgrade(GtfsFeed real, LocalDate day, List<RouteTarget> targets) {
		for (int i = 0; i < targets.size(); i++) {
			for (int j = i + 1; j < targets.size(); j++) {
				if (targets.get(i).sameRoute(targets.get(j))) {
					throw new IllegalArgumentException("Route named twice: " + targets.get(i));
				}
			}
		}
		List<DayTrip> trips = DayTrip.of(real, day);
		Filling filling = new Filling(new TunnelTraffic(plan.tunnel(), trips));
		for (RouteTarget target : targets) {
			filling.fill(trips, target, target.one(), target.other());
			filling.fill(trips, target, target.other(), target.one());
		}
		return new Upgraded(filling.copies.addTo(real), new Report(List.copyOf(filling.added), List.copyOf(filling.warnings)));
	}

	/** The trips added on one day, accumulated route by route. */
	private final class Filling {

		private final TunnelTraffic tunnel;
		private final AddedTrips copies = new AddedTrips();
		private final List<Added> added = new ArrayList<>();
		private final List<TunnelWarning> warnings = new ArrayList<>();

		Filling(TunnelTraffic tunnel) {
			this.tunnel = tunnel;
		}

		/** Fills the gaps between the trips of the line running from one terminus to the other. */
		void fill(List<DayTrip> trips, RouteTarget target, String from, String to) {
			List<DayTrip> route = trips.stream()
				.filter(trip -> trip.line().equals(target.line()) && trip.firstStop().equals(from) && trip.lastStop().equals(to))
				.sorted(Comparator.comparingInt(DayTrip::departure).thenComparing(DayTrip::tripId))
				.toList();
			int targetSeconds = target.targetMinutes() * SECONDS_PER_MINUTE;
			for (int i = 0; i + 1 < route.size(); i++) {
				DayTrip opening = route.get(i);
				int gap = route.get(i + 1).departure() - opening.departure();
				if (gap <= 0 || gap > plan.serviceGapSeconds()) {
					continue;
				}
				int tripsToAdd = Math.ceilDiv(gap, targetSeconds) - 1;
				for (int n = 1; n <= tripsToAdd; n++) {
					int leaving = wholeMinute(opening.departure() + (double) n * gap / (tripsToAdd + 1));
					add(opening, leaving - opening.departure());
				}
			}
		}

		private void add(DayTrip template, int shift) {
			DayTrip copy = copies.copy(template, 0, template.calls().size() - 1, shift);
			added.add(new Added(copy.line(), copy.tripId(), template.tripId(), copy.firstStop(), copy.lastStop(), copy.departure()));
			Optional<Passage> passage = tunnel.passageOf(copy, 0, copy.calls().size() - 1);
			if (passage.isPresent()) {
				if (!tunnel.hasRoom(passage.get())) {
					warnings.add(new TunnelWarning(copy.line(), copy.tripId(), passage.get().departure(),
						tunnel.headwayOf(passage.get())));
				}
				tunnel.record(passage.get());
			}
		}

		private static int wholeMinute(double seconds) {
			return (int) Math.round(seconds / SECONDS_PER_MINUTE) * SECONDS_PER_MINUTE;
		}
	}
}
