package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.runs.RunResults;
import it.unimib.milanrailsim.runs.RunResults.StationHourRow;
import it.unimib.milanrailsim.runs.RunResults.StationRow;
import it.unimib.milanrailsim.runs.RunResults.TrainRow;
import it.unimib.milanrailsim.runs.RunResults.UnfinishedRow;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** The result tabs that regroup delays: by station, and by train with the trains that never arrived. */
final class DelayTabs {

	private DelayTabs() {
	}

	private static final String EVERY_DIRECTION = "Tutte le direzioni";

	static Node stations(RunResults results, StopLabels labels) {
		if (results.stations().isEmpty()) {
			return unavailable("Dati per stazione non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		TableView<StationRow> table = new TableView<>(FXCollections.observableArrayList(results.stations().get()));
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Stazione", 220, row -> labels.station(row.station())));
		table.getColumns().add(Columns.number("Arrivi", 80, row -> (double) row.observations(), Columns::count));
		table.getColumns().add(Columns.number("Ritardo medio", 110, StationRow::meanDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("95° percentile", 110, StationRow::p95Delay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Massimo", 90, StationRow::maxDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Oltre 5 min", 100, StationRow::latePercent, Columns::percent));
		VBox stations = new VBox(8, muted("Stazioni dal ritardo medio più alto; l'anticipo conta zero e ogni binario"
			+ " di un nodo dettagliato conta per la sua stazione. Seleziona una stazione per il dettaglio orario."), table);
		VBox.setVgrow(table, Priority.ALWAYS);

		VBox hourly = new VBox(8, muted("Seleziona una stazione."));
		table.getSelectionModel().selectedItemProperty().addListener((observable, previous, row) -> {
			if (row != null) {
				hourly.getChildren().setAll(hourlyOf(results, labels, row.station()));
			}
		});
		SplitPane split = new SplitPane(stations, hourly);
		split.setDividerPositions(0.5);
		split.setPadding(new Insets(16, 0, 0, 0));
		return split;
	}

	/** The trains of one station by direction and planned hour, with a choice of the direction. */
	private static List<Node> hourlyOf(RunResults results, StopLabels labels, String station) {
		Label title = new Label(labels.station(station) + " · treni e ritardi per ora");
		title.getStyleClass().add("section-title");
		if (results.stationsHourly().isEmpty()) {
			return List.of(title, muted("Dettaglio orario non disponibile per questo run."));
		}
		List<StationHourRow> rows = results.stationsHourly().get().stream()
			.filter(row -> row.station().equals(station)).toList();
		FilteredList<StationHourRow> filtered = new FilteredList<>(FXCollections.observableArrayList(rows));
		ComboBox<String> direction = new ComboBox<>();
		direction.getItems().add(EVERY_DIRECTION);
		rows.stream().map(StationHourRow::direction).distinct().map(labels::station).sorted().forEach(direction.getItems()::add);
		direction.setValue(EVERY_DIRECTION);
		direction.valueProperty().addListener((observable, previous, chosen) -> filtered.setPredicate(
			row -> EVERY_DIRECTION.equals(chosen) || labels.station(row.direction()).equals(chosen)));
		TableView<StationHourRow> table = new TableView<>();
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Verso", 190, row -> row.direction().equals(station)
			? "termina qui" : labels.station(row.direction())));
		table.getColumns().add(Columns.number("Ora", 60, row -> (double) row.hour(), Columns::count));
		table.getColumns().add(Columns.number("Previsti", 80, row -> (double) row.trainsPlanned(), Columns::count));
		table.getColumns().add(Columns.number("Passati", 80, row -> (double) row.trainsCalled(), Columns::count));
		table.getColumns().add(Columns.number("Ritardo medio", 110, StationHourRow::meanDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Entro 5 min", 100, StationHourRow::punctualityPercent, Columns::percent));
		SortedList<StationHourRow> sorted = new SortedList<>(filtered);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);
		VBox.setVgrow(table, Priority.ALWAYS);
		return List.of(title, muted("L'ora è quella prevista dall'orario; la direzione è il capolinea della corsa."),
			direction, table);
	}

	static Node trains(RunResults results, StopLabels labels) {
		if (results.trains().isEmpty()) {
			return unavailable("Dati per treno non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		VBox column = new VBox(16);
		column.setPadding(new Insets(16, 0, 0, 0));
		List<UnfinishedRow> unfinished = results.unfinished().orElse(List.of());
		Label unfinishedTitle = new Label(unfinished.isEmpty() ? "Tutti i treni hanno raggiunto il capolinea"
			: unfinished.size() + " treni non hanno raggiunto il capolinea");
		unfinishedTitle.getStyleClass().add("section-title");
		column.getChildren().add(unfinishedTitle);
		if (!unfinished.isEmpty()) {
			column.getChildren().add(muted("Fermi in rete a fine simulazione o abbandonati dal motore: i loro ritardi"
				+ " non compaiono nelle medie, perché un treno che non arriva non registra un arrivo."));
			TableView<UnfinishedRow> table = new TableView<>(FXCollections.observableArrayList(unfinished));
			table.getStyleClass().add("data-table");
			table.getColumns().add(Columns.text("Treno", 150, UnfinishedRow::vehicle));
			table.getColumns().add(Columns.text("Linea", 70, UnfinishedRow::line));
			table.getColumns().add(Columns.text("Corsa", 110, UnfinishedRow::route));
			table.getColumns().add(Columns.text("Ultima fermata raggiunta", 240,
				row -> row.lastStop().isEmpty() ? "mai partito" : labels.stop(row.lastStop())));
			table.getColumns().add(Columns.number("Fermate mancanti", 130, row -> (double) row.remainingStops(), Columns::count));
			table.setPrefHeight(Math.min(320, 40 + 28 * unfinished.size()));
			column.getChildren().add(table);
		}

		Label worstTitle = new Label("Treni per ritardo massimo raggiunto");
		worstTitle.getStyleClass().add("section-title");
		TableView<TrainRow> worst = new TableView<>(FXCollections.observableArrayList(results.trains().get()));
		worst.getStyleClass().add("data-table");
		worst.getColumns().add(Columns.text("Treno", 150, TrainRow::vehicle));
		worst.getColumns().add(Columns.text("Linea", 70, TrainRow::line));
		worst.getColumns().add(Columns.number("Fermate", 90, row -> (double) row.stops(), Columns::count));
		worst.getColumns().add(Columns.number("Ritardo medio", 120, TrainRow::meanDelay, RunLibraryView::minutesSeconds));
		worst.getColumns().add(Columns.number("Ritardo massimo", 130, TrainRow::maxDelay, RunLibraryView::minutesSeconds));
		worst.getColumns().add(Columns.number("All'ultima fermata", 130, TrainRow::finalDelay, RunLibraryView::minutesSeconds));
		VBox.setVgrow(worst, Priority.ALWAYS);
		column.getChildren().addAll(worstTitle, worst);
		return column;
	}

	private static Node unavailable(String message) {
		VBox box = new VBox(muted(message));
		box.setPadding(new Insets(16, 0, 0, 0));
		return box;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
