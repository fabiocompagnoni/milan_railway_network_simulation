package it.unimib.milanrailsim.schedule;

import java.util.ArrayList;
import java.util.Collections;
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

	/** Special services outside the regular timetable. */
	private static final Set<String> EXCLUDED = Set.of("Trenord GP");
	/**
	 * Lines whose trains keep their length all day and come in two lengths: the
	 * longer type goes to the trains busiest at peak hours, not to every n-th one.
	 */
	private static final Set<String> PEAK_RANKED = Set.of("S10", "S30", "S40", "S50");

	private final LineAssignments assignments;
	private final Set<String> peakRanked;

	public RouteVehicleAssignment(LineAssignments assignments) {
		this(assignments, PEAK_RANKED);
	}

	/** @param peakRanked lines whose trains are typed by peak load, see {@link #vehicleTypeIdsByRank} */
	public RouteVehicleAssignment(LineAssignments assignments, Set<String> peakRanked) {
		this.assignments = assignments;
		this.peakRanked = Set.copyOf(peakRanked);
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

	public boolean ranksByPeakLoad(String routeShortName) {
		return peakRanked.contains(routeShortName);
	}

	/**
	 * Types of the trains of a line ordered from the busiest at peak hours to
	 * the least busy: each share takes its percentage of the trains, in the
	 * order the shares are listed, and the last one takes the remainder.
	 *
	 * @param trains number of trains the line uses in the day
	 * @return one type per train, by rank
	 */
	public List<String> vehicleTypeIdsByRank(String routeShortName, int trains) {
		List<LineAssignments.Share> shares = assignments.sharesOf(routeShortName);
		List<String> ranked = new ArrayList<>();
		for (LineAssignments.Share share : shares.subList(0, shares.size() - 1)) {
			long count = Math.min(Math.round(share.percent() / 100.0 * trains), trains - ranked.size());
			ranked.addAll(Collections.nCopies((int) count, share.vehicleTypeId()));
		}
		ranked.addAll(Collections.nCopies(trains - ranked.size(), shares.getLast().vehicleTypeId()));
		return ranked;
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
