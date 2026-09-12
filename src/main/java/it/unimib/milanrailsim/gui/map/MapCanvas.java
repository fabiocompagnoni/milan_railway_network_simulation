package it.unimib.milanrailsim.gui.map;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Map of the network over OpenStreetMap tiles: tracks, stations and, later,
 * moving trains. Wheel or pinch zooms around the cursor, drag pans,
 * {@link #fitToNetwork()} resets; hovering a line highlights it and shows its card.
 */
public final class MapCanvas extends Region {

	private static final double FIT_PADDING_PX = 24;
	private static final double ZOOM_STEP = 1.15;
	private static final double SUBURBAN_TRACK_WIDTH_PX = 2.5;
	private static final double MUTED_TRACK_WIDTH_PX = 1.2;
	private static final double HIGHLIGHT_WIDTH_PX = 5;
	private static final double SHARED_TRACK_SPACING_PX = 3;
	private static final double HOVER_TOLERANCE_PX = 6;
	private static final double STATION_RADIUS_PX = 3;
	private static final Font LABEL_FONT = Font.font("Inter", FontWeight.MEDIUM, 11);
	private static final Font CARD_TITLE_FONT = Font.font("Inter", FontWeight.SEMI_BOLD, 13);
	private static final Font CARD_FONT = Font.font("Inter", 12);

	private final Canvas canvas = new Canvas();
	private final Viewport viewport = new Viewport();
	private final NetworkMap network;
	private final TileStore tiles;
	private final List<NetworkMap.Station> stationsByImportance;
	private final Map<String, Bounds> trackBounds = new HashMap<>();
	private final Map<String, Double> textWidths = new HashMap<>();
	private MapPalette palette;
	private double dragX;
	private double dragY;
	private Hover hover;

	/** The line under the cursor, or every line of a regional-only track. */
	private record Hover(List<NetworkMap.Line> lines, double x, double y) {
	}

	/** @param tileCacheDir where downloaded OpenStreetMap tiles are kept for offline use */
	public MapCanvas(NetworkMap network, Path tileCacheDir, MapPalette palette) {
		this.network = network;
		this.tiles = new TileStore(tileCacheDir, this::draw);
		this.palette = palette;
		this.stationsByImportance = network.stations().stream()
			.sorted(Comparator.comparingInt(NetworkMap.Station::lineCount).reversed())
			.toList();
		network.tracks().forEach(track -> trackBounds.put(track.linkId(), bounds(track.polyline())));
		getChildren().add(canvas);
		setPickOnBounds(true);
		canvas.widthProperty().bind(widthProperty());
		canvas.heightProperty().bind(heightProperty());
		canvas.widthProperty().addListener(observable -> fitToNetwork());
		canvas.heightProperty().addListener(observable -> fitToNetwork());

		setOnScroll(event -> {
			if (event.getDeltaY() != 0) {
				zoom(event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP, event.getX(), event.getY());
			}
		});
		setOnZoom(event -> zoom(event.getZoomFactor(), event.getX(), event.getY()));
		setOnMousePressed(this::startDrag);
		setOnMouseDragged(event -> {
			viewport.pan(event.getX() - dragX, event.getY() - dragY);
			startDrag(event);
			draw();
		});
		setOnMouseMoved(event -> updateHover(event.getX(), event.getY()));
		setOnMouseExited(event -> updateHover(-1, -1));
	}

	public void setPalette(MapPalette palette) {
		this.palette = palette;
		draw();
	}

	public void zoomIn() {
		zoom(ZOOM_STEP, getWidth() / 2, getHeight() / 2);
	}

	public void zoomOut() {
		zoom(1 / ZOOM_STEP, getWidth() / 2, getHeight() / 2);
	}

	public void fitToNetwork() {
		viewport.fit(network.bounds(), getWidth(), getHeight(), FIT_PADDING_PX);
		draw();
	}

	/** Redraws with whatever is currently loaded; the tile store calls this as tiles arrive. */
	public void draw() {
		GraphicsContext g = canvas.getGraphicsContext2D();
		g.setFill(palette.background());
		g.fillRect(0, 0, getWidth(), getHeight());
		drawTiles(g);
		drawTracks(g);
		drawStations(g);
		drawAttribution(g);
		if (hover != null) {
			drawCard(g, hover);
		}
	}

	private void zoom(double factor, double screenX, double screenY) {
		viewport.zoom(factor, screenX, screenY);
		draw();
	}

	private void startDrag(MouseEvent event) {
		dragX = event.getX();
		dragY = event.getY();
	}

	private void updateHover(double x, double y) {
		Hover next = x < 0 ? null : linesAt(x, y).map(lines -> new Hover(lines, x, y)).orElse(null);
		boolean sameLines = next != null && hover != null && next.lines().equals(hover.lines());
		hover = next;
		if (!sameLines || next != null) {
			draw();
		}
	}

	private Optional<List<NetworkMap.Line>> linesAt(double x, double y) {
		double tolerance = HOVER_TOLERANCE_PX / viewport.scale();
		Bounds probe = new Bounds(viewport.toWorldX(x) - tolerance, viewport.toWorldY(y) - tolerance,
			viewport.toWorldX(x) + tolerance, viewport.toWorldY(y) + tolerance);
		Optional<List<NetworkMap.Line>> best = Optional.empty();
		double bestDistance = HOVER_TOLERANCE_PX;
		for (NetworkMap.Track track : network.tracks()) {
			if (!intersects(trackBounds.get(track.linkId()), probe)) {
				continue;
			}
			List<NetworkMap.Line> suburban = suburbanLines(track);
			int strokes = Math.max(1, suburban.size());
			for (int i = 0; i < strokes; i++) {
				double distance = distanceToPolyline(track.polyline(), offsetOf(i, strokes), x, y);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = Optional.of(suburban.isEmpty() ? allLines(track) : List.of(suburban.get(i)));
				}
			}
		}
		return best;
	}

	private void drawTiles(GraphicsContext g) {
		Bounds visible = new Bounds(viewport.toWorldX(0), viewport.toWorldY(getHeight()),
			viewport.toWorldX(getWidth()), viewport.toWorldY(0));
		for (TileGrid.Tile tile : TileGrid.covering(visible, TileGrid.zoomFor(viewport.scale()))) {
			tiles.get(tile).ifPresent(image -> drawTile(g, tile, image));
		}
		g.setFill(palette.tileVeil());
		g.fillRect(0, 0, getWidth(), getHeight());
	}

	private void drawTile(GraphicsContext g, TileGrid.Tile tile, Image image) {
		Bounds extent = TileGrid.extent(tile);
		double x = viewport.toScreenX(extent.minX());
		double y = viewport.toScreenY(extent.maxY());
		double size = extent.width() * viewport.scale();
		g.drawImage(image, x, y, size, size);
	}

	/** Lines sharing a track are drawn side by side, transit-map style, in a stable order. */
	private void drawTracks(GraphicsContext g) {
		g.setLineCap(StrokeLineCap.ROUND);
		g.setLineJoin(StrokeLineJoin.ROUND);
		List<NetworkMap.Line> highlighted = hover == null ? List.of() : hover.lines();
		for (NetworkMap.Track track : network.tracks()) {
			List<NetworkMap.Line> suburban = suburbanLines(track);
			if (suburban.isEmpty()) {
				boolean lit = allLines(track).stream().anyMatch(highlighted::contains);
				g.setStroke(lit ? palette.label() : palette.mutedTrack());
				g.setLineWidth(lit ? HIGHLIGHT_WIDTH_PX : MUTED_TRACK_WIDTH_PX);
				strokePolyline(g, track.polyline(), 0);
				continue;
			}
			for (int i = 0; i < suburban.size(); i++) {
				boolean lit = highlighted.contains(suburban.get(i));
				g.setStroke(suburban.get(i).color());
				g.setLineWidth(lit ? HIGHLIGHT_WIDTH_PX : SUBURBAN_TRACK_WIDTH_PX);
				strokePolyline(g, track.polyline(), offsetOf(i, suburban.size()));
			}
		}
	}

	private List<NetworkMap.Line> suburbanLines(NetworkMap.Track track) {
		return track.lineIds().stream().map(network::line).filter(NetworkMap.Line::suburban).toList();
	}

	private List<NetworkMap.Line> allLines(NetworkMap.Track track) {
		return track.lineIds().stream().map(network::line).toList();
	}

	private static double offsetOf(int index, int strokes) {
		return (index - (strokes - 1) / 2.0) * SHARED_TRACK_SPACING_PX;
	}

	/** Strokes the polyline shifted by {@code offset} pixels along each segment's normal. */
	private void strokePolyline(GraphicsContext g, Polyline polyline, double offset) {
		g.beginPath();
		for (int i = 1; i < polyline.size(); i++) {
			double[] segment = screenSegment(polyline, i, offset);
			if (i == 1) {
				g.moveTo(segment[0], segment[1]);
			}
			g.lineTo(segment[2], segment[3]);
		}
		g.stroke();
	}

	private double distanceToPolyline(Polyline polyline, double offset, double px, double py) {
		double best = Double.POSITIVE_INFINITY;
		for (int i = 1; i < polyline.size(); i++) {
			double[] s = screenSegment(polyline, i, offset);
			best = Math.min(best, distanceToSegment(px, py, s[0], s[1], s[2], s[3]));
		}
		return best;
	}

	/** Segment {@code i-1 → i} in screen pixels, shifted along its normal: {x0, y0, x1, y1}. */
	private double[] screenSegment(Polyline polyline, int i, double offset) {
		double x0 = viewport.toScreenX(polyline.x(i - 1));
		double y0 = viewport.toScreenY(polyline.y(i - 1));
		double x1 = viewport.toScreenX(polyline.x(i));
		double y1 = viewport.toScreenY(polyline.y(i));
		double length = Math.hypot(x1 - x0, y1 - y0);
		double nx = length == 0 ? 0 : -(y1 - y0) / length * offset;
		double ny = length == 0 ? 0 : (x1 - x0) / length * offset;
		return new double[] { x0 + nx, y0 + ny, x1 + nx, y1 + ny };
	}

	private static double distanceToSegment(double px, double py, double x0, double y0, double x1, double y1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double lengthSquared = dx * dx + dy * dy;
		double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / lengthSquared));
		return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
	}

	private static Bounds bounds(Polyline polyline) {
		Bounds bounds = Bounds.around(polyline.x(0), polyline.y(0));
		for (int i = 1; i < polyline.size(); i++) {
			bounds = bounds.including(polyline.x(i), polyline.y(i));
		}
		return bounds;
	}

	private static boolean intersects(Bounds a, Bounds b) {
		return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minY() <= b.maxY() && a.maxY() >= b.minY();
	}

	/** Markers for every visible station; names in importance order, dropped where they would overlap. */
	private void drawStations(GraphicsContext g) {
		LabelPlacer placer = new LabelPlacer();
		double labelHeight = LABEL_FONT.getSize();
		g.setFont(LABEL_FONT);
		g.setLineWidth(1);
		for (NetworkMap.Station station : stationsByImportance) {
			double x = viewport.toScreenX(station.x());
			double y = viewport.toScreenY(station.y());
			if (x < -STATION_RADIUS_PX || y < -STATION_RADIUS_PX || x > getWidth() + STATION_RADIUS_PX
					|| y > getHeight() + STATION_RADIUS_PX) {
				continue;
			}
			g.setFill(palette.station());
			g.setStroke(palette.stationOutline());
			g.fillOval(x - STATION_RADIUS_PX, y - STATION_RADIUS_PX, 2 * STATION_RADIUS_PX, 2 * STATION_RADIUS_PX);
			g.strokeOval(x - STATION_RADIUS_PX, y - STATION_RADIUS_PX, 2 * STATION_RADIUS_PX, 2 * STATION_RADIUS_PX);
			placer.place(x, y, textWidth(station.name(), LABEL_FONT), labelHeight).ifPresent(placement -> {
				g.setFill(palette.label());
				g.fillText(station.name(), placement.x(), placement.y() + labelHeight - 2);
			});
		}
	}

	/** A card beside the cursor: one row per hovered line with its colour, name and daily service. */
	private void drawCard(GraphicsContext g, Hover hover) {
		double padding = 10;
		double swatch = 14;
		double rowHeight = 34;
		double width = 0;
		for (NetworkMap.Line line : hover.lines()) {
			width = Math.max(width, Math.max(textWidth(title(line), CARD_TITLE_FONT), textWidth(detail(line), CARD_FONT)));
		}
		width += 2 * padding + swatch;
		double height = 2 * padding + rowHeight * hover.lines().size() - 6;
		double x = Math.min(hover.x() + 14, getWidth() - width - 8);
		double y = Math.min(hover.y() + 14, getHeight() - height - 8);

		g.setFill(palette.card());
		g.fillRoundRect(x, y, width, height, 8, 8);
		g.setStroke(palette.stationOutline());
		g.setLineWidth(1);
		g.strokeRoundRect(x, y, width, height, 8, 8);
		double rowY = y + padding;
		for (NetworkMap.Line line : hover.lines()) {
			g.setFill(line.color());
			g.fillRoundRect(x + padding, rowY + 3, 8, 8, 2, 2);
			g.setFill(palette.label());
			g.setFont(CARD_TITLE_FONT);
			g.fillText(title(line), x + padding + swatch, rowY + 11);
			g.setFill(palette.stationOutline());
			g.setFont(CARD_FONT);
			g.fillText(detail(line), x + padding + swatch, rowY + 27);
			rowY += rowHeight;
		}
	}

	private static String title(NetworkMap.Line line) {
		return line.id() + "  " + line.name();
	}

	private static String detail(NetworkMap.Line line) {
		return (line.suburban() ? "Suburbana" : "Regionale") + " · " + line.dailyDepartures()
			+ " corse al giorno · " + line.stationCount() + " stazioni";
	}

	private double textWidth(String text, Font font) {
		return textWidths.computeIfAbsent(font.getName() + font.getSize() + text, key -> {
			Text measure = new Text(text);
			measure.setFont(font);
			return measure.getLayoutBounds().getWidth();
		});
	}

	private void drawAttribution(GraphicsContext g) {
		g.setFont(Font.font("Inter", 10));
		g.setFill(palette.label());
		g.fillText(TileStore.ATTRIBUTION, 8, getHeight() - 8);
	}
}
