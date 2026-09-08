package it.unimib.milanrailsim.gui.app;

import javafx.scene.Scene;
import javafx.scene.text.Font;

import java.util.List;

/** Light and dark palettes; the structural stylesheet is shared and reads the palette tokens. */
public enum Theme {

	LIGHT("theme-light.css", "Chiaro"),
	DARK("theme-dark.css", "Scuro");

	private static final String CSS_ROOT = "/it/unimib/milanrailsim/gui/css/";
	private static final String FONT_ROOT = "/it/unimib/milanrailsim/gui/fonts/";

	private final String palette;
	private final String label;

	Theme(String palette, String label) {
		this.palette = palette;
		this.label = label;
	}

	public String label() {
		return label;
	}

	public Theme other() {
		return this == LIGHT ? DARK : LIGHT;
	}

	public void apply(Scene scene) {
		scene.getStylesheets().setAll(
			resource(CSS_ROOT + palette),
			resource(CSS_ROOT + "base.css"));
	}

	public static void loadFonts() {
		for (String file : List.of("Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf")) {
			Font.loadFont(Theme.class.getResourceAsStream(FONT_ROOT + file), 13);
		}
	}

	private static String resource(String path) {
		return Theme.class.getResource(path).toExternalForm();
	}
}
