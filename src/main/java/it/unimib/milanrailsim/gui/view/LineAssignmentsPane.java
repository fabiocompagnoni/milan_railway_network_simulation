package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.network.FleetConfig.TrainType;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.schedule.LineAssignments.Share;
import it.unimib.milanrailsim.schedule.RouteVehicleAssignment;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One row per line with its share bar; a row expands into an editor whose shares must reach 100%. */
final class LineAssignmentsPane extends ScrollPane {

	private static final String[] BAR_COLORS = { "#1E7A4E", "#3D7FBE", "#C8501E", "#8A5FBE", "#B08A00", "#5B6B62" };

	private final AppModel model;
	private final VBox rows = new VBox(6);
	private String openLine;

	LineAssignmentsPane(AppModel model) {
		this.model = model;
		setFitToWidth(true);
		getStyleClass().add("plain-scroll");
		rows.setPadding(new Insets(16, 0, 0, 0));
		setContent(rows);
		Label loading = new Label("Carico le linee…");
		loading.getStyleClass().add("text-muted");
		rows.getChildren().add(loading);
		model.feed().thenAccept(feed -> Platform.runLater(() -> rebuild(feed)));
		model.assignments().addListener(observable -> model.feed().thenAccept(feed -> Platform.runLater(() -> rebuild(feed))));
	}

	private void rebuild(GtfsFeed feed) {
		RouteVehicleAssignment rules = new RouteVehicleAssignment(model.assignments().get());
		List<String> lines = feed.routesById().values().stream()
			.filter(route -> route.type() == 2 && !rules.isExcluded(route.shortName()))
			.map(GtfsFeed.Route::shortName).distinct()
			.sorted(Comparator.comparing((String line) -> !line.startsWith("S")).thenComparing(LineAssignmentsPane::numericOrder))
			.toList();
		rows.getChildren().clear();
		for (String line : lines) {
			rows.getChildren().add(row(line));
			if (line.equals(openLine)) {
				rows.getChildren().add(editor(line));
			}
		}
	}

