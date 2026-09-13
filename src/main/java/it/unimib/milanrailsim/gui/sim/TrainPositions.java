package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.gui.map.Polyline;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where to draw each train between two consecutive frames: a train still on
 * the same link moves linearly from its earlier to its later position, one
 * that changed link is drawn at the later position. Trains dwelling on a
 * station loop sit on the station point. Motion is therefore continuous and
 * never runs backwards, at the price of showing the previous frame interval.
 */
public final class TrainPositions {

	/** A train on the map, in world coordinates. */
	public record Placement(TrainState state, double x, double y) {
	}

	private static final String STOP_PREFIX = "stop_";

	private final Map<String, Polyline> geometry;
	private final Map<String, double[]> stations;

	/**
	 * @param geometry link alignments keyed by link id, in the map's world coordinates
	 * @param stations station points keyed by station id, same coordinates
	 */
	public TrainPositions(Map<String, Polyline> geometry, Map<String, double[]> stations) {
		this.geometry = geometry;
		this.stations = stations;
	}

	/** @param fraction progress from {@code from} to {@code to}, 0 to 1 */
	public List<Placement> between(Frame from, Frame to, double fraction) {
		Map<String, TrainState> earlier = new HashMap<>();
		if (from != null) {
			from.trains().forEach(train -> earlier.put(train.id(), train));
		}
		List<Placement> placements = new ArrayList<>(to.trains().size());
		for (TrainState train : to.trains()) {
			TrainState before = earlier.get(train.id());
			double distance = before != null && before.link().equals(train.link())
				? before.position() + (train.position() - before.position()) * fraction
				: train.position();
			double[] point = locate(train.link(), distance);
			if (point != null) {
				placements.add(new Placement(train, point[0], point[1]));
			}
		}
		return placements;
	}

	private double[] locate(String link, double distance) {
		if (link.startsWith(STOP_PREFIX)) {
			return stations.get(link.substring(STOP_PREFIX.length()));
		}
		Polyline line = geometry.get(link);
		return line == null ? null : pointAt(line, Math.max(0, distance));
	}

	/** The point {@code distance} metres along the polyline, clamped to its ends. */
	static double[] pointAt(Polyline line, double distance) {
		double remaining = distance;
		for (int i = 1; i < line.size(); i++) {
			double dx = line.x(i) - line.x(i - 1);
			double dy = line.y(i) - line.y(i - 1);
			double segment = Math.hypot(dx, dy);
			if (remaining <= segment || i == line.size() - 1) {
				double t = segment == 0 ? 0 : Math.min(1, remaining / segment);
				return new double[] { line.x(i - 1) + dx * t, line.y(i - 1) + dy * t };
			}
			remaining -= segment;
		}
		return new double[] { line.x(0), line.y(0) };
	}
}
