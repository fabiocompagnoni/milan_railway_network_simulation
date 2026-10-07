package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.StopVisit;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Quality of service of a run, with the definitions of the Italian transport
 * regulator (ART, delibera 16/2018): delay is the positive difference between
 * actual and planned time, punctuality the share of trains run that arrive
 * within the threshold, regularity the share of planned trains that were run.
 * <p>
 * Punctuality is given twice: at the destination of each trip, as the
 * regulator measures it, and over every stop. A trip that was not completed is
 * no arrival, late or on time: it lowers regularity, not punctuality. Next to
 * the mean delay, which counts an early arrival as zero, stands the signed
 * mean deviation from the timetable, where early and late arrivals offset each
 * other.
 * <p>
 * Percentages are NaN where there is nothing to measure.
 *
 * @param stopsPlanned     stops of every planned trip
 * @param stopsServed      stops the trains called at
 * @param meanDelaySeconds mean arrival delay over the stops served, early arrivals counted as zero
 */
public record ServiceIndicators(int tripsScheduled, int tripsCompleted, int tripsInterrupted, int tripsNeverDeparted,
		int stopsPlanned, int stopsServed, double punctualityAtDestinationPercent, double punctualityAtStopsPercent,
		double meanDelaySeconds, double meanDeviationSeconds, double medianDelaySeconds, double p95DelaySeconds) {

	/** An arrival up to five minutes late is on time, by the regulator's and the European convention. */
	public static final double ON_TIME_THRESHOLD_SECONDS = 300;

	/** Completed trips over planned trips. */
	public double regularityPercent() {
		return tripsScheduled == 0 ? Double.NaN : 100.0 * tripsCompleted / tripsScheduled;
	}

	/** @param thresholdSeconds an arrival up to this late is on time */
	public static ServiceIndicators of(List<TripOutcome> trips, List<StopVisit> visits, double thresholdSeconds) {
		List<TripOutcome> completed = trips.stream().filter(trip -> trip.status() == TripStatus.COMPLETED).toList();
		long onTimeAtDestination = completed.stream()
			.filter(trip -> trip.arrivalDelaySeconds() <= thresholdSeconds).count();
		List<Double> delays = visits.stream().map(visit -> Math.max(0, visit.arrivalDelaySeconds())).sorted().toList();
		long onTimeAtStops = delays.stream().filter(delay -> delay <= thresholdSeconds).count();
		return new ServiceIndicators(trips.size(), completed.size(), count(trips, TripStatus.INTERRUPTED),
			count(trips, TripStatus.NEVER_DEPARTED),
			trips.stream().mapToInt(TripOutcome::stopsPlanned).sum(),
			trips.stream().mapToInt(TripOutcome::stopsServed).sum(),
			percent(onTimeAtDestination, completed.size()), percent(onTimeAtStops, delays.size()),
			delays.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
			visits.stream().mapToDouble(StopVisit::arrivalDelaySeconds).average().orElse(Double.NaN),
			percentile(delays, 50), percentile(delays, 95));
	}

	/** The indicators of each line, by line id. */
	public static Map<String, ServiceIndicators> byLine(List<TripOutcome> trips, List<StopVisit> visits,
			double thresholdSeconds) {
		TreeSet<String> lines = new TreeSet<>();
		trips.forEach(trip -> lines.add(trip.line()));
		visits.forEach(visit -> lines.add(visit.line()));
		Map<String, ServiceIndicators> byLine = new TreeMap<>();
		for (String line : lines) {
			byLine.put(line, of(trips.stream().filter(trip -> trip.line().equals(line)).toList(),
				visits.stream().filter(visit -> visit.line().equals(line)).toList(), thresholdSeconds));
		}
		return byLine;
	}

	private static int count(List<TripOutcome> trips, TripStatus status) {
		return (int) trips.stream().filter(trip -> trip.status() == status).count();
	}

	private static double percent(long part, int whole) {
		return whole == 0 ? Double.NaN : 100.0 * part / whole;
	}

	/** Nearest-rank percentile of sorted values. */
	private static double percentile(List<Double> sorted, int p) {
		if (sorted.isEmpty()) {
			return Double.NaN;
		}
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(rank, 1) - 1);
	}
}
