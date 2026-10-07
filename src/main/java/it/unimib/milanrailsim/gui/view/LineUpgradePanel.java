package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.RouteTracks;
import it.unimib.milanrailsim.network.RouteTracks.Length;
import it.unimib.milanrailsim.schedule.LineUpgrade;
import it.unimib.milanrailsim.schedule.LineUpgradePlan;
import it.unimib.milanrailsim.schedule.RegularRoutes;
import it.unimib.milanrailsim.schedule.RegularRoutes.Route;
import it.unimib.milanrailsim.schedule.RouteTarget;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The routes of the service day with, for each, whether it is upgraded and
 * the longest wait allowed on it; every route is upgraded unless unticked.
 * Next to the choices the table shows the length and single track of the
 * route on the simulated network, the longest wait of the published timetable
 * and, once the generator has run, the trips it adds and the longest wait
 * that results.
 */
final class LineUpgradePanel extends VBox {

	private static final int SECONDS_PER_MINUTE = 60;

	/** What was chosen on a route, kept across days so that changing the day does not lose it. */
	private record Choice(boolean included, int targetMinutes) {
	}

	/** One route of the table. */
	private static final class Row {
		final Route route;
		final String name;
		final Optional<Length> length;
		final BooleanProperty included = new SimpleBooleanProperty();
		final IntegerProperty targetMinutes = new SimpleIntegerProperty();
		final StringProperty realWait = new SimpleStringProperty("—");
		final StringProperty addedTrips = new SimpleStringProperty("—");
		final StringProperty resultingWait = new SimpleStringProperty("—");

		Row(Route route, String name, Optional<Length> length, Choice choice) {
			this.route = route;
			this.name = name;
			this.length = length;
			included.set(choice.included());
			targetMinutes.set(choice.targetMinutes());
		}

		RouteTarget target() {
			return new RouteTarget(route.line(), route.one(), route.other(), targetMinutes.get());
		}
	}

	private final LineUpgradePlan plan;
	private final Predicate<String> offeredLine;
	private final Runnable onChange;
	private final Map<List<String>, Choice> choices = new HashMap<>();
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final TableView<Row> table = new TableView<>(rows);
	private final Spinner<Integer> bulkTarget;
	private boolean includedByDefault = true;
	private LocalDate loadedDay;
	private RegularRoutes published;

	/**
	 * @param offeredLine the lines whose routes may be upgraded
	 * @param onChange    called whenever a choice changes, to refresh what depends on it
	 */
	LineUpgradePanel(LineUpgradePlan plan, Predicate<String> offeredLine, Runnable onChange) {
		super(8);
		this.plan = plan;
		this.offeredLine = offeredLine;
		this.onChange = onChange;
		bulkTarget = new Spinner<>(plan.minTargetMinutes(), plan.maxTargetMinutes(), plan.proposedTargetMinutes());
		bulkTarget.setEditable(true);
		bulkTarget.setPrefWidth(80);
		Button apply = new Button("Applica ai percorsi inclusi");
		apply.setOnAction(event -> applyToIncluded(bulkTarget.getValue()));
		HBox tools = new HBox(12, new Label("Attesa massima"), bulkTarget, new Label("minuti"), apply);
		tools.setAlignment(Pos.CENTER_LEFT);
		table.setEditable(true);
		table.setPrefHeight(360);
		table.getColumns().addAll(List.of(includedColumn(), Columns.text("Linea", 60, row -> row.route.line()),
			Columns.text("Percorso", 280, row -> row.name), Columns.text("Corse", 60, row -> String.valueOf(row.route.trips())),
			Columns.number("Lunghezza", 90, row -> row.length.map(Length::totalMetres).orElse(Double.NaN),
				LineUpgradePanel::kilometres),
			singleTrackColumn(),
			textColumn("Attesa max reale", 120, row -> row.realWait), targetColumn(),
			textColumn("Corse aggiunte", 110, row -> row.addedTrips), textColumn("Attesa max risultante", 150, row -> row.resultingWait)));
		getChildren().addAll(tools, table, muted("Si aggiungono corse fra due corse reali consecutive dello stesso percorso "
			+ "finché nessuna attesa supera l'obiettivo, nei due versi e sull'intero percorso. Le corse reali non vengono spostate; "
			+ "un vuoto di oltre " + plan.serviceGapSeconds() / SECONDS_PER_MINUTE + " minuti è una pausa di servizio e resta vuoto."));
	}

