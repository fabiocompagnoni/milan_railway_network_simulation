package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.network.FleetConfig;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Report charts of a run, rendered headless to PNG (Italian labels). */
public final class RunCharts {

	private static final int WIDTH = 1200;
	private static final int HEIGHT = 700;
	private static final int EARLIEST_MINUTE = -5;
	private static final int LATEST_MINUTE = 30;
	/** Colours told apart by colour-blind readers too (Okabe and Ito), one per train type. */
	private static final Color[] TYPE_COLOURS = { new Color(0x0072B2), new Color(0xE69F00), new Color(0x009E73),
		new Color(0x56B4E9), new Color(0xD55E00), new Color(0xCC79A7), new Color(0x999999), new Color(0xF0E442),
		new Color(0x000000), new Color(0x8C510A) };

	private RunCharts() {
	}

	/** Arrivals at a stop by minute of delay, from 5 minutes early to 30 late, with one class for each tail. */
	public static void delayHistogram(List<PunctualityAnalysis.StopVisit> visits, Path png) {
		DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		delayClasses(visits).forEach((minute, arrivals) -> dataset.addValue(arrivals, "arrivi", minute));
		JFreeChart chart = ChartFactory.createBarChart("Distribuzione dei ritardi all'arrivo in fermata",
			"ritardo [min] (negativo = anticipo)", "numero di arrivi", dataset, PlotOrientation.VERTICAL, false, false, false);
		// the labels of the two tails are wider than their bar and would be cut to an ellipsis
		chart.getCategoryPlot().getDomainAxis().setMaximumCategoryLabelWidthRatio(3);
		save(chart, png);
	}

	/**
	 * The arrivals in each class of delay, in order: below {@value #EARLIEST_MINUTE} minutes, one class per
	 * minute (a class holds the delays from its minute up to the next), {@value #LATEST_MINUTE} minutes and over.
	 */
	static Map<String, Integer> delayClasses(List<PunctualityAnalysis.StopVisit> visits) {
		Map<String, Integer> classes = new LinkedHashMap<>();
		String below = "< " + signed(EARLIEST_MINUTE);
		String over = "≥ " + LATEST_MINUTE;
		classes.put(below, 0);
		for (int minute = EARLIEST_MINUTE; minute < LATEST_MINUTE; minute++) {
			classes.put(signed(minute), 0);
		}
		classes.put(over, 0);
		for (PunctualityAnalysis.StopVisit visit : visits) {
			int minute = (int) Math.floor(visit.arrivalDelaySeconds() / 60);
			classes.merge(minute < EARLIEST_MINUTE ? below : minute >= LATEST_MINUTE ? over : signed(minute), 1, Integer::sum);
		}
		return classes;
	}

	private static String signed(int minute) {
		return minute < 0 ? "−" + -minute : String.valueOf(minute);
	}

	public static void delayByHour(List<PunctualityAnalysis.StopVisit> visits, Path png) {
		DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		meanDelayMinutesByPlannedHour(visits).forEach((hour, minutes) -> dataset.addValue(minutes, "ritardo medio", hour));
		JFreeChart chart = ChartFactory.createBarChart("Ritardo medio all'arrivo in fermata, per ora prevista",
			"ora prevista (24 e oltre: dopo la mezzanotte del giorno di servizio)", "ritardo medio [min], anticipo = 0",
			dataset, PlotOrientation.VERTICAL, false, false, false);
		save(chart, png);
	}

	/** Minutes of delay, an early arrival counting as zero, by the hour the arrival was planned in. */
	static Map<Integer, Double> meanDelayMinutesByPlannedHour(List<PunctualityAnalysis.StopVisit> visits) {
		Map<Integer, double[]> sumAndCountByHour = new TreeMap<>();
		for (PunctualityAnalysis.StopVisit visit : visits) {
			double[] aggregate = sumAndCountByHour.computeIfAbsent((int) (visit.plannedArrival() / 3600), key -> new double[2]);
			aggregate[0] += Math.max(0, visit.arrivalDelaySeconds());
			aggregate[1]++;
		}
		Map<Integer, Double> minutes = new TreeMap<>();
		sumAndCountByHour.forEach((hour, aggregate) -> minutes.put(hour, aggregate[0] / aggregate[1] / 60));
		return minutes;
	}

