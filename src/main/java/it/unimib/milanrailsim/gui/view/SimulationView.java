package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.gui.map.MapCanvas;
import it.unimib.milanrailsim.gui.map.NetworkMap;
import it.unimib.milanrailsim.gui.sim.LiveSession;
import it.unimib.milanrailsim.gui.sim.TrainPositions;
import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import it.unimib.milanrailsim.server.RailsimJob;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.BooleanBinding;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-bleed map of the network. With a live session it shows the trains as
 * the engine moves them, with a control bar (pause, speed, simulated clock)
 * and a drawer holding the line legend and the selected train. Once the
 * simulated day is over the bar follows the engine through the writing and
 * analysis phases; the application opens the results when they are ready.
 */
public final class SimulationView extends BorderPane {

	private static final double[] SPEEDS = { 10, 60, 300, 1000, Protocol.UNTHROTTLED };
	private static final String[] SPEED_LABELS = { "10×", "60×", "300×", "1000×", "max" };
	private static final String SIMULATING = RailsimJob.SIMULATING;

	private final AppModel model;
	private final StackPane stack = new StackPane();
	private final Set<String> hiddenLines = new HashSet<>();
	private MapCanvas map;
	private NetworkMap network;
	private AnimationTimer animation;

	public SimulationView(AppModel model) {
		this.model = model;
		setCenter(stack);
		showLoading();
		load();
	}

	private void showLoading() {
		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setMaxSize(32, 32);
		VBox box = new VBox(12, spinner, muted("Carico la rete…"));
		box.setAlignment(Pos.CENTER);
		stack.getChildren().setAll(box);
	}

	private void load() {
		Task<NetworkMap> task = new Task<>() {
			@Override
			protected NetworkMap call() {
				return NetworkMap.load(model.files().network(), model.files().transitSchedule(),
					model.files().linkGeometry(), model.gtfsDir(), model.files().crs());
			}
		};
		task.setOnSucceeded(event -> showMap(task.getValue()));
		task.setOnFailed(event -> {
			VBox box = new VBox(8, new Label("Impossibile caricare la rete."), muted(task.getException().getMessage()));
			box.setAlignment(Pos.CENTER);
			box.setMaxWidth(480);
			stack.getChildren().setAll(box);
		});
		Thread thread = new Thread(task, "network-map-loader");
		thread.setDaemon(true);
		thread.start();
	}

	private void showMap(NetworkMap loaded) {
		network = loaded;
		map = new MapCanvas(network, model.paths().tileCache(), model.theme().get().mapPalette());
		model.theme().addListener((observable, previous, current) -> map.setPalette(current.mapPalette()));
		stack.getChildren().setAll(map);
		LiveSession session = model.session().get();
		if (session == null) {
			stack.getChildren().add(idleBar());
		} else {
			attach(session);
		}
		model.session().addListener((observable, previous, current) -> {
			if (map == null) {
				return;
			}
			if (current != null) {
				attach(current);
			} else {
				detach();
			}
		});
	}

	/** Back to the plain map once a run is over and dismissed. */
	private void detach() {
		if (animation != null) {
			animation.stop();
		}
		map.setTrains(List.of());
		map.setOnTrainSelected(state -> {
		});
		stack.getChildren().removeIf(node -> node != map);
		stack.getChildren().add(idleBar());
	}

	private Node idleBar() {
		HBox controls = controlBar(zoomButtons());
		return controls;
	}

	private List<Node> zoomButtons() {
		Button zoomIn = new Button("+");
		zoomIn.setOnAction(event -> map.zoomIn());
		Button zoomOut = new Button("−");
		zoomOut.setOnAction(event -> map.zoomOut());
		Button fit = new Button("Adatta alla rete");
		fit.setOnAction(event -> map.fitToNetwork());
		return List.of(zoomIn, zoomOut, fit);
	}

	private HBox controlBar(List<Node> children) {
		HBox controls = new HBox(8);
		controls.getChildren().addAll(children);
		controls.getStyleClass().add("control-bar");
		controls.setAlignment(Pos.CENTER_LEFT);
		controls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
		StackPane.setMargin(controls, new Insets(0, 0, 16, 0));
		return controls;
	}

