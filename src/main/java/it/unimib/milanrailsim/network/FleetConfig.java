package it.unimib.milanrailsim.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rolling stock catalogue: one entry per railsim vehicle type with the
 * figures the engine needs, where each figure comes from and which ones are
 * estimates. Figures and sources are documented in
 * docs/network/infrastruttura-nodo-milano.md ("Parco rotabile").
 */
public record FleetConfig(List<TrainType> types) {

	public enum Traction {
		ELECTRIC("Elettrica"), DIESEL("Diesel");

		private final String label;

		Traction(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * @param id railsim vehicle type id, the key used by line assignments and past runs
	 * @param sources citation per figure, keyed by field name ({@code length}, {@code seats}, …)
	 * @param estimated names of the figures that are estimates without a published source
	 */
	public record TrainType(String id, String name, double lengthMeters, int seats, double vmaxKmh,
			double accelerationMps2, double decelerationMps2, Traction traction,
			Map<String, String> sources, Set<String> estimated) {

		public boolean isEstimated(String field) {
			return estimated.contains(field);
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final String TRENORD_FLEET = "Trenord, La flotta, trenord.it (consultato il 5 agosto 2026)";
	private static final String WIKIPEDIA_CARAVAGGIO = "Wikipedia, Elettrotreno FS ETR 421/521 (consultato il 5 agosto 2026)";
	private static final String TILO_SOURCES = "Schede RABe 524 FLIRT TSI: trainswiss.ch, sguggiari.ch, Wikipedia (consultati il 5 agosto 2026)";
	private static final double ESTIMATED_DECELERATION = 0.5;

	public FleetConfig {
		types = List.copyOf(types);
		long distinct = types.stream().map(TrainType::id).distinct().count();
		if (distinct != types.size()) {
			throw new IllegalArgumentException("Duplicate vehicle type ids");
		}
	}

	/** The eight types of the v1 model with the sources recorded in the infrastructure notes. */
	public static FleetConfig defaults() {
		return new FleetConfig(List.of(
			type("tsr", "TSR", 104.98, 436, 140, 1.0, Traction.ELECTRIC,
				Map.of("seats", TRENORD_FLEET + ", composizione a 4 casse", "vmax", TRENORD_FLEET),
				Set.of("length", "acceleration", "deceleration")),
			type("taf", "TAF", 103.97, 469, 140, 0.8, Traction.ELECTRIC,
				Map.of("seats", TRENORD_FLEET, "vmax", TRENORD_FLEET),
				Set.of("length", "acceleration", "deceleration")),
			type("caravaggio_421", "Caravaggio ETR 421", 109.6, 466, 160, 1.10, Traction.ELECTRIC,
				Map.of("length", WIKIPEDIA_CARAVAGGIO, "seats", WIKIPEDIA_CARAVAGGIO, "vmax", TRENORD_FLEET,
					"acceleration", WIKIPEDIA_CARAVAGGIO),
				Set.of("deceleration")),
			type("caravaggio_521", "Caravaggio ETR 521", 136.8, 598, 160, 1.10, Traction.ELECTRIC,
				Map.of("length", WIKIPEDIA_CARAVAGGIO, "seats", WIKIPEDIA_CARAVAGGIO, "vmax", TRENORD_FLEET,
					"acceleration", WIKIPEDIA_CARAVAGGIO),
				Set.of("deceleration")),
			type("donizetti", "Donizetti ETR 204", 84.2, 262, 160, 1.0, Traction.ELECTRIC,
				Map.of("seats", TRENORD_FLEET + ", oltre 200 posti a 3 casse", "vmax", TRENORD_FLEET),
				Set.of("length", "seats", "acceleration", "deceleration")),
			type("etr245", "ETR 245 Coradia Meridian", 82.2, 230, 160, 1.0, Traction.ELECTRIC,
				Map.of("length", TRENORD_FLEET, "seats", TRENORD_FLEET, "vmax", TRENORD_FLEET),
				Set.of("acceleration", "deceleration")),
			type("atr125", "ATR 125 Stadler GTW", 77.33, 231, 140, 0.6, Traction.DIESEL,
				Map.of("seats", TRENORD_FLEET, "vmax", TRENORD_FLEET),
				Set.of("length", "acceleration", "deceleration")),
			type("tilo_flirt_tsi", "FLIRT TSI (TILO)", 105.0, 244, 160, 1.0, Traction.ELECTRIC,
				Map.of("length", TILO_SOURCES, "seats", TILO_SOURCES, "vmax", TILO_SOURCES),
				Set.of("acceleration", "deceleration"))));
	}

	private static TrainType type(String id, String name, double length, int seats, double vmax,
			double acceleration, Traction traction, Map<String, String> sources, Set<String> estimated) {
		return new TrainType(id, name, length, seats, vmax, acceleration, ESTIMATED_DECELERATION, traction,
			sources, estimated);
	}

	public TrainType type(String id) {
		return types.stream().filter(type -> type.id().equals(id)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown vehicle type: " + id));
	}

	/** Replaces the type with the same id, or appends a new one. */
	public FleetConfig with(TrainType type) {
		List<TrainType> updated = new ArrayList<>(types);
		updated.removeIf(existing -> existing.id().equals(type.id()));
		updated.add(type);
		return new FleetConfig(updated);
	}

	public FleetConfig without(String id) {
		return new FleetConfig(types.stream().filter(type -> !type.id().equals(id)).toList());
	}

	public static FleetConfig read(Path file) {
		try {
			return new FleetConfig(JSON.readValue(file.toFile(), new TypeReference<List<TrainType>>() {
			}));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read fleet " + file, e);
		}
	}

	public void write(Path file) {
		try {
			Files.createDirectories(file.getParent());
			JSON.writeValue(file.toFile(), types);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write fleet " + file, e);
		}
	}
}
