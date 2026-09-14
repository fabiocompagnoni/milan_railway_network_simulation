package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.runs.RunLibrary.Entry;
import it.unimib.milanrailsim.runs.RunLibrary.Status;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.ScenarioSpec.SimulationType;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/** Every archived run at a glance, with open, export and delete. */
public final class RunLibraryView extends BorderPane {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	private final AppModel model;
	private final Consumer<Entry> onOpen;
	private final ObservableList<Entry> entries = FXCollections.observableArrayList();
	private final FilteredList<Entry> filtered = new FilteredList<>(entries);
	private final TableView<Entry> table = new TableView<>();
	private final TextField search = new TextField();
	private final ComboBox<SimulationType> typeFilter = new ComboBox<>();
	private final StackPane body = new StackPane();
	private final Label state = new Label();

	public RunLibraryView(AppModel model, Consumer<Entry> onOpen, Runnable onNew) {
		this.model = model;
		this.onOpen = onOpen;
		getStyleClass().add("content");

		Label title = new Label("Archivio");
		title.getStyleClass().add("title");
		Button create = new Button("+ Nuova simulazione");
		create.getStyleClass().add("primary");
		create.setOnAction(event -> onNew.run());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, title, spacer, create);
		header.setAlignment(Pos.CENTER_LEFT);

		search.setPromptText("Cerca per nome…");
		search.setPrefColumnCount(24);
		search.textProperty().addListener(observable -> applyFilter());
		typeFilter.getItems().add(null);
		typeFilter.getItems().addAll(SimulationType.values());
		typeFilter.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(SimulationType type) {
				return type == null ? "Tutti gli scenari" : type.label();
			}

