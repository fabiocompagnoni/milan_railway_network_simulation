package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.gui.config.ScenarioSpec;
import it.unimib.milanrailsim.gui.config.ScenarioSpec.SimulationType;
import it.unimib.milanrailsim.gui.config.ScenarioSpec.TimeWindow;
import it.unimib.milanrailsim.gui.sim.Preflight;
import it.unimib.milanrailsim.gui.sim.ScenarioSummary;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.ServiceCalendar;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.function.Consumer;

/**
 * Configures a run: service day, time window, simulation type with its
 * parameters, a live summary of what will be simulated and the preflight
 * checks; "Avvia simulazione" hands the resulting {@link ScenarioSpec} over.
 */
public final class NewSimulationView extends BorderPane {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final Duration SUMMARY_DEBOUNCE = Duration.millis(150);

	private final AppModel model;
	private final Consumer<ScenarioSpec> onStart;
	private final RouteVehicleAssignment assignment = new RouteVehicleAssignment();

	private final TextField name = new TextField();
	private final DatePicker day = new DatePicker();
	private final RadioButton wholeDay = new RadioButton("Giornata intera");
	private final RadioButton customWindow = new RadioButton("Finestra");
	private final Spinner<Integer> windowStart = hourSpinner(6);
	private final Spinner<Integer> windowEnd = hourSpinner(10);
	private final ToggleGroup typeGroup = new ToggleGroup();
	private final StackPane parameters = new StackPane();
	private final Spinner<Integer> metroHeadway = new Spinner<>(2, 15, 4);
	private final Spinner<Integer> collapseStart = new Spinner<>(3, 30, 10);
	private final Spinner<Integer> collapseStep = new Spinner<>(1, 10, 2);
	private final Spinner<Integer> collapseSteps = new Spinner<>(2, 10, 4);
	private final Slider dynamicReduction = new Slider(0, 60, 35);
	private final Label dynamicPreview = new Label();

	private final GridPane summaryGrid = new GridPane();
	private final FlowPane lineChips = new FlowPane(6, 6);
	private final Label summaryState = new Label();
	private final VBox findings = new VBox(6);
	private final Button start = new Button("Avvia simulazione");
	private final PauseTransition debounce = new PauseTransition(SUMMARY_DEBOUNCE);

	private final Map<SimulationType, Node> parameterPanels = new EnumMap<>(SimulationType.class);
	private GtfsFeed feed;
	private boolean blocked = true;

