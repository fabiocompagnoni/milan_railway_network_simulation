package it.unimib.milanrailsim.gui.app;

import javafx.application.Application;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/** Desktop application: the simulation is configured, run, watched and replayed from here. */
public final class MilanRailSimApp extends Application {

	private static final List<String> VIEWS = List.of(
		"Libreria run", "Nuova simulazione", "Simulazione",
		"Risultati", "Materiale rotabile", "Impostazioni");

	private Theme theme = Theme.LIGHT;

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		Theme.loadFonts();
		Navigation navigation = new Navigation();
		VIEWS.forEach(name -> navigation.addView(name, () -> placeholder(name)));

		Scene scene = new Scene(navigation, 1280, 800);
		theme.apply(scene);

		Button themeToggle = new Button();
		themeToggle.getStyleClass().add("theme-toggle");
		themeToggle.setText("Tema: " + theme.label());
		themeToggle.setOnAction(event -> {
			theme = theme.other();
			theme.apply(scene);
			themeToggle.setText("Tema: " + theme.label());
		});
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
		return new VBox(8, title, hint);
	}
}
