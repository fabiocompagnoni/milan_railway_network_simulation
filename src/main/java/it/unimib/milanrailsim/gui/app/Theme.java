package it.unimib.milanrailsim.gui.app;

import it.unimib.milanrailsim.gui.map.MapPalette;
import javafx.scene.Scene;
import javafx.scene.text.Font;

import java.util.List;

/** Light and dark palettes; the structural stylesheet is shared and reads the palette tokens. */
public enum Theme {

	LIGHT("theme-light.css", "Chiaro", MapPalette.LIGHT),
	DARK("theme-dark.css", "Scuro", MapPalette.DARK);

	private static final String CSS_ROOT = "/it/unimib/milanrailsim/gui/css/";
	private static final String FONT_ROOT = "/it/unimib/milanrailsim/gui/fonts/";

	private final String stylesheet;
	private final String label;
	private final MapPalette mapPalette;

	Theme(String stylesheet, String label, MapPalette mapPalette) {
		this.stylesheet = stylesheet;
		this.label = label;
		this.mapPalette = mapPalette;
	}

	public String label() {
		return label;
	}

	public MapPalette mapPalette() {
		return mapPalette;
	}

	public Theme other() {
		return this == LIGHT ? DARK : LIGHT;
	}

	public void apply(Scene scene) {
		scene.getStylesheets().setAll(
			resource(CSS_ROOT + stylesheet),
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
