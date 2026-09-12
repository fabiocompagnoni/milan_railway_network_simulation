package it.unimib.milanrailsim.gui.map;

import javafx.scene.paint.Color;

/**
 * Canvas colours cannot come from CSS, so each theme carries its own set.
 * {@code tileVeil} is painted over the base map to soften it under the network.
 */
public record MapPalette(Color background, Color tileVeil, Color mutedTrack, Color station,
		Color stationOutline, Color label, Color card) {

	public static final MapPalette LIGHT = new MapPalette(
		Color.web("#FAFBFA"), Color.web("#FAFBFA", 0.55), Color.web("#9AA8A0"), Color.WHITE,
		Color.web("#5B6B62"), Color.web("#16211B"), Color.web("#FFFFFF", 0.96));

	public static final MapPalette DARK = new MapPalette(
		Color.web("#0B100D"), Color.web("#0B100D", 0.7), Color.web("#4A5A50"), Color.web("#0F1512"),
		Color.web("#9AAAA0"), Color.web("#E6EDE8"), Color.web("#182019", 0.96));
}
