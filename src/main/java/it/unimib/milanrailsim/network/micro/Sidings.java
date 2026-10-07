package it.unimib.milanrailsim.network.micro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The layover beyond which a train waiting at a terminus leaves the network
 * instead of holding a platform ({@code data/nodes/sidings.json}): by day the
 * fleet is in continuous use, so a timetable gap of hours at a terminus means
 * the unit went to the sidings or the depot. The rule holds at every terminus;
 * the file also documents where such facilities were surveyed.
 */
public record Sidings(double longLayoverSeconds) {

	/** No sidings anywhere: every layover is spent on the platform. */
	public static Sidings none() {
		return new Sidings(Double.POSITIVE_INFINITY);
	}

	public static Sidings read(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(file.toFile());
			JsonNode threshold = root.get("longLayoverThresholdMin");
			if (threshold == null || !threshold.isNumber()) {
				throw new IllegalArgumentException("Sidings file " + file + " lacks longLayoverThresholdMin");
			}
			return new Sidings(threshold.asDouble() * 60);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read sidings " + file, e);
		}
	}

	/** The longest wait a circulation may spend at a terminus before its next trip. */
	public double maxLayoverSeconds() {
		return longLayoverSeconds;
	}
}
