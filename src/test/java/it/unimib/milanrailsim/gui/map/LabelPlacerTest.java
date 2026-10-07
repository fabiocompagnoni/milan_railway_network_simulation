package it.unimib.milanrailsim.gui.map;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LabelPlacerTest {

	private static final double WIDTH = 40;
	private static final double HEIGHT = 10;

	@Test
	void firstLabelGoesToTheRightOfItsAnchor() {
		LabelPlacer placer = new LabelPlacer();

		Optional<LabelPlacer.Placement> placement = placer.place(100, 100, WIDTH, HEIGHT);

		assertTrue(placement.isPresent());
		assertTrue(placement.get().x() > 100);
	}

	@Test
	void secondLabelMovesToAFreeSideWhenTheRightIsTaken() {
		LabelPlacer placer = new LabelPlacer();
		placer.place(100, 100, WIDTH, HEIGHT);

		Optional<LabelPlacer.Placement> placement = placer.place(102, 101, WIDTH, HEIGHT);

		assertTrue(placement.isPresent());
		assertTrue(placement.get().x() + WIDTH <= 102 || Math.abs(placement.get().y() - 101) > HEIGHT / 2);
	}

	@Test
	void givesUpWhenEverySideCollides() {
		LabelPlacer placer = new LabelPlacer();
		placer.place(-300, 100, 800, 800);

		assertTrue(placer.place(100, 100, WIDTH, HEIGHT).isEmpty());
	}

	@Test
	void distantLabelsNeverInterfere() {
		LabelPlacer placer = new LabelPlacer();
		placer.place(0, 0, WIDTH, HEIGHT);

		assertTrue(placer.place(500, 500, WIDTH, HEIGHT).isPresent());
	}
}
