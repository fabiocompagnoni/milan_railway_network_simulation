package it.unimib.milanrailsim.schedule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns a vehicle type to every departure of a line from its configured
 * shares, spreading the types evenly through the day: the n-th departure goes
 * to the type furthest below its share, so a 70/30 line never runs its minority
 * stock in one block.
 */
public final class RouteVehicleAssignment {

	/** TILO cross-border services that do not touch the modelled suburban network. */
	private static final Set<String> EXCLUDED = Set.of("S10", "S30", "S40", "S50", "Trenord GP");

	private final LineAssignments assignments;

	public RouteVehicleAssignment(LineAssignments assignments) {
		this.assignments = assignments;
	}

	public static RouteVehicleAssignment defaults() {
		return new RouteVehicleAssignment(LineAssignments.defaults());
	}

	public boolean isExcluded(String routeShortName) {
		return EXCLUDED.contains(routeShortName);
	}

	/** @param departureIndex position of the departure in the line's daily order, from 0 */
	public String vehicleTypeId(String routeShortName, int departureIndex) {
		if (isExcluded(routeShortName)) {
			throw new IllegalArgumentException("Excluded route: " + routeShortName);
		}
		if (!routeShortName.startsWith("S") && !routeShortName.startsWith("R")
				&& !assignments.byLine().containsKey(routeShortName)) {
			throw new IllegalArgumentException("No vehicle type assigned to route: " + routeShortName);
		}
		List<LineAssignments.Share> shares = assignments.sharesOf(routeShortName);
		Map<String, Integer> assigned = new HashMap<>();
		String chosen = null;
		for (int i = 0; i <= departureIndex; i++) {
			chosen = mostOwed(shares, assigned, i + 1);
			assigned.merge(chosen, 1, Integer::sum);
		}
		return chosen;
	}

	private static String mostOwed(List<LineAssignments.Share> shares, Map<String, Integer> assigned, int departures) {
		String best = null;
		double bestDeficit = Double.NEGATIVE_INFINITY;
		for (LineAssignments.Share share : shares) {
			double deficit = share.percent() / 100.0 * departures - assigned.getOrDefault(share.vehicleTypeId(), 0);
			if (deficit > bestDeficit) {
				bestDeficit = deficit;
				best = share.vehicleTypeId();
			}
		}
		return best;
	}
}
