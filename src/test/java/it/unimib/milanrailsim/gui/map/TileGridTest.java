package it.unimib.milanrailsim.gui.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileGridTest {

	private static final double EPSILON = 1e-6;

	@Test
	void zoomZeroIsTheWholeWorldInOneTile() {
		Bounds extent = TileGrid.extent(new TileGrid.Tile(0, 0, 0));

		assertEquals(-TileGrid.HALF_WORLD_M, extent.minX(), EPSILON);
		assertEquals(TileGrid.HALF_WORLD_M, extent.maxY(), EPSILON);
		assertEquals(2 * TileGrid.HALF_WORLD_M, extent.width(), EPSILON);
	}

	@Test
	void tileRowsGrowSouthwards() {
		Bounds top = TileGrid.extent(new TileGrid.Tile(1, 0, 0));
		Bounds bottom = TileGrid.extent(new TileGrid.Tile(1, 0, 1));

		assertEquals(0, top.minY(), EPSILON);
		assertEquals(0, bottom.maxY(), EPSILON);
	}

	@Test
	void coversAWorldRectangleWithTheTilesIntersectingIt() {
		// north-east quadrant plus a sliver of the neighbours at zoom 2
		double quarter = TileGrid.HALF_WORLD_M / 2;
		List<TileGrid.Tile> tiles = TileGrid.covering(new Bounds(-1, -1, quarter + 1, quarter + 1), 2);

		assertEquals(9, tiles.size());
		assertTrue(tiles.contains(new TileGrid.Tile(2, 2, 1)));
		assertTrue(tiles.contains(new TileGrid.Tile(2, 1, 2)));
	}

	@Test
	void clampsToTheWorldEdges() {
		List<TileGrid.Tile> tiles = TileGrid.covering(
			new Bounds(-1e9, -1e9, 1e9, 1e9), 1);

		assertEquals(4, tiles.size());
	}

	@Test
	void picksTheZoomWhoseTilePixelsMatchTheScreenScale() {
		// zoom 10 shows 152.9 m per pixel at the equator
		double metresPerPixel = 2 * TileGrid.HALF_WORLD_M / (256 * 1024);

		assertEquals(10, TileGrid.zoomFor(1 / metresPerPixel));
		assertEquals(19, TileGrid.zoomFor(1e9));
		assertEquals(0, TileGrid.zoomFor(1e-12));
	}
}
