package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.gui.view.SimulationView;
import javafx.application.Application;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Desktop application: the simulation is configured, run, watched and replayed from here. */
public final class MilanRailSimApp extends Application {

	private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(Theme.LIGHT);

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		Theme.loadFonts();
		ScenarioFiles files = ScenarioFiles.milan();
		AppPaths paths = AppPaths.defaults();
		Navigation navigation = new Navigation();
		navigation.addView("Libreria run", () -> placeholder("Libreria run"));
		navigation.addView("Nuova simulazione", () -> placeholder("Nuova simulazione"));
		navigation.addView("Simulazione", () -> new SimulationView(files, paths, theme));
		navigation.addView("Risultati", () -> placeholder("Risultati"));
		navigation.addView("Materiale rotabile", () -> placeholder("Materiale rotabile"));
		navigation.addView("Impostazioni", () -> placeholder("Impostazioni"));

		Scene scene = new Scene(navigation, 1280, 800);
		theme.addListener((observable, previous, current) -> current.apply(scene));
		theme.get().apply(scene);

		Button themeToggle = new Button();
		themeToggle.getStyleClass().add("theme-toggle");
		themeToggle.textProperty().bind(theme.map(current -> "Tema: " + current.label()));
		themeToggle.setOnAction(event -> theme.set(theme.get().other()));
		navigation.addFooter(themeToggle);

		stage.setTitle("Milan RailSim");
		stage.setMinWidth(720);
		stage.setMinHeight(480);
		stage.setScene(scene);
		stage.show();
	}

	private static Node placeholder(String name) {
		Label title = new Label(name);
		title.getStyleClass().add("title");
		Label hint = new Label("Vista in costruzione.");
		hint.getStyleClass().add("text-muted");
		VBox box = new VBox(8, title, hint);
		box.getStyleClass().add("content");
		return box;
	}
}