	/**
	 * Lists the routes of the day, keeping what was chosen on those already
	 * seen. Nothing happens when the day is the one already loaded.
	 *
	 * @param tracks measures the length and the single track of each route
	 */
	void load(GtfsFeed feed, LocalDate day, RouteTracks tracks) {
		if (day.equals(loadedDay)) {
			return;
		}
		rows.forEach(this::remember);
		loadedDay = day;
		published = new RegularRoutes(feed, day, plan.serviceGapSeconds());
		List<Row> loaded = new ArrayList<>();
		for (Route route : published.offered(plan.minTrips(), plan.minTripsPerDirection())) {
			if (offeredLine.test(route.line())) {
				Row row = new Row(route, name(feed, route.one()) + " – " + name(feed, route.other()), tracks.of(route.stops()),
					choiceFor(route));
				row.included.addListener(observable -> changed(row));
				row.targetMinutes.addListener(observable -> changed(row));
				loaded.add(row);
			}
		}
		loaded.sort((one, other) -> Integer.compare(lineOrder(one.route.line()), lineOrder(other.route.line())));
		rows.setAll(loaded);
	}

	/** Includes exactly the routes of an archived run, with their targets. */
	void prefill(List<RouteTarget> targets) {
		includedByDefault = false;
		choices.clear();
		for (RouteTarget target : targets) {
			choices.put(key(target.line(), target.one(), target.other()), new Choice(true, target.targetMinutes()));
		}
		for (Row row : rows) {
			Choice choice = choiceFor(row.route);
			row.included.set(choice.included());
			row.targetMinutes.set(choice.targetMinutes());
		}
		table.refresh();
	}

	List<RouteTarget> targets() {
		return rows.stream().filter(row -> row.included.get()).map(Row::target).toList();
	}

	/** Whether some included route asks for a wait below the given one. */
	long targetsBelow(int minutes) {
		return rows.stream().filter(row -> row.included.get() && row.targetMinutes.get() < minutes).count();
	}

	/**
	 * Shows, for every route, the longest wait of the published and of the
	 * upgraded timetable within the simulated window and the trips added.
	 */
	void show(RegularRoutes upgraded, LineUpgrade.Report report, int fromSeconds, int toSeconds) {
		for (Row row : rows) {
			row.realWait.set(minutes(published.longestWaitSeconds(row.route, fromSeconds, toSeconds)));
			if (!row.included.get()) {
				row.addedTrips.set("—");
				row.resultingWait.set("—");
				continue;
			}
			long added = report.added().stream().filter(trip -> trip.line().equals(row.route.line())
				&& Set.of(trip.from(), trip.to()).equals(Set.of(row.route.one(), row.route.other()))
				&& trip.departureSeconds() >= fromSeconds && trip.departureSeconds() <= toSeconds).count();
			row.addedTrips.set(String.valueOf(added));
			row.resultingWait.set(minutes(upgraded.longestWaitSeconds(row.route, fromSeconds, toSeconds)));
		}
	}

	private void applyToIncluded(int minutes) {
		rows.stream().filter(row -> row.included.get()).forEach(row -> row.targetMinutes.set(minutes));
		table.refresh();
	}

	private void changed(Row row) {
		remember(row);
		onChange.run();
	}

	private void remember(Row row) {
		choices.put(key(row.route.line(), row.route.one(), row.route.other()),
			new Choice(row.included.get(), row.targetMinutes.get()));
	}

