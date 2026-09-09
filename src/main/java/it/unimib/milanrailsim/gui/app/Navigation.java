package it.unimib.milanrailsim.gui.app;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Sidebar navigation: one button per view, labels hidden on narrow windows. */
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

	public void addView(String name, Supplier<Node> view) {
		views.put(name, view);
		ToggleButton button = new ToggleButton(name);
		button.getStyleClass().add("nav-button");
		button.setToggleGroup(group);
		button.textProperty().bind(Bindings.when(widthProperty().lessThan(COMPACT_BELOW_PX))
			.then(name.substring(0, 1)).otherwise(name));
		button.setOnAction(event -> show(name));
		sidebar.getChildren().add(button);
		if (group.getSelectedToggle() == null) {
			button.setSelected(true);
			show(name);
		}
	}

	public void addFooter(Node node) {
		Region spacer = new Region();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		sidebar.getChildren().addAll(spacer, node);
	}

	public void show(String name) {
		setCenter(views.get(name).get());
	}
}