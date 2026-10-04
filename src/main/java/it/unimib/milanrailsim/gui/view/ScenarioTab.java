package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.server.RailsimJob;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result tab on what the scenario changed in the published timetable:
 * the trips it added, those it gave up, the added trips the engine could not
 * schedule and the passages too close in the tunnel. It reads the reports
 * the engine leaves in the {@code scenario} folder of the run.
 */
final class ScenarioTab {

	/** The trips a scenario added to one line. */
	private record AddedRow(String line, int trips, double firstDeparture, double lastDeparture) {
	}

	private record SkippedRow(String relation, String line, double planned, String reason) {
	}

	/** The added trips of one line that pass in the tunnel too close to another train. */
	private record TunnelRow(String line, int passages, double shortestHeadway) {
	}

	private ScenarioTab() {
	}

	static Node of(Path runDir) {
		Path scenario = runDir.resolve("scenario");
		List<Map<String, String>> added = rows(scenario.resolve(RailsimJob.ADDED_TRIPS));
		List<Map<String, String>> skipped = rows(scenario.resolve(RailsimJob.SKIPPED_TRIPS));
		List<Map<String, String>> unscheduled = rows(scenario.resolve(RailsimJob.UNSCHEDULED_ADDED_TRIPS));
		List<Map<String, String>> tunnel = rows(scenario.resolve(RailsimJob.TUNNEL_WARNINGS));
		VBox column = new VBox(16);
		column.setPadding(new Insets(16, 0, 16, 0));
		if (added.isEmpty() && skipped.isEmpty() && unscheduled.isEmpty() && tunnel.isEmpty()) {
			column.getChildren().add(muted("Questo run simula l'orario pubblicato così com'è: lo scenario non aggiunge né toglie corse."));
			return column;
		}
		column.getChildren().add(new HBox(32,
			ResultsView.metric("Corse aggiunte", String.valueOf(added.size()), "all'orario pubblicato", false),
			ResultsView.metric("Corse rinunciate", String.valueOf(skipped.size()), "previste dallo scenario e non inserite", false),
			ResultsView.metric("Aggiunte non simulate", String.valueOf(unscheduled.size()),
				"fermano fuori dalla rete modellata", !unscheduled.isEmpty()),
			ResultsView.metric("Passaggi ravvicinati in galleria", String.valueOf(tunnel.size()),
				"corse aggiunte sotto l'intervallo minimo", !tunnel.isEmpty())));
		if (!added.isEmpty()) {
			column.getChildren().addAll(section("Corse aggiunte per linea"), addedTable(added));
		}
		if (!skipped.isEmpty()) {
			column.getChildren().addAll(section("Corse rinunciate"), skippedTable(skipped));
		}
		if (!tunnel.isEmpty()) {
			column.getChildren().addAll(section("Passaggi ravvicinati in galleria, per linea"), tunnelTable(tunnel));
		}
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		return scroll;
	}

	private static List<Map<String, String>> rows(Path csv) {
		return Files.exists(csv) ? CsvTable.read(csv) : List.of();
	}

	private static Node addedTable(List<Map<String, String>> added) {
		Map<String, List<Double>> departures = new TreeMap<>();
		added.forEach(row -> departures.computeIfAbsent(row.get("line"), key -> new ArrayList<>())
			.add(Double.parseDouble(row.get("departure_s"))));
		List<AddedRow> rows = new ArrayList<>();
		departures.forEach((line, times) -> rows.add(new AddedRow(line, times.size(),
			times.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
			times.stream().mapToDouble(Double::doubleValue).max().orElseThrow())));
		TableView<AddedRow> table = table(rows);
		table.getColumns().add(Columns.text("Linea", 80, AddedRow::line));
		table.getColumns().add(Columns.number("Corse aggiunte", 130, row -> (double) row.trips(), Columns::count));
		table.getColumns().add(Columns.number("Prima partenza", 130, AddedRow::firstDeparture, Columns::clock));
		table.getColumns().add(Columns.number("Ultima partenza", 130, AddedRow::lastDeparture, Columns::clock));
		return table;
	}

	private static Node skippedTable(List<Map<String, String>> skipped) {
		List<SkippedRow> rows = skipped.stream().map(row -> new SkippedRow(row.get("relation"), row.get("line"),
			Double.parseDouble(row.get("planned_s")), row.get("reason"))).toList();
		TableView<SkippedRow> table = table(rows);
		table.getColumns().add(Columns.text("Relazione", 200, SkippedRow::relation));
		table.getColumns().add(Columns.text("Linea", 80, SkippedRow::line));
		table.getColumns().add(Columns.number("Orario previsto", 130, SkippedRow::planned, Columns::clock));
		table.getColumns().add(Columns.text("Motivo", 420, SkippedRow::reason));
		return table;
	}

	private static Node tunnelTable(List<Map<String, String>> tunnel) {
		Map<String, List<Double>> headways = new TreeMap<>();
		tunnel.forEach(row -> headways.computeIfAbsent(row.get("line"), key -> new ArrayList<>())
			.add(Double.parseDouble(row.get("headway_s"))));
		List<TunnelRow> rows = new ArrayList<>();
		headways.forEach((line, seconds) -> rows.add(new TunnelRow(line, seconds.size(),
			seconds.stream().mapToDouble(Double::doubleValue).min().orElseThrow())));
		TableView<TunnelRow> table = table(rows);
		table.getColumns().add(Columns.text("Linea", 80, TunnelRow::line));
		table.getColumns().add(Columns.number("Passaggi", 110, row -> (double) row.passages(), Columns::count));
		table.getColumns().add(Columns.number("Intervallo più corto", 170, TunnelRow::shortestHeadway, RunLibraryView::minutesSeconds));
		return table;
	}

	private static <T> TableView<T> table(List<T> rows) {
		TableView<T> table = new TableView<>(FXCollections.observableArrayList(rows));
		table.getStyleClass().add("data-table");
		table.setPrefHeight(Math.min(360, 60 + 28 * rows.size()));
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
