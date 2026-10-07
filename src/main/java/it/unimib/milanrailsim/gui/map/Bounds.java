package it.unimib.milanrailsim.gui.map;

/** Axis-aligned rectangle in world coordinates. */
public record Bounds(double minX, double minY, double maxX, double maxY) {

	public double width() {
		return maxX - minX;
	}

	public double height() {
		return maxY - minY;
	}

	public double centreX() {
		return (minX + maxX) / 2;
	}

	public double centreY() {
		return (minY + maxY) / 2;
	}

	public Bounds including(double x, double y) {
		return new Bounds(Math.min(minX, x), Math.min(minY, y), Math.max(maxX, x), Math.max(maxY, y));
	}

	public static Bounds around(double x, double y) {
		return new Bounds(x, y, x, y);
	}
}
