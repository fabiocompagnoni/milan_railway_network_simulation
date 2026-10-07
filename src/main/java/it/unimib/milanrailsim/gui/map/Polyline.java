package it.unimib.milanrailsim.gui.map;

import java.util.function.UnaryOperator;

/** Immutable sequence of world points, stored as parallel arrays for cheap drawing. */
public final class Polyline {

	private final double[] xs;
	private final double[] ys;

	private Polyline(double[] xs, double[] ys) {
		this.xs = xs;
		this.ys = ys;
	}

	/**
	 * Parses the {@code link-geometry.csv} encoding: {@code x y} pairs separated by {@code ;}.
	 *
	 * @throws IllegalArgumentException on a malformed pair or fewer than two points
	 */
	public static Polyline parse(String points) {
		String[] pairs = points.split(";");
		if (pairs.length < 2) {
			throw new IllegalArgumentException("A polyline needs at least two points: " + points);
		}
		double[] xs = new double[pairs.length];
		double[] ys = new double[pairs.length];
		for (int i = 0; i < pairs.length; i++) {
			String[] pair = pairs[i].trim().split(" ");
			if (pair.length != 2) {
				throw new IllegalArgumentException("Malformed point '" + pairs[i] + "' in " + points);
			}
			xs[i] = Double.parseDouble(pair[0]);
			ys[i] = Double.parseDouble(pair[1]);
		}
		return new Polyline(xs, ys);
	}

	/** A copy with every point passed through {@code mapper}, which receives and returns {@code {x, y}}. */
	public Polyline transform(UnaryOperator<double[]> mapper) {
		double[] mappedXs = new double[xs.length];
		double[] mappedYs = new double[ys.length];
		for (int i = 0; i < xs.length; i++) {
			double[] point = mapper.apply(new double[] { xs[i], ys[i] });
			mappedXs[i] = point[0];
			mappedYs[i] = point[1];
		}
		return new Polyline(mappedXs, mappedYs);
	}

	public int size() {
		return xs.length;
	}

	public double x(int index) {
		return xs[index];
	}

	public double y(int index) {
		return ys[index];
	}
}
