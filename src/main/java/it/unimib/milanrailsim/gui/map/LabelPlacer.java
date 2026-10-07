package it.unimib.milanrailsim.gui.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Greedy label placement for one frame: each label tries the four sides of its
 * anchor and takes the first free one, or is dropped. Callers place labels in
 * priority order, so important names win the free space.
 */
public final class LabelPlacer {

	private static final double GAP_PX = 5;

	/** Top-left corner of the label box. */
	public record Placement(double x, double y) {
	}

	private record Box(double minX, double minY, double maxX, double maxY) {
		boolean intersects(Box other) {
			return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY;
		}
	}

	private final List<Box> taken = new ArrayList<>();

	/** Reserves the anchor's own footprint so labels never cover their marker. */
	public Optional<Placement> place(double anchorX, double anchorY, double width, double height) {
		Placement[] candidates = {
			new Placement(anchorX + GAP_PX, anchorY - height / 2),
			new Placement(anchorX - GAP_PX - width, anchorY - height / 2),
			new Placement(anchorX - width / 2, anchorY - GAP_PX - height),
			new Placement(anchorX - width / 2, anchorY + GAP_PX)
		};
		for (Placement candidate : candidates) {
			Box box = new Box(candidate.x(), candidate.y(), candidate.x() + width, candidate.y() + height);
			if (taken.stream().noneMatch(box::intersects)) {
				taken.add(box);
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}
}
