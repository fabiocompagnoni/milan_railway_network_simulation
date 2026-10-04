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
		Optional<List<UnfinishedRow>> unfinished, Optional<Costs> costs, Optional<Indicators> indicators,
		Optional<Map<String, Indicators>> indicatorsByLine, Optional<List<StationRow>> stations,
		Optional<List<StationHourRow>> stationsHourly, Optional<List<TrainRow>> trains, Optional<Energy> energy,
		Optional<List<EnergyLineRow>> energyByLine) {

	/**
	 * The energy of the run: electric trains in kWh, diesel trains in litres.
	 *
	 * @param drawnKilowattHours  taken from the substations
	 * @param demandKilowattHours what the trains would have drawn with no braking energy reused
	 * @param peakMinuteOfDay     minute with the highest mean power drawn, -1 when unknown
	 */
	public record Energy(double drawnKilowattHours, double demandKilowattHours, double regeneratedKilowattHours,
			double reusedByOthersKilowattHours, double lostKilowattHours, double electricTrainKilometres,
			double kilowattHoursPerTrainKilometre, double kilowattHoursPerTonneKilometre,
			double kilowattHoursPerTonneKilometreWithoutRecovery, double litres, double dieselTrainKilometres,
			double litresPerTrainKilometre, int peakMinuteOfDay, double peakKilowatt, int peakTrains) {
	}

	/** The energy of one line and traction; kWh columns are NaN for diesel, litres for electric. */
	public record EnergyLineRow(String line, String traction, double trainKilometres, double drawnKilowattHours,
			double regeneratedKilowattHours, double lostKilowattHours, double litres, double kilowattHoursPerTrainKilometre,
			double litresPerTrainKilometre) {
	}

	public static final String POWER_PROFILE = "power_profile";

	/** Arrival delays at a station, early arrivals counted as zero; {@code latePercent} is the share over five minutes. */
	public record StationRow(String station, int observations, double meanDelay, double p95Delay, double maxDelay,
			double latePercent) {
	}

	/**
	 * Trains calling at a station towards a terminus in one hour of the
	 * timetable; delays and punctuality are NaN when none of them called.
	 */
	public record StationHourRow(String station, String direction, int hour, int trainsPlanned, int trainsCalled,
			double meanDelay, double p95Delay, double punctualityPercent) {
	}

	/** {@code finalDelay} is the arrival delay at the last stop the train reached. */
	public record TrainRow(String vehicle, String line, int stops, double meanDelay, double maxDelay, double finalDelay) {
	}

	/**
	 * Quality of service as the analysis measured it (see
	 * {@code results.ServiceIndicators}); percentages and delays are NaN where
	 * there was nothing to measure. Absent in runs analysed before it was recorded.
	 */
	public record Indicators(int tripsScheduled, int tripsCompleted, int tripsInterrupted, int tripsNeverDeparted,
			double regularityPercent, int stopsPlanned, int stopsServed, double punctualityAtDestinationPercent,
			double punctualityAtStopsPercent, double meanDelay, double meanDeviation, double medianDelay,
			double p95Delay) {
	}

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

	/**
	 * The costs of the timetable as planned.
	 *
	 * @param simulated the costs of the day as it was simulated; empty for a run analysed before they existed
	 */
	public record Costs(String currency, Map<String, Double> byCategory, double trainKm, double trainHours,
			int fleetSize, double total, Optional<Costs> simulated) {
	}

	public static final String DELAY_HISTOGRAM = "delay_histogram";
	public static final String DELAY_BY_HOUR = "delay_by_hour";
	public static final String TRAINS_RUNNING = "trains_running";
	public static final String SPACE_TIME = "space_time";
	public static final String COST_BREAKDOWN = "cost_breakdown";

	public static RunResults load(Path dir) {
		return new RunResults(dir,
			optional(dir.resolve("punctuality_by_line.csv"), RunResults::readByLine),
			optional(dir.resolve("punctuality.csv"), RunResults::readVisits),
			optional(dir.resolve("unfinished.csv"), RunResults::readUnfinished),
			optional(dir.resolve("costs.json"), RunResults::readCosts),
			optional(dir.resolve("indicators.json"), RunResults::readIndicators),
			optional(dir.resolve("indicators_by_line.csv"), RunResults::readIndicatorsByLine),
			optional(dir.resolve("stations.csv"), RunResults::readStations),
			optional(dir.resolve("stations_hourly.csv"), RunResults::readStationsHourly),
			optional(dir.resolve("trains.csv"), RunResults::readTrains),
			optional(dir.resolve("energy.json"), RunResults::readEnergy),
			optional(dir.resolve("energy_by_line.csv"), RunResults::readEnergyByLine));
	}

	private static Energy readEnergy(Path json) {
		try {
			JsonNode root = new ObjectMapper().readTree(json.toFile());
			JsonNode electric = root.path("electric");
			JsonNode diesel = root.path("diesel");
			JsonNode peak = root.path("peakMinute");
			return new Energy(electric.path("drawnFromSubstationsKilowattHours").asDouble(),
				electric.path("demandWithoutRecoveryKilowattHours").asDouble(),
				electric.path("regeneratedKilowattHours").asDouble(), electric.path("reusedByOtherTrainsKilowattHours").asDouble(),
				electric.path("lostInBrakingKilowattHours").asDouble(), electric.path("trainKilometres").asDouble(),
				electric.path("kilowattHoursPerTrainKilometre").asDouble(), electric.path("kilowattHoursPerTonneKilometre").asDouble(),
				electric.path("kilowattHoursPerTonneKilometreWithoutRecovery").asDouble(), diesel.path("litres").asDouble(),
				diesel.path("trainKilometres").asDouble(), diesel.path("litresPerTrainKilometre").asDouble(),
				peak.path("minuteOfDay").asInt(-1), peak.path("lineKilowatt").asDouble(), peak.path("trainsInService").asInt());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + json, e);
		}
	}

	private static List<EnergyLineRow> readEnergyByLine(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new EnergyLineRow(row.get("line"), row.get("traction"),
			number(row.get("train_km")), number(row.get("drawn_kwh")), number(row.get("regenerated_kwh")),
			number(row.get("lost_kwh")), number(row.get("litres")), number(row.get("kwh_per_train_km")),
			number(row.get("litres_per_train_km")))).toList();
	}

	private static List<StationRow> readStations(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new StationRow(row.get("station"),
			Integer.parseInt(row.get("observations")), number(row.get("mean_delay_s")), number(row.get("p95_delay_s")),
			number(row.get("max_delay_s")), number(row.get("late_pct")))).toList();
	}

	private static List<StationHourRow> readStationsHourly(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new StationHourRow(row.get("station"), row.get("direction"),
			Integer.parseInt(row.get("hour")), Integer.parseInt(row.get("trains_planned")),
			Integer.parseInt(row.get("trains_called")), number(row.get("mean_delay_s")), number(row.get("p95_delay_s")),
			number(row.get("punctuality_pct")))).toList();
	}

	private static List<TrainRow> readTrains(Path csv) {
		return CsvTable.read(csv).stream().map(row -> new TrainRow(row.get("vehicle"), row.get("line"),
			Integer.parseInt(row.get("stops")), number(row.get("mean_delay_s")), number(row.get("max_delay_s")),
			number(row.get("final_delay_s")))).toList();
	}

	private static Indicators readIndicators(Path json) {
		try {
			JsonNode root = new ObjectMapper().readTree(json.toFile());
			return new Indicators(root.path("tripsScheduled").asInt(), root.path("tripsCompleted").asInt(),
				root.path("tripsInterrupted").asInt(), root.path("tripsNeverDeparted").asInt(),
				measured(root, "regularityPercent"), root.path("stopsPlanned").asInt(),
				root.path("stopsServed").asInt(), measured(root, "punctualityAtDestinationPercent"),
				measured(root, "punctualityAtStopsPercent"), measured(root, "meanDelaySeconds"),
				measured(root, "meanDeviationSeconds"), measured(root, "medianDelaySeconds"),
				measured(root, "p95DelaySeconds"));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + json, e);
		}
	}

	/** An indicator with nothing to measure is written as null. */
	private static double measured(JsonNode root, String field) {
		return root.hasNonNull(field) ? root.get(field).asDouble() : Double.NaN;
	}

	private static Map<String, Indicators> readIndicatorsByLine(Path csv) {
		Map<String, Indicators> byLine = new LinkedHashMap<>();
		for (Map<String, String> row : CsvTable.read(csv)) {
			byLine.put(row.get("line"), new Indicators(Integer.parseInt(row.get("trips_scheduled")),
				Integer.parseInt(row.get("trips_completed")), Integer.parseInt(row.get("trips_interrupted")),
				Integer.parseInt(row.get("trips_never_departed")), number(row.get("regularity_pct")),
				Integer.parseInt(row.get("stops_planned")), Integer.parseInt(row.get("stops_served")),
				number(row.get("punctuality_destination_pct")), number(row.get("punctuality_stops_pct")),
				number(row.get("mean_delay_s")), number(row.get("mean_deviation_s")),
				number(row.get("median_delay_s")), number(row.get("p95_delay_s"))));
		}
		return byLine;
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
			return costs(new ObjectMapper().readTree(json.toFile()));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + json, e);
		}
	}

	private static Costs costs(JsonNode node) {
		Map<String, Double> byCategory = new LinkedHashMap<>();
		node.path("byCategory").properties().forEach(field -> byCategory.put(field.getKey(), field.getValue().asDouble()));
		Optional<Costs> simulated = node.has("simulated") ? Optional.of(costs(node.get("simulated"))) : Optional.empty();
		return new Costs(node.path("currency").asText("EUR"), byCategory, node.path("trainKm").asDouble(),
			node.path("trainHours").asDouble(), node.path("fleetSize").asInt(), node.path("total").asDouble(), simulated);
	}

	private static double number(String value) {
		return value == null || value.isBlank() ? Double.NaN : Double.parseDouble(value);
	}
}
