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
 * declared regional mix.
 * <p>
 * The operator assigns trains by depot and shift, not by line, so a line sees
 * several types through the day: the shares stand for that mix. They are set
 * so that the trains of each type a weekday needs stay within the fleet in
 * service (docs/network/materiale-rotabile.md). Of the Coradia Meridian
 * family, the ETR 245 runs on suburban lines and the ETR 425 on regional ones.
 */
public record LineAssignments(Map<String, List<Share>> byLine) {

	public record Share(String vehicleTypeId, int percent) {
	}

	/** The stock of a regional line with no rule of its own: mostly the new Caravaggio, then TSR. */
	public static final List<Share> REGIONAL_DEFAULT =
		List.of(new Share("caravaggio_521", 60), new Share("tsr", 30), new Share("etr425", 10));

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	/** Suburban lines run mostly TSR; the TAF, in service the longest, fill the gaps. */
	private static final List<Share> SUBURBAN_MIX = List.of(new Share("tsr", 60), new Share("caravaggio_521", 20),
		new Share("etr245", 10), new Share("taf", 10));
	private static final Set<String> SUBURBAN_MIXED = Set.of("S1", "S2", "S5", "S6", "S8", "S9", "S12", "S13", "S19");
	private static final List<Share> SUBURBAN_CARAVAGGIO_MIX = List.of(new Share("caravaggio_521", 70), new Share("tsr", 30));
	private static final Set<String> SUBURBAN_MOSTLY_CARAVAGGIO = Set.of("S3", "S4");
	/** TILO runs 30 six-car sets and 23 four-car ones: the shares follow the fleet, the longer type first. */
	private static final List<Share> TILO_MIX = List.of(new Share("tilo_flirt_6", 57), new Share("tilo_flirt_4", 43));
	private static final Set<String> TILO = Set.of("S10", "S30", "S40", "S50");
	private static final Map<String, String> DEDICATED = Map.ofEntries(
		Map.entry("S7", "atr125"), Map.entry("R18", "atr125"),
		Map.entry("R3", "atr125"), Map.entry("RE3", "atr125"),
		Map.entry("R9", "atr125"), Map.entry("S31", "atr125"),
		Map.entry("S11", "caravaggio_521"),
		Map.entry("RE54", "caravaggio_421"), Map.entry("RE51", "caravaggio_421"),
		Map.entry("RE13", "donizetti"), Map.entry("R34", "donizetti"),
		Map.entry("RE8", "donizetti"), Map.entry("R13", "donizetti"),
		Map.entry("R11", "donizetti"), Map.entry("R12", "donizetti"),
		Map.entry("R7", "donizetti"), Map.entry("R21", "donizetti"),
		Map.entry("R6", "donizetti"),
		Map.entry("RE80", "tilo_flirt_tsi"),
		Map.entry("R8", "atr803"), Map.entry("R35", "atr803"),
		Map.entry("R36", "atr803"), Map.entry("R37", "atr803"));

	public LineAssignments {
		byLine.forEach((line, shares) -> {
			int total = shares.stream().mapToInt(Share::percent).sum();
			if (total != 100) {
				throw new IllegalArgumentException("Shares of line " + line + " sum to " + total + ", not 100");
			}
		});
		byLine = Map.copyOf(byLine);
	}

	/** The rules of October 2026: a mix on the suburban lines, dedicated stock where a line has one, the regional mix elsewhere. */
	public static LineAssignments defaults() {
		Map<String, List<Share>> byLine = new TreeMap<>();
		SUBURBAN_MIXED.forEach(line -> byLine.put(line, SUBURBAN_MIX));
		SUBURBAN_MOSTLY_CARAVAGGIO.forEach(line -> byLine.put(line, SUBURBAN_CARAVAGGIO_MIX));
		TILO.forEach(line -> byLine.put(line, TILO_MIX));
		DEDICATED.forEach((line, type) -> byLine.put(line, List.of(new Share(type, 100))));
		return new LineAssignments(byLine);
	}

	/** Shares of a line, or the regional mix when it is not configured. */
	public List<Share> sharesOf(String line) {
		return byLine.getOrDefault(line, REGIONAL_DEFAULT);
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
