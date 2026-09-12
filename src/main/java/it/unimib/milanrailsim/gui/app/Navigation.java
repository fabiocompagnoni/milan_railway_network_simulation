package it.unimib.milanrailsim.gui.app;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Sidebar navigation: one button per view with its icon; labels hidden on narrow windows. */
public final class Navigation extends BorderPane {

	private static final double COMPACT_BELOW_PX = 1100;

	private final Map<String, Supplier<Node>> views = new LinkedHashMap<>();
	private final ToggleGroup group = new ToggleGroup();
	private final VBox sidebar = new VBox();

	public Navigation() {
		sidebar.getStyleClass().add("sidebar");
		Label brand = new Label("Milan RailSim");
		brand.getStyleClass().add("brand");
		sidebar.getChildren().add(brand);
		setLeft(sidebar);
	}

	/** @param icon an Ikonli literal such as {@code mdal-map}; alone on narrow windows, beside the name otherwise */
	public void addView(String name, String icon, Supplier<Node> view) {
		views.put(name, view);
		ToggleButton button = new ToggleButton(name, new FontIcon(icon));
		button.setUserData(name);
		button.getStyleClass().add("nav-button");
		button.setToggleGroup(group);
		button.textProperty().bind(Bindings.when(compact()).then("").otherwise(name));
		button.setOnAction(event -> show(name));
		sidebar.getChildren().add(button);
		if (group.getSelectedToggle() == null) {
			button.setSelected(true);
			show(name);
		}
	}

	/** True on narrow windows, when the sidebar shows icons only. */
	public BooleanBinding compact() {
		return widthProperty().lessThan(COMPACT_BELOW_PX);
	}

	public void addFooter(Node node) {
		Region spacer = new Region();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		sidebar.getChildren().addAll(spacer, node);
	}

	public void show(String name) {
		group.getToggles().stream()
			.filter(toggle -> name.equals(toggle.getUserData()))
			.forEach(toggle -> toggle.setSelected(true));
		setCenter(views.get(name).get());
	}
}