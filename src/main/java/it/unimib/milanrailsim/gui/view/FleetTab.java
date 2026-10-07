package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.results.FleetUse;
import it.unimib.milanrailsim.results.RunCharts;
import it.unimib.milanrailsim.runs.FleetComparison;
import it.unimib.milanrailsim.runs.RunFleet;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The result tab on the trains of a run: how many it used and of which
 * type, how many were running hour by hour, and what it needs beyond the
 * real day it is compared with.
 */
final class FleetTab {

	/** The run a scenario is measured against, with the name shown for it. */
	record Reference(String runName, RunFleet fleet) {
	}

	private record LineRow(String line, String type, int trains) {
	}

	private static final String PEAK = "Massimo in corsa nello stesso istante";
	private static final String ACTIVE = "Treni attivi nell'ora";
	private static final String COMPARISON_FILE = "fleet_vs_baseline.csv";
	private static final String COMPARISON_CHART = "charts/fleet_vs_baseline.png";
	private static final String TOTAL = "Totale";

	private FleetTab() {
	}

	/**
	 * @param fleet     empty for a run archived without its trips
	 * @param reference the real day of the same service date, when one is archived
	 */
	static Node of(Path runDir, Optional<RunFleet> fleet, Optional<Reference> reference) {
		if (fleet.isEmpty()) {
			VBox box = new VBox(muted("Flotta non disponibile per questo run: è stato analizzato prima che le corse venissero salvate."));
			box.setPadding(new Insets(16, 0, 0, 0));
			return box;
		}
		RunFleet run = fleet.get();
		FleetUse use = run.use();
		Optional<FleetComparison> comparison = reference.map(real -> FleetComparison.of(real.fleet(), run));
		List<Node> figures = new ArrayList<>();
		figures.add(ResultsView.metric("Treni utilizzati", String.valueOf(use.trains().size()),
			"treni distinti dei giri treno del modello", false));
		use.busiestHour().ifPresent(hour -> figures.add(ResultsView.metric("Massimo in corsa insieme",
			String.valueOf(hour.peak()), "nell'ora delle " + hour.hour() + " · " + hour.active() + " treni attivi nell'ora", false)));
		comparison.ifPresent(compared -> figures.add(ResultsView.metric("Treni in più del giorno reale",
			String.format(Locale.ITALY, "%+d", compared.difference()),
			compared.referenceTrains() + " nel run " + reference.get().runName(), compared.difference() > 0)));
		HBox headline = new HBox(32);
		headline.getChildren().setAll(figures);

		VBox column = new VBox(16, headline, section("Treni in corsa nello stesso istante, massimo di ogni ora"), byHourChart(run));
		column.getChildren().addAll(section("Treni utilizzati per tipo"), byTypeChart(run, reference));
		if (comparison.isPresent()) {
			column.getChildren().addAll(comparisonTable(comparison.get()), saveButton(runDir, run, reference.get(), comparison.get()));
		} else {
			column.getChildren().addAll(typeTable(run),
				muted("Nessun run reale dello stesso giorno in archivio con cui confrontare: i treni in più non sono calcolati."));
		}
		column.getChildren().addAll(section("Treni per linea"), lineTable(run), section("Treni per ora"), hourTable(run),
			muted("Un treno è in corsa dalla partenza prevista all'arrivo effettivo di una corsa; fra due corse è fermo. Il"
				+ " massimo dell'ora è il numero più alto di treni in corsa nello stesso istante, diviso per tipo in quell'istante;"
				+ " gli attivi sono i treni che nell'ora hanno fatto almeno un tratto di corsa. I treni sono quelli dei giri"
				+ " treno del modello, in cui ogni linea usa il proprio parco: non sono i turni reali dell'operatore."));
		column.setPadding(new Insets(16, 0, 16, 0));
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		return scroll;
	}

	private static Node byHourChart(RunFleet run) {
		StackedBarChart<String, Number> chart = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
		chart.setAnimated(false);
		chart.setPrefHeight(360);
		List<FleetUse.Hour> hours = run.use().hours();
		if (hours.isEmpty()) {
			return chart;
		}
		Map<Integer, FleetUse.Hour> byHour = new TreeMap<>();
		hours.forEach(hour -> byHour.put(hour.hour(), hour));
		List<String> labels = new ArrayList<>();
		for (int hour = hours.getFirst().hour(); hour <= hours.getLast().hour(); hour++) {
			labels.add(String.valueOf(hour));
		}
		((CategoryAxis) chart.getXAxis()).setCategories(FXCollections.observableArrayList(labels));
		for (String type : inCatalogueOrder(run.use().byType())) {
			XYChart.Series<String, Number> series = new XYChart.Series<>();
			series.setName(run.typeName(type));
			for (String label : labels) {
				FleetUse.Hour hour = byHour.get(Integer.parseInt(label));
				series.getData().add(new XYChart.Data<>(label, hour == null ? 0 : hour.peakByType().getOrDefault(type, 0)));
			}
			chart.getData().add(series);
		}
		return chart;
	}

	private static Node byTypeChart(RunFleet run, Optional<Reference> reference) {
		BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
		chart.setAnimated(false);
		chart.setPrefHeight(320);
		Map<String, Integer> used = new TreeMap<>(run.use().byType());
		reference.ifPresent(real -> real.fleet().use().byType().keySet().forEach(type -> used.putIfAbsent(type, 0)));
		List<String> types = inCatalogueOrder(used);
		reference.ifPresent(real -> chart.getData().add(series("giorno reale (" + real.runName() + ")",
			types, real.fleet().use().byType(), run)));
		chart.getData().add(series("questo run", types, run.use().byType(), run));
		chart.setLegendVisible(reference.isPresent());
		return chart;
	}

