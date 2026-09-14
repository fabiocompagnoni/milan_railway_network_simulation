package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.runs.DelaySummaries;
import it.unimib.milanrailsim.runs.DelaySummaries.StationRow;
import it.unimib.milanrailsim.runs.DelaySummaries.TrainRow;
import it.unimib.milanrailsim.runs.RunResults;
import it.unimib.milanrailsim.runs.RunResults.UnfinishedRow;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** The result tabs that regroup delays: by station, and by train with the trains that never arrived. */
final class DelayTabs {

	private DelayTabs() {
	}

	static Node stations(RunResults results, StopLabels labels, double lateThresholdSeconds) {
		if (results.visits().isEmpty()) {
			return unavailable("Dati per stazione non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		List<StationRow> rows = DelaySummaries.byStation(results.visits().get(), lateThresholdSeconds);
		TableView<StationRow> table = new TableView<>(FXCollections.observableArrayList(rows));
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Stazione", 240, row -> labels.station(row.station())));
		table.getColumns().add(Columns.number("Arrivi osservati", 130, row -> (double) row.observations(), Columns::count));
		table.getColumns().add(Columns.number("Ritardo medio", 120, StationRow::meanDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("95° percentile", 120, StationRow::p95Delay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Massimo", 100, StationRow::maxDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Oltre " + (int) (lateThresholdSeconds / 60) + " min", 110,
			row -> 100 * row.lateShare(), Columns::percent));
		VBox column = new VBox(8, muted("Le stazioni sono ordinate dal ritardo medio più alto; ogni binario di un nodo"
			+ " dettagliato conta per la sua stazione."), table);
		VBox.setVgrow(table, Priority.ALWAYS);
		column.setPadding(new Insets(16, 0, 0, 0));
		return column;
	}

	static Node trains(RunResults results, StopLabels labels) {
		if (results.visits().isEmpty()) {
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
		List<TrainRow> trains = DelaySummaries.byTrain(results.visits().get());
		TableView<TrainRow> worst = new TableView<>(FXCollections.observableArrayList(trains));
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