	/** Trips in progress through the day, minute by minute. */
	public static void trainsRunning(List<PunctualityAnalysis.TripOutcome> trips, Path png) {
		XYSeries series = new XYSeries("corse in circolazione");
		int[] running = tripsRunningByMinute(trips);
		for (int minute = 0; minute < running.length; minute++) {
			series.add(minute / 60.0, running[minute]);
		}
		JFreeChart chart = ChartFactory.createXYLineChart("Corse in corso nel giorno",
			"ora", "corse in corso", new XYSeriesCollection(series), PlotOrientation.VERTICAL, false, false, false);
		save(chart, png);
	}

	/**
	 * How many completed trips are between their planned departure and their
	 * actual arrival at each minute since midnight; trips that did not
	 * complete have no arrival and are left out.
	 */
	static int[] tripsRunningByMinute(List<PunctualityAnalysis.TripOutcome> trips) {
		List<PunctualityAnalysis.TripOutcome> completed = trips.stream()
			.filter(trip -> trip.status() == PunctualityAnalysis.TripStatus.COMPLETED).toList();
		int lastMinute = completed.stream().mapToInt(trip -> (int) (trip.actualArrival() / 60)).max().orElse(0);
		int[] running = new int[lastMinute + 2];
		for (PunctualityAnalysis.TripOutcome trip : completed) {
			for (int minute = (int) (trip.plannedDeparture() / 60); minute <= (int) (trip.actualArrival() / 60); minute++) {
				running[minute]++;
			}
		}
		return running;
	}

	/** One polyline per train, named in the legend: x = time [s], y = distance along the segment [m]. */
	public static void spaceTime(Map<String, List<double[]>> trajectoriesByTrain, Path png) {
		XYSeriesCollection dataset = new XYSeriesCollection();
		trajectoriesByTrain.forEach((train, points) -> {
			XYSeries series = new XYSeries(train);
			points.forEach(point -> series.add(point[0] / 3600, point[1] / 1000));
			dataset.addSeries(series);
		});
		JFreeChart chart = ChartFactory.createXYLineChart("Diagramma spazio-tempo",
			"ora", "distanza [km]", dataset, PlotOrientation.VERTICAL, true, false, false);
		save(chart, png);
	}

	public static void costBreakdown(CostModel.Breakdown breakdown, Path png) {
		DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		new TreeMap<>(breakdown.byCategory())
			.forEach((category, cost) -> dataset.addValue(cost, "costo", category));
		JFreeChart chart = ChartFactory.createBarChart(
			"Costi per categoria [" + breakdown.currency() + "]",
			"categoria", breakdown.currency(), dataset, PlotOrientation.VERTICAL, false, false, false);
		save(chart, png);
	}

	/** Power drawn from the substations and braking power reused, minute by minute. */
	public static void powerProfile(List<EnergyLedger.Minute> profile, Path png) {
		XYSeries drawn = new XYSeries("assorbita dalla linea");
		XYSeries recovered = new XYSeries("recuperata in frenata");
		for (EnergyLedger.Minute minute : profile) {
			drawn.add(minute.minuteOfDay() / 60.0, minute.lineKilowatt() / 1000);
			recovered.add(minute.minuteOfDay() / 60.0, minute.recoveredKilowatt() / 1000);
		}
		XYSeriesCollection dataset = new XYSeriesCollection();
		dataset.addSeries(drawn);
		dataset.addSeries(recovered);
		JFreeChart chart = ChartFactory.createXYLineChart("Potenza elettrica nel giorno (media al minuto)",
			"ora", "potenza [MW]", dataset, PlotOrientation.VERTICAL, true, false, false);
		save(chart, png);
	}

