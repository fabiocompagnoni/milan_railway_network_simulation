package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.map.MapCanvas;
import it.unimib.milanrailsim.gui.map.NetworkMap;
import it.unimib.milanrailsim.runs.RunVehicles;
import it.unimib.milanrailsim.server.Protocol.Energy;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The panel beside the map of a running or replayed simulation: the energy
 * of the day, the delay of every line, and the train being followed. It is
 * refreshed with each frame; a click on a line brings it out on the map, a
 * click on a train or its code in the search field makes the map follow it.
 */
final class SimulationPanel extends VBox {

	private static final double WIDTH_PX = 320;
	private static final String NOT_AVAILABLE = "—";

	/** The labels of one line in the list. */
	private record LineRow(HBox box, Label trains, Label delayNow, Label meanDelay) {
	}

	private final NetworkMap network;
	private final MapCanvas map;
	private final Map<String, String> stationNames = new HashMap<>();
	private final Map<String, LineRow> rows = new LinkedHashMap<>();
	private final Label power = figure();
	private final Label energy = figure();
	private final Label fuel = figure();
	private final VBox train = new VBox(4);
	private final Label running = figure();
	private final Label runningByType = muted("");
	private final Path runDir;
	private Optional<RunVehicles> vehicles = Optional.empty();
	private String highlightedLine;
	private String followedTrain;
	private Frame shown;

	SimulationPanel(NetworkMap network, MapCanvas map, Path runDir) {
		this.network = network;
		this.map = map;
		this.runDir = runDir;
		network.stations().forEach(station -> stationNames.put(station.id(), station.name()));
		Label title = new Label(runDir.getFileName().toString());
		title.getStyleClass().add("card-title");
		ScrollPane lines = new ScrollPane(lines());
		lines.getStyleClass().add("plain-scroll");
		lines.setFitToWidth(true);
		VBox.setVgrow(lines, Priority.ALWAYS);
		setSpacing(12);
		getChildren().addAll(title, section("Energia"), energyCard(), section("Treni in corsa"),
			new HBox(12, running), runningByType, section("Linee"), linesHeader(), lines,
			section("Treno seguito"), search(), train);
		getStyleClass().add("drawer");
		setPrefWidth(WIDTH_PX);
		setMaxWidth(WIDTH_PX);
		map.setOnTrainSelected(state -> follow(state == null ? null : state.id()));
		showTrain(Optional.empty());
	}

	/** Shows the figures of a frame; frames already shown are skipped. */
	void show(Frame frame) {
		if (frame == shown) {
			return;
		}
		shown = frame;
		showEnergy(frame.energy());
		showRunning(frame);
		showLines(frame);
		showTrain(frame.trains().stream().filter(state -> state.id().equals(followedTrain)).findFirst());
	}

	private GridPane energyCard() {
		GridPane card = new GridPane();
		card.setHgap(16);
		card.setVgap(4);
		card.addRow(0, muted("Potenza assorbita ora"), power);
		card.addRow(1, muted("Energia da inizio giornata"), energy);
		card.addRow(2, muted("Gasolio da inizio giornata"), fuel);
		return card;
	}

	private void showEnergy(Energy reading) {
		if (reading == null) {
			List.of(power, energy, fuel).forEach(label -> label.setText(NOT_AVAILABLE));
			return;
		}
		power.setText(String.format(Locale.ITALY, "%.1f MW", reading.lineKilowatt() / 1000));
		energy.setText(String.format(Locale.ITALY, "%,.1f MWh", reading.kilowattHours() / 1000));
		fuel.setText(String.format(Locale.ITALY, "%,.0f l", reading.litres()));
	}

	/**
	 * The trains on a trip at this instant, and how many of each type. A
	 * recording made before frames carried the trip of a train cannot tell a
	 * running train from one standing between two trips: all of them count.
	 */
	private void showRunning(Frame frame) {
		List<TrainState> onATrip = frame.trains().stream().filter(state -> state.destination() != null).toList();
		List<TrainState> counted = onATrip.isEmpty() ? frame.trains() : onATrip;
		running.setText(String.valueOf(counted.size()));
		if (vehicles.isEmpty()) {
			// the engine writes the vehicles of the day before the first frame
			vehicles = RunVehicles.read(runDir);
		}
		runningByType.setText(vehicles.map(known -> {
			Map<String, Integer> byType = new TreeMap<>();
			counted.forEach(state -> byType.merge(known.typeName(known.typeOfVehicle().getOrDefault(state.id(), NOT_AVAILABLE)),
				1, Integer::sum));
			return byType.entrySet().stream().map(type -> type.getKey() + " " + type.getValue())
				.collect(Collectors.joining(" · "));
		}).orElse(""));
	}

	private HBox linesHeader() {
		return lineBox(new Region(), muted("Linea"), muted("Treni"), muted("Ritardo ora"), muted("Medio"));
	}

	private VBox lines() {
		VBox list = new VBox(2);
		for (NetworkMap.Line line : network.lines()) {
			Label trains = number();
			Label delayNow = number();
			Label meanDelay = number();
			HBox box = lineBox(new Circle(5, line.color()), new Label(line.id()), trains, delayNow, meanDelay);
			box.getStyleClass().add("line-row");
			box.setOnMouseClicked(event -> highlight(line.id()));
			rows.put(line.id(), new LineRow(box, trains, delayNow, meanDelay));
			list.getChildren().add(box);
		}
		return list;
	}

