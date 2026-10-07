package it.unimib.milanrailsim.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * Where a terminus declares that lines share their trains, a vehicle may also
 * take the departure of another line of the pool: on a single stub track the
 * train that came in is the only one that can go out (Bergamo, where the
 * Brescia line ends on one track and the first arrival of the day leaves for
 * Cremona).
 * <p>
 * Design follows gtfs2matsim's CreateVehicleCirculation, reimplemented
 * because that class is package-private and injects deadhead links.
 */
public final class TripChains {

	/** One trip: its line, termini and the times it occupies a vehicle. */
	public record Journey(String tripId, String line, String firstStop, String lastStop, double start, double end) {
	}

	/** What a terminus allows a vehicle between two trips. */
	public interface Termini {

		/** Longest wait before the next trip; {@link Double#POSITIVE_INFINITY} where a vehicle may wait indefinitely. */
		double maxLayoverSeconds(String stop);

		/** Whether the two lines are run by one pool of trains at the stop, so a train in on one may leave on the other. */
		boolean shareStock(String stop, String line, String other);
	}

	private TripChains() {
	}

	/**
	 * Chains with no line sharing its trains with another.
	 *
	 * @param maxLayoverSeconds longest wait a vehicle may spend at a terminus before the next trip;
	 *                          {@link Double#POSITIVE_INFINITY} where it may wait indefinitely
	 * @return chains in order of first departure, each a list of journeys in service order
	 */
	public static List<List<Journey>> chain(List<Journey> journeys, int turnaroundSeconds,
			ToDoubleFunction<String> maxLayoverSeconds) {
		return chain(journeys, turnaroundSeconds, new Termini() {

			@Override
			public double maxLayoverSeconds(String stop) {
				return maxLayoverSeconds.applyAsDouble(stop);
			}

			@Override
			public boolean shareStock(String stop, String line, String other) {
				return false;
			}
		});
	}

	/** @return chains in order of first departure, each a list of journeys in service order */
	public static List<List<Journey>> chain(List<Journey> journeys, int turnaroundSeconds, Termini termini) {
		List<Journey> ordered = journeys.stream().sorted(Comparator.comparingDouble(Journey::start)).toList();
		List<List<Journey>> chains = new ArrayList<>();
		for (Journey journey : ordered) {
			String terminus = journey.firstStop();
			List<Journey> free = null;
			for (List<Journey> chain : chains) {
				Journey last = chain.getLast();
				boolean sameStock = last.line().equals(journey.line()) || termini.shareStock(terminus, last.line(), journey.line());
				double wait = journey.start() - last.end();
				if (sameStock && last.lastStop().equals(terminus)
						&& wait >= turnaroundSeconds && wait <= termini.maxLayoverSeconds(terminus)
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