	private Choice choiceFor(Route route) {
		Choice chosen = choices.get(key(route.line(), route.one(), route.other()));
		if (chosen != null) {
			return chosen;
		}
		return new Choice(includedByDefault, plan.proposedTargetMinutes());
	}

	private TableColumn<Row, Boolean> includedColumn() {
		TableColumn<Row, Boolean> column = new TableColumn<>("Incluso");
		column.setCellValueFactory(cell -> cell.getValue().included);
		column.setCellFactory(CheckBoxTableCell.forTableColumn(column));
		column.setEditable(true);
		column.setPrefWidth(70);
		return column;
	}

	private TableColumn<Row, Row> targetColumn() {
		TableColumn<Row, Row> column = new TableColumn<>("Obiettivo (min)");
		column.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
		column.setCellFactory(col -> new TargetCell());
		column.setPrefWidth(120);
		return column;
	}

	/** Sorts on the metres of single track; a route with no measured length sorts first. */
	private static TableColumn<Row, Optional<Length>> singleTrackColumn() {
		TableColumn<Row, Optional<Length>> column = new TableColumn<>("Binario unico");
		column.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().length));
		column.setComparator(Comparator.comparingDouble(length -> length.map(Length::singleTrackMetres).orElse(-1.0)));
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(Optional<Length> item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? "" : item.map(LineUpgradePanel::singleTrack).orElse("—"));
			}
		});
		column.setPrefWidth(170);
		return column;
	}

	private static TableColumn<Row, String> textColumn(String title, double width,
			Function<Row, StringProperty> value) {
		TableColumn<Row, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> value.apply(cell.getValue()));
		column.setPrefWidth(width);
		return column;
	}

	/** A spinner bound to the target of the row it shows. */
	private final class TargetCell extends TableCell<Row, Row> {

		private final Spinner<Integer> spinner = new Spinner<>(plan.minTargetMinutes(), plan.maxTargetMinutes(),
			plan.proposedTargetMinutes());
		private Row bound;

		TargetCell() {
			spinner.setEditable(true);
			spinner.setPrefWidth(90);
			spinner.valueProperty().addListener((observable, previous, value) -> {
				if (bound != null && value != null) {
					bound.targetMinutes.set(value);
				}
			});
		}

		@Override
		protected void updateItem(Row row, boolean empty) {
			super.updateItem(row, empty);
			bound = null;
			if (empty || row == null) {
				setGraphic(null);
				return;
			}
			spinner.getValueFactory().setValue(row.targetMinutes.get());
			spinner.disableProperty().bind(row.included.not());
			bound = row;
			setGraphic(spinner);
		}
	}

	private static String minutes(OptionalInt seconds) {
		return seconds.isEmpty() ? "—" : Math.round(seconds.getAsInt() / (double) SECONDS_PER_MINUTE) + " min";
	}

	private static String kilometres(double metres) {
		return String.format(Locale.ITALY, "%.1f km", metres / 1000);
	}

	/** The single track of a route, with the sections whose track count is not measured when there are any. */
	private static String singleTrack(Length length) {
		String single = kilometres(length.singleTrackMetres());
		return length.unmeasuredMetres() > 0 ? single + " (+" + kilometres(length.unmeasuredMetres()) + " non misurati)" : single;
	}

	private static String name(GtfsFeed feed, String stopId) {
		GtfsFeed.Stop stop = feed.stopsById().get(stopId);
		return stop == null ? stopId : stop.name();
	}

	/** The same key whichever terminus is named first. */
	private static List<String> key(String line, String one, String other) {
		return List.of(line, one.compareTo(other) < 0 ? one + "|" + other : other + "|" + one);
	}

	/** Suburban lines first, in numeric order, then the others. */
	private static int lineOrder(String line) {
		String digits = line.replaceAll("\\D", "");
		int number = digits.isEmpty() ? 0 : Integer.parseInt(digits);
		return (line.matches("S\\d+") ? 0 : 1000) + number;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		label.setWrapText(true);
		return label;
	}
}
