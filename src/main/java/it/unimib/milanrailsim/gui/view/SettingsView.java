package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.gui.config.GtfsImport;
import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.results.CostParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** App-level configuration: timetable source, cost parameters, archive folders and the measured network. */
public final class SettingsView extends BorderPane {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final Path DEFAULT_COSTS = Path.of("config", "costs.json");

	private final AppModel model;
	private final Consumer<Path> openFolder;
	private final StackPane content = new StackPane();

	public SettingsView(AppModel model, Consumer<Path> openFolder) {
		this.model = model;
		this.openFolder = openFolder;
		getStyleClass().add("content");
		Label title = new Label("Impostazioni");
		title.getStyleClass().add("title");
		setTop(title);

		VBox nav = new VBox(4);
		nav.getStyleClass().add("sub-nav");
		ToggleGroup group = new ToggleGroup();
		Map<String, Supplier<Node>> sections = new LinkedHashMap<>();
		sections.put("Dati di input", this::inputSection);
		sections.put("Costi", this::costsSection);
		sections.put("Archivio", this::archiveSection);
		sections.put("Rete", this::networkSection);
		sections.forEach((name, section) -> {
			ToggleButton button = new ToggleButton(name);
			button.getStyleClass().add("nav-button");
			button.setToggleGroup(group);
			button.setOnAction(event -> content.getChildren().setAll(section.get()));
			nav.getChildren().add(button);
		});
		group.selectedToggleProperty().addListener((observable, previous, selected) -> {
			if (selected == null) {
				previous.setSelected(true);
			}
		});
		((ToggleButton) group.getToggles().getFirst()).fire();
		content.setAlignment(Pos.TOP_LEFT);
		BorderPane body = new BorderPane(content);
		body.setLeft(nav);
		BorderPane.setMargin(content, new Insets(0, 0, 0, 24));
		BorderPane.setMargin(body, new Insets(16, 0, 0, 0));
		setCenter(body);
	}