	/**
	 * The trains running at the busiest instant of each hour, stacked by type.
	 *
	 * @param typeNames name of each vehicle type by id
	 */
	public static void trainsByHour(FleetUse fleet, Map<String, String> typeNames, Path png) {
		List<String> types = inCatalogueOrder(fleet.byType().keySet());
		Map<Integer, FleetUse.Hour> byHour = new TreeMap<>();
		fleet.hours().forEach(hour -> byHour.put(hour.hour(), hour));
		DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		if (!byHour.isEmpty()) {
			int first = byHour.keySet().iterator().next();
			int last = fleet.hours().getLast().hour();
			// hours with no train keep their place on the axis
			for (int hour = first; hour <= last; hour++) {
				FleetUse.Hour counted = byHour.get(hour);
				for (String type : types) {
					dataset.addValue(counted == null ? 0 : counted.peakByType().getOrDefault(type, 0),
						typeNames.getOrDefault(type, type), Integer.valueOf(hour));
				}
			}
		}
		JFreeChart chart = ChartFactory.createStackedBarChart("Treni in circolo per ora, per tipo",
			"ora (24 e oltre: dopo la mezzanotte del giorno di servizio)", "treni in corsa nello stesso istante, massimo dell'ora",
			dataset, PlotOrientation.VERTICAL, true, false, false);
		paintByType(chart, types);
		save(chart, png);
	}

	/** The trains the day used, one bar per type. */
	public static void fleetByType(FleetUse fleet, Map<String, String> typeNames, Path png) {
		fleetAgainstReference(Map.of(), fleet.byType(), typeNames, png);
	}

	/**
	 * The trains a run used next to those of its reference day, by type.
	 *
	 * @param reference trains of the reference day by type id; empty to chart the run alone
	 * @param run       trains of the run by type id
	 */
	public static void fleetAgainstReference(Map<String, Integer> reference, Map<String, Integer> run,
			Map<String, String> typeNames, Path png) {
		Set<String> present = new TreeSet<>(run.keySet());
		present.addAll(reference.keySet());
		DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		for (String type : inCatalogueOrder(present)) {
			String name = typeNames.getOrDefault(type, type);
			if (!reference.isEmpty()) {
				dataset.addValue(reference.getOrDefault(type, 0), "giorno reale di riferimento", name);
			}
			dataset.addValue(run.getOrDefault(type, 0), "questo run", name);
		}
		JFreeChart chart = ChartFactory.createBarChart("Treni utilizzati, per tipo", "tipo di treno", "treni",
			dataset, PlotOrientation.VERTICAL, !reference.isEmpty(), false, false);
		chart.getCategoryPlot().getDomainAxis().setCategoryLabelPositions(CategoryLabelPositions.UP_45);
		save(chart, png);
	}

	/** The types in the order of the rolling stock catalogue, then any other in alphabetical order. */
	private static List<String> inCatalogueOrder(Set<String> types) {
		List<String> ordered = new ArrayList<>();
		FleetConfig.defaults().types().stream().map(FleetConfig.TrainType::id).filter(types::contains).forEach(ordered::add);
		new TreeSet<>(types).stream().filter(type -> !ordered.contains(type)).forEach(ordered::add);
		return ordered;
	}

	/** A type keeps its colour in every chart, whichever types the run has. */
	private static void paintByType(JFreeChart chart, List<String> types) {
		List<String> catalogue = FleetConfig.defaults().types().stream().map(FleetConfig.TrainType::id).toList();
		for (int series = 0; series < types.size(); series++) {
			int index = catalogue.indexOf(types.get(series));
			chart.getCategoryPlot().getRenderer().setSeriesPaint(series,
				TYPE_COLOURS[(index < 0 ? catalogue.size() + series : index) % TYPE_COLOURS.length]);
		}
	}

	private static void save(JFreeChart chart, Path png) {
		try {
			ChartUtils.saveChartAsPNG(png.toFile(), chart, WIDTH, HEIGHT);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write chart " + png, e);
		}
	}
}
