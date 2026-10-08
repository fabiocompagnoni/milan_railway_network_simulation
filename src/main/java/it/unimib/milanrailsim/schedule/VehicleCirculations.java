package it.unimib.milanrailsim.schedule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the one-vehicle-per-trip schedule into circulations: every chain of
 * trips (see {@link TripChains}) becomes one vehicle, typed through the line
 * assignment, listing its GTFS trips in {@code servedTrips}. The chains are
 * the ones the timetable was built with, so a train's next leg starts on the
 * platform its previous leg ended on.
 */
public final class VehicleCirculations {

	private static final Logger log = LogManager.getLogger(VehicleCirculations.class);
	private static final int SECONDS_PER_DAY = 24 * 3600;
	/** Peak hours, the bands of the high-frequency scenario: 6:30-9:30 and 16:30-19:30. */
	private static final int MORNING_PEAK_START = 6 * 3600 + 1800;
	private static final int MORNING_PEAK_END = 9 * 3600 + 1800;
	private static final int EVENING_PEAK_START = 16 * 3600 + 1800;
	private static final int EVENING_PEAK_END = 19 * 3600 + 1800;

	private VehicleCirculations() {
	}

	private record Stop(TransitLine line, Departure departure) {
	}

	/** Rewrites departure vehicle ids in place and returns the replacement vehicles container. */
	public static Vehicles apply(TransitSchedule schedule, Vehicles tripVehicles, List<List<String>> chains,
			RouteVehicleAssignment assignment) {
		Vehicles circulated = VehicleUtils.createVehiclesContainer();
		tripVehicles.getVehicleTypes().values().forEach(circulated::addVehicleType);

		Map<String, Stop> departuresByTrip = new HashMap<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					departuresByTrip.put(departure.getId().toString(), new Stop(line, departure));
				}
			}
		}

		Map<String, String> typeByPeakLoad = typesByPeakLoad(chains, departuresByTrip, assignment);
		Map<String, Integer> perLine = new HashMap<>();
		for (List<String> chain : chains) {
			Stop first = departuresByTrip.get(chain.getFirst());
			if (first == null) {
				throw new IllegalArgumentException("Chain starts with unknown trip " + chain.getFirst());
			}
			String lineId = first.line().getId().toString();
			int index = perLine.merge(lineId, 1, Integer::sum) - 1;
			Id<Vehicle> vehicleId = Id.create(lineId + "_circ_" + (index + 1), Vehicle.class);
			for (String tripId : chain) {
				Stop stop = departuresByTrip.get(tripId);
				if (stop == null) {
					throw new IllegalArgumentException("Chain refers to unknown trip " + tripId);
				}
				stop.departure().setVehicleId(vehicleId);
			}
			String typeId = typeByPeakLoad.containsKey(chain.getFirst())
				? typeByPeakLoad.get(chain.getFirst())
				: assignment.vehicleTypeId(lineId, index);
			VehicleType type = circulated.getVehicleTypes().get(Id.create(typeId, VehicleType.class));
			if (type == null) {
				throw new IllegalArgumentException("Unknown vehicle type: " + typeId);
			}
			Vehicle vehicle = VehicleUtils.createVehicle(vehicleId, type);
			vehicle.getAttributes().putAttribute("servedTrips", String.join(",", chain));
			circulated.addVehicle(vehicle);
		}
		log.info("Chained {} trips into {} circulations", tripVehicles.getVehicles().size(), chains.size());
		return circulated;
	}

	/**
	 * Types of the trains of the lines ranked by peak load, keyed by the first
	 * trip of each chain: a train keeps its length all day, so the longer type
	 * goes to the trains with the most departures at peak hours.
	 */
	private static Map<String, String> typesByPeakLoad(List<List<String>> chains, Map<String, Stop> departuresByTrip,
			RouteVehicleAssignment assignment) {
		Map<String, List<List<String>>> chainsByLine = new TreeMap<>();
		for (List<String> chain : chains) {
			Stop first = departuresByTrip.get(chain.getFirst());
			if (first != null && assignment.ranksByPeakLoad(first.line().getId().toString())) {
				chainsByLine.computeIfAbsent(first.line().getId().toString(), line -> new ArrayList<>()).add(chain);
			}
		}
		Map<String, String> types = new HashMap<>();
		chainsByLine.forEach((line, ofLine) -> {
			List<List<String>> busiestFirst = ofLine.stream()
				.sorted(Comparator.comparingLong((List<String> chain) -> peakDepartures(chain, departuresByTrip)).reversed())
				.toList();
			List<String> ranked = assignment.vehicleTypeIdsByRank(line, busiestFirst.size());
			for (int rank = 0; rank < busiestFirst.size(); rank++) {
				types.put(busiestFirst.get(rank).getFirst(), ranked.get(rank));
			}
		});
		return types;
	}

	private static long peakDepartures(List<String> chain, Map<String, Stop> departuresByTrip) {
		return chain.stream()
			.map(departuresByTrip::get)
			.filter(stop -> stop != null && isPeak(stop.departure().getDepartureTime()))
			.count();
	}

	private static boolean isPeak(double departureSeconds) {
		double secondsOfDay = departureSeconds % SECONDS_PER_DAY;
		return secondsOfDay >= MORNING_PEAK_START && secondsOfDay < MORNING_PEAK_END
			|| secondsOfDay >= EVENING_PEAK_START && secondsOfDay < EVENING_PEAK_END;
	}
}
