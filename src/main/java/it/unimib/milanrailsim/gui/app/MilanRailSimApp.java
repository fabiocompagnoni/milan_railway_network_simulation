package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.DataRoot;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.gui.view.FleetView;
import it.unimib.milanrailsim.gui.view.NewSimulationView;
import it.unimib.milanrailsim.gui.view.ResultsView;
import it.unimib.milanrailsim.gui.view.RunLibraryView;
import it.unimib.milanrailsim.gui.view.SettingsView;
import it.unimib.milanrailsim.gui.view.SimulationView;
import it.unimib.milanrailsim.server.Protocol;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;
import javafx.stage.Stage;

import java.nio.file.Path;

/** Desktop application: the simulation is configured, run, watched and replayed from here. */
public final class MilanRailSimApp extends Application {

	private AppModel model;

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		Theme.loadFonts();
		try {
			model = new AppModel(AppPaths.defaults(), ScenarioFiles.milan(DataRoot.resolve()));
		} catch (IllegalStateException e) {
			Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
			alert.setTitle("Milan RailSim");
			alert.setHeaderText("Dati non trovati");
			alert.showAndWait();
			Platform.exit();
			return;
		}
		ObjectProperty<Theme> theme = model.theme();
		Navigation navigation = new Navigation(theme);
		navigation.addView("Simulazione", "mdmz-map", () -> new SimulationView(model));
		model.session().addListener((observable, previous, session) -> {
			if (session != null) {
				session.finished().addListener((finishedObservable, was, finished) -> {
					if (finished && session.error().get() == null) {
						openResults(navigation, session.runDir());
					}
				});
			}
		});
		navigation.addView("Nuova simulazione", "mdal-add_circle_outline", () -> new NewSimulationView(model, spec -> {
			model.startRun(spec, Protocol.UNTHROTTLED);
			navigation.show("Simulazione");
		}));
		navigation.addView("Archivio", "mdal-folder_open", () -> new RunLibraryView(model, run -> {
			model.selectedRun().set(run);
			navigation.show("Risultati");
		}, () -> navigation.show("Nuova simulazione")));
		navigation.addView("Risultati", "mdal-bar_chart", () -> new ResultsView(model, () -> navigation.show("Archivio")));
		navigation.addView("Materiale rotabile", "mdmz-train", () -> new FleetView(model));
		navigation.addView("Impostazioni", "mdmz-settings", () -> new SettingsView(model,
			folder -> getHostServices().showDocument(folder.toUri().toString())));

		Scene scene = new Scene(navigation, 1280, 800);
		theme.addListener((observable, previous, current) -> current.apply(scene));
		theme.get().apply(scene);

		Button themeToggle = new Button();
		themeToggle.getStyleClass().add("nav-button");
		themeToggle.graphicProperty().bind(theme.map(current -> new FontIcon(current.icon())));
		BooleanBinding compact = navigation.compact();
		themeToggle.textProperty().bind(Bindings.createStringBinding(
			() -> compact.get() ? "" : theme.get().label(), compact, theme));
		themeToggle.tooltipProperty().bind(theme.map(current ->
			new Tooltip("Passa al " + current.other().label().toLowerCase())));
		themeToggle.setOnAction(event -> theme.set(theme.get().other()));
		navigation.addFooter(themeToggle);

		stage.setTitle("Milan RailSim");
		stage.getIcons().setAll(Logo.mark());
		stage.setMinWidth(720);
		stage.setMinHeight(480);
		stage.setScene(scene);
		stage.show();
	}

	/** A run that completed is archived and analyzed: its results are what the user is waiting for. */
	private void openResults(Navigation navigation, Path runDir) {
		model.session().set(null);
		model.selectedRun().set(model.runs().entry(runDir));
		navigation.show("Risultati");
	}

}
