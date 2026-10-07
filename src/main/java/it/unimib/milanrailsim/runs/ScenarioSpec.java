package it.unimib.milanrailsim.runs;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.unimib.milanrailsim.schedule.RouteTarget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Everything a run is configured with; written next to its results so a run
 * stays reproducible. Parameters that do not apply to the run are null.
 *
 * @param window the part of the service day to simulate; null for the whole day
 * @param metroHeadwayMinutes longest wait allowed at peak hours on the urban relations (high-frequency Passante)
 * @param routeTargets the routes upgraded and the longest wait allowed on each (line upgrade)
 */
public record ScenarioSpec(
		String name,
		SimulationType type,
		LocalDate serviceDate,
		TimeWindow window,
		Integer metroHeadwayMinutes,
		List<RouteTarget> routeTargets) {

	public ScenarioSpec {
		routeTargets = routeTargets == null ? null : List.copyOf(routeTargets);
	}

	public enum SimulationType {
		REAL("Reale"), METRO_LIKE("Passante ad alta frequenza"), LINE_UPGRADE("Potenziamento per linea");

		private final String label;

		SimulationType(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public record TimeWindow(LocalTime start, LocalTime end) {
	}

	// a scenario written by a later version may carry parameters this one does not know: the run stays readable
	private static final ObjectMapper JSON = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.enable(SerializationFeature.INDENT_OUTPUT);

	public static ScenarioSpec real(String name, LocalDate serviceDate, TimeWindow window) {
		return new ScenarioSpec(name, SimulationType.REAL, serviceDate, window, null, null);
	}

	/** The same scenario under another run name. */
	public ScenarioSpec named(String runName) {
		return new ScenarioSpec(runName, type, serviceDate, window, metroHeadwayMinutes, routeTargets);
	}

	public void write(Path file) {
		try {
			Files.createDirectories(file.getParent());
			JSON.writeValue(file.toFile(), this);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write scenario " + file, e);
		}
	}

	public static ScenarioSpec read(Path file) {
		try {
			return JSON.readValue(file.toFile(), ScenarioSpec.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read scenario " + file, e);
		}
	}
}