	public NewSimulationView(AppModel model, Consumer<ScenarioSpec> onStart) {
		this.model = model;
		this.onStart = onStart;
		getStyleClass().add("content");

		VBox form = new VBox(24, scenarioSection(), typeSection(), summarySection());
		ScrollPane scroll = new ScrollPane(form);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		setCenter(scroll);
		setBottom(actionBar());

		debounce.setOnFinished(event -> refresh());
		setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ENTER && event.isControlDown() && !start.isDisabled()) {
				startRun();
			}
		});
		showLoading();
		model.feed().thenAccept(loaded -> Platform.runLater(() -> onFeedLoaded(loaded)));
	}

	private Node scenarioSection() {
		Label title = new Label("Nuova simulazione");
		title.getStyleClass().add("title");

		GridPane grid = new GridPane();
		grid.setHgap(16);
		grid.setVgap(12);
		grid.add(new Label("Nome run"), 0, 0);
		name.setPrefColumnCount(28);
		name.textProperty().addListener(observable -> scheduleRefresh());
		grid.add(name, 1, 0);

		grid.add(new Label("Giorno di servizio"), 0, 1);
		day.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(LocalDate date) {
				return date == null ? "" : DAY.format(date);
			}

			@Override
			public LocalDate fromString(String text) {
				return text == null || text.isBlank() ? null : LocalDate.parse(text, DAY);
			}
		});
		day.valueProperty().addListener(observable -> scheduleRefresh());
		grid.add(day, 1, 1);

		ToggleGroup windowGroup = new ToggleGroup();
		wholeDay.setToggleGroup(windowGroup);
		customWindow.setToggleGroup(windowGroup);
		wholeDay.setSelected(true);
		HBox range = new HBox(6, windowStart, new Label("–"), windowEnd);
		range.setAlignment(Pos.CENTER_LEFT);
		range.disableProperty().bind(customWindow.selectedProperty().not());
		windowGroup.selectedToggleProperty().addListener(observable -> scheduleRefresh());
		windowStart.valueProperty().addListener(observable -> scheduleRefresh());
		windowEnd.valueProperty().addListener(observable -> scheduleRefresh());
		HBox window = new HBox(16, wholeDay, customWindow, range);
		window.setAlignment(Pos.CENTER_LEFT);
		grid.add(new Label("Finestra oraria"), 0, 2);
		grid.add(window, 1, 2);

		return new VBox(16, title, grid);
	}

	private Node typeSection() {
		Label title = new Label("Tipo di simulazione");
		title.getStyleClass().add("section-title");
		HBox segments = new HBox();
		segments.getStyleClass().add("segmented");
		for (SimulationType type : SimulationType.values()) {
			ToggleButton button = new ToggleButton(type.label());
			button.setUserData(type);
			button.setToggleGroup(typeGroup);
			button.getStyleClass().add("segment");
			segments.getChildren().add(button);
		}
		typeGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
			if (selected == null) {
				previous.setSelected(true);
				return;
			}
			showParameters((SimulationType) selected.getUserData());
			scheduleRefresh();
		});
		typeGroup.getToggles().getFirst().setSelected(true);
		parameters.setAlignment(Pos.TOP_LEFT);
		return new VBox(12, title, segments, parameters);
	}

	private void showParameters(SimulationType type) {
		Node panel = parameterPanels.computeIfAbsent(type, key -> switch (key) {
			case REAL -> muted("Orario reale del giorno scelto, senza modifiche.");
			case METRO_LIKE -> metroPanel();
			case COLLAPSE -> collapsePanel();
			case DYNAMIC -> dynamicPanel();
		});
		parameters.getChildren().setAll(panel);
	}

	private Node metroPanel() {
		metroHeadway.valueProperty().addListener(observable -> scheduleRefresh());
		HBox row = new HBox(12, new Label("Distanziamento obiettivo sul Passante"), metroHeadway, new Label("minuti"));
		row.setAlignment(Pos.CENTER_LEFT);
		return new VBox(8, row, muted("Vale solo per il tratto urbano comune alle linee S del Passante; "
			+ "il resto della rete resta all'orario reale."));
	}

	private Node collapsePanel() {
		for (Spinner<Integer> spinner : List.of(collapseStart, collapseStep, collapseSteps)) {
			spinner.valueProperty().addListener(observable -> scheduleRefresh());
		}
		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(8);
		grid.addRow(0, new Label("Distanziamento iniziale"), collapseStart, new Label("minuti"));
		grid.addRow(1, new Label("Riduzione a ogni passo"), collapseStep, new Label("minuti"));
		grid.addRow(2, new Label("Numero di passi"), collapseSteps);
		return new VBox(8, grid, muted("Una campagna di run, uno per passo, finché il servizio degrada."));
	}

	private Node dynamicPanel() {
		dynamicReduction.setShowTickMarks(true);
		dynamicReduction.setShowTickLabels(true);
		dynamicReduction.setMajorTickUnit(10);
		dynamicReduction.setBlockIncrement(5);
		dynamicReduction.setSnapToTicks(false);
		dynamicReduction.setPrefWidth(360);
		dynamicReduction.valueProperty().addListener(observable -> scheduleRefresh());
		Label value = new Label();
		value.textProperty().bind(dynamicReduction.valueProperty().map(v -> Math.round(v.doubleValue()) + " %"));
		HBox row = new HBox(12, new Label("Riduzione degli intervalli, tutte le linee"), dynamicReduction, value);
		row.setAlignment(Pos.CENTER_LEFT);
		dynamicPreview.getStyleClass().add("text-muted");
		return new VBox(8, row, dynamicPreview);
	}

	private Node summarySection() {
		Label title = new Label("Riepilogo");
		title.getStyleClass().add("section-title");
		summaryGrid.setHgap(24);
		summaryGrid.setVgap(6);
		summaryState.getStyleClass().add("text-muted");
		return new VBox(12, title, summaryState, lineChips, summaryGrid, findings);
	}

	private Node actionBar() {
		start.getStyleClass().add("primary");
		start.setOnAction(event -> startRun());
		HBox bar = new HBox(start);
		bar.setAlignment(Pos.CENTER_RIGHT);
		bar.setPadding(new Insets(16, 0, 0, 0));
		return bar;
	}

	private void showLoading() {
		summaryState.setText("Carico l'orario…");
		start.setDisable(true);
	}

	private void onFeedLoaded(GtfsFeed loaded) {
		feed = loaded;
		NavigableSet<LocalDate> dates = ServiceCalendar.availableDates(loaded.calendarDateRows());
		day.setDayCellFactory(picker -> new DateCell() {
			@Override
			public void updateItem(LocalDate date, boolean empty) {
				super.updateItem(date, empty);
				setDisable(empty || !dates.contains(date));
			}
		});
		LocalDate today = LocalDate.now();
		day.setValue(dates.contains(today) ? today : dates.ceiling(today) != null ? dates.ceiling(today) : dates.last());
		name.setText(suggestedName());
		refresh();
	}

	private String suggestedName() {
		SimulationType type = (SimulationType) typeGroup.getSelectedToggle().getUserData();
		String slug = switch (type) {
			case REAL -> "reale";
			case METRO_LIKE -> "passante";
			case COLLAPSE -> "collasso";
			case DYNAMIC -> "dinamica";
		};
		return slug + "_" + day.getValue();
	}

	private void scheduleRefresh() {
		if (feed != null) {
			debounce.playFromStart();
		}
	}

	private void refresh() {
		ScenarioSpec spec = spec();
		ScenarioSummary summary = ScenarioSummary.of(feed, assignment, spec);
		List<Preflight.Finding> checks = new Preflight(model.paths().runs(), model.paths().costsFile())
			.check(spec.name(), spec.runCount());
		showSummary(spec, summary);
		showFindings(checks);
		blocked = checks.stream().anyMatch(Preflight.Finding::blocking);
		start.setDisable(blocked);
	}

	private void showSummary(ScenarioSpec spec, ScenarioSummary summary) {
		summaryState.setText(spec.serviceDate() == null ? "Scegli un giorno di servizio." : "");
		lineChips.getChildren().clear();
		for (String line : summary.lines()) {
			Label chip = new Label(line);
			chip.getStyleClass().add("chip");
			chip.setStyle("-fx-background-color: #" + feed.routesById().values().stream()
				.filter(route -> route.shortName().equals(line)).findFirst().map(GtfsFeed.Route::color).orElse("888888"));
			lineChips.getChildren().add(chip);
		}
		summaryGrid.getChildren().clear();
		int row = 0;
		row = metric(row, "Linee coinvolte", String.valueOf(summary.lines().size()));
		row = metric(row, "Corse attese", summary.extraTrips() > 0
			? summary.trips() + " (di cui " + summary.extraTrips() + " aggiunte, stima)" : String.valueOf(summary.trips()));
		row = metric(row, "Flotta", fleetText(summary.fleetSharesPercent()));
		row = metric(row, "Run prodotti", String.valueOf(spec.runCount()));
		metric(row, "Spazio stimato", String.format("~%.1f GB", spec.runCount() * 1.0));
		if (spec.type() == SimulationType.DYNAMIC) {
			dynamicPreview.setText(spec.dynamicReductionPercent() == 0
				? "0 % equivale allo scenario Reale."
				: "A questo valore: circa " + summary.extraTrips() + " corse in più sull'intera giornata (stima).");
		}
	}

	private int metric(int row, String label, String value) {
		Label key = new Label(label);
		key.getStyleClass().add("text-muted");
		Label text = new Label(value);
		text.getStyleClass().add("metric");
		summaryGrid.addRow(row, key, text);
		return row + 1;
	}

	private static String fleetText(Map<String, Integer> shares) {
		StringBuilder text = new StringBuilder();
		shares.forEach((type, percent) -> text.append(text.isEmpty() ? "" : ", ").append(type).append(' ').append(percent).append('%'));
		return text.isEmpty() ? "—" : text.toString();
	}

	private void showFindings(List<Preflight.Finding> checks) {
		findings.getChildren().clear();
		for (Preflight.Finding finding : checks) {
			Label label = new Label((finding.blocking() ? "Blocco: " : "Avviso: ") + finding.message());
			label.setWrapText(true);
			label.getStyleClass().add(finding.blocking() ? "finding-blocking" : "finding-warning");
			findings.getChildren().add(label);
		}
	}

	private ScenarioSpec spec() {
		SimulationType type = (SimulationType) typeGroup.getSelectedToggle().getUserData();
		TimeWindow window = customWindow.isSelected()
			? new TimeWindow(LocalTime.of(windowStart.getValue(), 0), LocalTime.of(windowEnd.getValue(), 0)) : null;
		return new ScenarioSpec(name.getText().trim(), type, day.getValue(), window,
			type == SimulationType.METRO_LIKE ? metroHeadway.getValue() : null,
			type == SimulationType.COLLAPSE ? collapseStart.getValue() : null,
			type == SimulationType.COLLAPSE ? collapseStep.getValue() : null,
			type == SimulationType.COLLAPSE ? collapseSteps.getValue() : null,
			type == SimulationType.DYNAMIC ? (int) Math.round(dynamicReduction.getValue()) : null);
	}

	private void startRun() {
		refresh();
		if (!blocked) {
			onStart.accept(spec());
		}
	}

	private static Spinner<Integer> hourSpinner(int initial) {
		Spinner<Integer> spinner = new Spinner<>(0, 30, initial);
		spinner.setPrefWidth(72);
		spinner.setEditable(true);
		return spinner;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
