package it.unimib.milanrailsim.gui.app;

import javafx.scene.image.Image;

/**
 * The application's logos, exported as PNG from the SVG sources in
 * {@code assets/logo} since JavaFX renders no SVG. The sidebar logos are
 * exported at the size they are shown at, with an {@code @2x} twin JavaFX picks
 * on high-density screens: scaling a large image down when drawing blurs it.
 * The extended logo comes in one version per theme because its wordmark is dark
 * on light and white on dark.
 */
public final class Logo {

	private static final String IMAGE_ROOT = "/it/unimib/milanrailsim/gui/images/";

	private Logo() {
	}

	/** The round mark at full size, scaled by the window system. */
	public static Image windowIcon() {
		return image("logo.png");
	}

	/** The round mark of the compact sidebar. */
	public static Image mark() {
		return image("logo_mark.png");
	}

	public static Image extended(Theme theme) {
		return image(theme == Theme.DARK ? "logo_extended_dark.png" : "logo_extended.png");
	}

	private static Image image(String file) {
		return new Image(Logo.class.getResource(IMAGE_ROOT + file).toExternalForm());
	}
}
