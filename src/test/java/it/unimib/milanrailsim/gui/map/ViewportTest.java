package it.unimib.milanrailsim.gui.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewportTest {

	private static final double EPSILON = 1e-9;

	private static Viewport fitted() {
		Viewport viewport = new Viewport();
		viewport.fit(new Bounds(1000, 5000, 3000, 6000), 400, 300, 0);
		return viewport;
	}

	@Test
	void fitCentresBoundsAndKeepsAspectRatio() {
		Viewport viewport = fitted();

		// 2000 x 1000 world into 400 x 300: limited by width, scale 0.2, centred vertically
		assertEquals(0.2, viewport.scale(), EPSILON);
		assertEquals(200, viewport.toScreenX(2000), EPSILON);
		assertEquals(150, viewport.toScreenY(5500), EPSILON);
	}

	@Test
	void screenYGrowsDownwardsWhileWorldYGrowsUpwards() {
		Viewport viewport = fitted();

		assertTrue(viewport.toScreenY(6000) < viewport.toScreenY(5000));
	}

	@Test
	void zoomKeepsTheWorldPointUnderTheCursorFixed() {
		Viewport viewport = fitted();
		double worldX = viewport.toWorldX(50);
		double worldY = viewport.toWorldY(70);

		viewport.zoom(2, 50, 70);

		assertEquals(0.4, viewport.scale(), EPSILON);
		assertEquals(worldX, viewport.toWorldX(50), EPSILON);
		assertEquals(worldY, viewport.toWorldY(70), EPSILON);
	}

	@Test
	void panShiftsScreenCoordinatesByTheSameAmount() {
		Viewport viewport = fitted();
		double before = viewport.toScreenX(2000);

		viewport.pan(30, -10);

		assertEquals(before + 30, viewport.toScreenX(2000), EPSILON);
		assertEquals(140, viewport.toScreenY(5500), EPSILON);
	}

	@Test
	void roundTripsBetweenWorldAndScreen() {
		Viewport viewport = fitted();
		viewport.zoom(3, 10, 10);

		assertEquals(1234.5, viewport.toWorldX(viewport.toScreenX(1234.5)), EPSILON);
		assertEquals(5678.9, viewport.toWorldY(viewport.toScreenY(5678.9)), EPSILON);
	}

	@Test
	void zoomIsClampedToSensibleRange() {
		Viewport viewport = fitted();

		viewport.zoom(1e9, 0, 0);
		double max = viewport.scale();
		viewport.zoom(1e-12, 0, 0);
		double min = viewport.scale();

		assertTrue(max < 1e9 && min > 0);
	}
}
