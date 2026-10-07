package it.unimib.milanrailsim.results;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Accumulates the energy of a simulated day from samples of every train in
 * service, taken at regular intervals: per trip, and per minute for the load
 * on the electric network.
 * <p>
 * On a 3 kV d.c. line a braking train can return energy only to a train
 * drawing power nearby: the substations do not take it back. At each sample
 * the energy a train regenerates feeds its own auxiliaries first, then the
 * electric trains in traction within the radius, nearest first; what nobody
 * takes is lost in the braking resistors.
 */
public final class EnergyLedger {

	/**
	 * The state of one train at a sampling instant.
	 *
	 * @param demandKilowatt      drawn for traction and auxiliaries (electric trains)
	 * @param regeneratedKilowatt returned by the brakes (electric trains)
	 * @param speed               metres per second
	 * @param x                   position, metres
	 */
	public record Sample(String trip, String line, boolean electric, double demandKilowatt, double regeneratedKilowatt,
			double litresPerHour, double massTonnes, double speed, double x, double y) {
	}

	/**
	 * The energy of one trip.
	 *
	 * @param drawnKilowattHours       taken from the substations
	 * @param receivedKilowattHours    taken from braking trains nearby
	 * @param regeneratedKilowattHours returned by the trip's own brakes
	 * @param givenKilowattHours       of those, taken by other trains
	 * @param lostKilowattHours        of those, taken by nobody
	 */
	public record TripEnergy(String line, boolean electric, double drawnKilowattHours, double receivedKilowattHours,
			double regeneratedKilowattHours, double givenKilowattHours, double lostKilowattHours, double litres,
			double kilometres, double tonneKilometres) {

		/** What the trip would have drawn had no braking energy been reused, by itself or by others. */
		public double demandKilowattHours() {
			return drawnKilowattHours + receivedKilowattHours + regeneratedKilowattHours - givenKilowattHours - lostKilowattHours;
		}

		TripEnergy plus(TripEnergy other) {
			return new TripEnergy(line, electric, drawnKilowattHours + other.drawnKilowattHours,
				receivedKilowattHours + other.receivedKilowattHours, regeneratedKilowattHours + other.regeneratedKilowattHours,
				givenKilowattHours + other.givenKilowattHours, lostKilowattHours + other.lostKilowattHours,
				litres + other.litres, kilometres + other.kilometres, tonneKilometres + other.tonneKilometres);
		}
	}

	/**
	 * Sums of trips. Electric and diesel trips are kept apart because a
	 * kilometre or a tonne-kilometre of one says nothing of the other.
	 */
	public record Totals(TripEnergy electric, TripEnergy diesel) {

		private static final TripEnergy NO_ELECTRIC = new TripEnergy("", true, 0, 0, 0, 0, 0, 0, 0, 0);
		private static final TripEnergy NO_DIESEL = new TripEnergy("", false, 0, 0, 0, 0, 0, 0, 0, 0);

		static Totals of(java.util.Collection<TripEnergy> trips) {
			TripEnergy electric = trips.stream().filter(TripEnergy::electric).reduce(NO_ELECTRIC, TripEnergy::plus);
			TripEnergy diesel = trips.stream().filter(trip -> !trip.electric()).reduce(NO_DIESEL, TripEnergy::plus);
			return new Totals(electric, diesel);
		}
	}

	/**
	 * The load of one minute of the day, as means over the minute.
	 *
	 * @param minuteOfDay       minutes since midnight of the service day
	 * @param lineKilowatt      drawn from the substations by all trains
	 * @param recoveredKilowatt braking power reused, by the braking trains themselves and by others
	 * @param trainsInService   largest number of trains sampled together in the minute
	 */
	public record Minute(int minuteOfDay, double lineKilowatt, double recoveredKilowatt, double lostKilowatt,
			double litresPerHour, int trainsInService) {
	}

	/** What the day used, by trip (in order of first appearance) and by minute. */
	public record Use(Map<String, TripEnergy> byTrip, List<Minute> profile) {

		/** The minute with the highest mean power drawn from the substations. */
		public Optional<Minute> peak() {
			return profile.stream().max(Comparator.comparingDouble(Minute::lineKilowatt));
		}

		public Totals totals() {
			return Totals.of(byTrip.values());
		}

		/** The totals of each line, by line id. */
		public Map<String, Totals> byLine() {
			Map<String, List<TripEnergy>> trips = new TreeMap<>();
			byTrip.values().forEach(trip -> trips.computeIfAbsent(trip.line(), key -> new ArrayList<>()).add(trip));
			Map<String, Totals> totals = new TreeMap<>();
			trips.forEach((line, ofLine) -> totals.put(line, Totals.of(ofLine)));
			return totals;
		}
	}