	private static XYChart.Series<String, Number> series(String name, List<String> types, Map<String, Integer> trains, RunFleet names) {
		XYChart.Series<String, Number> series = new XYChart.Series<>();
		series.setName(name);
		types.forEach(type -> series.getData().add(new XYChart.Data<>(names.typeName(type), trains.getOrDefault(type, 0))));
		return series;
	}

	/** The types in the order of the rolling stock catalogue, then any other in alphabetical order. */
	private static List<String> inCatalogueOrder(Map<String, Integer> byType) {
		List<String> ordered = new ArrayList<>();
		FleetConfig.defaults().types().stream().map(FleetConfig.TrainType::id).filter(byType::containsKey).forEach(ordered::add);
		byType.keySet().stream().sorted().filter(type -> !ordered.contains(type)).forEach(ordered::add);
		return ordered;
	}

	private static Node comparisonTable(FleetComparison comparison) {
		List<FleetComparison.Row> rows = new ArrayList<>(comparison.rows());
		rows.add(new FleetComparison.Row("", TOTAL, comparison.referenceTrains(), comparison.runTrains()));
		TableView<FleetComparison.Row> table = table(rows);
		table.getColumns().add(Columns.text("Tipo di treno", 220, FleetComparison.Row::name));
		table.getColumns().add(Columns.number("Giorno reale", 120, row -> (double) row.referenceTrains(), Columns::count));
		table.getColumns().add(Columns.number("Questo run", 120, row -> (double) row.runTrains(), Columns::count));
		table.getColumns().add(Columns.number("Differenza", 120, row -> (double) row.difference(),
			value -> String.format(Locale.ITALY, "%+d", Math.round(value))));
		return table;
	}

	private static Node typeTable(RunFleet run) {
		List<FleetComparison.Row> rows = new ArrayList<>();
		for (String type : inCatalogueOrder(run.use().byType())) {
			rows.add(new FleetComparison.Row(type, run.typeName(type), 0, run.use().byType().get(type)));
		}
		rows.add(new FleetComparison.Row("", TOTAL, 0, run.use().trains().size()));
		TableView<FleetComparison.Row> table = table(rows);
		table.getColumns().add(Columns.text("Tipo di treno", 220, FleetComparison.Row::name));
		table.getColumns().add(Columns.number("Treni utilizzati", 140, row -> (double) row.runTrains(), Columns::count));
		return table;
	}

	private static Node lineTable(RunFleet run) {
		List<LineRow> rows = new ArrayList<>();
		run.use().byLineAndType().forEach((line, byType) -> byType.forEach((type, trains) ->
			rows.add(new LineRow(line, run.typeName(type), trains))));
		TableView<LineRow> table = table(rows);
		table.getColumns().add(Columns.text("Linea", 90, LineRow::line));
		table.getColumns().add(Columns.text("Tipo di treno", 220, LineRow::type));
		table.getColumns().add(Columns.number("Treni", 100, row -> (double) row.trains(), Columns::count));
		return table;
	}

	/** One row per hour and one column per train type, for the measure chosen above the table. */
	private static Node hourTable(RunFleet run) {
		ComboBox<String> measure = new ComboBox<>(FXCollections.observableArrayList(PEAK, ACTIVE));
		measure.setValue(PEAK);
		TableView<FleetUse.Hour> table = table(run.use().hours());
		table.getColumns().add(Columns.number("Ora", 70, hour -> (double) hour.hour(), Columns::count));
		table.getColumns().add(Columns.number(TOTAL, 90,
			hour -> (double) (PEAK.equals(measure.getValue()) ? hour.peak() : hour.active()), Columns::count));
		for (String type : inCatalogueOrder(run.use().byType())) {
			table.getColumns().add(Columns.number(run.typeName(type), 150, hour -> (double) (PEAK.equals(measure.getValue())
				? hour.peakByType() : hour.activeByType()).getOrDefault(type, 0), Columns::count));
		}
		measure.valueProperty().addListener((observable, previous, chosen) -> table.refresh());
		return new VBox(8, measure, table);
	}

	/** Leaves the comparison in the folder of the run, next to its other tables and charts. */
	private static Node saveButton(Path runDir, RunFleet run, Reference reference, FleetComparison comparison) {
		Label saved = muted("");
		Button save = new Button("Salva il confronto nella cartella del run");
		save.setOnAction(event -> {
			List<String> lines = new ArrayList<>();
			lines.add("type,name,reference_trains,run_trains,difference");
			comparison.rows().forEach(row -> lines.add(String.join(",", row.type(), row.name(),
				Integer.toString(row.referenceTrains()), Integer.toString(row.runTrains()), Integer.toString(row.difference()))));
			lines.add(String.join(",", "total", TOTAL, Integer.toString(comparison.referenceTrains()),
				Integer.toString(comparison.runTrains()), Integer.toString(comparison.difference())));
			try {
				Files.write(runDir.resolve(COMPARISON_FILE), lines);
				Files.createDirectories(runDir.resolve(COMPARISON_CHART).getParent());
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot write the fleet comparison in " + runDir, e);
			}
			RunCharts.fleetAgainstReference(reference.fleet().use().byType(), run.use().byType(), run.typeNames(),
				runDir.resolve(COMPARISON_CHART));
			saved.setText("Salvati " + COMPARISON_FILE + " e " + COMPARISON_CHART + " (riferimento: " + reference.runName() + ").");
		});
		return new HBox(12, save, saved);
	}

	private static <T> TableView<T> table(List<T> rows) {
		TableView<T> table = new TableView<>(FXCollections.observableArrayList(rows));
		table.getStyleClass().add("data-table");
		table.setPrefHeight(Math.min(380, 60 + 28 * rows.size()));
		return table;
	}

	private static Label section(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-title");
		return label;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
