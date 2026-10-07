package it.unimib.milanrailsim.gui.map;

/**
 * Affine mapping between world metres (y up) and screen pixels (y down):
 * {@code screen = offset + world * scale}, with a flipped y axis.
 */
public final class Viewport {

	private static final double MIN_SCALE = 1e-4;
	private static final double MAX_SCALE = 10;

	private double scale = 1;
	private double offsetX;
	private double offsetY;

	public double scale() {
		return scale;
	}

	public double toScreenX(double worldX) {
		return offsetX + worldX * scale;
	}

	public double toScreenY(double worldY) {
		return offsetY - worldY * scale;
	}

	public double toWorldX(double screenX) {
		return (screenX - offsetX) / scale;
	}

	public double toWorldY(double screenY) {
		return (offsetY - screenY) / scale;
	}

	/** Scales and centres {@code bounds} inside a {@code width x height} area, leaving {@code padding} pixels free. */
	public void fit(Bounds bounds, double width, double height, double padding) {
		double usableWidth = Math.max(1, width - 2 * padding);
		double usableHeight = Math.max(1, height - 2 * padding);
		scale = clamp(Math.min(usableWidth / bounds.width(), usableHeight / bounds.height()));
		offsetX = width / 2 - bounds.centreX() * scale;
		offsetY = height / 2 + bounds.centreY() * scale;
	}

	/** Multiplies the scale by {@code factor} keeping the world point under the given screen pixel fixed. */
	public void zoom(double factor, double screenX, double screenY) {
		double worldX = toWorldX(screenX);
		double worldY = toWorldY(screenY);
		scale = clamp(scale * factor);
		offsetX = screenX - worldX * scale;
		offsetY = screenY + worldY * scale;
	}

	public void pan(double deltaX, double deltaY) {
		offsetX += deltaX;
		offsetY += deltaY;
	}

	private static double clamp(double value) {
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}
}
