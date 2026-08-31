package it.unimib.milanrailsim.schedule;

import java.util.Map;
import java.util.Set;

/**
 * Route-to-vehicle-type assignment of the v1 model. Table and rationale in
 * docs/network/infrastruttura-nodo-milano.md ("Assegnazione rotta-tipo v1").
 */
public class RouteVehicleAssignment implements VehicleAssignment {

	private static final Set<String> EXCLUDED = Set.of("S10", "S30", "S40", "S50", "Trenord GP");
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

	public boolean isExcluded(String routeShortName) {
		return EXCLUDED.contains(routeShortName);
	}

	public String vehicleTypeId(String routeShortName, int departureIndex) {
		if (isExcluded(routeShortName)) {
			throw new IllegalArgumentException("Excluded route: " + routeShortName);
		}
		if (SUBURBAN_ALTERNATING.contains(routeShortName)) {
			return departureIndex % 10 <= 6 ? "tsr" : "taf";
		}
		String dedicated = DEDICATED.get(routeShortName);
		if (dedicated != null) {
			return dedicated;
		}
		if (routeShortName.startsWith("RE") || routeShortName.startsWith("R")) {
			return "caravaggio_521";
		}
		throw new IllegalArgumentException("No vehicle type assigned to route: " + routeShortName);
	}
}
