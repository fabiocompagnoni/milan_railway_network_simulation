package it.unimib.milanrailsim.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.milanrailsim.schedule.DensificationPlan.Tunnel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The fixed parameters of the line upgrade scenario, as declared in
 * {@code data/scenarios/potenziamento-per-linea.json}; the routes to upgrade
 * and their targets are chosen for each run.
 *
 * @param serviceGapSeconds     a gap between two trips of a route longer than this is a break in service, not a headway
 * @param minTrips              trips a route needs on the day to be offered for upgrade
 * @param minTripsPerDirection  trips it needs in each direction
 * @param tunnel                where passages closer than the minimum headway are reported; they are never refused
 */
public record LineUpgradePlan(int serviceGapSeconds, int minTrips, int minTripsPerDirection, int minTargetMinutes,
		int maxTargetMinutes, int proposedTargetMinutes, Tunnel tunnel) {

	public LineUpgradePlan {
		if (minTargetMinutes < 1 || minTargetMinutes > maxTargetMinutes
				|| proposedTargetMinutes < minTargetMinutes || proposedTargetMinutes > maxTargetMinutes) {
			throw new IllegalArgumentException("Targets must satisfy 1 <= min <= proposed <= max, not " + minTargetMinutes
				+ ", " + proposedTargetMinutes + ", " + maxTargetMinutes);
		}
	}

	/** @throws IllegalArgumentException when a field is missing or the targets are inconsistent, naming the problem */
	public static LineUpgradePlan read(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(file.toFile());
			JsonNode route = required(root, "regularRoute", file);
			JsonNode targets = required(root, "targetMinutes", file);
			JsonNode tunnel = required(root, "tunnel", file);
			return new LineUpgradePlan(required(root, "serviceGapMinutes", file).asInt() * 60,
				required(route, "minTrips", file).asInt(), required(route, "minTripsPerDirection", file).asInt(),
				required(targets, "min", file).asInt(), required(targets, "max", file).asInt(),
				required(targets, "proposed", file).asInt(),
				new Tunnel(required(tunnel, "referenceStop", file).asText(), required(tunnel, "minHeadwaySeconds", file).asInt()));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read scenario " + file, e);
		}
	}

	private static JsonNode required(JsonNode node, String field, Path file) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw new IllegalArgumentException("Scenario file " + file + " lacks " + field);
		}
		return value;
	}
}
