package it.unimib.milanrailsim.results;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Per-line aggregation of arrival delays (nearest-rank percentile). */
public final class LineSummaries {

	public record LineSummary(String line, int observations, double meanDelaySeconds,
			double medianDelaySeconds, double p95DelaySeconds, double maxDelaySeconds) {
	}

	private LineSummaries() {
	}

	public static List<LineSummary> of(List<PunctualityAnalysis.StopVisit> visits) {
		Map<String, List<Double>> delaysByLine = visits.stream()
			.collect(Collectors.groupingBy(PunctualityAnalysis.StopVisit::line, TreeMap::new,
				Collectors.mapping(PunctualityAnalysis.StopVisit::arrivalDelaySeconds, Collectors.toList())));
		return delaysByLine.entrySet().stream()
			.map(entry -> summarize(entry.getKey(), entry.getValue()))
			.toList();
	}

	private static LineSummary summarize(String line, List<Double> delays) {
		List<Double> sorted = delays.stream().sorted().toList();
		double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
		return new LineSummary(line, sorted.size(), mean,
			percentile(sorted, 50), percentile(sorted, 95), sorted.get(sorted.size() - 1));
	}

	private static double percentile(List<Double> sorted, int p) {
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(rank, 1) - 1);
	}
}
