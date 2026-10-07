package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.RailVehicleTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Chains the one-vehicle-per-trip schedule into realistic circulations: a
 * vehicle ending a trip takes the line's next departure from the same
 * terminus once the turnaround time has passed (crews change ends, shifts
 * rotate). Departures that cannot be chained start a new vehicle — no
 * artificial transfer legs are ever added.
 * <p>
 * Design follows gtfs2matsim's CreateVehicleCirculation, reimplemented
 * because that class is package-private and injects deadhead links into the
 * network.
 */
public final class VehicleCirculations {

	private static final Logger log = LogManager.getLogger(VehicleCirculations.class);

	private VehicleCirculations() {
	}

	/**
	 * Rewrites departure vehicle ids in place and returns the replacement
	 * vehicles container: one vehicle per circulation, typed through the
	 * line assignment, listing its GTFS trips in {@code servedTrips}.
	 */
	public static Vehicles apply(TransitSchedule schedule, Vehicles tripVehicles,
			int turnaroundSeconds, RouteVehicleAssignment assignment) {
		Vehicles circulated = VehicleUtils.createVehiclesContainer();
		RailVehicleTypes.all().forEach(circulated::addVehicleType);

		int totalCirculations = 0;
		for (TransitLine line : schedule.getTransitLines().values()) {
			totalCirculations += chainLine(line, circulated, turnaroundSeconds, assignment);
		}
		log.info("Chained {} trips into {} circulations",
			tripVehicles.getVehicles().size(), totalCirculations);
		return circulated;
	}

	private record Trip(TransitRoute route, Departure departure) {

		double start() {
			return departure.getDepartureTime();
		}

		double end() {
			return departure.getDepartureTime()
				+ route.getStops().getLast().getArrivalOffset().seconds();
		}

		Id<TransitStopFacility> firstStop() {
			return route.getStops().getFirst().getStopFacility().getId();
		}

		Id<TransitStopFacility> lastStop() {
			return route.getStops().getLast().getStopFacility().getId();
		}
	}

	private static final class Circulation {

		private final Id<Vehicle> vehicleId;
		private final List<String> servedTrips = new ArrayList<>();
		private Id<TransitStopFacility> currentStop;
		private double freeFrom;

		private Circulation(Id<Vehicle> vehicleId, Trip first) {
			this.vehicleId = vehicleId;
			serve(first);
		}

		private boolean canTake(Trip trip, int turnaroundSeconds) {
			return trip.firstStop().equals(currentStop)
				&& trip.start() >= freeFrom + turnaroundSeconds;
		}

		private void serve(Trip trip) {
			trip.departure().setVehicleId(vehicleId);
			servedTrips.add(trip.departure().getId().toString());
			currentStop = trip.lastStop();
			freeFrom = trip.end();
		}
	}

	private static int chainLine(TransitLine line, Vehicles circulated, int turnaroundSeconds,
			RouteVehicleAssignment assignment) {
		List<Trip> trips = new ArrayList<>();
		for (TransitRoute route : line.getRoutes().values()) {
			for (Departure departure : route.getDepartures().values()) {
				trips.add(new Trip(route, departure));
			}
		}
		trips.sort(Comparator.comparingDouble(Trip::start));

		List<Circulation> circulations = new ArrayList<>();
		for (Trip trip : trips) {
			Circulation free = circulations.stream()
				.filter(circulation -> circulation.canTake(trip, turnaroundSeconds))
				.min(Comparator.comparingDouble(circulation -> circulation.freeFrom))
				.orElse(null);
			if (free != null) {
				free.serve(trip);
			} else {
				Id<Vehicle> vehicleId = Id.create(
					line.getId() + "_circ_" + (circulations.size() + 1), Vehicle.class);
				circulations.add(new Circulation(vehicleId, trip));
			}
		}

		Map<Id<Vehicle>, Circulation> byId = new TreeMap<>();
		circulations.forEach(circulation -> byId.put(circulation.vehicleId, circulation));
		int index = 0;
		for (Circulation circulation : byId.values()) {
			String typeId = assignment.vehicleTypeId(line.getId().toString(), index++);
			VehicleType type = circulated.getVehicleTypes().get(Id.create(typeId, VehicleType.class));
			if (type == null) {
				throw new IllegalArgumentException("Unknown vehicle type: " + typeId);
			}
			Vehicle vehicle = VehicleUtils.createVehicle(circulation.vehicleId, type);
			vehicle.getAttributes().putAttribute("servedTrips", String.join(",", circulation.servedTrips));
			circulated.addVehicle(vehicle);
		}
		return circulations.size();
	}
}