	private Node inputSection() {
		Label feedTitle = sectionTitle("Orario GTFS");
		GridPane info = grid();
		Label state = muted("Leggo il feed…");
		Button load = new Button("Carica orario…");
		Button loadZip = new Button("Carica archivio zip…");
		load.setOnAction(event -> {
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Cartella del feed GTFS");
			File chosen = chooser.showDialog(getScene().getWindow());
			if (chosen != null) {
				installFeed(chosen.toPath(), info, state);
			}
		});
		loadZip.setOnAction(event -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Archivio GTFS");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivio zip", "*.zip"));
			File chosen = chooser.showOpenDialog(getScene().getWindow());
			if (chosen != null) {
				installFeed(chosen.toPath(), info, state);
			}
		});
		describeFeed(model.gtfsDir(), info, state);

		Label networkTitle = sectionTitle("Rete");
		GridPane network = grid();
		row(network, 0, "Rete mesoscopica", model.files().network().toString());
		row(network, 1, "Orario railsim", model.files().transitSchedule().toString());
		row(network, 2, "Geometrie tracciati", model.files().linkGeometry().toString());
		row(network, 3, "Sistema di riferimento", model.files().crs());

		return panelColumn(feedTitle, info, state, new HBox(8, load, loadZip),
			muted("L'orario per il giorno scelto viene generato al lancio di ogni run; i run già archiviati non cambiano."),
			networkTitle, network);
	}

	private void describeFeed(Path dir, GridPane info, Label state) {
		Task<GtfsImport.FeedInfo> task = new Task<>() {
			@Override
			protected GtfsImport.FeedInfo call() {
				return GtfsImport.inspect(dir);
			}
		};
		task.setOnSucceeded(event -> {
			GtfsImport.FeedInfo feed = task.getValue();
			info.getChildren().clear();
			row(info, 0, "Cartella", dir.toString());
			row(info, 1, "Operatore", feed.agency());
			row(info, 2, "Giorni di servizio", DAY.format(feed.firstDay()) + " – " + DAY.format(feed.lastDay()));
			row(info, 3, "Linee", String.valueOf(feed.routes()));
			row(info, 4, "Fermate", String.valueOf(feed.stops()));
			state.setText("");
		});
		task.setOnFailed(event -> state.setText(task.getException().getMessage()));
		Thread thread = new Thread(task, "feed-inspect");
		thread.setDaemon(true);
		thread.start();
	}

	private void installFeed(Path source, GridPane info, Label state) {
		state.setText("Controllo il feed…");
		Task<Void> task = new Task<>() {
			@Override
			protected Void call() {
				GtfsImport.install(source, model.paths().gtfsDir());
				return null;
			}
		};
		task.setOnSucceeded(event -> {
			model.reloadFeed();
			describeFeed(model.gtfsDir(), info, state);
		});
		task.setOnFailed(event -> {
			Throwable cause = task.getException();
			state.setText(cause instanceof IllegalArgumentException ? cause.getMessage()
				: "Orario non caricato: " + cause.getMessage());
		});
		Thread thread = new Thread(task, "feed-install");
		thread.setDaemon(true);
		thread.start();
	}

	private Node costsSection() {
		CostsTable table = new CostsTable(model.paths().costsFile(), DEFAULT_COSTS);
		return panelColumn(sectionTitle("Costi"), table,
			muted("Valori unitari usati dal computo economico dei run. Le fonti sono documentate nel repository "
				+ "e si aggiornano lì, non da qui."));
	}

	private Node archiveSection() {
		GridPane grid = grid();
		row(grid, 0, "Cartella dei run", model.paths().runs().toString());
		row(grid, 1, "Cartella di configurazione", model.paths().config().toString());
		row(grid, 2, "Cache mappa", model.paths().tileCache().toString());
		row(grid, 3, "Spazio libero", freeSpace(model.paths().root()));
		Button openRuns = new Button("Apri cartella dei run");
		openRuns.setOnAction(event -> openFolder.accept(model.paths().runs()));
		Button openConfig = new Button("Apri cartella di configurazione");
		openConfig.setOnAction(event -> openFolder.accept(model.paths().config()));
		return panelColumn(sectionTitle("Archivio"), grid, new HBox(8, openRuns, openConfig));
	}

	private Node networkSection() {
		TableView<Map<String, String>> table = new TableView<>();
		table.getStyleClass().add("data-table");
		addColumn(table, "Tratta", "link_id", 150);
		addColumn(table, "Stato", "status", 70);
		addColumn(table, "Binari", "passenger_lines_mode", 60);
		addColumn(table, "Lunghezza OSM (m)", "osm_min_m", 120);
		addColumn(table, "Velocità eq. (km/h)", "eq_speed_kmh", 120);
		addColumn(table, "Way OSM", "osm_way_ids", 320);
		table.setPlaceholder(muted("Carico le misure…"));
		Task<List<Map<String, String>>> task = new Task<>() {
			@Override
			protected List<Map<String, String>> call() {
				return CsvTable.read(Path.of("data", "osm", "2026-08-05-network-sweep", "link_measures.csv"));
			}
		};
		task.setOnSucceeded(event -> table.getItems().setAll(task.getValue()));
		Thread thread = new Thread(task, "measures-load");
		thread.setDaemon(true);
		thread.start();
		VBox.setVgrow(table, Priority.ALWAYS);
		return panelColumn(sectionTitle("Rete misurata"),
			muted("Misure OSM per tratta usate dalla rete mesoscopica: sola lettura, fonte data/osm nel repository."), table);
	}

	private static void addColumn(TableView<Map<String, String>> table, String title, String key, double width) {
		TableColumn<Map<String, String>, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getOrDefault(key, "")));
		column.setPrefWidth(width);
		table.getColumns().add(column);
	}

	private static String freeSpace(Path root) {
		try {
			Path existing = root;
			while (!Files.exists(existing)) {
				existing = existing.getParent();
			}
			return String.format(Locale.ITALY, "%.1f GB liberi di %.0f GB",
				Files.getFileStore(existing).getUsableSpace() / 1e9, Files.getFileStore(existing).getTotalSpace() / 1e9);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read free space of " + root, e);
		}
	}

	private static Node panelColumn(Node... children) {
		VBox column = new VBox(12, children);
		column.setMaxWidth(900);
		return column;
	}

	private static GridPane grid() {
		GridPane grid = new GridPane();
		grid.setHgap(16);
		grid.setVgap(6);
		return grid;
	}

	private static void row(GridPane grid, int row, String label, String value) {
		Label key = muted(label);
		key.setMinWidth(180);
		Label text = new Label(value);
		text.getStyleClass().add("metric");
		grid.addRow(row, key, text);
	}

	private static Label sectionTitle(String text) {
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

	/** Editable cost table backed by the costs JSON; explicit apply, reset to the repository defaults. */
	private static final class CostsTable extends VBox {

		private final Path file;
		private final TableView<CostRow> table = new TableView<>();
		private final Label unsaved = new Label("Modifiche non salvate");
		private final Button apply = new Button("Applica");

		private static final class CostRow {
			final String category;
			final SimpleStringProperty value;
			final String unit;
			final String source;

			CostRow(String category, double value, String unit, String source) {
				this.category = category;
				this.value = new SimpleStringProperty(String.valueOf(value));
				this.unit = unit;
				this.source = source;
			}
		}

		CostsTable(Path file, Path defaults) {
			this.file = file;
			setSpacing(8);
			table.getStyleClass().add("data-table");
			table.setEditable(true);
			table.setPrefHeight(260);
			TableColumn<CostRow, String> category = new TableColumn<>("Categoria");
			category.setCellValueFactory(cell -> new SimpleStringProperty(label(cell.getValue().category)));
			category.setPrefWidth(160);
			TableColumn<CostRow, String> value = new TableColumn<>("Valore");
			value.setCellValueFactory(cell -> cell.getValue().value);
			value.setCellFactory(TextFieldTableCell.forTableColumn());
			value.setOnEditCommit(event -> {
				event.getRowValue().value.set(event.getNewValue());
				unsaved.setVisible(true);
				validate();
			});
			value.setPrefWidth(90);
			TableColumn<CostRow, String> unit = new TableColumn<>("Unità");
			unit.setCellValueFactory(cell -> new SimpleStringProperty(unitLabel(cell.getValue().unit)));
			unit.setPrefWidth(110);
			TableColumn<CostRow, String> source = new TableColumn<>("Fonte");
			source.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().source));
			source.setPrefWidth(420);
			table.getColumns().addAll(List.of(category, value, unit, source));
			load(file);

			unsaved.getStyleClass().add("finding-warning");
			unsaved.setVisible(false);
			apply.getStyleClass().add("primary");
			apply.setOnAction(event -> save());
			Button cancel = new Button("Annulla");
			cancel.setOnAction(event -> load(file));
			Button reset = new Button("Ripristina valori di default");
			reset.setOnAction(event -> {
				Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
					"Ripristinare i valori di default del repository?", ButtonType.OK, ButtonType.CANCEL);
				if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
					load(defaults);
					save();
				}
			});
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox actions = new HBox(8, unsaved, spacer, reset, cancel, apply);
			actions.setAlignment(Pos.CENTER_LEFT);
			getChildren().addAll(table, actions);
			validate();
		}

		private void load(Path source) {
			CostParameters parameters = CostParameters.load(source);
			table.getItems().clear();
			parameters.categories().forEach((name, entry) ->
				table.getItems().add(new CostRow(name, entry.unitCost(), entry.unit(), entry.source())));
			unsaved.setVisible(source != file);
			validate();
		}

		private void validate() {
			boolean valid = table.getItems().stream().allMatch(row -> {
				try {
					return Double.parseDouble(row.value.get().replace(',', '.')) >= 0;
				} catch (NumberFormatException e) {
					return false;
				}
			});
			apply.setDisable(!valid);
		}

		private void save() {
			Map<String, Map<String, Object>> categories = new LinkedHashMap<>();
			for (CostRow row : table.getItems()) {
				categories.put(row.category, Map.of(
					"unitCost", Double.parseDouble(row.value.get().replace(',', '.')),
					"unit", row.unit,
					"source", row.source));
			}
			try {
				Files.createDirectories(file.getParent());
				new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
					.writeValue(file.toFile(), Map.of("currency", "EUR", "categories", categories));
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot write " + file, e);
			}
			unsaved.setVisible(false);
		}

		private static String label(String category) {
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

		private static String unitLabel(String unit) {
			return switch (unit) {
				case "train_hour" -> "€/treno-ora";
				case "train_km" -> "€/treno-km";
				case "train_day" -> "€/treno-giorno";
				default -> unit;
			};
		}
	}
}
