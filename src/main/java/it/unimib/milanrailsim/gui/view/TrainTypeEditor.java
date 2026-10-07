package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.FleetConfig.Traction;
import it.unimib.milanrailsim.network.FleetConfig.TrainType;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Form for one train type. Every figure needs a source or the "estimated"
 * flag, and stays inside physical ranges; the id is fixed after creation
 * because assignments and archived runs refer to it.
 */
final class TrainTypeEditor extends VBox {

	private record Field(String key, String label, String unit, double min, double max) {
	}

	private static final Field[] FIELDS = {
		new Field("length", "Lunghezza", "m", 20, 400),
		new Field("seats", "Posti", "", 20, 2000),
		new Field("vmax", "Velocità massima", "km/h", 60, 300),
		new Field("acceleration", "Accelerazione", "m/s²", 0.2, 2.0),
		new Field("deceleration", "Decelerazione", "m/s²", 0.2, 2.0)
	};

	private final FleetConfig fleet;
	private final TrainType existing;
	private final TextField id = new TextField();
	private final TextField name = new TextField();
	private final ComboBox<Traction> traction = new ComboBox<>();
	private final Map<String, TextField> values = new LinkedHashMap<>();
	private final Map<String, TextField> sources = new HashMap<>();
	private final Map<String, CheckBox> estimated = new HashMap<>();
	private final Label error = new Label();
	private final Button save = new Button("Salva");

	TrainTypeEditor(FleetConfig fleet, TrainType existing, Consumer<TrainType> onSave, Runnable onCancel) {
		this.fleet = fleet;
		this.existing = existing;
		setSpacing(12);
		getStyleClass().add("panel");
		Label title = new Label(existing == null ? "Nuovo tipo di treno" : "Modifica " + existing.name());
		title.getStyleClass().add("section-title");

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(8);
		grid.addRow(0, new Label("Identificativo"), id, muted("minuscolo, es. tsr; fisso dopo il salvataggio"));
		grid.addRow(1, new Label("Nome"), name);
		traction.getItems().addAll(Traction.values());
		traction.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(Traction value) {
				return value == null ? "" : value.label();
			}

			@Override
			public Traction fromString(String text) {
				return null;
			}
		});
		grid.addRow(2, new Label("Trazione"), traction);
		grid.addRow(3, muted("Valore"), muted(""), muted("Fonte"), muted("Stimato"));
		int row = 4;
		for (Field field : FIELDS) {
			TextField value = new TextField();
			value.setPrefColumnCount(8);
			TextField source = new TextField();
			source.setPrefColumnCount(28);
			CheckBox flag = new CheckBox();
			values.put(field.key(), value);
			sources.put(field.key(), source);
			estimated.put(field.key(), flag);
			grid.addRow(row++, new Label(field.label()), value, new Label(field.unit()), source, flag);
			value.textProperty().addListener(observable -> validate());
			source.textProperty().addListener(observable -> validate());
			flag.selectedProperty().addListener(observable -> validate());
		}
		id.textProperty().addListener(observable -> validate());
		name.textProperty().addListener(observable -> validate());
		traction.valueProperty().addListener(observable -> validate());

		error.getStyleClass().add("finding-blocking");
		error.setWrapText(true);
		save.getStyleClass().add("primary");
		save.setOnAction(event -> onSave.accept(build()));
		Button cancel = new Button("Annulla");
		cancel.setOnAction(event -> onCancel.run());
		HBox actions = new HBox(8, cancel, save);
		actions.setAlignment(Pos.CENTER_RIGHT);
		getChildren().addAll(title, grid, error, actions);
		fill();
		validate();
	}

	private void fill() {
		if (existing == null) {
			traction.setValue(Traction.ELECTRIC);
			return;
		}
		id.setText(existing.id());
		id.setDisable(true);
		name.setText(existing.name());
		traction.setValue(existing.traction());
		values.get("length").setText(String.valueOf(existing.lengthMeters()));
		values.get("seats").setText(String.valueOf(existing.seats()));
		values.get("vmax").setText(String.valueOf(existing.vmaxKmh()));
		values.get("acceleration").setText(String.valueOf(existing.accelerationMps2()));
		values.get("deceleration").setText(String.valueOf(existing.decelerationMps2()));
		existing.sources().forEach((field, source) -> {
			if (sources.containsKey(field)) {
				sources.get(field).setText(source);
			}
		});
		existing.estimated().forEach(field -> {
			if (estimated.containsKey(field)) {
				estimated.get(field).setSelected(true);
			}
		});
	}

	private void validate() {
		String problem = problem();
		error.setText(problem == null ? "" : problem);
		save.setDisable(problem != null);
	}

	private String problem() {
		String typeId = id.getText().trim();
		if (!typeId.matches("[a-z][a-z0-9_]*")) {
			return "L'identificativo deve essere in minuscolo, con lettere, cifre e trattino basso.";
		}
		if (existing == null && fleet.types().stream().anyMatch(type -> type.id().equals(typeId))) {
			return "Esiste già un tipo con identificativo «" + typeId + "».";
		}
		if (name.getText().isBlank()) {
			return "Indica il nome del treno.";
		}
		for (Field field : FIELDS) {
			double value;
			try {
				value = Double.parseDouble(values.get(field.key()).getText().trim().replace(',', '.'));
			} catch (NumberFormatException e) {
				return field.label() + ": inserisci un numero.";
			}
			if (value < field.min() || value > field.max()) {
				return field.label() + " deve essere tra " + field.min() + " e " + field.max() + " " + field.unit() + ".";
			}
			if (sources.get(field.key()).getText().isBlank() && !estimated.get(field.key()).isSelected()) {
				return field.label() + ": indica la fonte oppure segna il valore come stimato.";
			}
		}
		return null;
	}

	private TrainType build() {
		Map<String, String> sourceByField = new HashMap<>();
		Set<String> estimatedFields = new HashSet<>();
		for (Field field : FIELDS) {
			String source = sources.get(field.key()).getText().trim();
			if (!source.isEmpty()) {
				sourceByField.put(field.key(), source);
			}
			if (estimated.get(field.key()).isSelected()) {
				estimatedFields.add(field.key());
			}
		}
		return new TrainType(id.getText().trim(), name.getText().trim(),
			number("length"), (int) Math.round(number("seats")), number("vmax"),
			number("acceleration"), number("deceleration"), traction.getValue(), sourceByField, estimatedFields);
	}

	private double number(String field) {
		return Double.parseDouble(values.get(field).getText().trim().replace(',', '.'));
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("text-muted");
		return label;
	}
}
