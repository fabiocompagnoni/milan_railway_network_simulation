package it.unimib.milanrailsim.results;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Analyzes a finished simulation run and archives everything the report and
 * the GUI need: punctuality tables, cost breakdown, charts and manifest.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.results.AnalyzeRun}
 * with optional args {@code [runOutputDir] [scenario] [runsBaseDir] [costsFile] [spaceTimeLine]}.
 */
public final class AnalyzeRun {

	private static final Logger log = LogManager.getLogger(AnalyzeRun.class);

	private static final String DEFAULT_OUTPUT = "output/milan-baseline";
	private static final String DEFAULT_SCENARIO = "baseline";
	private static final String DEFAULT_RUNS_BASE = System.getProperty("user.home") + "/MilanRailSim/runs";
	private static final String DEFAULT_COSTS = "config/costs.json";
	private static final String DEFAULT_SPACE_TIME_LINE = "S1";

	private AnalyzeRun() {
	}

	public static void main(String[] args) {
		run(Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT),
			args.length > 1 ? args[1] : DEFAULT_SCENARIO,
			Path.of(args.length > 2 ? args[2] : DEFAULT_RUNS_BASE),
			Path.of(args.length > 3 ? args[3] : DEFAULT_COSTS),
			args.length > 4 ? args[4] : DEFAULT_SPACE_TIME_LINE);
	}

	static Path run(Path runOutputDir, String scenario, Path runsBaseDir, Path costsFile, String spaceTimeLine) {
		log.info("Reading the run outputs in {}", runOutputDir);
		return analyze(new Request(RunData.fromOutput(runOutputDir), RunArchive.create(runsBaseDir, scenario), scenario,
			costsFile, spaceTimeLine, null, log::info));
	}

	/**
	 * What to analyze and where to put it.
	 *
	 * @param outcome  how the engine saw the run end, or null when analyzing the
	 *                 outputs of a run this process did not drive
	 * @param progress told the name of each phase as it starts
	 */
	public record Request(RunData data, RunArchive archive, String scenario, Path costsFile,
			String spaceTimeLine, RunOutcome outcome, Consumer<String> progress) {
	}

	/** Runs the analysis of a finished simulation and fills the archive; returns its folder. */
	public static Path analyze(Request request) {
		RunData data = request.data();
		RunArchive archive = request.archive();
		request.progress().accept("Analisi: puntualità");
		List<PunctualityAnalysis.StopVisit> visits = data.punctuality().visits();
		List<PunctualityAnalysis.Unfinished> unfinished = data.punctuality().unfinished();
		List<PunctualityAnalysis.TripOutcome> trips = data.punctuality().trips();
		ServiceIndicators indicators = ServiceIndicators.of(trips, visits, ServiceIndicators.ON_TIME_THRESHOLD_SECONDS);
		archive.writeLines("punctuality.csv", punctualityCsv(visits));
		archive.writeLines("punctuality_by_line.csv", byLineCsv(visits));
		archive.writeLines("trips.csv", tripsCsv(trips, data.energy().map(EnergyLedger.Use::byTrip).orElse(Map.of())));
		archive.writeLines("indicators_by_line.csv", indicatorsByLineCsv(trips, visits));
		archive.writeJson("indicators.json", indicatorsJson(indicators));
		archive.writeLines("stations.csv", stationsCsv(visits));
		archive.writeLines("stations_hourly.csv", stationsHourlyCsv(data.punctuality().plannedCalls(), visits));
		archive.writeLines("trains.csv", trainsCsv(visits));
		archive.writeLines("unfinished.csv", unfinishedCsv(unfinished));

		data.energy().ifPresent(energy -> {
			request.progress().accept("Analisi: energia");
			archive.writeJson("energy.json", EnergyReport.json(energy));
			archive.writeLines("energy_by_line.csv", EnergyReport.byLineCsv(energy));
			archive.writeLines("power_profile.csv", EnergyReport.profileCsv(energy));
		});

		request.progress().accept("Analisi: costi");
		CostModel.Breakdown costs = new CostModel(data.schedule(), data.vehicles(), data.network(),
			CostParameters.load(request.costsFile())).compute();
		archive.writeJson("costs.json", costsJson(costs));

		request.progress().accept("Analisi: grafici");
		RunCharts.delayHistogram(visits, archive.chart("delay_histogram"));
		RunCharts.delayByHour(visits, archive.chart("delay_by_hour"));
		RunCharts.spaceTime(trajectories(data.timeDistanceCsv(), request.spaceTimeLine()), archive.chart("space_time"));
		RunCharts.costBreakdown(costs, archive.chart("cost_breakdown"));

		request.progress().accept("Archiviazione");
		int anomalies = data.punctuality().anomalyCount();
		archive.writeJson("manifest.json", manifest(request, visits, anomalies, unfinished.size(), costs));

		logSummary(visits, anomalies, unfinished.size(), archive.dir());
		return archive.dir();
	}

	private static List<String> punctualityCsv(List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("vehicle,line,route,trip,stop_sequence,destination,stop,planned_arrival_s,actual_arrival_s,"
			+ "arrival_delay_s,planned_departure_s,actual_departure_s,departure_delay_s");
		for (PunctualityAnalysis.StopVisit visit : visits) {
			lines.add(String.join(",", visit.vehicle(), visit.line(), visit.route(), visit.trip(),
				Integer.toString(visit.stopSequence()), visit.destination(), visit.stop(),
				format(visit.plannedArrival()), format(visit.actualArrival()),
				format(visit.arrivalDelaySeconds()),
				format(visit.plannedDeparture()), format(visit.actualDeparture()),
				format(visit.departureDelaySeconds())));
		}
		return lines;
	}

	private static List<String> byLineCsv(List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("line,observations,mean_delay_s,median_delay_s,p95_delay_s,max_delay_s");
		for (LineSummaries.LineSummary summary : LineSummaries.of(visits)) {
			lines.add(String.join(",", summary.line(), Integer.toString(summary.observations()),
				format(summary.meanDelaySeconds()), format(summary.medianDelaySeconds()),
				format(summary.p95DelaySeconds()), format(summary.maxDelaySeconds())));
		}
		return lines;
	}

	/** @param energy the energy of the trips that were measured, by trip id; the columns stay empty for the others */
	private static List<String> tripsCsv(List<PunctualityAnalysis.TripOutcome> trips,
			Map<String, EnergyLedger.TripEnergy> energy) {
		List<String> lines = new ArrayList<>();
		lines.add("trip,line,route,vehicle,origin,destination,planned_departure_s,planned_arrival_s,"
			+ "actual_arrival_s,arrival_delay_s,stops_planned,stops_served,status,km,drawn_kwh,regenerated_kwh,litres");
		for (PunctualityAnalysis.TripOutcome trip : trips) {
			EnergyLedger.TripEnergy used = energy.get(trip.trip());
			lines.add(String.join(",", trip.trip(), trip.line(), trip.route(), trip.vehicle(), trip.origin(),
				trip.destination(), format(trip.plannedDeparture()), format(trip.plannedArrival()),
				format(trip.actualArrival()), format(trip.arrivalDelaySeconds()),
				Integer.toString(trip.stopsPlanned()), Integer.toString(trip.stopsServed()),
				trip.status().name().toLowerCase(java.util.Locale.ROOT),
				used == null ? "" : EnergyReport.number(used.kilometres()),
				used == null || !used.electric() ? "" : EnergyReport.number(used.drawnKilowattHours()),
				used == null || !used.electric() ? "" : EnergyReport.number(used.regeneratedKilowattHours()),
				used == null || used.electric() ? "" : EnergyReport.number(used.litres())));
		}
		return lines;
	}

	private static List<String> indicatorsByLineCsv(List<PunctualityAnalysis.TripOutcome> trips,
			List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("line,trips_scheduled,trips_completed,trips_interrupted,trips_never_departed,regularity_pct,"
			+ "stops_planned,stops_served,punctuality_destination_pct,punctuality_stops_pct,mean_delay_s,"
			+ "mean_deviation_s,median_delay_s,p95_delay_s");
		ServiceIndicators.byLine(trips, visits, ServiceIndicators.ON_TIME_THRESHOLD_SECONDS).forEach((line, of) ->
			lines.add(String.join(",", line, Integer.toString(of.tripsScheduled()),
				Integer.toString(of.tripsCompleted()), Integer.toString(of.tripsInterrupted()),
				Integer.toString(of.tripsNeverDeparted()), decimal(of.regularityPercent()),
				Integer.toString(of.stopsPlanned()), Integer.toString(of.stopsServed()),
				decimal(of.punctualityAtDestinationPercent()), decimal(of.punctualityAtStopsPercent()),
				format(of.meanDelaySeconds()), format(of.meanDeviationSeconds()),
				format(of.medianDelaySeconds()), format(of.p95DelaySeconds()))));
		return lines;
	}

	private static List<String> stationsCsv(List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("station,observations,mean_delay_s,p95_delay_s,max_delay_s,late_pct");
		for (StationTables.StationRow row : StationTables.byStation(visits, ServiceIndicators.ON_TIME_THRESHOLD_SECONDS)) {
			lines.add(String.join(",", row.station(), Integer.toString(row.observations()),
				format(row.meanDelaySeconds()), format(row.p95DelaySeconds()), format(row.maxDelaySeconds()),
				decimal(row.latePercent())));
		}
		return lines;
	}

	private static List<String> stationsHourlyCsv(List<PunctualityAnalysis.PlannedCall> planned,
			List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("station,direction,hour,trains_planned,trains_called,mean_delay_s,p95_delay_s,punctuality_pct");
		for (StationTables.HourRow row : StationTables.hourly(planned, visits, ServiceIndicators.ON_TIME_THRESHOLD_SECONDS)) {
			lines.add(String.join(",", row.station(), row.direction(), Integer.toString(row.hour()),
				Integer.toString(row.trainsPlanned()), Integer.toString(row.trainsCalled()),
				format(row.meanDelaySeconds()), format(row.p95DelaySeconds()), decimal(row.punctualityPercent())));
		}
		return lines;
	}

	private static List<String> trainsCsv(List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("vehicle,line,stops,mean_delay_s,max_delay_s,final_delay_s");
		for (StationTables.TrainRow row : StationTables.byTrain(visits)) {
			lines.add(String.join(",", row.vehicle(), row.line(), Integer.toString(row.stops()),
				format(row.meanDelaySeconds()), format(row.maxDelaySeconds()), format(row.finalDelaySeconds())));
		}
		return lines;
	}

	private static Map<String, Object> indicatorsJson(ServiceIndicators indicators) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("onTimeThresholdSeconds", ServiceIndicators.ON_TIME_THRESHOLD_SECONDS);
		json.put("tripsScheduled", indicators.tripsScheduled());
		json.put("tripsCompleted", indicators.tripsCompleted());
		json.put("tripsInterrupted", indicators.tripsInterrupted());
		json.put("tripsNeverDeparted", indicators.tripsNeverDeparted());
		json.put("regularityPercent", number(indicators.regularityPercent()));
		json.put("stopsPlanned", indicators.stopsPlanned());
		json.put("stopsServed", indicators.stopsServed());
		json.put("punctualityAtDestinationPercent", number(indicators.punctualityAtDestinationPercent()));
		json.put("punctualityAtStopsPercent", number(indicators.punctualityAtStopsPercent()));
		json.put("meanDelaySeconds", number(indicators.meanDelaySeconds()));
		json.put("meanDeviationSeconds", number(indicators.meanDeviationSeconds()));
		json.put("medianDelaySeconds", number(indicators.medianDelaySeconds()));
		json.put("p95DelaySeconds", number(indicators.p95DelaySeconds()));
		return json;
	}

	/** JSON has no NaN: an indicator with nothing to measure is written as null. */
	private static Double number(double value) {
		return Double.isNaN(value) ? null : value;
	}

	private static String decimal(double value) {
		return Double.isNaN(value) ? "" : String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static List<String> unfinishedCsv(List<PunctualityAnalysis.Unfinished> unfinished) {
		List<String> lines = new ArrayList<>();
		lines.add("vehicle,line,route,last_stop,remaining_stops");
		for (PunctualityAnalysis.Unfinished train : unfinished) {
			lines.add(String.join(",", train.vehicle(), train.line(), train.route(), train.lastStop(),
				Integer.toString(train.remainingStops())));
		}
		return lines;
	}

	private static Map<String, Object> costsJson(CostModel.Breakdown costs) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("currency", costs.currency());
		json.put("byCategory", costs.byCategory());
		json.put("trainKm", costs.trainKm());
		json.put("trainHours", costs.trainHours());
		json.put("fleetSize", costs.fleetSize());
		json.put("total", costs.byCategory().values().stream().mapToDouble(Double::doubleValue).sum());
		return json;
	}

	private static Map<String, Object> manifest(Request request, List<PunctualityAnalysis.StopVisit> visits,
			int anomalies, int unfinished, CostModel.Breakdown costs) {
		double meanDelay = visits.stream()
			.mapToDouble(PunctualityAnalysis.StopVisit::arrivalDelaySeconds).average().orElse(0);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("scenario", request.scenario());
		manifest.put("created", java.time.LocalDateTime.now().toString());
		manifest.put("sourceOutput", request.data().outputDir().toAbsolutePath().toString());
		manifest.put("stopVisits", visits.size());
		manifest.put("anomalies", anomalies);
		manifest.put("unfinishedTrains", unfinished);
		manifest.put("meanArrivalDelaySeconds", meanDelay);
		manifest.put("totalCost", costsJson(costs).get("total"));
		manifest.put("currency", costs.currency());
		RunOutcome outcome = request.outcome();
		if (outcome != null) {
			manifest.put("trainsArrived", outcome.arrived());
			manifest.put("trainsAborted", outcome.aborted());
			manifest.put("trainsStalled", outcome.stalled());
			manifest.put("simulatedEndSeconds", outcome.simulatedEndSeconds());
			manifest.put("wallClockSeconds", outcome.wallClock().toSeconds());
		}
		return manifest;
	}

	/** Time-distance polylines of the requested line, from the railsim CSV. */
	private static Map<String, List<double[]>> trajectories(Path csv, String line) {
		Map<String, List<double[]>> byTrain = new TreeMap<>();
		try (BufferedReader reader = IOUtils.getBufferedReader(csv.toString())) {
			String header = reader.readLine();
			if (header == null || !header.startsWith("vehicle_id,line_id")) {
				throw new IllegalArgumentException("Unexpected time-distance format in " + csv);
			}
			String row;
			while ((row = reader.readLine()) != null) {
				String[] fields = row.split(",");
				if (!fields[1].equals(line)) {
					continue;
				}
				byTrain.computeIfAbsent(fields[0], key -> new ArrayList<>())
					.add(new double[] { Double.parseDouble(fields[4]), Double.parseDouble(fields[5]) });
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + csv, e);
		}
		return byTrain;
	}

	private static void logSummary(List<PunctualityAnalysis.StopVisit> visits, int anomalies, int unfinished,
			Path archived) {
		log.info("Archived {} stop visits ({} anomalies, {} trains unfinished) to {}", visits.size(), anomalies,
			unfinished, archived);
		visits.stream()
			.sorted(Comparator.comparingDouble(PunctualityAnalysis.StopVisit::arrivalDelaySeconds)
				.reversed())
			.limit(10)
			.forEach(visit -> log.info("worst arrivals: {} ({}) at {} late by {} s",
				visit.vehicle(), visit.line(), visit.stop(), (long) visit.arrivalDelaySeconds()));
	}

	private static String format(double value) {
		return Double.isNaN(value) ? "" : String.format(java.util.Locale.ROOT, "%.0f", value);
	}
}
