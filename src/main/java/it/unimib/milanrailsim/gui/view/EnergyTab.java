package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.runs.RunResults;
import it.unimib.milanrailsim.runs.RunResults.Energy;
import it.unimib.milanrailsim.runs.RunResults.EnergyLineRow;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;

/** The result tab on the energy of a run: totals, the load on the electric network through the day, and the lines. */
final class EnergyTab {

	private EnergyTab() {
	}

	static Node of(RunResults results) {
		if (results.energy().isEmpty()) {
			VBox box = new VBox(muted("Consumi non disponibili per questo run: è stato simulato prima che venissero misurati."));
			box.setPadding(new Insets(16, 0, 0, 0));
			return box;
		}
		Energy energy = results.energy().get();
		HBox electric = new HBox(32,
			ResultsView.metric("Energia dalla rete", megawattHours(energy.drawnKilowattHours()),
				"senza recupero " + megawattHours(energy.demandKilowattHours()), false),
			ResultsView.metric("Recuperata in frenata", megawattHours(energy.regeneratedKilowattHours() - energy.lostKilowattHours()),
				"di cui da altri treni " + megawattHours(energy.reusedByOthersKilowattHours()), false),
			ResultsView.metric("Dissipata in frenata", megawattHours(energy.lostKilowattHours()),
				"nessun treno vicino ad assorbirla", false),
			ResultsView.metric("Consumo specifico", String.format(Locale.ITALY, "%.1f kWh/treno-km", energy.kilowattHoursPerTrainKilometre()),
				String.format(Locale.ITALY, "%.3f kWh/t-km · %.3f senza recupero", energy.kilowattHoursPerTonneKilometre(),
					energy.kilowattHoursPerTonneKilometreWithoutRecovery()), false),
			ResultsView.metric("Picco di potenza", String.format(Locale.ITALY, "%.0f MW", energy.peakKilowatt() / 1000),
				energy.peakMinuteOfDay() < 0 ? null : "alle " + clock(energy.peakMinuteOfDay()) + " · " + energy.peakTrains()
					+ " treni in servizio", false),
			ResultsView.metric("Gasolio", String.format(Locale.ITALY, "%,.0f l", energy.litres()),
				String.format(Locale.ITALY, "%.2f l/treno-km", energy.litresPerTrainKilometre()), false));
		VBox column = new VBox(16, electric);
		results.chart(RunResults.POWER_PROFILE).ifPresent(file -> {
			ImageView chart = new ImageView(new Image(file.toUri().toString()));
			chart.setPreserveRatio(true);
			chart.setFitWidth(900);
			column.getChildren().add(chart);
		});
		results.energyByLine().ifPresent(rows -> column.getChildren().add(lines(rows)));
		column.getChildren().add(muted("Consumo calcolato dal moto simulato di ogni treno durante le corse: accelerazione,"
			+ " resistenza al moto, servizi ausiliari. L'energia di frenata alimenta gli ausiliari del treno e i treni"
			+ " elettrici in trazione entro 10 km; il resto è dissipato. Parametri e fonti nel file energy-model.json del run."));
		column.setPadding(new Insets(16, 0, 16, 0));
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		return scroll;
	}

	private static Node lines(java.util.List<EnergyLineRow> rows) {
		TableView<EnergyLineRow> table = new TableView<>(FXCollections.observableArrayList(rows));
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Linea", 80, EnergyLineRow::line));
		table.getColumns().add(Columns.text("Trazione", 100, row -> row.traction().equals("diesel") ? "Diesel" : "Elettrica"));
		table.getColumns().add(Columns.number("Treni-km", 100, EnergyLineRow::trainKilometres, EnergyTab::whole));
		table.getColumns().add(Columns.number("Energia dalla rete [kWh]", 170, EnergyLineRow::drawnKilowattHours, EnergyTab::whole));
		table.getColumns().add(Columns.number("Recuperabile [kWh]", 150, EnergyLineRow::regeneratedKilowattHours, EnergyTab::whole));
		table.getColumns().add(Columns.number("Dissipata [kWh]", 130, EnergyLineRow::lostKilowattHours, EnergyTab::whole));
		table.getColumns().add(Columns.number("kWh/treno-km", 120, EnergyLineRow::kilowattHoursPerTrainKilometre, EnergyTab::decimal));
		table.getColumns().add(Columns.number("Gasolio [l]", 110, EnergyLineRow::litres, EnergyTab::whole));
		table.getColumns().add(Columns.number("l/treno-km", 110, EnergyLineRow::litresPerTrainKilometre, EnergyTab::decimal));
		table.setPrefHeight(420);
		return table;
	}

	private static String megawattHours(double kilowattHours) {
		return String.format(Locale.ITALY, "%,.0f MWh", kilowattHours / 1000);
	}

	private static String whole(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ITALY, "%,.0f", value);
	}

	private static String decimal(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ITALY, "%.2f", value);
	}

	private static String clock(int minuteOfDay) {
		return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
