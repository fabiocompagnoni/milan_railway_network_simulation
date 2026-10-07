package it.unimib.milanrailsim.network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Platform tracks per terminal station from the survey table
 * ({@code stop_id, nome, corse_giorno, banchine_OSM_da_correggere, linee, binari_reali…}).
 * A surveyed count is authoritative; the OSM platform count is a provisional
 * stand-in until the survey fills the row.
 */
public final class StationTracks {

	public record Tracks(int count, boolean provisional) {
	}

	private final Map<String, Tracks> byStation;

	private StationTracks(Map<String, Tracks> byStation) {
		this.byStation = Map.copyOf(byStation);
	}

	public static StationTracks empty() {
		return new StationTracks(Map.of());
	}

	/** Fields may be quoted (line lists contain commas); a stray carriage return in the header is tolerated. */
	public static StationTracks read(Path csv) {
		List<String> lines;
		try {
			lines = List.of(Files.readString(csv).replace("\r", "").split("\n"));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read station tracks " + csv, e);
		}
		if (lines.isEmpty() || lines.getFirst().isBlank()) {
			throw new IllegalArgumentException("Empty station tracks table: " + csv);
		}
		List<String> header = split(lines.getFirst());
		int id = header.indexOf("stop_id");
		int osm = header.indexOf("banchine_OSM_da_correggere");
		int real = -1;
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i).startsWith("binari_reali")) {
				real = i;
			}
		}
		if (id < 0 || osm < 0 || real < 0) {
			throw new IllegalArgumentException("Station tracks table needs stop_id, banchine_OSM_da_correggere, binari_reali: " + csv);
		}
		Map<String, Tracks> byStation = new HashMap<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			List<String> fields = split(line);
			String surveyed = real < fields.size() ? fields.get(real).trim() : "";
			String platforms = osm < fields.size() ? fields.get(osm).trim() : "";
			if (!surveyed.isEmpty()) {
				byStation.put(fields.get(id), new Tracks(Integer.parseInt(surveyed), false));
			} else if (!platforms.isEmpty()) {
				byStation.put(fields.get(id), new Tracks(Integer.parseInt(platforms), true));
			}
		}
		return new StationTracks(byStation);
	}

	public Optional<Tracks> of(String stationId) {
		return Optional.ofNullable(byStation.get(stationId));
	}

	public int size() {
		return byStation.size();
	}

	private static List<String> split(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (char c : line.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			} else if (c == ',' && !quoted) {
				fields.add(field.toString());
				field.setLength(0);
			} else {
				field.append(c);
			}
		}
		fields.add(field.toString());
		return fields;
	}
}
