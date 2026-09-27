package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.config.AppPaths;
import it.unimib.milanrailsim.gui.config.ScenarioFiles;
import it.unimib.milanrailsim.gui.sim.LiveSession;
import it.unimib.milanrailsim.gui.sim.ReplaySession;
import it.unimib.milanrailsim.gui.sim.Session;
import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.runs.RunLibrary;
import it.unimib.milanrailsim.runs.ScenarioSpec;
import it.unimib.milanrailsim.server.RailsimJob;
import it.unimib.milanrailsim.schedule.LineAssignments;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.matsim.core.network.NetworkUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** State shared by every view: folders, scenario files, theme and the timetable, loaded once in the background. */
public final class AppModel {

	private final AppPaths paths;
	private final ScenarioFiles files;
	private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(Theme.LIGHT);
	private final ObjectProperty<CompletableFuture<GtfsFeed>> feed = new SimpleObjectProperty<>();
	private final ObjectProperty<FleetConfig> fleet = new SimpleObjectProperty<>();
	private final ObjectProperty<LineAssignments> assignments = new SimpleObjectProperty<>();
	private final ObjectProperty<RunLibrary.Entry> selectedRun = new SimpleObjectProperty<>();
	private final ObjectProperty<Session> session = new SimpleObjectProperty<>();
	private final ObjectProperty<ScenarioSpec> draft = new SimpleObjectProperty<>();
	private final CompletableFuture<Set<String>> networkStops;

	public AppModel(AppPaths paths, ScenarioFiles files) {
		this.paths = paths;
		this.files = files;
		feed.set(CompletableFuture.supplyAsync(() -> GtfsFeed.load(gtfsDir())));
		networkStops = CompletableFuture.supplyAsync(() -> NetworkUtils.readNetwork(files.engineNetwork().toString())
			.getNodes().values().stream()
			.filter(node -> node.getAttributes().getAttribute("gtfsStopName") != null)
			.map(node -> node.getId().toString()).collect(Collectors.toUnmodifiableSet()));
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
	public ObjectProperty<Session> session() {
		return session;
	}

	/** Plays an archived run back from its recording on the map. */
	public ReplaySession startReplay(RunLibrary.Entry entry) {
		ReplaySession replay = ReplaySession.open(entry.dir());
		session.set(replay);
		return replay;
	}

	/**
	 * A scenario the new-simulation form starts from instead of its defaults:
	 * an archived run to repeat, under a name not yet taken. Consumed on read.
	 */
	public void draftFrom(RunLibrary.Entry entry, ScenarioSpec spec) {
		draft.set(spec.named(runs().freeName(entry.name())));
	}

	public Optional<ScenarioSpec> takeDraft() {
		Optional<ScenarioSpec> taken = Optional.ofNullable(draft.get());
		draft.set(null);
		return taken;
	}

	/** Writes the scenario into its run folder and spawns the engine on it. */
	public LiveSession startRun(ScenarioSpec spec, double initialSpeed) {
		Path runDir = paths.runs().resolve(spec.name());
		spec.write(runDir.resolve("scenario.json"));
		if (spec.type() == ScenarioSpec.SimulationType.METRO_LIKE) {
			keepDensificationPlan(runDir);
		}
		RailsimJob.Inputs inputs = new RailsimJob.Inputs(runDir, files.engineConfig(),
			files.engineNetwork(), gtfsDir(), paths.fleetTypesFile(), paths.lineAssignmentsFile(), paths.costsFile(),
			Files.exists(files.stationTracks()) ? files.stationTracks() : null,
			Files.isDirectory(files.microNodes()) ? files.microNodes() : null);
		LiveSession started = LiveSession.start(inputs, initialSpeed);
		session.set(started);
		return started;
	}

	/** A run keeps the plan it was generated from: the one in the project data may change afterwards. */
	private void keepDensificationPlan(Path runDir) {
		try {
			Files.copy(files.densificationPlan(), runDir.resolve(RailsimJob.DENSIFICATION_PLAN), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot copy " + files.densificationPlan() + " into " + runDir, e);
		}
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
		if (Files.exists(costs) || !Files.exists(files.defaultCosts())) {
			return;
		}
		try {
			Files.createDirectories(costs.getParent());
			Files.copy(files.defaultCosts(), costs);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot seed " + costs, e);
		}
	}
}
