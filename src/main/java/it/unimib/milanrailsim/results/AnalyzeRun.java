package it.unimib.milanrailsim.results;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Analyzes a finished simulation run and archives everything the report and
 * the GUI need: punctuality tables, cost breakdown, charts, manifest and a
 * copy of the raw outputs.
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

	static Path run(Path runOutputDir, String scenario, Path runsBaseDir, Path costsFile,
			String spaceTimeLine) {
		return analyze(new Request(runOutputDir, RunArchive.create(runsBaseDir, scenario), scenario, costsFile,
			spaceTimeLine, null, log::info));
	}

	/**
	 * What to analyze and where to put it.
	 *
	 * @param outcome  how the engine saw the run end, or null when analyzing the
	 *                 outputs of a run this process did not drive
	 * @param progress told the name of each phase as it starts
	 */
	public record Request(Path runOutputDir, RunArchive archive, String scenario, Path costsFile,
			String spaceTimeLine, RunOutcome outcome, Consumer<String> progress) {
	}

	/** Runs the analysis of a finished simulation and fills the archive; returns its folder. */
	public static Path analyze(Request request) {
		Path runOutputDir = request.runOutputDir();
		RunArchive archive = request.archive();
		request.progress().accept("Analisi: lettura dell'orario simulato");
		Scenario matsim = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(matsim)
			.readFile(locate(runOutputDir, "*.output_transitSchedule.xml*").toString());
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles)
			.readFile(locate(runOutputDir, "*.output_transitVehicles.xml*").toString());
		Network network = NetworkUtils
			.readNetwork(locate(runOutputDir, "*.output_network.xml*").toString());

		request.progress().accept("Analisi: lettura degli eventi");
		PunctualityAnalysis punctuality = new PunctualityAnalysis(matsim.getTransitSchedule());
		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler(punctuality);
		new MatsimEventsReader(events)
			.readFile(locate(runOutputDir.resolve("ITERS/it.0"), "*.events.xml*").toString());
		List<PunctualityAnalysis.StopVisit> visits = punctuality.visits();
		List<PunctualityAnalysis.Unfinished> unfinished = punctuality.unfinished();

		request.progress().accept("Analisi: costi");
		CostModel.Breakdown costs = new CostModel(matsim.getTransitSchedule(), vehicles, network,
			CostParameters.load(request.costsFile())).compute();

		request.progress().accept("Analisi: grafici");
		archive.writeLines("punctuality.csv", punctualityCsv(visits));
		archive.writeLines("punctuality_by_line.csv", byLineCsv(visits));
		archive.writeLines("unfinished.csv", unfinishedCsv(unfinished));
		archive.writeJson("costs.json", costsJson(costs));
		RunCharts.delayHistogram(visits, archive.chart("delay_histogram"));
		RunCharts.delayByHour(visits, archive.chart("delay_by_hour"));
		RunCharts.spaceTime(trajectories(runOutputDir, request.spaceTimeLine()), archive.chart("space_time"));
		RunCharts.costBreakdown(costs, archive.chart("cost_breakdown"));

		request.progress().accept("Archiviazione");
		archive.copyRaw(runOutputDir);
		archive.writeJson("manifest.json", manifest(request, visits, punctuality.anomalyCount(), unfinished.size(), costs));

		logSummary(visits, punctuality.anomalyCount(), unfinished.size(), archive.dir());
		return archive.dir();
	}

	private static Path locate(Path dir, String glob) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(file -> dir.getFileSystem()
					.getPathMatcher("glob:" + glob).matches(file.getFileName()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
					"No file matching " + glob + " in " + dir));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list " + dir, e);
		}
	}

	private static List<String> punctualityCsv(List<PunctualityAnalysis.StopVisit> visits) {
		List<String> lines = new ArrayList<>();
		lines.add("vehicle,line,route,stop,planned_arrival_s,actual_arrival_s,arrival_delay_s,"
			+ "planned_departure_s,actual_departure_s,departure_delay_s");
		for (PunctualityAnalysis.StopVisit visit : visits) {
			lines.add(String.join(",", visit.vehicle(), visit.line(), visit.route(), visit.stop(),
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
		manifest.put("sourceOutput", request.runOutputDir().toAbsolutePath().toString());
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
	private static Map<String, List<double[]>> trajectories(Path runOutputDir, String line) {
		Path csv = locate(runOutputDir.resolve("ITERS/it.0"), "*railsimTimeDistance.csv*");
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
