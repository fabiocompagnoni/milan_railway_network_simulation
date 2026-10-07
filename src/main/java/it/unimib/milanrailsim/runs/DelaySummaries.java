package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.results.StopFacilities;
import it.unimib.milanrailsim.runs.RunResults.VisitRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Delay figures of a run regrouped by station and by train, from its stop visits. */
public final class DelaySummaries {

	/** {@code lateShare} is the fraction of arrivals later than the threshold, 0 to 1. */
	public record StationRow(String station, int observations, double meanDelay, double p95Delay, double maxDelay,
			double lateShare) {
	}

	/** {@code finalDelay} is the arrival delay at the last stop the train reached. */
	public record TrainRow(String vehicle, String line, int stops, double meanDelay, double maxDelay, double finalDelay) {
	}

	private DelaySummaries() {
	}

	/** Stations worst first; a station's visits are those at any of its platforms. */
	public static List<StationRow> byStation(List<VisitRow> visits, double lateThresholdSeconds) {
		Map<String, List<Double>> delays = new LinkedHashMap<>();
		for (VisitRow visit : visits) {
			if (!Double.isNaN(visit.arrivalDelay())) {
				delays.computeIfAbsent(stationOf(visit.stop()), key -> new ArrayList<>()).add(visit.arrivalDelay());
			}
		}
		List<StationRow> rows = new ArrayList<>();
		delays.forEach((station, list) -> {
			List<Double> sorted = list.stream().sorted().toList();
			double late = sorted.stream().filter(delay -> delay > lateThresholdSeconds).count();
			rows.add(new StationRow(station, sorted.size(), mean(sorted), percentile(sorted, 0.95), sorted.getLast(),
				late / sorted.size()));
		});
		rows.sort(Comparator.comparingDouble(StationRow::meanDelay).reversed());
		return List.copyOf(rows);
	}

	/** Trains worst first, by the largest arrival delay they reached. */
	public static List<TrainRow> byTrain(List<VisitRow> visits) {
		Map<String, List<VisitRow>> byVehicle = new LinkedHashMap<>();
		for (VisitRow visit : visits) {
			if (!Double.isNaN(visit.arrivalDelay())) {
				byVehicle.computeIfAbsent(visit.vehicle(), key -> new ArrayList<>()).add(visit);
			}
		}
		List<TrainRow> rows = new ArrayList<>();
		byVehicle.forEach((vehicle, list) -> {
			List<VisitRow> ordered = list.stream().sorted(Comparator.comparingDouble(VisitRow::actualArrival)).toList();
			List<Double> delays = ordered.stream().map(VisitRow::arrivalDelay).toList();
			rows.add(new TrainRow(vehicle, ordered.getFirst().line(), ordered.size(), mean(delays),
				delays.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN), delays.getLast()));
		});
		rows.sort(Comparator.comparingDouble(TrainRow::maxDelay).reversed());
		return List.copyOf(rows);
	}

	/** The station a stop facility belongs to. */
	public static String stationOf(String facilityId) {
		return StopFacilities.stationOf(facilityId);
	}

	private static double mean(List<Double> values) {
		return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
	}

	private static double percentile(List<Double> sorted, double fraction) {
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(fraction * sorted.size()) - 1));
	}
}
