package it.unimib.milanrailsim.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Chains the trips of a line into vehicle circulations: a vehicle ending a
 * trip takes the line's next departure from the same terminus once the
 * turnaround has passed (crews change ends, shifts rotate), unless the wait
 * would exceed the layover the terminus allows, since a train idle for hours
 * goes to the sidings rather than blocking a platform. The earliest-free
 * vehicle takes the trip; departures that cannot be chained start a new one.
 * No artificial transfer legs are ever added.
 * <p>
 * Design follows gtfs2matsim's CreateVehicleCirculation, reimplemented
 * because that class is package-private and injects deadhead links.
 */
public final class TripChains {

	/** One trip: its line, termini and the times it occupies a vehicle. */
	public record Journey(String tripId, String line, String firstStop, String lastStop, double start, double end) {
	}

	private TripChains() {
	}

	/**
	 * @param maxLayoverSeconds longest wait a vehicle may spend at a terminus before the next trip;
	 *                          {@link Double#POSITIVE_INFINITY} where it may wait indefinitely
	 * @return chains in order of first departure, each a list of journeys in service order
	 */
	public static List<List<Journey>> chain(List<Journey> journeys, int turnaroundSeconds,
			ToDoubleFunction<String> maxLayoverSeconds) {
		Map<String, List<Journey>> byLine = new LinkedHashMap<>();
		for (Journey journey : journeys) {
			byLine.computeIfAbsent(journey.line(), key -> new ArrayList<>()).add(journey);
		}
		List<List<Journey>> chains = new ArrayList<>();
		for (List<Journey> line : byLine.values()) {
			chains.addAll(chainLine(line, turnaroundSeconds, maxLayoverSeconds));
		}
		chains.sort(Comparator.comparingDouble(chain -> chain.getFirst().start()));
		return chains;
	}

	private static List<List<Journey>> chainLine(List<Journey> journeys, int turnaroundSeconds,
			ToDoubleFunction<String> maxLayoverSeconds) {
		List<Journey> ordered = journeys.stream().sorted(Comparator.comparingDouble(Journey::start)).toList();
		List<List<Journey>> chains = new ArrayList<>();
		for (Journey journey : ordered) {
			List<Journey> free = null;
			for (List<Journey> chain : chains) {
				Journey last = chain.getLast();
				boolean sameTerminus = last.lastStop().equals(journey.firstStop());
				double wait = journey.start() - last.end();
				if (sameTerminus && wait >= turnaroundSeconds && wait <= maxLayoverSeconds.applyAsDouble(journey.firstStop())
						&& (free == null || last.end() < free.getLast().end())) {
					free = chain;
				}
			}
			if (free == null) {
				free = new ArrayList<>();
				chains.add(free);
			}
			free.add(journey);
		}
		return chains;
	}
}
