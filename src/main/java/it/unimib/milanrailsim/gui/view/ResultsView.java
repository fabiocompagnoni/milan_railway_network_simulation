package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.runs.RunLibrary.Entry;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.RunResults.LineRow;
import it.unimib.milanrailsim.runs.RunResults.VisitRow;
import it.unimib.milanrailsim.runs.RunResults;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Results of one archived run: headline figures, charts, per-line and per-visit tables, costs, export. */
public final class ResultsView extends BorderPane {

	private static final double ON_TIME_THRESHOLD_S = 300;

	private final AppModel model;
	private final Runnable onOpenLibrary;
	private final StackPane body = new StackPane();
	private Entry baseline;
	private RunResults baselineResults;
	private final ToggleButton compare = new ToggleButton("Confronta con reale");
	private final HBox headline = new HBox(32);
	private Entry entry;
	private RunResults results;

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
		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setMaxSize(32, 32);
		VBox loading = new VBox(12, spinner, muted("Carico i risultati del run…"));
		loading.setAlignment(Pos.CENTER);
		body.getChildren().setAll(loading);
		Task<RunResults> task = new Task<>() {
			@Override
			protected RunResults call() {
				baseline = findBaseline(selected);
				baselineResults = baseline == null ? null : RunResults.load(baseline.dir());
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
		Label subtitle = muted(entry.scenarioLabel() + entry.spec().map(spec -> " · " + spec.serviceDate()).orElse(""));
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
			new Tab("Corse", visitsTab()), new Tab("Costi", costsTab()));
		VBox.setVgrow(tabs, Priority.ALWAYS);
		VBox column = new VBox(16, header, headline, tabs);
		body.getChildren().setAll(column);
	}

	private void fillHeadline() {
		RunLibrary.Manifest manifest = entry.manifest().orElseThrow();
		boolean delta = compare.isSelected() && baseline != null;
		RunLibrary.Manifest base = delta ? baseline.manifest().orElseThrow() : null;
		headline.getChildren().setAll(
			metric("Ritardo medio all'arrivo", RunLibraryView.minutesSeconds(manifest.meanArrivalDelaySeconds()),
				delta ? RunLibraryView.minutesSeconds(manifest.meanArrivalDelaySeconds() - base.meanArrivalDelaySeconds()) : null,
				delta && manifest.meanArrivalDelaySeconds() > base.meanArrivalDelaySeconds()),
			metric("Puntualità (entro 5 min)", results.punctuality(ON_TIME_THRESHOLD_S).map(ResultsView::percent).orElse("—"),
				delta ? baselineResults.punctuality(ON_TIME_THRESHOLD_S).flatMap(b -> results.punctuality(ON_TIME_THRESHOLD_S)
					.map(v -> String.format(Locale.ITALY, "%+.1f punti", v - b))).orElse(null) : null, false),
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
		VBox column = new VBox(16, charts, spaceTime);
		column.setPadding(new Insets(16, 0, 0, 0));
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		return scroll;
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
		TableView<LineRow> table = new TableView<>(FXCollections.observableArrayList(results.byLine().get()));
		table.getStyleClass().add("data-table");
		table.getColumns().add(text("Linea", 90, LineRow::line));
		table.getColumns().add(number("Fermate osservate", 130, row -> (double) row.observations(), v -> String.valueOf(v.intValue())));
		table.getColumns().add(number("Ritardo medio", 120, LineRow::meanDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(number("Mediana", 100, LineRow::medianDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(number("95° percentile", 120, LineRow::p95Delay, RunLibraryView::minutesSeconds));
		table.getColumns().add(number("Massimo", 100, LineRow::maxDelay, RunLibraryView::minutesSeconds));
		if (baselineResults != null && baselineResults.byLine().isPresent()) {
			Map<String, LineRow> base = new java.util.HashMap<>();
			baselineResults.byLine().get().forEach(row -> base.put(row.line(), row));
			table.getColumns().add(number("Δ medio vs reale", 130,
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
				&& (text.isEmpty() || row.vehicle().toLowerCase(Locale.ROOT).contains(text) || row.stop().toLowerCase(Locale.ROOT).contains(text)));
		};
		search.textProperty().addListener(observable -> filter.run());
		line.valueProperty().addListener(observable -> filter.run());

		TableView<VisitRow> table = new TableView<>();
		table.getStyleClass().add("data-table");
		table.getColumns().add(text("Treno", 150, VisitRow::vehicle));
		table.getColumns().add(text("Linea", 70, VisitRow::line));
		table.getColumns().add(text("Fermata", 100, VisitRow::stop));
		table.getColumns().add(number("Arrivo previsto", 110, VisitRow::plannedArrival, ResultsView::clock));
		table.getColumns().add(number("Arrivo effettivo", 110, VisitRow::actualArrival, ResultsView::clock));
		table.getColumns().add(number("Ritardo arrivo", 110, VisitRow::arrivalDelay, RunLibraryView::minutesSeconds));
		table.getColumns().add(number("Partenza prevista", 120, VisitRow::plannedDeparture, ResultsView::clock));
		table.getColumns().add(number("Partenza effettiva", 120, VisitRow::actualDeparture, ResultsView::clock));
		table.getColumns().add(number("Ritardo partenza", 120, VisitRow::departureDelay, RunLibraryView::minutesSeconds));
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
			grid.addRow(row++, new Label(visit.stop()), new Label(clock(visit.plannedArrival())),
				new Label(clock(visit.actualArrival())), delay);
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
				new Label(costs.total() == 0 ? "—" : percent(100 * category.getValue() / costs.total())));
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
		CheckBox csv = new CheckBox("Tabelle CSV (puntualità)");
		CheckBox json = new CheckBox("Dati JSON (manifest, costi, scenario)");
		CheckBox png = new CheckBox("Grafici PNG");
		CheckBox raw = new CheckBox("Output grezzo del motore (raw, pesante)");
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
			if (name.startsWith("raw")) {
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

	private static <T> TableColumn<T, String> text(String title, double width, Function<T, String> value) {
		TableColumn<T, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
		column.setPrefWidth(width);
		return column;
	}

	private static <T> TableColumn<T, Double> number(String title, double width, Function<T, Double> value,
			Function<Double, String> format) {
		TableColumn<T, Double> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
		column.setComparator(Comparator.comparingDouble(v -> Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v));
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(Double item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty ? "" : item == null || Double.isNaN(item) ? "—" : format.apply(item));
				setAlignment(Pos.CENTER_RIGHT);
			}
		});
		column.setPrefWidth(width);
		return column;
	}

	static String clock(double secondsOfDay) {
		if (Double.isNaN(secondsOfDay)) {
			return "—";
		}
		long total = Math.round(secondsOfDay);
		return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
	}

	private static String percent(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ITALY, "%.1f %%", value);
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
