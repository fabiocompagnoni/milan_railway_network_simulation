package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.gui.map.MapCanvas;
import it.unimib.milanrailsim.gui.map.NetworkMap;
import it.unimib.milanrailsim.gui.sim.Session;
import it.unimib.milanrailsim.gui.sim.TrainPositions;
import it.unimib.milanrailsim.server.Protocol;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.BooleanBinding;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Full-bleed map of the network. With a live session it shows the trains as
 * the engine moves them, with a control bar (pause, speed, simulated clock)
 * and a {@link SimulationPanel} with the energy, the lines and the train followed. Once the
 * simulated day is over the bar follows the engine through the writing and
 * analysis phases; the application opens the results when they are ready.
 */
public final class SimulationView extends BorderPane {

	private static final double[] SPEEDS = { 10, 60, 300, 1000, Protocol.UNTHROTTLED };
	private static final String[] SPEED_LABELS = { "10×", "60×", "300×", "1000×", "max" };
	private static final double TIMELINE_WIDTH_PX = 220;

	private final AppModel model;
	private final StackPane stack = new StackPane();
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
		VBox box = new VBox(12, new LoadingRing(32), muted("Carico la rete…"));
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
		Session session = model.session().get();
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
		map.follow(null);
		map.setHighlightedLine(null);
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

	private void attach(Session session) {
		if (animation != null) {
			animation.stop();
		}
		TrainPositions positions = new TrainPositions(network.geometry(), network.stationPoints());
		Label clock = new Label("--:--:--");
		clock.getStyleClass().add("clock");
		Label status = new Label();
		status.getStyleClass().add("text-muted");

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
		if (speedGroup.getSelectedToggle() == null) {
			speedGroup.getToggles().get(1).setSelected(true);
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
			if (paused != (session.speed().get() == 0)) {
				session.setSpeed(paused ? 0 : (double) speedGroup.getSelectedToggle().getUserData());
			}
		});
		// a recording pauses by itself at its end: the button follows
		session.speed().addListener((observable, previous, speed) -> pause.setSelected(speed.doubleValue() == 0));
		Optional<Session.Timeline> timeline = session.timeline();
		Button stop = new Button(timeline.isPresent() ? "Chiudi" : "Interrompi");
		stop.setOnAction(event -> session.stop());
		List<Node> bar = new ArrayList<>(List.of(pause, speeds, clock));
		timeline.ifPresent(recording -> bar.add(timelineSlider(session, recording)));
		bar.addAll(List.of(status, stop));
		HBox controls = controlBar(bar);
		controls.getChildren().addAll(zoomButtons());
		// after the last simulated second the engine writes and analyzes: nothing left to pace or interrupt
		BooleanBinding wrappingUp = session.phase().isNotEqualTo(session.runningPhase()).or(session.finished());
		pause.disableProperty().bind(wrappingUp);
		speeds.disableProperty().bind(wrappingUp);
		stop.disableProperty().bind(session.finished());

		SimulationPanel panel = new SimulationPanel(network, map, session.runDir().getFileName().toString());
		StackPane.setAlignment(panel, Pos.TOP_RIGHT);
		StackPane.setMargin(panel, new Insets(16, 16, 80, 0));
		stack.getChildren().removeIf(node -> node != map);
		stack.getChildren().addAll(controls, panel, workBanner(session));
		setOnKeyPressed(event -> {
			if (event.getCode() == javafx.scene.input.KeyCode.SPACE && !pause.isDisabled()) {
				pause.setSelected(!pause.isSelected());
			}
		});

		session.phase().addListener((observable, previous, phase) -> status.setText(phaseText(session)));
		session.activeTrains().addListener(observable -> status.setText(phaseText(session)));
		session.error().addListener((observable, previous, error) -> showError(error));
		status.setText(phaseText(session));

		animation = new AnimationTimer() {
			@Override
			public void handle(long now) {
				Session.Playback playback = session.playback();
				if (playback != null) {
					clock.setText(clock(playback.time()));
					map.setTrains(positions.between(playback.from(), playback.to(), playback.fraction()));
					panel.show(playback.to());
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

	/** Where the recording is, draggable to any moment of it; it follows the clock while not being dragged. */
	private Node timelineSlider(Session session, Session.Timeline recording) {
		Slider slider = new Slider();
		slider.getStyleClass().add("timeline");
		slider.setPrefWidth(TIMELINE_WIDTH_PX);
		// the span is known only once the recording is loaded
		session.phase().addListener((observable, previous, phase) -> {
			slider.setMin(recording.start());
			slider.setMax(recording.end());
		});
		slider.setMin(recording.start());
		slider.setMax(recording.end());
		slider.valueProperty().addListener((observable, previous, value) -> {
			if (slider.isValueChanging() || slider.isFocused()) {
				recording.seek(value.doubleValue());
			}
		});
		AnimationTimer follower = new AnimationTimer() {
			@Override
			public void handle(long now) {
				Session.Playback playback = session.playback();
				if (playback != null && !slider.isValueChanging() && !slider.isFocused()) {
					slider.setValue(playback.time());
				}
			}
		};
		follower.start();
		session.finished().addListener((observable, was, finished) -> {
			if (finished) {
				follower.stop();
			}
		});
		return slider;
	}

	/**
	 * Before the first simulated second the engine starts, generates the
	 * timetable and loads the scenario; after the last one it writes its outputs
	 * and analyzes them. Both take minutes on a full day: the user must see that
	 * work is going on, which phase it is in and for how long.
	 */
	private Node workBanner(Session session) {
		LoadingRing spinner = new LoadingRing(36);
		Label title = new Label(session.preparationTitle());
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
			if (session.runningPhase().equals(previous)) {
				title.setText("Simulazione conclusa, preparo i risultati");
			}
		});
		BooleanBinding working = session.phase().isNotEqualTo(session.runningPhase()).and(session.finished().not());
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

	private static String phaseText(Session session) {
		String phase = session.phase().get();
		return session.runningPhase().equals(phase) ? phase + " · " + session.activeTrains().get() + " treni attivi" : phase;
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
