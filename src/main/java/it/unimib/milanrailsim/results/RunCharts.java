package it.unimib.milanrailsim.results;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Report charts of a run, rendered headless to PNG (Italian labels). */
public final class RunCharts {

	private static final int WIDTH = 1200;
	private static final int HEIGHT = 700;

	private RunCharts() {
	}

	public static void delayHistogram(List<PunctualityAnalysis.StopVisit> visits, Path png) {
		HistogramDataset dataset = new HistogramDataset();
		double[] delays = visits.stream()
			.mapToDouble(PunctualityAnalysis.StopVisit::arrivalDelaySeconds).toArray();
		dataset.addSeries("arrivi", delays, 40);
		JFreeChart chart = ChartFactory.createHistogram("Distribuzione dei ritardi all'arrivo",
			"ritardo [s]", "numero di arrivi", dataset, PlotOrientation.VERTICAL, false, false, false);
		save(chart, png);
	}

	public static void delayByHour(List<PunctualityAnalysis.StopVisit> visits, Path png) {
		Map<Integer, double[]> sumAndCountByHour = new TreeMap<>();
		for (PunctualityAnalysis.StopVisit visit : visits) {
			int hour = (int) (visit.actualArrival() / 3600);
			double[] aggregate = sumAndCountByHour.computeIfAbsent(hour, key -> new double[2]);
			aggregate[0] += visit.arrivalDelaySeconds();
			aggregate[1]++;
		}
		XYSeries series = new XYSeries("ritardo medio");
		sumAndCountByHour.forEach((hour, aggregate) ->
			series.add((double) hour, aggregate[0] / aggregate[1]));
		JFreeChart chart = ChartFactory.createXYLineChart("Ritardo medio per ora del giorno",
			"ora", "ritardo medio [s]", new XYSeriesCollection(series),
			PlotOrientation.VERTICAL, false, false, false);
		save(chart, png);
	}

	/** One polyline per train: x = time [s], y = distance along the segment [m]. */
	public static void spaceTime(Map<String, List<double[]>> trajectoriesByTrain, Path png) {
		XYSeriesCollection dataset = new XYSeriesCollection();
		trajectoriesByTrain.forEach((train, points) -> {
			XYSeries series = new XYSeries(train);
			points.forEach(point -> series.add(point[0] / 3600, point[1] / 1000));
			dataset.addSeries(series);
		});
		JFreeChart chart = ChartFactory.createXYLineChart("Diagramma spazio-tempo",
			"ora", "distanza [km]", dataset, PlotOrientation.VERTICAL, false, false, false);
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

	private static void save(JFreeChart chart, Path png) {
		try {
			ChartUtils.saveChartAsPNG(png.toFile(), chart, WIDTH, HEIGHT);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write chart " + png, e);
		}
	}
}
