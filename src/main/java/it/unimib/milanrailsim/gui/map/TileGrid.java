package it.unimib.milanrailsim.gui.map;

import java.util.ArrayList;
import java.util.List;

/** Slippy-map tile arithmetic in Web Mercator metres (EPSG:3857), the CRS the map draws in. */
public final class TileGrid {

	/** Half the projected world width: the Mercator extent is symmetric around the origin. */
	public static final double HALF_WORLD_M = 20037508.342789244;
	public static final int TILE_PX = 256;
	public static final int MAX_ZOOM = 19;

	public record Tile(int zoom, int x, int y) {
	}

	private TileGrid() {
	}

	public static Bounds extent(Tile tile) {
		double size = tileSize(tile.zoom());
		double minX = -HALF_WORLD_M + tile.x() * size;
		double maxY = HALF_WORLD_M - tile.y() * size;
		return new Bounds(minX, maxY - size, minX + size, maxY);
	}

	/** Tiles at {@code zoom} intersecting {@code world}, row by row. */
	public static List<Tile> covering(Bounds world, int zoom) {
		double size = tileSize(zoom);
		int last = (1 << zoom) - 1;
		int minX = clamp((int) Math.floor((world.minX() + HALF_WORLD_M) / size), last);
		int maxX = clamp((int) Math.floor((world.maxX() + HALF_WORLD_M) / size), last);
		int minY = clamp((int) Math.floor((HALF_WORLD_M - world.maxY()) / size), last);
		int maxY = clamp((int) Math.floor((HALF_WORLD_M - world.minY()) / size), last);
		List<Tile> tiles = new ArrayList<>();
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				tiles.add(new Tile(zoom, x, y));
			}
		}
		return tiles;
	}

	/** The zoom whose tiles are closest to one image pixel per screen pixel at {@code pixelsPerMetre}. */
	public static int zoomFor(double pixelsPerMetre) {
		double zoom = Math.log(2 * HALF_WORLD_M * pixelsPerMetre / TILE_PX) / Math.log(2);
		return (int) Math.max(0, Math.min(MAX_ZOOM, Math.round(zoom)));
	}

	private static double tileSize(int zoom) {
		return 2 * HALF_WORLD_M / (1 << zoom);
	}

	private static int clamp(int index, int last) {
		return Math.max(0, Math.min(last, index));
	}
}