	private void attach(LiveSession session) {
		if (animation != null) {
			animation.stop();
		}
		TrainPositions positions = new TrainPositions(network.geometry(), network.stationPoints());
		Label clock = new Label("--:--:--");
		clock.getStyleClass().add("clock");
		Label status = new Label();
		status.getStyleClass().add("text-muted");
		VBox detail = new VBox(4);

		ToggleButton pause = new ToggleButton();
		pause.setGraphic(new FontIcon("mdmz-pause"));
		pause.setTooltip(new javafx.scene.control.Tooltip("Pausa / riprendi (spazio)"));
		ToggleGroup speedGroup = new ToggleGroup();
		HBox speeds = new HBox();
		speeds.getStyleClass().add("segmented");
		for (int i = 0; i < SPEEDS.length; i++) {
			ToggleButton button = new ToggleButton(SPEED_LABELS[i]);
			button.getStyleClass().add("segment");
			button.setUserData(SPEEDS[i]);
			button.setToggleGroup(speedGroup);
			button.setSelected(SPEEDS[i] == session.speed().get());
			speeds.getChildren().add(button);
		}
		speedGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
			if (selected == null) {
				previous.setSelected(true);
			} else if (!pause.isSelected()) {
				session.setSpeed((double) selected.getUserData());
			}
		});
		pause.selectedProperty().addListener((observable, was, paused) -> {
			pause.setGraphic(new FontIcon(paused ? "mdmz-play_arrow" : "mdmz-pause"));
			session.setSpeed(paused ? 0 : (double) speedGroup.getSelectedToggle().getUserData());
		});
		Button stop = new Button("Interrompi");
		stop.setOnAction(event -> session.stop());
		HBox controls = controlBar(List.of(pause, speeds, clock, status, stop));
		controls.getChildren().addAll(zoomButtons());
		// after the last simulated second the engine writes and analyzes: nothing left to pace or interrupt
		BooleanBinding wrappingUp = session.phase().isNotEqualTo(SIMULATING).or(session.finished());
		pause.disableProperty().bind(wrappingUp);
		speeds.disableProperty().bind(wrappingUp);
		stop.disableProperty().bind(wrappingUp);

		VBox drawer = drawer(session, detail);
		stack.getChildren().removeIf(node -> node != map);
		stack.getChildren().addAll(controls, drawer, workBanner(session));
		setOnKeyPressed(event -> {
			if (event.getCode() == javafx.scene.input.KeyCode.SPACE && !session.finished().get()) {
				pause.setSelected(!pause.isSelected());
			}
		});

		session.phase().addListener((observable, previous, phase) -> status.setText(phaseText(session)));
		session.activeTrains().addListener(observable -> status.setText(phaseText(session)));
		session.error().addListener((observable, previous, error) -> showError(error));
		status.setText(phaseText(session));
		map.setOnTrainSelected(state -> showTrain(detail, state));

		animation = new AnimationTimer() {
			@Override
			public void handle(long now) {
				LiveSession.Playback playback = session.playback();
				if (playback != null) {
					clock.setText(clock(playback.time()));
					map.setTrains(positions.between(playback.from(), playback.to(), playback.fraction()));
				}
			}
		};
		animation.start();
		session.finished().addListener((observable, was, finished) -> {
			if (finished) {
				animation.stop();
			}
		});
	}

	/**
	 * Before the first simulated second the engine starts, generates the
	 * timetable and loads the scenario; after the last one it writes its outputs
	 * and analyzes them. Both take minutes on a full day: the user must see that
	 * work is going on, which phase it is in and for how long.
	 */
	private Node workBanner(LiveSession session) {
		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setMaxSize(40, 40);
		Label title = new Label("Preparo la simulazione");
		title.getStyleClass().add("section-title");
		Label phase = muted("");
		phase.textProperty().bind(session.phase());
		Label elapsed = muted("");
		VBox text = new VBox(4, title, phase, elapsed);
		HBox banner = new HBox(16, spinner, text);
		banner.setAlignment(Pos.CENTER_LEFT);
		banner.getStyleClass().add("banner-done");
		banner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(banner, Pos.CENTER);

		session.phase().addListener((observable, previous, current) -> {
			if (SIMULATING.equals(previous)) {
				title.setText("Simulazione conclusa, preparo i risultati");
			}
		});
		BooleanBinding working = session.phase().isNotEqualTo(SIMULATING).and(session.finished().not());
		banner.visibleProperty().bind(working);
		banner.managedProperty().bind(banner.visibleProperty());
		AnimationTimer stopwatch = new AnimationTimer() {
			private long startedNanos;

			@Override
			public void start() {
				startedNanos = System.nanoTime();
				super.start();
			}

			@Override
			public void handle(long now) {
				elapsed.setText("Trascorsi " + clock((now - startedNanos) / 1e9));
			}
		};
		working.addListener((observable, was, active) -> {
			if (active) {
				stopwatch.start();
			} else {
				stopwatch.stop();
			}
		});
		if (working.get()) {
			stopwatch.start();
		}
		return banner;
	}

	/** A run that failed deserves more than a line in the control bar. */
	private void showError(String error) {
		Label title = new Label("La simulazione si è interrotta");
		title.getStyleClass().add("section-title");
		Label text = muted(error);
		text.setMaxWidth(520);
		VBox banner = new VBox(6, title, text);
		banner.getStyleClass().add("banner-error");
		banner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(banner, Pos.TOP_CENTER);
		StackPane.setMargin(banner, new Insets(16, 0, 0, 0));
		stack.getChildren().add(banner);
	}

	private VBox drawer(LiveSession session, VBox detail) {
		Label legendTitle = new Label("Linee");
		legendTitle.getStyleClass().add("section-title");
		VBox legend = new VBox(4);
		for (NetworkMap.Line line : network.lines()) {
			CheckBox box = new CheckBox(line.id());
			box.setSelected(true);
			box.setGraphic(new Circle(5, line.color()));
			box.selectedProperty().addListener((observable, was, shown) -> {
				if (shown) {
					hiddenLines.remove(line.id());
				} else {
					hiddenLines.add(line.id());
				}
				map.setHiddenLines(hiddenLines);
			});
			legend.getChildren().add(box);
		}
		ScrollPane legendScroll = new ScrollPane(legend);
		legendScroll.getStyleClass().add("plain-scroll");
		legendScroll.setFitToWidth(true);
		VBox.setVgrow(legendScroll, Priority.ALWAYS);

		Label detailTitle = new Label("Treno selezionato");
		detailTitle.getStyleClass().add("section-title");
		detail.getChildren().setAll(muted("Clicca un treno sulla mappa."));

		Label runTitle = new Label(session.runDir().getFileName().toString());
		runTitle.getStyleClass().add("card-title");
		VBox drawer = new VBox(12, runTitle, legendTitle, legendScroll, detailTitle, detail);
		drawer.getStyleClass().add("drawer");
		drawer.setPrefWidth(220);
		drawer.setMaxWidth(220);
		drawer.setMaxHeight(Region.USE_PREF_SIZE);
		StackPane.setAlignment(drawer, Pos.TOP_RIGHT);
		StackPane.setMargin(drawer, new Insets(16, 16, 80, 0));
		return drawer;
	}

	private void showTrain(VBox detail, TrainState state) {
		if (state == null) {
			detail.getChildren().setAll(muted("Clicca un treno sulla mappa."));
			return;
		}
		NetworkMap.Line line = network.line(state.line());
		Label id = new Label(state.id());
		id.getStyleClass().add("metric");
		Label delay = new Label(String.format(Locale.ITALY, "Ritardo %s", RunLibraryView.minutesSeconds(state.delay())));
		if (state.delay() > 300) {
			delay.getStyleClass().add("finding-blocking");
		}
		detail.getChildren().setAll(id,
			muted(line == null ? state.line() : line.id() + " · " + line.name()),
			muted(String.format(Locale.ITALY, "%.0f km/h · tratta %s", state.speed() * 3.6, state.link())),
			delay);
	}

	private static String phaseText(LiveSession session) {
		String phase = session.phase().get();
		return SIMULATING.equals(phase) ? phase + " · " + session.activeTrains().get() + " treni attivi" : phase;
	}

	static String clock(double seconds) {
		long total = Math.round(seconds);
		return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
