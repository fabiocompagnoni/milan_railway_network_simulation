package it.unimib.milanrailsim.network;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OSM measures for skeleton links, loaded from the committed sweep CSV
 * (data/osm). A value is exposed only when it passed the quality gates below;
 * everything else stays empty so the caller cannot mistake a rejected
 * measurement for a real one (project correctness rule).
 */
final class MesoMeasuresTable {

	/**
	 * The path-length measure is trusted only when the shortest OSM path is
	 * plausible against the straight line: below 1.0 is geometrically
	 * impossible; above 2.5 the ruler found a detour, not the line (real
	 * curvature observed in this network reaches ~2.3 on mountain branches,
	 * and the GTFS oracle guards the range below that).
	 */
	private static final double MIN_PLAUSIBLE_RATIO = 1.0;
	private static final double MAX_PLAUSIBLE_RATIO = 2.5;

	record Measure(String linkId, Optional<Integer> tracksTotal, Optional<Double> lengthM,
			Optional<Double> eqSpeedKmh, Optional<Integer> gtfsMinSeconds, String osmWayIds) {
	}

	private final Map<String, Measure> byLinkId;

	private MesoMeasuresTable(Map<String, Measure> byLinkId) {
		this.byLinkId = byLinkId;
	}

	static MesoMeasuresTable load(Path csv) {
		Map<String, Measure> table = new LinkedHashMap<>();
		for (Map<String, String> row : CsvTable.read(csv)) {
			String linkId = row.get("link_id");
			if (table.containsKey(linkId)) {
				throw new IllegalArgumentException("Duplicate link_id in measures: " + linkId);
			}
			table.put(linkId, toMeasure(linkId, row));
		}
		return new MesoMeasuresTable(table);
	}

	private static Measure toMeasure(String linkId, Map<String, String> row) {
		if (!"ok".equals(row.get("status"))) {
			return new Measure(linkId, Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.empty(), "");
		}
		Optional<Integer> tracks = parseInt(row.get("passenger_lines_mode"))
			.or(() -> parseInt(row.get("n_tracks")).filter(n -> n <= 2));
		Optional<Double> length = parseDouble(row.get("osm_min_m"))
			.filter(l -> parseDouble(row.get("ratio"))
				.map(r -> r >= MIN_PLAUSIBLE_RATIO && r <= MAX_PLAUSIBLE_RATIO)
				.orElse(false));
		Optional<Double> eqSpeed = parseDouble(row.get("eq_speed_kmh"));
		return new Measure(linkId, tracks, length, eqSpeed,
			parseInt(row.get("gtfs_min_s")).filter(s -> s > 0), row.get("osm_way_ids"));
	}

	private static Optional<Integer> parseInt(String value) {
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(Integer.parseInt(value));
	}

	private static Optional<Double> parseDouble(String value) {
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(Double.parseDouble(value));
	}

	Map<String, Measure> byLinkId() {
		return byLinkId;
	}
}
