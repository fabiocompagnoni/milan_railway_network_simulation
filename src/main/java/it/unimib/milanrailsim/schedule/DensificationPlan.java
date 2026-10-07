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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Where and how much the urban timetable is densified, as declared in
 * {@code data/scenarios/passante-alta-frequenza.json}: the relations that
 * receive added trips, the minimum headways and, per kind of day, the hours
 * in which trips are added. Relations are served in the order of the file.
 *
 * @param serviceGapSeconds a gap between two trains longer than this is a break in service, not a headway to fill
 * @param minSpacingSeconds no trip is added if the headway it produces falls below this
 */
public record DensificationPlan(List<Relation> relations, Tunnel tunnel, int serviceGapSeconds, int minSpacingSeconds,
		Map<DayKind, List<Band>> dayProfiles) {

	/**
	 * Longest waits the scenario offers as a target, shortest last. Both divide
	 * the thirty minutes of the real lines, so on a line running alone the
	 * added trips fall at even fractions of a real headway.
	 */
	public static final List<Integer> CADENCES_MINUTES = List.of(15, 10);

	public enum Intensity {
		/** Every gap above the target receives its trips. */
		FULL,
		/** Every other such gap does. */
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
	 * Trips added where the trains of a flow leave too long a wait, in both
	 * directions.
	 *
	 * @param flow          two stop ids: the headway is measured on every train, of any line, calling at the first
	 *                      and then at the second, and the other way round for the opposite direction
	 * @param services      what the added trips are copies of, taken in turn
	 * @param throughTunnel whether the trips run through the Passante tunnel and must keep its minimum headway
	 * @param promptTermini stop ids of the ends of the services where an added trip leaves as soon as the train of an
	 *                      added trip that ended there has turned around; at most one end of each service
	 */
	public record Relation(String id, List<String> flow, List<Service> services, Intensity intensity, boolean throughTunnel,
			Set<String> promptTermini) {

		public Relation {
			flow = List.copyOf(flow);
			services = List.copyOf(services);
			promptTermini = Set.copyOf(promptTermini);
			for (Service service : services) {
				if (promptTermini.contains(service.from()) && promptTermini.contains(service.to())) {
					throw new IllegalArgumentException("Relation " + id + " asks for prompt departures at both ends of " + service.line()
						+ ": the time gained at one end is spent at the other");
				}
			}
		}

		/** A relation whose added trips all leave at even fractions of the gap they fill. */
		public Relation(String id, List<String> flow, List<Service> services, Intensity intensity, boolean throughTunnel) {
			this(id, flow, services, intensity, throughTunnel, Set.of());
		}
	}

	/**
	 * A kind of added trip: the copy of a real trip of a line, between two of its stops.
	 *
	 * @param from stop id of one end
	 * @param to   stop id of the other end
	 */
	public record Service(String line, String from, String to) {
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
				required(root, "serviceGapMinutes", file).asInt() * 60, required(root, "minSpacingSeconds", file).asInt(), profiles);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read scenario " + file, e);
		}
	}

	private static Relation relation(JsonNode node, Path file) {
		String id = required(node, "id", file).asText();
		List<String> flow = new ArrayList<>();
		required(node, "flow", file).forEach(stop -> flow.add(stop.asText()));
		List<Service> services = new ArrayList<>();
		for (JsonNode service : required(node, "services", file)) {
			services.add(new Service(required(service, "line", file).asText(), required(service, "from", file).asText(),
				required(service, "to", file).asText()));
		}
		if (flow.size() != 2 || flow.getFirst().equals(flow.getLast())) {
			throw new IllegalArgumentException("Relation " + id + " in " + file + " needs a flow of two different stops");
		}
		if (services.isEmpty() || services.stream().anyMatch(service -> service.from().equals(service.to()))) {
			throw new IllegalArgumentException("Relation " + id + " in " + file + " needs services between two different stops");
		}
		Set<String> promptTermini = new HashSet<>();
		if (node.has("promptTermini")) {
			node.get("promptTermini").forEach(stop -> promptTermini.add(stop.asText()));
		}
		return new Relation(id, flow, services, Intensity.valueOf(required(node, "intensity", file).asText().toUpperCase()),
			required(node, "throughTunnel", file).asBoolean(), promptTermini);
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
	 * The longest wait to allow at a moment of a day: the chosen target at
	 * peak hours, one step longer off peak, none outside the bands of the day.
	 *
	 * @param peakCadenceMinutes one of {@link #CADENCES_MINUTES}
	 * @return the target in seconds, or empty when no trip is to be added at that time
	 * @throws IllegalArgumentException for a target the scenario does not offer
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

	/**
	 * How many trips a gap between two trains takes: as many as keep the wait
	 * within the target, fewer if the headway would fall below the minimum
	 * spacing.
	 */
	public int tripsFitting(int gapSeconds, int cadenceSeconds) {
		int trips = Math.ceilDiv(gapSeconds, cadenceSeconds) - 1;
		while (trips > 0 && gapSeconds / (trips + 1) < minSpacingSeconds) {
			trips--;
		}
		return Math.max(0, trips);
	}
}
