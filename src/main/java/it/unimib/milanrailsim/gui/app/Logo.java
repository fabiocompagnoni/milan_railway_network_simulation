package it.unimib.milanrailsim.gui.app;

import javafx.scene.image.Image;

/**
 * The application's logos, exported as PNG from the SVG sources in
 * {@code assets/logo} since JavaFX renders no SVG. The round mark serves as
 * window icon and as the brand of the compact sidebar; the extended logo comes
 * in one version per theme because its wordmark is dark on light and white on
 * dark.
 */
public final class Logo {

	private static final String IMAGE_ROOT = "/it/unimib/milanrailsim/gui/images/";

	private Logo() {
	}

	public static Image mark() {
		return image("logo.png");
	}

	public static Image extended(Theme theme) {
		return image(theme == Theme.DARK ? "logo_extended_dark.png" : "logo_extended.png");
	}

	private static Image image(String file) {
		return new Image(Logo.class.getResource(IMAGE_ROOT + file).toExternalForm());
	}
}
