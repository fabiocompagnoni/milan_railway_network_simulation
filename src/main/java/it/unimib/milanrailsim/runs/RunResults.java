package it.unimib.milanrailsim.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.milanrailsim.network.CsvTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The analysis files of one archived run, read as they are. Every part is
 * optional: an interrupted run may have produced only some of them.
 */
public record RunResults(Path dir, Optional<List<LineRow>> byLine, Optional<List<VisitRow>> visits,
		Optional<List<UnfinishedRow>> unfinished, Optional<Costs> costs) {

	public record LineRow(String line, int observations, double meanDelay, double medianDelay, double p95Delay,
			double maxDelay) {
	}

	/** A train that never reached its last planned stop: where it got to and how many stops were left. */
	public record UnfinishedRow(String vehicle, String line, String route, String lastStop, int remainingStops) {
	}

	/** Seconds since midnight for times, NaN where the engine recorded nothing. */
	public record VisitRow(String vehicle, String line, String route, String stop, double plannedArrival,
			double actualArrival, double arrivalDelay, double plannedDeparture, double actualDeparture,
			double departureDelay) {
	}

	public record Costs(String currency, Map<String, Double> byCategory, double trainKm, double trainHours,
			int fleetSize, double total) {
	}

	public static final String DELAY_HISTOGRAM = "delay_histogram";
	public static final String DELAY_BY_HOUR = "delay_by_hour";
	public static final String SPACE_TIME = "space_time";
	public static final String COST_BREAKDOWN = "cost_breakdown";

	public static RunResults load(Path dir) {
		return new RunResults(dir,
			optional(dir.resolve("punctuality_by_line.csv"), RunResults::readByLine),
			optional(dir.resolve("punctuality.csv"), RunResults::readVisits),
			optional(dir.resolve("unfinished.csv"), RunResults::readUnfinished),
			optional(dir.resolve("costs.json"), RunResults::readCosts));
	}

	public Optional<Path> chart(String name) {
		Path file = dir.resolve("charts").resolve(name + ".png");
		return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
	}

	/** Share of stop arrivals within {@code thresholdSeconds} of the plan, in percent, or empty without visit data. */
	public Optional<Double> punctuality(double thresholdSeconds) {
		return visits.map(rows -> punctuality(rows, thresholdSeconds));
	}

	/** Share of the given arrivals within {@code thresholdSeconds} of the plan, in percent; NaN when none was measured. */
	public static double punctuality(List<VisitRow> rows, double thresholdSeconds) {
		long measured = rows.stream().filter(row -> !Double.isNaN(row.arrivalDelay())).count();
		long onTime = rows.stream().filter(row -> !Double.isNaN(row.arrivalDelay()) && row.arrivalDelay() <= thresholdSeconds).count();
		return measured == 0 ? Double.NaN : 100.0 * onTime / measured;
	}

	private static <T> Optional<T> optional(Path file, java.util.function.Function<Path, T> reader) {
		return Files.isRegularFile(file) ? Optional.of(reader.apply(file)) : Optional.empty();
	}

	private static List<LineRow> readByLine(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new LineRow(row.get("line"),
			Integer.parseInt(row.get("observations")), number(row.get("mean_delay_s")),
			number(row.get("median_delay_s")), number(row.get("p95_delay_s")), number(row.get("max_delay_s")))).toList();
	}

	private static List<VisitRow> readVisits(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new VisitRow(row.get("vehicle"), row.get("line"),
			row.get("route"), row.get("stop"), number(row.get("planned_arrival_s")), number(row.get("actual_arrival_s")),
			number(row.get("arrival_delay_s")), number(row.get("planned_departure_s")),
			number(row.get("actual_departure_s")), number(row.get("departure_delay_s")))).toList();
	}

	private static List<UnfinishedRow> readUnfinished(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new UnfinishedRow(row.get("vehicle"), row.get("line"),
			row.get("route"), row.get("last_stop"), Integer.parseInt(row.get("remaining_stops")))).toList();
	}

	private static Costs readCosts(Path json) {
		try {
			JsonNode root = new ObjectMapper().readTree(json.toFile());
			Map<String, Double> byCategory = new LinkedHashMap<>();
			root.path("byCategory").properties().forEach(field -> byCategory.put(field.getKey(), field.getValue().asDouble()));
			return new Costs(root.path("currency").asText("EUR"), byCategory, root.path("trainKm").asDouble(),
				root.path("trainHours").asDouble(), root.path("fleetSize").asInt(), root.path("total").asDouble());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + json, e);
		}
	}

	private static double number(String value) {
		return value == null || value.isBlank() ? Double.NaN : Double.parseDouble(value);
	}
}
