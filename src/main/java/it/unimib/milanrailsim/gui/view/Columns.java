package it.unimib.milanrailsim.gui.view;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;

import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

/** Table columns and number formats shared by the result tables. */
final class Columns {

	private Columns() {
	}

	static <T> TableColumn<T, String> text(String title, double width, Function<T, String> value) {
		TableColumn<T, String> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
		column.setPrefWidth(width);
		return column;
	}

	/** Numeric column: sorts on the value, shows it formatted and right-aligned; NaN shows as a dash. */
	static <T> TableColumn<T, Double> number(String title, double width, Function<T, Double> value,
			Function<Double, String> format) {
		TableColumn<T, Double> column = new TableColumn<>(title);
		column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
		column.setComparator(Comparator.comparingDouble(v -> Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v));
		column.setCellFactory(col -> new TableCell<>() {
			@Override
			protected void updateItem(Double item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty ? "" : item == null || Double.isNaN(item) ? "—" : format.apply(item));
				setAlignment(Pos.CENTER_RIGHT);
			}
		});
		column.setPrefWidth(width);
		return column;
	}

	static String clock(double secondsOfDay) {
		if (Double.isNaN(secondsOfDay)) {
			return "—";
		}
		long total = Math.round(secondsOfDay);
		return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
	}

	static String percent(double value) {
		return Double.isNaN(value) ? "—" : String.format(Locale.ITALY, "%.1f %%", value);
	}

	static String count(double value) {
		return String.valueOf((int) Math.round(value));
	}
}
