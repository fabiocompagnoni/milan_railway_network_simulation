package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The trains a day put in service: which ones, of which type, and how many
 * were running hour by hour.
 * <p>
 * A train is a vehicle the timetable gives at least one trip. It is running
 * from the planned departure of a trip to its actual arrival; a trip that did
 * not complete has no arrival and counts over its planned times, as it does
 * in the costs. Between two trips a train stands at a platform or in the
 * sidings and is not running.
 */
public final class FleetUse {

	private static final int SECONDS_PER_HOUR = 3600;

	/**
	 * @param line  the line of the first trip of the train; a train two lines share counts under that one
	 * @param trips trips the timetable gives the train
	 */
	public record Train(String vehicle, String type, String line, int trips) {
	}

	/**
	 * The trains of one hour of the service day; hours past midnight keep counting (24, 25).
	 *
	 * @param peak         the most trains running at the same instant in the hour
	 * @param peakByType   the trains running at that instant, by type
	 * @param active       trains that ran for at least an instant in the hour
	 * @param activeByType the same, by type
	 */
	public record Hour(int hour, int peak, Map<String, Integer> peakByType, int active, Map<String, Integer> activeByType) {
	}

	/** One trip as the time a train of a type is running. */
	private record Run(String vehicle, String type, double start, double end) {

		boolean coversInstant(double time) {
			return start <= time && time < end;
		}

		boolean overlaps(double from, double to) {
			return start < to && end > from;
		}
	}

	private final List<Train> trains;
	private final List<Hour> hours;

	private FleetUse(List<Train> trains, List<Hour> hours) {
		this.trains = List.copyOf(trains);
		this.hours = List.copyOf(hours);
	}

	/**
	 * @param trips         every trip of the timetable with its outcome
	 * @param typeOfVehicle vehicle type id by vehicle id
	 * @throws IllegalArgumentException if a trip runs on a vehicle with no type
	 */
	public static FleetUse of(List<TripOutcome> trips, Map<String, String> typeOfVehicle) {
		List<TripOutcome> inOrder = trips.stream().sorted(Comparator.comparingDouble(TripOutcome::plannedDeparture)).toList();
		Map<String, Train> trains = new TreeMap<>();
		List<Run> runs = new ArrayList<>();
		for (TripOutcome trip : inOrder) {
			String type = typeOfVehicle.get(trip.vehicle());
			if (type == null) {
				throw new IllegalArgumentException("No vehicle type for " + trip.vehicle() + ", running trip " + trip.trip());
			}
			trains.merge(trip.vehicle(), new Train(trip.vehicle(), type, trip.line(), 1),
				(known, added) -> new Train(known.vehicle(), known.type(), known.line(), known.trips() + 1));
			runs.add(new Run(trip.vehicle(), type, trip.plannedDeparture(),
				trip.status() == TripStatus.COMPLETED ? trip.actualArrival() : trip.plannedArrival()));
		}
		return new FleetUse(new ArrayList<>(trains.values()), hours(runs));
	}

	private static List<Hour> hours(List<Run> runs) {
		if (runs.isEmpty()) {
			return List.of();
		}
		int first = (int) (runs.stream().mapToDouble(Run::start).min().orElseThrow() / SECONDS_PER_HOUR);
		// a run ending on the hour does not reach into the next one
		int last = (int) (Math.nextDown(runs.stream().mapToDouble(Run::end).max().orElseThrow()) / SECONDS_PER_HOUR);
		List<Hour> hours = new ArrayList<>();
		for (int hour = first; hour <= Math.max(first, last); hour++) {
			double from = (double) hour * SECONDS_PER_HOUR;
			double to = from + SECONDS_PER_HOUR;
			List<Run> inHour = runs.stream().filter(run -> run.overlaps(from, to)).toList();
			if (!inHour.isEmpty()) {
				hours.add(hour(hour, from, inHour));
			}
		}
		return hours;
	}

	private static Hour hour(int hour, double from, List<Run> inHour) {
		// the number running only rises where a run starts, so the peak is at the start of the hour or of a run
		List<Double> instants = new ArrayList<>();
		instants.add(from);
		inHour.stream().map(Run::start).filter(start -> start > from).forEach(instants::add);
		List<Run> busiest = List.of();
		for (double instant : instants) {
			List<Run> running = oncePerTrain(inHour.stream().filter(run -> run.coversInstant(instant)).toList());
			if (running.size() > busiest.size()) {
				busiest = running;
			}
		}
		List<Run> active = oncePerTrain(inHour);
		return new Hour(hour, busiest.size(), byType(busiest), active.size(), byType(active));
	}

	/**
	 * A late train is still on a trip at the planned time of its next one, and
	 * when that next trip never leaves the two overlap: the train is one.
	 */
	private static List<Run> oncePerTrain(List<Run> runs) {
		Set<String> counted = new HashSet<>();
		return runs.stream().filter(run -> counted.add(run.vehicle())).toList();
	}

	private static Map<String, Integer> byType(List<Run> runs) {
		Map<String, Integer> counts = new TreeMap<>();
		runs.forEach(run -> counts.merge(run.type(), 1, Integer::sum));
		return counts;
	}

	/** The trains of the day, by vehicle id. */
	public List<Train> trains() {
		return trains;
	}

	/** How many trains of each type the day used. */
	public Map<String, Integer> byType() {
		Map<String, Integer> counts = new TreeMap<>();
		trains.forEach(train -> counts.merge(train.type(), 1, Integer::sum));
		return counts;
	}

	/** How many trains of each type every line used, by line and then by type. */
	public Map<String, Map<String, Integer>> byLineAndType() {
		Map<String, Map<String, Integer>> counts = new TreeMap<>();
		trains.forEach(train -> counts.computeIfAbsent(train.line(), key -> new TreeMap<>()).merge(train.type(), 1, Integer::sum));
		return counts;
	}

	/** The hours in which a train ran, in order. */
	public List<Hour> hours() {
		return hours;
	}

	/** The hour with the most trains running together; the earliest of them when several tie. */
	public Optional<Hour> busiestHour() {
		Hour busiest = null;
		for (Hour hour : hours) {
			if (busiest == null || hour.peak() > busiest.peak()) {
				busiest = hour;
			}
		}
		return Optional.ofNullable(busiest);
	}
}
