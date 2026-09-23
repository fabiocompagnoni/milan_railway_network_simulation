package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.runs.RunLibrary.Entry;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.RunResults.LineRow;
import it.unimib.milanrailsim.runs.RunResults.UnfinishedRow;
import it.unimib.milanrailsim.runs.RunResults.VisitRow;
import it.unimib.milanrailsim.runs.RunResults;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Results of one archived run: what was simulated and how it ended, headline
 * figures, charts, delays by line, station and train, every stop visit, costs
 * and export. A real-timetable run of the same day and window, if archived,
 * serves as the baseline the headline can be compared with.
 */
public final class ResultsView extends BorderPane {

	private static final double ON_TIME_THRESHOLD_S = 300;
	private static final double SEVERE_THRESHOLD_S = 900;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ITALY);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final AppModel model;
	private final Runnable onOpenLibrary;
	private final StackPane body = new StackPane();
	private final ToggleButton compare = new ToggleButton("Confronta con reale");
	private final HBox headline = new HBox(32);
	private Entry baseline;
	private RunResults baselineResults;
	private Entry entry;
	private RunResults results;
	private StopLabels labels;

	public ResultsView(AppModel model, Runnable onOpenLibrary) {
		this.model = model;
		this.onOpenLibrary = onOpenLibrary;
		getStyleClass().add("content");
		setCenter(body);
		Entry selected = model.selectedRun().get();
		if (selected == null) {
			showEmpty();
		} else {
			load(selected);
		}
	}

	private void showEmpty() {
		Label title = new Label("Nessun run selezionato");
		title.getStyleClass().add("section-title");
		Label text = muted("Apri un run dall'archivio per vederne i risultati.");
		Button open = new Button("Apri l'archivio");
		open.setOnAction(event -> onOpenLibrary.run());
		VBox box = new VBox(8, title, text, open);
		box.setAlignment(Pos.CENTER);
		body.getChildren().setAll(box);
	}

	private void load(Entry selected) {
		entry = selected;
		VBox loading = new VBox(12, new LoadingRing(32), muted("Carico i risultati del run…"));
		loading.setAlignment(Pos.CENTER);
		body.getChildren().setAll(loading);
		Task<RunResults> task = new Task<>() {
			@Override
			protected RunResults call() {
				baseline = findBaseline(selected);
				baselineResults = baseline == null ? null : RunResults.load(baseline.dir());
				labels = new StopLabels(model.feed().join().stopsById().values().stream()
					.collect(Collectors.toMap(GtfsFeed.Stop::id, GtfsFeed.Stop::name, (first, second) -> first)));
				return RunResults.load(selected.dir());
			}
		};
		task.setOnSucceeded(event -> {
			results = task.getValue();
			show();
		});
		task.setOnFailed(event -> {
			Label title = new Label("Impossibile leggere questo run");
			title.getStyleClass().add("section-title");
			Button open = new Button("Apri l'archivio");
			open.setOnAction(e -> onOpenLibrary.run());
			VBox box = new VBox(8, title, muted(task.getException().getMessage()), open);
			box.setAlignment(Pos.CENTER);
			body.getChildren().setAll(box);
		});
		Thread thread = new Thread(task, "results-load");
		thread.setDaemon(true);
		thread.start();
	}

	/** The completed real-timetable run with the same service day and window, if any and not this run. */
	private Entry findBaseline(Entry run) {
		Optional<ScenarioSpec> spec = run.spec();
		if (spec.isEmpty()) {
			return null;
		}
		return model.runs().scan().stream()
			.filter(candidate -> candidate.status() == RunLibrary.Status.COMPLETED && !candidate.name().equals(run.name()))
			.filter(candidate -> candidate.spec().map(other -> other.type() == ScenarioSpec.SimulationType.REAL
				&& other.serviceDate().equals(spec.get().serviceDate())
				&& java.util.Objects.equals(other.window(), spec.get().window())).orElse(false))
			.findFirst().orElse(null);
	}

	private void show() {
		Label title = new Label("Run " + entry.name());
		title.getStyleClass().add("title");
		Label subtitle = muted(describe(entry));
		compare.setDisable(baseline == null);
		compare.setTooltip(new javafx.scene.control.Tooltip(baseline == null
			? "Nessun run reale con stesso giorno e finestra oraria trovato" : "Confronta con " + baseline.name()));
		compare.setOnAction(event -> fillHeadline());
		Button export = new Button("Salva come…");
		export.setOnAction(event -> exportDialog());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, new VBox(2, title, subtitle), spacer, compare, export);
		header.setAlignment(Pos.CENTER_LEFT);
		fillHeadline();

		TabPane tabs = new TabPane();
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().addAll(new Tab("Sintesi", summaryTab()), new Tab("Linee", linesTab()),
			new Tab("Stazioni", DelayTabs.stations(results, labels, ON_TIME_THRESHOLD_S)),
			new Tab("Treni", DelayTabs.trains(results, labels)), new Tab("Corse", visitsTab()),
			new Tab("Costi", costsTab()));
		VBox.setVgrow(tabs, Priority.ALWAYS);
		VBox column = new VBox(16, header, headline, tabs);
		body.getChildren().setAll(column);
	}

	/** Scenario, service day, simulated window and when the analysis ran. */
	static String describe(Entry entry) {
		StringBuilder text = new StringBuilder(entry.scenarioLabel());
		entry.spec().ifPresent(spec -> {
			text.append(" · ").append(spec.serviceDate());
			text.append(" · ").append(spec.window() == null ? "giornata intera"
				: TIME.format(spec.window().start()) + "–" + TIME.format(spec.window().end()));
		});
		entry.manifest().ifPresent(manifest -> text.append(" · analizzato il ").append(STAMP.format(manifest.created())));
		return text.toString();
	}

	private void fillHeadline() {
		RunLibrary.Manifest manifest = entry.manifest().orElseThrow();
		boolean delta = compare.isSelected() && baseline != null;
		RunLibrary.Manifest base = delta ? baseline.manifest().orElseThrow() : null;
		headline.getChildren().setAll(
			metric("Ritardo medio all'arrivo", RunLibraryView.minutesSeconds(manifest.meanArrivalDelaySeconds()),
				delta ? RunLibraryView.minutesSeconds(manifest.meanArrivalDelaySeconds() - base.meanArrivalDelaySeconds()) : null,
				delta && manifest.meanArrivalDelaySeconds() > base.meanArrivalDelaySeconds()),
			metric("Puntualità (entro 5 min)", results.punctuality(ON_TIME_THRESHOLD_S).map(Columns::percent).orElse("—"),
				delta ? baselineResults.punctuality(ON_TIME_THRESHOLD_S).flatMap(b -> results.punctuality(ON_TIME_THRESHOLD_S)
					.map(v -> String.format(Locale.ITALY, "%+.1f punti", v - b))).orElse(null) : null, false),
			metric("Arrivi oltre 15 min", results.punctuality(SEVERE_THRESHOLD_S).map(v -> Columns.percent(100 - v)).orElse("—"),
				null, results.punctuality(SEVERE_THRESHOLD_S).map(v -> 100 - v > 5).orElse(false)),
			metric("Treni non arrivati", String.valueOf(manifest.unfinishedTrains()),
				manifest.unfinishedTrains() > 0 ? "vedi la scheda Treni" : null, manifest.unfinishedTrains() > 0),
			metric("Costo totale", RunLibraryView.euro(manifest.totalCost()),
				delta ? String.format(Locale.ITALY, "%+,.0f €", manifest.totalCost() - base.totalCost()) : null,
				delta && manifest.totalCost() > base.totalCost()),
			metric("Fermate osservate", String.valueOf(manifest.stopVisits()), null, false),
			metric("Anomalie", String.valueOf(manifest.anomalies()),
				manifest.anomalies() > 0 ? "corse fuori orario rispetto al programma" : null, manifest.anomalies() > 0));
	}

	private static Node metric(String label, String value, String note, boolean warn) {
		Label key = muted(label);
		Label text = new Label(value);
		text.getStyleClass().add("headline-value");
		VBox box = new VBox(2, key, text);
		if (note != null) {
			Label extra = new Label(note);
			extra.getStyleClass().add(warn ? "finding-blocking" : "text-muted");
			box.getChildren().add(extra);
		}
		return box;
	}

	private Node summaryTab() {
		HBox charts = new HBox(16, chart(RunResults.DELAY_HISTOGRAM, 520), chart(RunResults.DELAY_BY_HOUR, 520));
		ScrollPane spaceTime = new ScrollPane(chart(RunResults.SPACE_TIME, 1040));
		spaceTime.getStyleClass().add("plain-scroll");
		spaceTime.setFitToHeight(true);
		VBox column = new VBox(16, outcomePanel(), charts, spaceTime);
		column.setPadding(new Insets(16, 0, 0, 0));
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		return scroll;
	}

	/** How the simulated day ended, as the engine saw it. */
	private Node outcomePanel() {
		Label title = new Label("Esito della simulazione");
		title.getStyleClass().add("section-title");
		VBox panel = new VBox(8, title);
		panel.getStyleClass().add("panel");
		Optional<RunLibrary.Outcome> outcome = entry.manifest().flatMap(RunLibrary.Manifest::outcome);
		if (outcome.isEmpty()) {
			panel.getChildren().add(muted("Questo run è stato analizzato senza registrare l'esito del motore."));
			return panel;
		}
		RunLibrary.Outcome seen = outcome.get();
		int unfinished = entry.manifest().map(RunLibrary.Manifest::unfinishedTrains).orElse(0);
		HBox figures = new HBox(32,
			metric("Treni arrivati a fine turno", String.valueOf(seen.arrived()), null, false),
			metric("Ancora in rete alla fine", String.valueOf(seen.stalled()),
				seen.stalled() > 0 ? "fermi o in ritardo alle " + Columns.clock(seen.simulatedEndSeconds()) : null,
				seen.stalled() > 0),
			metric("Abbandonati dal motore", String.valueOf(seen.aborted()), null, seen.aborted() > 0),
			metric("Senza capolinea raggiunto", String.valueOf(unfinished), null, unfinished > 0),
			metric("Fine della giornata simulata", Columns.clock(seen.simulatedEndSeconds()),
				"ultimo arrivo previsto più tre ore", false),
			metric("Tempo di calcolo", duration(seen.wallClockSeconds()), "orario, simulazione e analisi", false));
		panel.getChildren().add(figures);
		return panel;
	}

	static String duration(long seconds) {
		return seconds >= 3600
			? String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
			: String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
	}

	private Node chart(String name, double width) {
		return results.chart(name).<Node>map(file -> {
			ImageView view = new ImageView(new Image(file.toUri().toString()));
			view.setPreserveRatio(true);
			view.setFitWidth(width);
			return view;
		}).orElseGet(() -> muted("Grafico «" + name + "» non disponibile per questo run: l'analisi non lo ha prodotto."));
	}

	private Node linesTab() {
		if (results.byLine().isEmpty()) {
			return partial("Dati per linea non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		Map<String, List<VisitRow>> visitsByLine = results.visits().orElse(List.of()).stream()
			.collect(Collectors.groupingBy(VisitRow::line));
		Map<String, Long> unfinishedByLine = results.unfinished().orElse(List.of()).stream()
			.collect(Collectors.groupingBy(UnfinishedRow::line, Collectors.counting()));
		TableView<LineRow> table = new TableView<>(FXCollections.observableArrayList(results.byLine().get()));
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Linea", 90, LineRow::line));
		table.getColumns().add(Columns.number("Fermate osservate", 130, row -> (double) row.observations(), Columns::count));
		table.getColumns().add(Columns.number("Puntualità (5 min)", 130,
			row -> RunResults.punctuality(visitsByLine.getOrDefault(row.line(), List.of()), ON_TIME_THRESHOLD_S), Columns::percent));
		table.getColumns().add(Columns.number("Ritardo medio", 120, LineRow::meanDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Mediana", 100, LineRow::medianDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("95° percentile", 120, LineRow::p95Delay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Massimo", 100, LineRow::maxDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Treni non arrivati", 130,
			row -> (double) unfinishedByLine.getOrDefault(row.line(), 0L), Columns::count));
		if (baselineResults != null && baselineResults.byLine().isPresent()) {
			Map<String, LineRow> base = new HashMap<>();
			baselineResults.byLine().get().forEach(row -> base.put(row.line(), row));
			table.getColumns().add(Columns.number("Δ medio vs reale", 130,
				row -> base.containsKey(row.line()) ? row.meanDelay() - base.get(row.line()).meanDelay() : Double.NaN,
				RunLibraryView::minutesSeconds));
		}
		BorderPane.setMargin(table, new Insets(16, 0, 0, 0));
		return new BorderPane(table);
	}

	private Node visitsTab() {
		if (results.visits().isEmpty()) {
			return partial("Dati delle corse non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		FilteredList<VisitRow> filtered = new FilteredList<>(FXCollections.observableArrayList(results.visits().get()));
		TextField search = new TextField();
		search.setPromptText("Cerca treno o fermata…");
		ComboBox<String> line = new ComboBox<>();
		line.getItems().add("Tutte le linee");
		results.visits().get().stream().map(VisitRow::line).distinct().sorted().forEach(line.getItems()::add);
		line.setValue("Tutte le linee");
		Runnable filter = () -> {
			String text = search.getText().trim().toLowerCase(Locale.ROOT);
			String chosen = line.getValue();
			filtered.setPredicate(row -> ("Tutte le linee".equals(chosen) || row.line().equals(chosen))
				&& (text.isEmpty() || row.vehicle().toLowerCase(Locale.ROOT).contains(text)
					|| labels.stop(row.stop()).toLowerCase(Locale.ROOT).contains(text)));
		};
		search.textProperty().addListener(observable -> filter.run());
		line.valueProperty().addListener(observable -> filter.run());

		TableView<VisitRow> table = new TableView<>();
		table.getStyleClass().add("data-table");
		table.getColumns().add(Columns.text("Treno", 150, VisitRow::vehicle));
		table.getColumns().add(Columns.text("Linea", 70, VisitRow::line));
		table.getColumns().add(Columns.text("Fermata", 200, row -> labels.stop(row.stop())));
		table.getColumns().add(Columns.number("Arrivo previsto", 110, VisitRow::plannedArrival, Columns::clock));
		table.getColumns().add(Columns.number("Arrivo effettivo", 110, VisitRow::actualArrival, Columns::clock));
		table.getColumns().add(Columns.number("Ritardo arrivo", 110, VisitRow::arrivalDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(Columns.number("Partenza prevista", 120, VisitRow::plannedDeparture, Columns::clock));
		table.getColumns().add(Columns.number("Partenza effettiva", 120, VisitRow::actualDeparture, Columns::clock));
		table.getColumns().add(Columns.number("Ritardo partenza", 120, VisitRow::departureDelay, RunLibraryView::minutesSeconds));
		SortedList<VisitRow> sorted = new SortedList<>(filtered);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);

		VBox detail = new VBox(6);
		detail.getStyleClass().add("panel");
		detail.getChildren().add(muted("Seleziona una corsa per vederne le fermate."));
		table.getSelectionModel().selectedItemProperty().addListener((observable, previous, row) -> {
			if (row != null) {
				showTrain(detail, row.vehicle());
			}
		});
		SplitPane split = new SplitPane(table, detail);
		split.setDividerPositions(0.7);
		HBox filters = new HBox(12, search, line);
		filters.setPadding(new Insets(16, 0, 12, 0));
		BorderPane pane = new BorderPane(split);
		pane.setTop(filters);
		return pane;
	}

	private void showTrain(VBox detail, String vehicle) {
		detail.getChildren().clear();
		Label title = new Label(vehicle);
		title.getStyleClass().add("section-title");
		detail.getChildren().add(title);
		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(4);
		int row = 0;
		grid.addRow(row++, muted("Fermata"), muted("Previsto"), muted("Effettivo"), muted("Ritardo"));
		for (VisitRow visit : results.visits().orElseThrow()) {
			if (!visit.vehicle().equals(vehicle)) {
				continue;
			}
			Label delay = new Label(Double.isNaN(visit.arrivalDelay()) ? "—" : RunLibraryView.minutesSeconds(visit.arrivalDelay()));
			if (visit.arrivalDelay() > ON_TIME_THRESHOLD_S) {
				delay.getStyleClass().add("finding-blocking");
			}
			grid.addRow(row++, new Label(labels.stop(visit.stop())), new Label(Columns.clock(visit.plannedArrival())),
				new Label(Columns.clock(visit.actualArrival())), delay);
		}
		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		detail.getChildren().add(scroll);
	}

	private Node costsTab() {
		if (results.costs().isEmpty()) {
			return partial("Dati costi non disponibili per questo run: l'analisi non li ha prodotti.");
		}
		RunResults.Costs costs = results.costs().get();
		HBox figures = new HBox(32,
			metric("Treni-km", String.format(Locale.ITALY, "%,.0f", costs.trainKm()), null, false),
			metric("Treni-ora", String.format(Locale.ITALY, "%,.0f", costs.trainHours()), null, false),
			metric("Flotta impiegata", String.valueOf(costs.fleetSize()), null, false),
			metric("Totale", RunLibraryView.euro(costs.total()), null, false));
		GridPane table = new GridPane();
		table.setHgap(24);
		table.setVgap(6);
		table.addRow(0, muted("Categoria"), muted("Importo"), muted("Quota"));
		int row = 1;
		for (Map.Entry<String, Double> category : costs.byCategory().entrySet()) {
			Label amount = new Label(RunLibraryView.euro(category.getValue()));
			amount.getStyleClass().add("metric");
			table.addRow(row++, new Label(categoryLabel(category.getKey())), amount,
				new Label(costs.total() == 0 ? "—" : Columns.percent(100 * category.getValue() / costs.total())));
		}
		HBox content = new HBox(32, chart(RunResults.COST_BREAKDOWN, 420), table);
		content.setAlignment(Pos.TOP_LEFT);
		VBox column = new VBox(16, figures, content);
		column.setPadding(new Insets(16, 0, 0, 0));
		return column;
	}

	private void exportDialog() {
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.setTitle("Salva come…");
		dialog.setHeaderText("Scegli cosa copiare del run " + entry.name());
		CheckBox csv = new CheckBox("Tabelle CSV (puntualità, treni non arrivati)");
		CheckBox json = new CheckBox("Dati JSON (manifest, costi, scenario)");
		CheckBox png = new CheckBox("Grafici PNG");
		CheckBox raw = new CheckBox("Output grezzo del motore (orario, eventi, fotogrammi: pesante)");
		for (CheckBox box : List.of(csv, json, png)) {
			box.setSelected(true);
		}
		dialog.getDialogPane().setContent(new VBox(8, csv, json, png, raw));
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
		dialog.getDialogPane().getStylesheets().setAll(getScene().getStylesheets());
		if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
			return;
		}
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Cartella di destinazione");
		File chosen = chooser.showDialog(getScene().getWindow());
		if (chosen == null) {
			return;
		}
		int copied = model.runs().export(entry, chosen.toPath(), path -> {
			String name = path.toString();
			if (name.startsWith("raw") || name.startsWith("output") || name.startsWith("scenario")
					|| name.startsWith("frames")) {
				return raw.isSelected();
			}
			return name.endsWith(".csv") ? csv.isSelected() : name.endsWith(".json") ? json.isSelected()
				: name.endsWith(".png") && png.isSelected();
		});
		new Alert(Alert.AlertType.INFORMATION, "Copiati " + copied + " file in " + chosen.toPath().resolve(entry.name()) + ".")
			.showAndWait();
	}

	private static Node partial(String message) {
		VBox box = new VBox(muted(message));
		box.setPadding(new Insets(16, 0, 0, 0));
		return box;
	}

	private static String categoryLabel(String category) {
		return switch (category) {
			case "staff" -> "Personale";
			case "electricity" -> "Elettricità";
			case "diesel" -> "Gasolio";
			case "maintenance" -> "Manutenzione";
			case "rolling_stock" -> "Materiale rotabile";
			case "track_access" -> "Accesso alla rete";
			default -> category;
		};
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
