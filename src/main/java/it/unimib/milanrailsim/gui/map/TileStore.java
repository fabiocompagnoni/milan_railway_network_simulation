package it.unimib.milanrailsim.gui.map;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenStreetMap raster tiles with a memory and a disk cache, so the map works
 * offline once an area has been seen. Requests carry the User-Agent the OSM
 * tile usage policy asks for; the caller must draw the attribution.
 */
public final class TileStore {

	public static final String ATTRIBUTION = "© OpenStreetMap contributors";

	private static final Logger log = LogManager.getLogger(TileStore.class);
	private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
	private static final String USER_AGENT = "MilanRailSim/1.0 (Milano-Bicocca student project)";
	private static final int MEMORY_TILES = 400;
	private static final int DOWNLOAD_THREADS = 4;

	private final Path cacheDir;
	private final Runnable onTileLoaded;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService downloads = Executors.newFixedThreadPool(DOWNLOAD_THREADS, runnable -> {
		Thread thread = new Thread(runnable, "tile-download");
		thread.setDaemon(true);
		return thread;
	});
	private final Set<TileGrid.Tile> pending = ConcurrentHashMap.newKeySet();
	private final Map<TileGrid.Tile, Image> memory = Collections.synchronizedMap(
		new LinkedHashMap<>(MEMORY_TILES, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<TileGrid.Tile, Image> eldest) {
				return size() > MEMORY_TILES;
			}
		});

	/** @param onTileLoaded invoked on the JavaFX thread whenever a tile becomes drawable */
	public TileStore(Path cacheDir, Runnable onTileLoaded) {
		this.cacheDir = cacheDir;
		this.onTileLoaded = onTileLoaded;
	}

	/** The tile if already in memory; otherwise schedules disk or network loading and returns empty. */
	public Optional<Image> get(TileGrid.Tile tile) {
		Image cached = memory.get(tile);
		if (cached != null) {
			return Optional.of(cached);
		}
		if (pending.add(tile)) {
			downloads.submit(() -> load(tile));
		}
		return Optional.empty();
	}

	private void load(TileGrid.Tile tile) {
		try {
			Path file = cacheDir.resolve(String.valueOf(tile.zoom()))
				.resolve(String.valueOf(tile.x())).resolve(tile.y() + ".png");
			if (!Files.exists(file)) {
				download(tile, file);
			}
			try (InputStream input = Files.newInputStream(file)) {
				memory.put(tile, new Image(input));
			}
			Platform.runLater(onTileLoaded);
		} catch (IOException | InterruptedException e) {
			log.warn("Tile {} unavailable: {}", tile, e.getMessage());
		} finally {
			pending.remove(tile);
		}
	}

	private void download(TileGrid.Tile tile, Path file) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(TILE_URL, tile.zoom(), tile.x(), tile.y())))
			.header("User-Agent", USER_AGENT)
			.timeout(Duration.ofSeconds(20))
			.build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		Files.createDirectories(file.getParent());
		Files.write(file, response.body());
	}
}
