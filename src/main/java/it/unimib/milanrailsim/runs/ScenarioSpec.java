package it.unimib.milanrailsim.runs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Everything a run is configured with; written next to its results so a run
 * stays reproducible. Parameters that do not apply to the run are null.
 *
 * @param window the part of the service day to simulate; null for the whole day
 * @param metroHeadwayMinutes target headway on the Passante core (high-frequency Passante)
 * @param collapseStartHeadwayMinutes first headway of the collapse campaign
 * @param collapseStepMinutes headway decrease between collapse steps
 * @param collapseSteps number of runs in the collapse campaign
 * @param dynamicReductionPercent headway reduction applied to every line (dynamic)
 */
public record ScenarioSpec(
		String name,
		SimulationType type,
		LocalDate serviceDate,
		TimeWindow window,
		Integer metroHeadwayMinutes,
		Integer collapseStartHeadwayMinutes,
		Integer collapseStepMinutes,
		Integer collapseSteps,
		Integer dynamicReductionPercent) {

	public enum SimulationType {
		REAL("Reale"), METRO_LIKE("Passante ad alta frequenza"), COLLAPSE("Collasso"), DYNAMIC("Dinamica");

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

	private static final ObjectMapper JSON = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.enable(SerializationFeature.INDENT_OUTPUT);

	public static ScenarioSpec real(String name, LocalDate serviceDate, TimeWindow window) {
		return new ScenarioSpec(name, SimulationType.REAL, serviceDate, window, null, null, null, null, null);
	}

	/** Number of engine runs this scenario produces: one, or one per collapse step. */
	public int runCount() {
		return type == SimulationType.COLLAPSE ? collapseSteps : 1;
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