	private static final class TripTotals {
		private final String line;
		private final boolean electric;
		private double drawn;
		private double received;
		private double regenerated;
		private double given;
		private double lost;
		private double litres;
		private double kilometres;
		private double tonneKilometres;

		private TripTotals(String line, boolean electric) {
			this.line = line;
			this.electric = electric;
		}
	}

	private static final class MinuteTotals {
		private double lineKilowattSeconds;
		private double recoveredKilowattSeconds;
		private double lostKilowattSeconds;
		private double litreSecondsPerHour;
		private int trains;
	}

	private final double recoveryRadiusMetres;
	private final Map<String, TripTotals> trips = new LinkedHashMap<>();
	private final Map<Integer, MinuteTotals> minutes = new TreeMap<>();

	public EnergyLedger(double recoveryRadiusMetres) {
		this.recoveryRadiusMetres = recoveryRadiusMetres;
	}

	/**
	 * Books the interval starting at {@code time} with every train in the given state throughout it.
	 *
	 * @param time     seconds since midnight of the service day
	 * @param duration seconds
	 */
	public void tick(double time, double duration, List<Sample> samples) {
		double hours = duration / 3600;
		double[] open = new double[samples.size()];
		double[] surplus = new double[samples.size()];
		double[] received = new double[samples.size()];
		double[] given = new double[samples.size()];
		double selfUsed = 0;
		for (int i = 0; i < samples.size(); i++) {
			Sample sample = samples.get(i);
			if (sample.electric()) {
				double own = Math.min(sample.demandKilowatt(), sample.regeneratedKilowatt());
				open[i] = sample.demandKilowatt() - own;
				surplus[i] = sample.regeneratedKilowatt() - own;
				selfUsed += own;
			}
		}
		for (int giver = 0; giver < samples.size(); giver++) {
			if (surplus[giver] <= 0) {
				continue;
			}
			for (int taker : takersNearestFirst(samples, giver, open)) {
				double passed = Math.min(surplus[giver] - given[giver], open[taker] - received[taker]);
				given[giver] += passed;
				received[taker] += passed;
				if (given[giver] >= surplus[giver]) {
					break;
				}
			}
		}
		MinuteTotals minute = minutes.computeIfAbsent((int) Math.floor(time / 60), key -> new MinuteTotals());
		minute.trains = Math.max(minute.trains, samples.size());
		minute.recoveredKilowattSeconds += selfUsed * duration;
		for (int i = 0; i < samples.size(); i++) {
			Sample sample = samples.get(i);
			TripTotals trip = trips.computeIfAbsent(sample.trip(), key -> new TripTotals(sample.line(), sample.electric()));
			double drawn = open[i] - received[i];
			double lost = surplus[i] - given[i];
			trip.drawn += drawn * hours;
			trip.received += received[i] * hours;
			trip.regenerated += sample.regeneratedKilowatt() * hours;
			trip.given += given[i] * hours;
			trip.lost += lost * hours;
			trip.litres += sample.litresPerHour() * hours;
			trip.kilometres += sample.speed() * duration / 1000;
			trip.tonneKilometres += sample.massTonnes() * sample.speed() * duration / 1000;
			minute.lineKilowattSeconds += drawn * duration;
			minute.recoveredKilowattSeconds += given[i] * duration;
			minute.lostKilowattSeconds += lost * duration;
			minute.litreSecondsPerHour += sample.litresPerHour() * duration;
		}
	}

	/** Electric trains with demand still open within the radius of the giver, nearest first. */
	private List<Integer> takersNearestFirst(List<Sample> samples, int giver, double[] open) {
		Sample from = samples.get(giver);
		List<Integer> takers = new ArrayList<>();
		for (int i = 0; i < samples.size(); i++) {
			if (i != giver && open[i] > 0 && distance(from, samples.get(i)) <= recoveryRadiusMetres) {
				takers.add(i);
			}
		}
		takers.sort(Comparator.comparingDouble(i -> distance(from, samples.get(i))));
		return takers;
	}

	private static double distance(Sample a, Sample b) {
		return Math.hypot(a.x() - b.x(), a.y() - b.y());
	}

	public Use use() {
		Map<String, TripEnergy> byTrip = new LinkedHashMap<>();
		trips.forEach((trip, totals) -> byTrip.put(trip, new TripEnergy(totals.line, totals.electric, totals.drawn,
			totals.received, totals.regenerated, totals.given, totals.lost, totals.litres, totals.kilometres,
			totals.tonneKilometres)));
		List<Minute> profile = new ArrayList<>();
		minutes.forEach((minute, totals) -> profile.add(new Minute(minute, totals.lineKilowattSeconds / 60,
			totals.recoveredKilowattSeconds / 60, totals.lostKilowattSeconds / 60, totals.litreSecondsPerHour / 60,
			totals.trains)));
		return new Use(byTrip, List.copyOf(profile));
	}
}
