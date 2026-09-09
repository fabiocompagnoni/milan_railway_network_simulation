package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.Theme;
import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.gui.map.MapCanvas;
import it.unimib.milanrailsim.gui.map.NetworkMap;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Full-bleed map with a floating control bar; live and replay modes plug into it later. */
public final class SimulationView extends BorderPane {

	private final StackPane stack = new StackPane();
	private final AppPaths paths;
	private final ObservableValue<Theme> theme;

	public SimulationView(ScenarioFiles files, AppPaths paths, ObservableValue<Theme> theme) {
		this.paths = paths;
		this.theme = theme;
		getStyleClass().add("simulation-view");
		setCenter(stack);
		showLoading();
		load(files);
	}

	private void showLoading() {
		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setMaxSize(32, 32);
		Label text = new Label("Carico la rete…");
		text.getStyleClass().add("text-muted");
		VBox box = new VBox(12, spinner, text);
		box.setAlignment(Pos.CENTER);
		stack.getChildren().setAll(box);
	}

	private void load(ScenarioFiles files) {
		Task<NetworkMap> task = new Task<>() {
			@Override
			protected NetworkMap call() {
				return NetworkMap.load(files.network(), files.transitSchedule(), files.linkGeometry(),
					files.gtfsDir(), files.crs());
			}
		};
		task.setOnSucceeded(event -> showMap(task.getValue()));
		task.setOnFailed(event -> showError(task.getException()));
		Thread thread = new Thread(task, "network-map-loader");
		thread.setDaemon(true);
		thread.start();
	}

	private void showMap(NetworkMap network) {
		MapCanvas map = new MapCanvas(network, paths.tileCache(), theme.getValue().mapPalette());
		theme.addListener((observable, previous, current) -> map.setPalette(current.mapPalette()));

		Button zoomIn = new Button("+");
		zoomIn.setOnAction(event -> map.zoomIn());
		Button zoomOut = new Button("−");
		zoomOut.setOnAction(event -> map.zoomOut());
		Button fit = new Button("Adatta alla rete");
		fit.setOnAction(event -> map.fitToNetwork());
		HBox controls = new HBox(8, zoomIn, zoomOut, fit);
		controls.getStyleClass().add("control-bar");
		controls.setAlignment(Pos.CENTER_LEFT);
		controls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
		StackPane.setMargin(controls, new Insets(0, 0, 16, 0));

		stack.getChildren().setAll(map, controls);
	}

	private void showError(Throwable cause) {
		Label title = new Label("Impossibile caricare la rete.");
		Label detail = new Label(cause.getMessage());
		detail.getStyleClass().add("text-muted");
		detail.setWrapText(true);
		VBox box = new VBox(8, title, detail);
		box.setAlignment(Pos.CENTER);
		box.setMaxWidth(480);
		stack.getChildren().setAll(box);
	}
}