	private Node row(String line) {
		List<Share> shares = model.assignments().get().sharesOf(line);
		Label name = new Label(line);
		name.getStyleClass().add("metric");
		name.setMinWidth(56);
		HBox bar = new HBox();
		bar.setPrefHeight(10);
		bar.setPrefWidth(220);
		bar.setMaxWidth(220);
		for (int i = 0; i < shares.size(); i++) {
			Region segment = new Region();
			segment.setStyle("-fx-background-color: " + BAR_COLORS[i % BAR_COLORS.length] + ";");
			HBox.setHgrow(segment, Priority.ALWAYS);
			segment.setMaxWidth(220 * shares.get(i).percent() / 100.0);
			segment.setPrefWidth(220 * shares.get(i).percent() / 100.0);
			bar.getChildren().add(segment);
		}
		Label text = new Label(shares.stream().map(share -> share.percent() + "% " + typeName(share.vehicleTypeId()))
			.reduce((a, b) -> a + " · " + b).orElse(""));
		Label configured = new Label(model.assignments().get().byLine().containsKey(line) ? "" : "default regionale");
		configured.getStyleClass().add("text-muted");
		Button toggle = new Button(line.equals(openLine) ? "Chiudi" : "Modifica");
		toggle.setOnAction(event -> {
			openLine = line.equals(openLine) ? null : line;
			model.feed().thenAccept(feed -> Platform.runLater(() -> rebuild(feed)));
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(12, name, bar, text, configured, spacer, toggle);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("list-row");
		return row;
	}

	private Node editor(String line) {
		List<Share> shares = new ArrayList<>(model.assignments().get().sharesOf(line));
		VBox box = new VBox(10);
		box.getStyleClass().add("panel");
		Label total = new Label();
		VBox shareRows = new VBox(8);
		Button save = new Button("Salva");
		save.getStyleClass().add("primary");
		Runnable refresh = () -> {
			int sum = shares.stream().mapToInt(Share::percent).sum();
			total.setText(sum == 100 ? "Totale: 100%" : sum < 100
				? "Le quote sommano al " + sum + "%. Manca il " + (100 - sum) + "% per completare l'assegnazione."
				: "Le quote sommano al " + sum + "%. Eccede del " + (sum - 100) + "%.");
			total.getStyleClass().setAll(sum == 100 ? "metric" : "finding-blocking");
			save.setDisable(sum != 100);
		};
		Runnable render = () -> renderShares(shares, shareRows, refresh);
		render.run();

		ComboBox<TrainType> newType = new ComboBox<>();
		newType.getItems().addAll(model.fleet().get().types());
		newType.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(TrainType type) {
				return type == null ? "" : type.name();
			}

			@Override
			public TrainType fromString(String text) {
				return null;
			}
		});
		newType.setPromptText("Aggiungi tipo");
		newType.valueProperty().addListener((observable, previous, type) -> {
			if (type != null && shares.stream().noneMatch(share -> share.vehicleTypeId().equals(type.id()))) {
				shares.add(new Share(type.id(), 0));
				render.run();
			}
			Platform.runLater(() -> newType.setValue(null));
		});
		Button normalize = new Button("Normalizza a 100%");
		normalize.setOnAction(event -> {
			int sum = shares.stream().mapToInt(Share::percent).sum();
			if (sum == 0) {
				return;
			}
			int assigned = 0;
			for (int i = 0; i < shares.size(); i++) {
				int percent = i == shares.size() - 1 ? 100 - assigned : (int) Math.round(100.0 * shares.get(i).percent() / sum);
				assigned += percent;
				shares.set(i, new Share(shares.get(i).vehicleTypeId(), percent));
			}
			render.run();
		});
		save.setOnAction(event -> {
			model.saveAssignments(model.assignments().get().with(line, shares));
			openLine = null;
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, newType, normalize, spacer, save);
		actions.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().addAll(total, shareRows, actions);
		return box;
	}

	private void renderShares(List<Share> shares, VBox shareRows, Runnable refresh) {
		shareRows.getChildren().clear();
		for (int i = 0; i < shares.size(); i++) {
			shareRows.getChildren().add(shareRow(shares, i, refresh, () -> renderShares(shares, shareRows, refresh)));
		}
		refresh.run();
	}

	private Node shareRow(List<Share> shares, int index, Runnable refresh, Runnable rerender) {
		Share share = shares.get(index);
		Label name = new Label(typeName(share.vehicleTypeId()));
		name.setMinWidth(180);
		Slider slider = new Slider(0, 100, share.percent());
		slider.setPrefWidth(240);
		Spinner<Integer> spinner = new Spinner<>(0, 100, share.percent());
		spinner.setPrefWidth(80);
		spinner.setEditable(true);
		slider.valueProperty().addListener((observable, previous, value) -> {
			int percent = (int) Math.round(value.doubleValue());
			spinner.getValueFactory().setValue(percent);
		});
		spinner.valueProperty().addListener((observable, previous, value) -> {
			shares.set(index, new Share(share.vehicleTypeId(), value));
			slider.setValue(value);
			refresh.run();
		});
		Label note = new Label(share.percent() == 0 ? "tipo non in circolazione" : "");
		note.getStyleClass().add("text-muted");
		Button remove = new Button("Rimuovi");
		remove.setDisable(shares.size() == 1);
		remove.setOnAction(event -> {
			shares.remove(index);
			rerender.run();
		});
		HBox row = new HBox(12, name, slider, spinner, new Label("%"), note, remove);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private String typeName(String typeId) {
		return model.fleet().get().types().stream().filter(type -> type.id().equals(typeId))
			.map(TrainType::name).findFirst().orElse(typeId);
	}

	private static int numericOrder(String line) {
		String digits = line.replaceAll("\\D", "");
		return (line.startsWith("RE") ? 1000 : 0) + (digits.isEmpty() ? 0 : Integer.parseInt(digits));
	}
}
