package it.unimib.milanrailsim.gui.app;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
	private static final double EXTENDED_WIDTH_PX = 176;
	private static final double MARK_WIDTH_PX = 36;

	private final Map<String, Supplier<Node>> views = new LinkedHashMap<>();
	private final ToggleGroup group = new ToggleGroup();
	private final VBox sidebar = new VBox();

	/** @param theme the current theme, followed by the extended logo */
	public Navigation(ObservableValue<Theme> theme) {
		sidebar.getStyleClass().add("sidebar");
		sidebar.getChildren().add(brand(theme));
		setLeft(sidebar);
	}

	/** The extended logo of the theme, or the round mark alone on narrow windows. */
	private Node brand(ObservableValue<Theme> theme) {
		ImageView logo = new ImageView();
		logo.setPreserveRatio(true);
		logo.setSmooth(true);
		BooleanBinding compact = compact();
		logo.imageProperty().bind(Bindings.createObjectBinding(
			() -> compact.get() ? Logo.mark() : Logo.extended(theme.getValue()), compact, theme));
		logo.fitWidthProperty().bind(Bindings.when(compact).then(MARK_WIDTH_PX).otherwise(EXTENDED_WIDTH_PX));
		HBox brand = new HBox(logo);
		brand.getStyleClass().add("brand");
		brand.setAlignment(Pos.CENTER);
		return brand;
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