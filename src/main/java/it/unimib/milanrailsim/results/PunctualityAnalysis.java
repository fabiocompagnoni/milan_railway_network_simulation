package it.unimib.milanrailsim.results;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares simulated stop visits against the planned schedule, one record per
 * vehicle and stop with arrival and departure delays.
 * <p>
 * Unlike the generation pipeline, this analysis tool never aborts on
 * inconsistent input: anomalies (vehicles or stops outside the schedule) are
 * counted, logged and reported so the remaining measurements still come out.
 */
public final class PunctualityAnalysis
		implements VehicleArrivesAtFacilityEventHandler, VehicleDepartsAtFacilityEventHandler {

	private static final Logger log = LogManager.getLogger(PunctualityAnalysis.class);

	public record StopVisit(String vehicle, String line, String route, String stop,
			double plannedArrival, double actualArrival,
			double plannedDeparture, double actualDeparture) {

		public double arrivalDelaySeconds() {
			return actualArrival - plannedArrival;
		}

		public double departureDelaySeconds() {
			return actualDeparture - plannedDeparture;
		}
	}

	private record PlannedStop(String line, String route, String stop,
			double plannedArrival, double plannedDeparture) {
	}

	private final Map<Id<Vehicle>, Deque<PlannedStop>> planByVehicle = new HashMap<>();
	private final Map<Id<Vehicle>, StopVisit> openVisits = new HashMap<>();
	private final List<StopVisit> visits = new ArrayList<>();
	private int anomalyCount;

	public PunctualityAnalysis(TransitSchedule schedule) {
		// a circulation vehicle serves several departures: collect them all,
		// then flatten each vehicle's plan in chronological order
		Map<Id<Vehicle>, List<List<PlannedStop>>> plansByVehicle = new HashMap<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					List<PlannedStop> plan = new ArrayList<>();
					for (TransitRouteStop stop : route.getStops()) {
						plan.add(new PlannedStop(line.getId().toString(), route.getId().toString(),
							stop.getStopFacility().getId().toString(),
							departure.getDepartureTime() + stop.getArrivalOffset().seconds(),
							departure.getDepartureTime() + stop.getDepartureOffset().seconds()));
					}
					plansByVehicle.computeIfAbsent(departure.getVehicleId(), key -> new ArrayList<>())
						.add(plan);
				}
			}
		}
		plansByVehicle.forEach((vehicleId, plans) -> {
			plans.sort(Comparator.comparingDouble(plan -> plan.getFirst().plannedArrival()));
			Deque<PlannedStop> flattened = new ArrayDeque<>();
			plans.forEach(flattened::addAll);
			planByVehicle.put(vehicleId, flattened);
		});
	}

	@Override
	public void handleEvent(VehicleArrivesAtFacilityEvent event) {
		Deque<PlannedStop> plan = planByVehicle.get(event.getVehicleId());
		if (plan == null) {
			anomaly("vehicle outside the schedule: " + event.getVehicleId());
			return;
		}
		PlannedStop next = plan.peek();
		if (next == null || !sameStation(next.stop(), event.getFacilityId().toString())) {
			anomaly("unexpected stop " + event.getFacilityId() + " for vehicle " + event.getVehicleId());
			return;
		}
		plan.poll();
		openVisits.put(event.getVehicleId(), new StopVisit(event.getVehicleId().toString(),
			next.line(), next.route(), event.getFacilityId().toString(),
			next.plannedArrival(), event.getTime(), next.plannedDeparture(), Double.NaN));
	}

	/**
	 * The planned facility is one platform of a station; railsim may divert the
	 * train to another platform of the same stop area, which is still the
	 * planned stop.
	 */
	private static boolean sameStation(String plannedFacility, String actualFacility) {
		return StopFacilities.stationOf(plannedFacility).equals(StopFacilities.stationOf(actualFacility));
	}

	@Override
	public void handleEvent(VehicleDepartsAtFacilityEvent event) {
		StopVisit open = openVisits.remove(event.getVehicleId());
		if (open == null || !open.stop().equals(event.getFacilityId().toString())) {
			anomaly("departure without matching arrival at " + event.getFacilityId()
				+ " for vehicle " + event.getVehicleId());
			return;
		}
		visits.add(new StopVisit(open.vehicle(), open.line(), open.route(), open.stop(),
			open.plannedArrival(), open.actualArrival(), open.plannedDeparture(), event.getTime()));
	}

	private void anomaly(String message) {
		anomalyCount++;
		log.warn("Punctuality anomaly: {}", message);
	}

	/** Completed visits; terminus arrivals without a departure event are appended here. */
	public List<StopVisit> visits() {
		List<StopVisit> all = new ArrayList<>(visits);
		all.addAll(openVisits.values());
		return List.copyOf(all);
	}

	/** A vehicle that did not reach every planned stop: where it got to and how many stops were left. */
	public record Unfinished(String vehicle, String line, String route, String lastStop, int remainingStops) {
	}

	/**
	 * Vehicles whose plan was not completed when the events ended, ordered by
	 * id. Punctuality alone hides them, since a train that never arrives leaves
	 * no visit to be late.
	 */
	public List<Unfinished> unfinished() {
		Map<String, String> lastStops = new HashMap<>();
		for (StopVisit visit : visits()) {
			lastStops.put(visit.vehicle(), visit.stop());
		}
		List<Unfinished> result = new ArrayList<>();
		planByVehicle.forEach((vehicle, plan) -> {
			if (!plan.isEmpty()) {
				PlannedStop next = plan.peek();
				result.add(new Unfinished(vehicle.toString(), next.line(), next.route(),
					lastStops.getOrDefault(vehicle.toString(), ""), plan.size()));
			}
		});
		result.sort(Comparator.comparing(Unfinished::vehicle));
		return List.copyOf(result);
	}

	public int anomalyCount() {
		return anomalyCount;
	}
}
