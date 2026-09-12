package it.unimib.milanrailsim.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which vehicle types run on each line and in what share of departures.
 * Shares of a line must sum to 100. Lines absent from the map fall back to the
 * declared regional default, since regional services are predominantly
 * Caravaggio stock (docs/network/infrastruttura-nodo-milano.md).
 */
public record LineAssignments(Map<String, List<Share>> byLine) {

	public record Share(String vehicleTypeId, int percent) {
	}

	public static final String REGIONAL_DEFAULT_TYPE = "caravaggio_521";

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final Set<String> SUBURBAN_ALTERNATING =
		Set.of("S1", "S2", "S3", "S4", "S5", "S6", "S8", "S9", "S12", "S13", "S19");
	private static final Map<String, String> DEDICATED = Map.ofEntries(
		Map.entry("S7", "atr125"), Map.entry("R18", "atr125"),
		Map.entry("R3", "atr125"), Map.entry("RE3", "atr125"),
		Map.entry("R9", "atr125"), Map.entry("S31", "atr125"),
		Map.entry("S11", "caravaggio_521"), Map.entry("RE1", "caravaggio_521"),
		Map.entry("RE54", "caravaggio_421"), Map.entry("RE51", "caravaggio_421"),
		Map.entry("RE13", "donizetti"), Map.entry("R34", "donizetti"),
		Map.entry("R35", "donizetti"), Map.entry("R36", "donizetti"),
		Map.entry("R37", "donizetti"),
		Map.entry("RE80", "tilo_flirt_tsi"));

	public LineAssignments {
		byLine.forEach((line, shares) -> {
			int total = shares.stream().mapToInt(Share::percent).sum();
			if (total != 100) {
				throw new IllegalArgumentException("Shares of line " + line + " sum to " + total + ", not 100");
			}
		});
		byLine = Map.copyOf(byLine);
	}

	/** The v1 rules: 70/30 TSR/TAF on the suburban lines, dedicated stock elsewhere (sources in the docs). */
	public static LineAssignments defaults() {
		Map<String, List<Share>> byLine = new TreeMap<>();
		SUBURBAN_ALTERNATING.forEach(line -> byLine.put(line, List.of(new Share("tsr", 70), new Share("taf", 30))));
		DEDICATED.forEach((line, type) -> byLine.put(line, List.of(new Share(type, 100))));
		return new LineAssignments(byLine);
	}

	/** Shares of a line, or the regional default when it is not configured. */
	public List<Share> sharesOf(String line) {
		return byLine.getOrDefault(line, List.of(new Share(REGIONAL_DEFAULT_TYPE, 100)));
	}

	public LineAssignments with(String line, List<Share> shares) {
		Map<String, List<Share>> updated = new LinkedHashMap<>(byLine);
		updated.put(line, List.copyOf(shares));
		return new LineAssignments(updated);
	}

	public static LineAssignments read(Path file) {
		try {
			Map<String, List<Share>> byLine = JSON.readValue(file.toFile(), new TypeReference<>() {
			});
			return new LineAssignments(byLine);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read line assignments " + file, e);
		}
	}

	public void write(Path file) {
		try {
			Files.createDirectories(file.getParent());
			JSON.writeValue(file.toFile(), new TreeMap<>(byLine));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write line assignments " + file, e);
		}
	}
}
