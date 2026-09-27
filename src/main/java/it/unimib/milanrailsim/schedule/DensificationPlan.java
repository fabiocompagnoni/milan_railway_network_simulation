package it.unimib.milanrailsim.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where and how much the urban timetable is densified, as declared in
 * {@code data/scenarios/passante-alta-frequenza.json}: the relations that
 * receive added trips, the minimum headway of the Passante tunnel and, per
 * kind of day, the hours in which trips are added.
 *
 * @param serviceGapSeconds a gap between two trips of a line longer than this is a break in service, not a headway to fill
 */
public record DensificationPlan(List<Relation> relations, Tunnel tunnel, int serviceGapSeconds,
		Map<DayKind, List<Band>> dayProfiles) {

	/** Cadences the scenario offers, densest last; the real lines run every thirty minutes. */
	static final List<Integer> CADENCES_MINUTES = List.of(20, 15, 10);

	public enum Intensity {
		/** The line reaches the cadence. */
		FULL,
		/** Every other gap receives its trips. */
		REDUCED
	}

	public enum DayKind {
		WEEKDAY, SATURDAY, HOLIDAY;

		static DayKind of(LocalDate day) {
			return switch (day.getDayOfWeek()) {
				case SATURDAY -> SATURDAY;
				case SUNDAY -> HOLIDAY;
				default -> WEEKDAY;
			};
		}
	}

	public enum Level {
		PEAK, OFF_PEAK
	}

	/**
	 * Trips added between two stations, in both directions.
	 *
	 * @param lines         the lines whose trips are copied; several lines alternate
	 * @param from          stop id of one end
	 * @param to            stop id of the other end
	 * @param throughTunnel whether the trips run through the Passante tunnel and must keep its minimum headway
	 */
	public record Relation(String id, List<String> lines, String from, String to, Intensity intensity, boolean throughTunnel) {
	}

	/** @param referenceStop stop id where the headway of the tunnel is measured */
	public record Tunnel(String referenceStop, int minHeadwaySeconds) {
	}

	/**
	 * Hours of a day in which trips are added, upper bound excluded.
	 *
	 * @param onlyIntensity the relations the band applies to, or empty for all of them
	 */
	public record Band(int fromSeconds, int toSeconds, Level level, Optional<Intensity> onlyIntensity) {

		boolean covers(int secondsOfDay, Intensity intensity) {
			return secondsOfDay >= fromSeconds && secondsOfDay < toSeconds
				&& onlyIntensity.map(intensity::equals).orElse(true);
		}
	}

	public DensificationPlan {
		relations = List.copyOf(relations);
		dayProfiles = Map.copyOf(dayProfiles);
	}

	/** @throws IllegalArgumentException when a field is missing or a relation is incomplete, naming it */
	public static DensificationPlan read(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(file.toFile());
			List<Relation> relations = new ArrayList<>();
			for (JsonNode node : required(root, "relations", file)) {
				relations.add(relation(node, file));
			}
			JsonNode tunnel = required(root, "tunnel", file);
			Map<DayKind, List<Band>> profiles = new EnumMap<>(DayKind.class);
			JsonNode days = required(root, "dayProfiles", file);
			for (DayKind kind : DayKind.values()) {
				List<Band> bands = new ArrayList<>();
				for (JsonNode node : required(days, kind.name().toLowerCase(), file)) {
					bands.add(band(node, file));
				}
				profiles.put(kind, List.copyOf(bands));
			}
			return new DensificationPlan(relations,
				new Tunnel(required(tunnel, "referenceStop", file).asText(), required(tunnel, "minHeadwaySeconds", file).asInt()),
				required(root, "serviceGapMinutes", file).asInt() * 60, profiles);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read scenario " + file, e);
		}
	}

	private static Relation relation(JsonNode node, Path file) {
		String id = required(node, "id", file).asText();
		List<String> lines = new ArrayList<>();
		required(node, "lines", file).forEach(line -> lines.add(line.asText()));
		String from = required(node, "from", file).asText();
		String to = required(node, "to", file).asText();
		if (lines.isEmpty() || from.isBlank() || to.isBlank() || from.equals(to)) {
			throw new IllegalArgumentException("Relation " + id + " in " + file + " needs at least one line and two different ends");
		}
		return new Relation(id, List.copyOf(lines), from, to,
			Intensity.valueOf(required(node, "intensity", file).asText().toUpperCase()),
			required(node, "throughTunnel", file).asBoolean());
	}

	private static Band band(JsonNode node, Path file) {
		JsonNode only = node.get("onlyIntensity");
		return new Band(LocalTime.parse(required(node, "from", file).asText()).toSecondOfDay(),
			LocalTime.parse(required(node, "to", file).asText()).toSecondOfDay(),
			"peak".equals(required(node, "level", file).asText()) ? Level.PEAK : Level.OFF_PEAK,
			only == null ? Optional.empty() : Optional.of(Intensity.valueOf(only.asText().toUpperCase())));
	}

	private static JsonNode required(JsonNode node, String field, Path file) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw new IllegalArgumentException("Scenario file " + file + " lacks " + field);
		}
		return value;
	}

	/**
	 * The headway to reach at a moment of a day: the chosen cadence at peak
	 * hours, one step sparser off peak, none outside the bands of the day.
	 *
	 * @param peakCadenceMinutes one of 20, 15 and 10
	 * @return the target headway in seconds, or empty when no trip is to be added at that time
	 * @throws IllegalArgumentException for a cadence the scenario does not offer
	 */
	public OptionalInt cadenceSeconds(LocalDate day, int secondsOfDay, int peakCadenceMinutes, Intensity intensity) {
		int step = CADENCES_MINUTES.indexOf(peakCadenceMinutes);
		if (step < 0) {
			throw new IllegalArgumentException("Cadence must be one of " + CADENCES_MINUTES + " minutes, not " + peakCadenceMinutes);
		}
		Optional<Band> band = dayProfiles.get(DayKind.of(day)).stream()
			.filter(candidate -> candidate.covers(secondsOfDay, intensity)).findFirst();
		if (band.isEmpty()) {
			return OptionalInt.empty();
		}
		int applied = band.get().level() == Level.PEAK ? step : step - 1;
		return applied < 0 ? OptionalInt.empty() : OptionalInt.of(CADENCES_MINUTES.get(applied) * 60);
	}
}
