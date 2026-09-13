package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.gui.sim.LiveSession;
import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.server.RailsimJob;
import it.unimib.milanrailsim.schedule.CreateTransitScheduleFromFeed;
import it.unimib.milanrailsim.schedule.LineAssignments;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.matsim.core.network.NetworkUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** State shared by every view: folders, scenario files, theme and the timetable, loaded once in the background. */
public final class AppModel {

	private static final Path DEFAULT_COSTS = Path.of("config", "costs.json");

	private final AppPaths paths;
	private final ScenarioFiles files;
	private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(Theme.LIGHT);
	private final ObjectProperty<CompletableFuture<GtfsFeed>> feed = new SimpleObjectProperty<>();
	private final ObjectProperty<FleetConfig> fleet = new SimpleObjectProperty<>();
	private final ObjectProperty<LineAssignments> assignments = new SimpleObjectProperty<>();
	private final ObjectProperty<RunLibrary.Entry> selectedRun = new SimpleObjectProperty<>();
	private final ObjectProperty<LiveSession> session = new SimpleObjectProperty<>();
	private final CompletableFuture<Set<String>> networkStops;

	public AppModel(AppPaths paths, ScenarioFiles files) {
		this.paths = paths;
		this.files = files;
		feed.set(CompletableFuture.supplyAsync(() -> GtfsFeed.load(gtfsDir())));
		networkStops = CompletableFuture.supplyAsync(() -> NetworkUtils.readNetwork(files.mesoNetwork().toString())
			.getNodes().keySet().stream().map(Object::toString).collect(Collectors.toUnmodifiableSet()));
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

	/** The run being simulated now, if any; views observe it to show the live map. */
	public ObjectProperty<LiveSession> session() {
		return session;
	}

	/** Writes the scenario into its run folder and spawns the engine on it. */
	public LiveSession startRun(ScenarioSpec spec, double initialSpeed) {
		Path runDir = paths.runs().resolve(spec.name());
		spec.write(runDir.resolve("scenario.json"));
		RailsimJob.Inputs inputs = new RailsimJob.Inputs(runDir, Path.of("scenarios", "milan", "config.xml"),
			files.mesoNetwork(), gtfsDir(), paths.fleetTypesFile(), paths.lineAssignmentsFile(), paths.costsFile(),
			Files.exists(CreateTransitScheduleFromFeed.STATION_TRACKS) ? CreateTransitScheduleFromFeed.STATION_TRACKS : null);
		LiveSession started = LiveSession.start(inputs, initialSpeed);
		session.set(started);
		return started;
	}

	public RunLibrary runs() {
		return new RunLibrary(paths.runs());
	}

	/** The run the results view shows; null until one is opened from the library. */
	public ObjectProperty<RunLibrary.Entry> selectedRun() {
		return selectedRun;
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

	/** Ids of the stations the mesoscopic network models, loaded once in the background. */
	public CompletableFuture<Set<String>> networkStops() {
		return networkStops;
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
