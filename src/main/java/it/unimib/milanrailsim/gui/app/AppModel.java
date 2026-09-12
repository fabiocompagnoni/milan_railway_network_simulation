package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.schedule.LineAssignments;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** State shared by every view: folders, scenario files, theme and the timetable, loaded once in the background. */
public final class AppModel {

	private static final Path DEFAULT_COSTS = Path.of("config", "costs.json");

	private final AppPaths paths;
	private final ScenarioFiles files;
	private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(Theme.LIGHT);
	private final ObjectProperty<CompletableFuture<GtfsFeed>> feed = new SimpleObjectProperty<>();
	private final ObjectProperty<FleetConfig> fleet = new SimpleObjectProperty<>();
	private final ObjectProperty<LineAssignments> assignments = new SimpleObjectProperty<>();

	public AppModel(AppPaths paths, ScenarioFiles files) {
		this.paths = paths;
		this.files = files;
		feed.set(CompletableFuture.supplyAsync(() -> GtfsFeed.load(gtfsDir())));
		seedCosts();
		fleet.set(Files.exists(paths.fleetTypesFile()) ? FleetConfig.read(paths.fleetTypesFile()) : FleetConfig.defaults());
		assignments.set(Files.exists(paths.lineAssignmentsFile())
			? LineAssignments.read(paths.lineAssignmentsFile()) : LineAssignments.defaults());
	}

	public AppPaths paths() {
		return paths;
	}

	public ScenarioFiles files() {
		return files;
	}

	public ObjectProperty<Theme> theme() {
		return theme;
	}

	public ObjectProperty<FleetConfig> fleet() {
		return fleet;
	}

	public ObjectProperty<LineAssignments> assignments() {
		return assignments;
	}

	/** Persists the catalogue: the next runs use it, archived ones keep their own copy. */
	public void saveFleet(FleetConfig updated) {
		updated.write(paths.fleetTypesFile());
		fleet.set(updated);
	}

	public void saveAssignments(LineAssignments updated) {
		updated.write(paths.lineAssignmentsFile());
		assignments.set(updated);
	}

	/** The feed loaded by the user, if any, otherwise the one committed with the scenario. */
	public Path gtfsDir() {
		Path user = paths.gtfsDir();
		return Files.isDirectory(user) ? user : files.gtfsDir();
	}

	/** Completes on a background thread; callers hop back to the JavaFX thread themselves. */
	public CompletableFuture<GtfsFeed> feed() {
		return feed.get();
	}

	/** Fires when a new feed has been installed; views showing timetable data reload from {@link #feed()}. */
	public ObjectProperty<CompletableFuture<GtfsFeed>> feedProperty() {
		return feed;
	}

	public void reloadFeed() {
		feed.set(CompletableFuture.supplyAsync(() -> GtfsFeed.load(gtfsDir())));
	}

	/** The user's cost parameters start as a copy of the repository defaults. */
	private void seedCosts() {
		Path costs = paths.costsFile();
		if (Files.exists(costs) || !Files.exists(DEFAULT_COSTS)) {
			return;
		}
		try {
			Files.createDirectories(costs.getParent());
			Files.copy(DEFAULT_COSTS, costs);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot seed " + costs, e);
		}
	}
}