	private static HBox lineBox(Node dot, Label name, Label trains, Label delayNow, Label meanDelay) {
		name.setPrefWidth(64);
		trains.setPrefWidth(48);
		delayNow.setPrefWidth(84);
		meanDelay.setPrefWidth(60);
		if (dot instanceof Region region) {
			region.setPrefWidth(10);
		}
		HBox box = new HBox(6, dot, name, trains, delayNow, meanDelay);
		box.setAlignment(Pos.CENTER_LEFT);
		return box;
	}

	/** A second click on the line brought out puts the map back to normal. */
	private void highlight(String lineId) {
		if (highlightedLine != null) {
			rows.get(highlightedLine).box().getStyleClass().remove("line-row-selected");
		}
		highlightedLine = lineId.equals(highlightedLine) ? null : lineId;
		if (highlightedLine != null) {
			rows.get(highlightedLine).box().getStyleClass().add("line-row-selected");
		}
		map.setHighlightedLine(highlightedLine);
	}

	/**
	 * Per line: the trains in the frame, the mean of their delays at this
	 * instant with early trains at zero, and the mean delay since the start
	 * of the day the engine sends.
	 */
	private void showLines(Frame frame) {
		Map<String, double[]> now = new HashMap<>();
		for (TrainState state : frame.trains()) {
			double[] sum = now.computeIfAbsent(state.line(), key -> new double[2]);
			sum[0] += Math.max(0, state.delay());
			sum[1]++;
		}
		rows.forEach((line, row) -> {
			double[] sum = now.get(line);
			row.trains().setText(sum == null ? "0" : String.valueOf((int) sum[1]));
			row.delayNow().setText(sum == null ? NOT_AVAILABLE : RunLibraryView.minutesSeconds(sum[0] / sum[1]));
			Integer mean = frame.meanDelayByLine() == null ? null : frame.meanDelayByLine().get(line);
			row.meanDelay().setText(mean == null ? NOT_AVAILABLE : RunLibraryView.minutesSeconds(mean));
		});
	}

	private TextField search() {
		TextField field = new TextField();
		field.setPromptText("Codice del treno, es. S1_circ_3");
		field.setOnAction(event -> {
			String wanted = field.getText().trim();
			if (shown == null || wanted.isEmpty()) {
				return;
			}
			shown.trains().stream().map(TrainState::id).filter(id -> id.equalsIgnoreCase(wanted)).findFirst()
				.or(() -> shown.trains().stream().map(TrainState::id)
					.filter(id -> id.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))).findFirst())
				.ifPresentOrElse(this::follow, () -> train.getChildren().setAll(muted("Nessun treno in circolazione con questo codice.")));
		});
		return field;
	}

	private void follow(String trainId) {
		followedTrain = trainId;
		map.follow(trainId);
		showTrain(shown == null || trainId == null ? Optional.empty()
			: shown.trains().stream().filter(state -> state.id().equals(trainId)).findFirst());
	}

	private void showTrain(Optional<TrainState> followed) {
		if (followedTrain == null) {
			train.getChildren().setAll(muted("Clicca un treno sulla mappa o cercalo per codice."));
			return;
		}
		Label id = new Label(followedTrain);
		id.getStyleClass().add("metric");
		if (followed.isEmpty()) {
			train.getChildren().setAll(id, muted("Non in circolazione in questo momento."));
			return;
		}
		TrainState state = followed.get();
		NetworkMap.Line line = network.line(state.line());
		Label delay = new Label(RunLibraryView.minutesSeconds(state.delay()));
		if (state.delay() > 300) {
			delay.getStyleClass().add("finding-blocking");
		}
		GridPane facts = new GridPane();
		facts.setHgap(16);
		facts.setVgap(2);
		facts.addRow(0, muted("Linea"), new Label(line == null ? state.line() : line.id() + " · " + line.name()));
		facts.addRow(1, muted("Direzione"), new Label(station(state.destination())));
		facts.addRow(2, muted("Prossima fermata"), new Label(station(state.nextStop())));
		facts.addRow(3, muted("Velocità"), new Label(String.format(Locale.ITALY, "%.0f km/h", state.speed() * 3.6)));
		facts.addRow(4, muted("Ritardo"), delay);
		facts.addRow(5, muted("Potenza"), new Label(state.power() == null ? NOT_AVAILABLE
			: String.format(Locale.ITALY, "%,.0f kW", state.power())));
		train.getChildren().setAll(id, facts);
	}

	private String station(String stationId) {
		return stationId == null ? NOT_AVAILABLE : stationNames.getOrDefault(stationId, stationId);
	}

	private static Label section(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-title");
		return label;
	}

	private static Label figure() {
		Label label = new Label(NOT_AVAILABLE);
		label.getStyleClass().add("metric");
		return label;
	}

	private static Label number() {
		Label label = new Label(NOT_AVAILABLE);
		label.setAlignment(Pos.CENTER_RIGHT);
		return label;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
