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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the one-vehicle-per-trip schedule into circulations: every chain of
 * trips (see {@link TripChains}) becomes one vehicle, typed through the line
 * assignment, listing its GTFS trips in {@code servedTrips}. The chains are
 * the ones the timetable was built with, so a train's next leg starts on the
 * platform its previous leg ended on.
 */
public final class VehicleCirculations {

	private static final Logger log = LogManager.getLogger(VehicleCirculations.class);

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
			String typeId = assignment.vehicleTypeId(lineId, index);
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
}
