package it.unimib.milanrailsim.network.micro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stations with sidings or a depot ({@code data/nodes/sidings.json}) and the
 * layover beyond which a train waiting there leaves the network instead of
 * holding a platform: by day the fleet is in continuous use, so a timetable
 * gap of hours at a terminus means the unit went to the sidings.
 */
public record Sidings(Set<String> stations, double longLayoverSeconds) {

	public Sidings {
		stations = Set.copyOf(stations);
	}

	/** No sidings anywhere: every layover is spent on the platform. */
	public static Sidings none() {
		return new Sidings(Set.of(), Double.POSITIVE_INFINITY);
	}

	public static Sidings read(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(file.toFile());
			JsonNode threshold = root.get("longLayoverThresholdMin");
			if (threshold == null || !threshold.isNumber()) {
				throw new IllegalArgumentException("Sidings file " + file + " lacks longLayoverThresholdMin");
			}
			Set<String> stations = new LinkedHashSet<>();
			for (JsonNode location : root.path("locations")) {
				JsonNode station = location.get("station");
				if (station == null || station.isNull()) {
					throw new IllegalArgumentException("Sidings location without station in " + file);
				}
				stations.add(station.asText());
			}
			return new Sidings(stations, threshold.asDouble() * 60);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read sidings " + file, e);
		}
	}

	/** The longest wait a circulation may spend at a stop before the next trip: the threshold where sidings exist, unbounded elsewhere. */
	public double maxLayoverSeconds(String stopId) {
		return stations.contains(stopId) ? longLayoverSeconds : Double.POSITIVE_INFINITY;
	}
}
