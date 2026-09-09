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

/**
 * Map of the network over OpenStreetMap tiles: tracks, stations and, later,
 * moving trains. Wheel or pinch zooms around the cursor, drag pans,
 * {@link #fitToNetwork()} resets.
 */
public final class MapCanvas extends Region {

	private static final double FIT_PADDING_PX = 24;
	private static final double ZOOM_STEP = 1.15;
	private static final double SUBURBAN_TRACK_WIDTH_PX = 2.5;
	private static final double MUTED_TRACK_WIDTH_PX = 1.2;
	private static final double STATION_RADIUS_PX = 3;
	private static final Font LABEL_FONT = Font.font("Inter", FontWeight.MEDIUM, 11);

	private final Canvas canvas = new Canvas();
	private final Viewport viewport = new Viewport();
	private final NetworkMap network;
	private final TileStore tiles;
	private final List<NetworkMap.Station> stationsByImportance;
	private final Map<String, Double> labelWidths = new HashMap<>();
	private MapPalette palette;
	private double dragX;
	private double dragY;

	/** @param tileCacheDir where downloaded OpenStreetMap tiles are kept for offline use */
	public MapCanvas(NetworkMap network, Path tileCacheDir, MapPalette palette) {
		this.network = network;
		this.tiles = new TileStore(tileCacheDir, this::draw);
		this.palette = palette;
		this.stationsByImportance = network.stations().stream()
			.sorted(Comparator.comparingInt(NetworkMap.Station::lineCount).reversed())
			.toList();
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
	}

	private void zoom(double factor, double screenX, double screenY) {
		viewport.zoom(factor, screenX, screenY);
		draw();
	}

	private void startDrag(MouseEvent event) {
		dragX = event.getX();
		dragY = event.getY();
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

	private void drawTracks(GraphicsContext g) {
		g.setLineCap(StrokeLineCap.ROUND);
		g.setLineJoin(StrokeLineJoin.ROUND);
		for (NetworkMap.Track track : network.tracks()) {
			boolean suburban = track.color() != null;
			g.setStroke(suburban ? track.color() : palette.mutedTrack());
			g.setLineWidth(suburban ? SUBURBAN_TRACK_WIDTH_PX : MUTED_TRACK_WIDTH_PX);
			strokePolyline(g, track.polyline());
		}
	}

	private void strokePolyline(GraphicsContext g, Polyline polyline) {
		g.beginPath();
		g.moveTo(viewport.toScreenX(polyline.x(0)), viewport.toScreenY(polyline.y(0)));
		for (int i = 1; i < polyline.size(); i++) {
			g.lineTo(viewport.toScreenX(polyline.x(i)), viewport.toScreenY(polyline.y(i)));
		}
		g.stroke();
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
			placer.place(x, y, labelWidth(station.name()), labelHeight).ifPresent(placement -> {
				g.setFill(palette.label());
				g.fillText(station.name(), placement.x(), placement.y() + labelHeight - 2);
			});
		}
	}

	private double labelWidth(String name) {
		return labelWidths.computeIfAbsent(name, text -> {
			Text measure = new Text(text);
			measure.setFont(LABEL_FONT);
			return measure.getLayoutBounds().getWidth();
		});
	}

	private void drawAttribution(GraphicsContext g) {
		g.setFont(Font.font("Inter", 10));
		g.setFill(palette.label());
		g.fillText(TileStore.ATTRIBUTION, 8, getHeight() - 8);
	}
}
