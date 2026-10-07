package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.PlannedCall;
import it.unimib.milanrailsim.results.PunctualityAnalysis.StopVisit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The delays of a run regrouped by station, by train, and by station,
 * direction and hour. Delays are arrival delays with an early arrival counted
 * as zero; the platforms of a detailed station count for the station.
 */
public final class StationTables {

	/** @param latePercent share of the arrivals later than the threshold */
	public record StationRow(String station, int observations, double meanDelaySeconds, double p95DelaySeconds,
			double maxDelaySeconds, double latePercent) {
	}

	/** @param finalDelaySeconds arrival delay at the last stop the train reached */
	public record TrainRow(String vehicle, String line, int stops, double meanDelaySeconds, double maxDelaySeconds,
			double finalDelaySeconds) {
	}

	/**
	 * The trains calling at a station towards one terminus in one hour of
	 * the timetable. A late train stays in the hour it was planned in, so the
	 * rows of two runs of the same day compare one to one.
	 *
	 * @param direction     station the trips end at
	 * @param hour          hour of the planned arrival, counted from midnight of the service day
	 * @param trainsPlanned calls the timetable has in the hour
	 * @param trainsCalled  of those, the calls that took place
	 */
	public record HourRow(String station, String direction, int hour, int trainsPlanned, int trainsCalled,
			double meanDelaySeconds, double p95DelaySeconds, double punctualityPercent) {
	}

	private record HourKey(String station, String direction, int hour) implements Comparable<HourKey> {

		@Override
		public int compareTo(HourKey other) {
			return Comparator.comparing(HourKey::station).thenComparing(HourKey::direction)
				.thenComparingInt(HourKey::hour).compare(this, other);
		}
	}

	private StationTables() {
	}

	/** Stations worst first, by mean delay. */
	public static List<StationRow> byStation(List<StopVisit> visits, double lateThresholdSeconds) {
		Map<String, List<Double>> delays = new LinkedHashMap<>();
		for (StopVisit visit : visits) {
			delays.computeIfAbsent(StopFacilities.stationOf(visit.stop()), key -> new ArrayList<>()).add(delay(visit));
		}
		List<StationRow> rows = new ArrayList<>();
		delays.forEach((station, list) -> {
			List<Double> sorted = list.stream().sorted().toList();
			long late = sorted.stream().filter(delay -> delay > lateThresholdSeconds).count();
			rows.add(new StationRow(station, sorted.size(), mean(sorted), percentile(sorted, 95), sorted.getLast(),
				100.0 * late / sorted.size()));
		});
		rows.sort(Comparator.comparingDouble(StationRow::meanDelaySeconds).reversed());
		return List.copyOf(rows);
	}

	/** Trains worst first, by the largest delay they reached. */
	public static List<TrainRow> byTrain(List<StopVisit> visits) {
		Map<String, List<StopVisit>> byVehicle = new LinkedHashMap<>();
		for (StopVisit visit : visits) {
			byVehicle.computeIfAbsent(visit.vehicle(), key -> new ArrayList<>()).add(visit);
		}
		List<TrainRow> rows = new ArrayList<>();
		byVehicle.forEach((vehicle, list) -> {
			List<StopVisit> ordered = list.stream().sorted(Comparator.comparingDouble(StopVisit::actualArrival)).toList();
			List<Double> delays = ordered.stream().map(StationTables::delay).toList();
			rows.add(new TrainRow(vehicle, ordered.getFirst().line(), ordered.size(), mean(delays),
				delays.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), delays.getLast()));
		});
		rows.sort(Comparator.comparingDouble(TrainRow::maxDelaySeconds).reversed());
		return List.copyOf(rows);
	}

	/** One row per station, direction and planned hour, in that order; mean and percentile are NaN where no train called. */
	public static List<HourRow> hourly(List<PlannedCall> planned, List<StopVisit> visits, double onTimeThresholdSeconds) {
		Map<HourKey, Integer> plannedCalls = new TreeMap<>();
		for (PlannedCall call : planned) {
			plannedCalls.merge(new HourKey(StopFacilities.stationOf(call.stop()), call.destination(),
				hour(call.plannedArrival())), 1, Integer::sum);
		}
		Map<HourKey, List<Double>> delays = new TreeMap<>();
		for (StopVisit visit : visits) {
			delays.computeIfAbsent(new HourKey(StopFacilities.stationOf(visit.stop()), visit.destination(),
				hour(visit.plannedArrival())), key -> new ArrayList<>()).add(delay(visit));
		}
		List<HourRow> rows = new ArrayList<>();
		plannedCalls.forEach((key, count) -> {
			List<Double> sorted = delays.getOrDefault(key, List.of()).stream().sorted().toList();
			long onTime = sorted.stream().filter(delay -> delay <= onTimeThresholdSeconds).count();
			rows.add(new HourRow(key.station(), key.direction(), key.hour(), count, sorted.size(),
				sorted.isEmpty() ? Double.NaN : mean(sorted), sorted.isEmpty() ? Double.NaN : percentile(sorted, 95),
				sorted.isEmpty() ? Double.NaN : 100.0 * onTime / sorted.size()));
		});
		return List.copyOf(rows);
	}

	private static double delay(StopVisit visit) {
		return Math.max(0, visit.arrivalDelaySeconds());
	}

	private static int hour(double secondsOfDay) {
		return (int) Math.floor(secondsOfDay / 3600);
	}

	private static double mean(List<Double> values) {
		return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
	}

	/** Nearest-rank percentile of sorted values. */
	private static double percentile(List<Double> sorted, int p) {
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(rank, 1) - 1);
	}
}