			@Override
			public SimulationType fromString(String text) {
				return null;
			}
		});
		typeFilter.setValue(null);
		typeFilter.valueProperty().addListener(observable -> applyFilter());
		HBox filters = new HBox(12, search, typeFilter);
		filters.setAlignment(Pos.CENTER_LEFT);

		setTop(new VBox(16, header, filters));
		buildTable();
		state.getStyleClass().add("text-muted");
		body.getChildren().setAll(table);
		BorderPane.setMargin(body, new Insets(16, 0, 0, 0));
		setCenter(body);
		setBottom(actions());
		setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ENTER) {
				selected().ifPresent(this::open);
			} else if (event.getCode() == KeyCode.DELETE) {
				deleteSelected();
			} else if (event.getCode() == KeyCode.F && event.isControlDown()) {
				search.requestFocus();
			} else if (event.getCode() == KeyCode.N && event.isControlDown()) {
				onNew.run();
			}
		});
		reload();
	}

	private void buildTable() {
		table.getStyleClass().add("data-table");
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.setPlaceholder(state);
		TableColumn<Entry, String> name = column("Run", 220, entry -> entry.name());
		TableColumn<Entry, String> scenario = column("Scenario", 170, Entry::scenarioLabel);
		TableColumn<Entry, String> day = column("Giorno simulato", 120,
			entry -> entry.spec().map(spec -> spec.serviceDate().toString()).orElse("—"));
		TableColumn<Entry, String> status = column("Stato", 100, entry -> entry.status().label());
		TableColumn<Entry, Double> delay = numeric("Ritardo medio", 110,
			entry -> entry.manifest().map(RunLibrary.Manifest::meanArrivalDelaySeconds).orElse(Double.NaN),
			RunLibraryView::minutesSeconds);
		TableColumn<Entry, Double> cost = numeric("Costo", 110,
			entry -> entry.manifest().map(RunLibrary.Manifest::totalCost).orElse(Double.NaN), RunLibraryView::euro);
		TableColumn<Entry, Double> anomalies = numeric("Anomalie", 80,
			entry -> entry.manifest().map(m -> (double) m.anomalies()).orElse(Double.NaN),
			value -> String.valueOf(value.intValue()));
		TableColumn<Entry, Double> unfinished = numeric("Non arrivati", 100,
			entry -> entry.manifest().map(m -> (double) m.unfinishedTrains()).orElse(Double.NaN),
			value -> String.valueOf(value.intValue()));
		TableColumn<Entry, String> created = column("Creato", 130,
			entry -> entry.manifest().map(m -> STAMP.format(m.created())).orElse("—"));
		table.getColumns().setAll(List.of(name, scenario, day, status, delay, cost, unfinished, anomalies, created));
		table.setRowFactory(view -> {
			javafx.scene.control.TableRow<Entry> row = new javafx.scene.control.TableRow<>();
			row.setOnMouseClicked(event -> {
				if (event.getClickCount() == 2 && !row.isEmpty()) {
					open(row.getItem());
				}
			});
			return row;
		});
		SortedList<Entry> sorted = new SortedList<>(filtered);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);
	}

	private Node actions() {
		Button open = new Button("Apri risultati");
		open.setOnAction(event -> selected().ifPresent(this::open));
		Button export = new Button("Esporta…");
		export.setOnAction(event -> selected().ifPresent(this::export));
		Button delete = new Button("Elimina");
		delete.setOnAction(event -> deleteSelected());
		Label count = new Label();
		count.getStyleClass().add("text-muted");
		table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Entry>) change -> {
			int size = table.getSelectionModel().getSelectedItems().size();
			count.setText(size == 0 ? "" : size == 1 ? "1 selezionato" : size + " selezionati");
			boolean single = size == 1;
			open.setDisable(!single || table.getSelectionModel().getSelectedItem().status() != Status.COMPLETED);
			export.setDisable(!single);
			delete.setDisable(size == 0);
		});
		open.setDisable(true);
		export.setDisable(true);
		delete.setDisable(true);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox bar = new HBox(8, count, spacer, open, export, delete);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(12, 0, 0, 0));
		return bar;
	}

	private void reload() {
		state.setText("Carico le simulazioni…");
		Task<List<Entry>> task = new Task<>() {
			@Override
			protected List<Entry> call() {
				return model.runs().scan();
			}
		};
		task.setOnSucceeded(event -> {
			entries.setAll(task.getValue());
			state.setText(entries.isEmpty() ? "Nessuna simulazione ancora. Avvia la prima simulazione per vedere qui i risultati."
				: "Nessun run corrisponde ai filtri.");
		});
		task.setOnFailed(event -> state.setText("Impossibile leggere le simulazioni archiviate. Verifica i permessi di "
			+ model.paths().runs() + " e riprova."));
		Thread thread = new Thread(task, "runs-scan");
		thread.setDaemon(true);
		thread.start();
	}

	private void applyFilter() {
		String text = search.getText().trim().toLowerCase(Locale.ROOT);
		SimulationType type = typeFilter.getValue();
		filtered.setPredicate(entry -> (text.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(text))
			&& (type == null || entry.spec().map(spec -> spec.type() == type).orElse(false)));
	}

	private Optional<Entry> selected() {
		return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
	}

	private void open(Entry entry) {
		if (entry.status() == Status.COMPLETED) {
			onOpen.accept(entry);
		}
	}

	private void export(Entry entry) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Cartella di destinazione");
		File chosen = chooser.showDialog(getScene().getWindow());
		if (chosen == null) {
			return;
		}
		int copied = model.runs().export(entry, chosen.toPath(), path -> true);
		new Alert(Alert.AlertType.INFORMATION, "Copiati " + copied + " file in " + chosen.toPath().resolve(entry.name()) + ".")
			.showAndWait();
	}

	private void deleteSelected() {
		List<Entry> chosen = List.copyOf(table.getSelectionModel().getSelectedItems());
		if (chosen.isEmpty()) {
			return;
		}
		String what = chosen.size() == 1 ? "il run «" + chosen.getFirst().name() + "»" : chosen.size() + " run";
		Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Eliminare " + what + "? L'azione non è reversibile.",
			ButtonType.OK, ButtonType.CANCEL);
		if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
			chosen.forEach(model.runs()::delete);
			reload();
		}
	}

	private static TableColumn<Entry, String> column(String title, double width, java.util.function.Function<Entry, String> value) {
		TableColumn<Entry, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
		column.setPrefWidth(width);
		return column;
	}

	/** Numeric column: sorts on the raw value, shows it formatted, right-aligned; NaN shows as a dash. */
	private static TableColumn<Entry, Double> numeric(String title, double width,
			java.util.function.Function<Entry, Double> value, java.util.function.Function<Double, String> format) {
		TableColumn<Entry, Double> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
		column.setComparator(Comparator.comparingDouble(v -> Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v));
		column.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
			@Override
			protected void updateItem(Double item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null || Double.isNaN(item) ? (empty ? "" : "—") : format.apply(item));
				setAlignment(Pos.CENTER_RIGHT);
			}
		});
		column.setPrefWidth(width);
		return column;
	}

	static String minutesSeconds(double seconds) {
		long rounded = Math.round(Math.abs(seconds));
		return (seconds < 0 ? "−" : "") + rounded / 60 + "m " + String.format(Locale.ROOT, "%02d", rounded % 60) + "s";
	}

	static String euro(double amount) {
		return String.format(Locale.ITALY, "€ %,.0f", amount);
	}
}
