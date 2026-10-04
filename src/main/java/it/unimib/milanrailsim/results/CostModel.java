package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.network.RailVehicleTypes;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Operating costs of a scenario, with the unit costs of
 * {@link CostParameters}, computed twice over the same categories.
 * <p>
 * The planned costs come from the timetable alone: crew hours (staff),
 * train-kilometres split by traction (electricity/diesel, plus maintenance
 * and track access), and the minimum fleet able to cover the timetable
 * (rolling stock, peak concurrent trips per type).
 * <p>
 * The simulated costs come from what the run did: crew hours include the
 * delays, energy is the kWh and litres measured times their price, and
 * rolling stock counts every train the simulation used. A trip that was not
 * completed is charged as the timetable has it, so a scenario that breaks
 * down never comes out cheaper than one that runs.
 * <p>
 * An unset unit cost stops the computation: no figure is ever produced from
 * a placeholder.
 */
public final class CostModel {

	private static final String ENERGY_PRICE = "traction_energy";
	private static final String FUEL_PRICE = "diesel_fuel";

	public record Breakdown(String currency, Map<String, Double> byCategory,
			double trainKm, double trainHours, int fleetSize) {
	}

	private final TransitSchedule schedule;
	private final Vehicles vehicles;
	private final Network network;
	private final CostParameters parameters;

	public CostModel(TransitSchedule schedule, Vehicles vehicles, Network network,
			CostParameters parameters) {
		this.schedule = schedule;
		this.vehicles = vehicles;
		this.network = network;
		this.parameters = parameters;
	}

	public Breakdown compute() {
		double electricKm = 0;
		double dieselKm = 0;
		double hours = 0;
		Map<String, List<double[]>> serviceWindowsByType = new TreeMap<>();

		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				double routeKm = routeLengthKm(route);
				double durationSeconds = route.getStops().getLast().getArrivalOffset().seconds();
				for (Departure departure : route.getDepartures().values()) {
					String type = vehicleType(departure.getVehicleId());
					if (isDiesel(departure.getVehicleId())) {
						dieselKm += routeKm;
					} else {
						electricKm += routeKm;
					}
					hours += durationSeconds / 3600.0;
					serviceWindowsByType.computeIfAbsent(type, key -> new ArrayList<>())
						.add(new double[] { departure.getDepartureTime(),
							departure.getDepartureTime() + durationSeconds });
				}
			}
		}
		int fleetSize = serviceWindowsByType.values().stream().mapToInt(CostModel::peakConcurrency).sum();
		double totalKm = electricKm + dieselKm;

		Map<String, Double> costs = new LinkedHashMap<>();
		costs.put("staff", hours * unitCost("staff"));
		costs.put("electricity", electricKm * unitCost("electricity"));
		costs.put("diesel", dieselKm * unitCost("diesel"));
		costs.put("maintenance", totalKm * unitCost("maintenance"));
		costs.put("rolling_stock", fleetSize * unitCost("rolling_stock"));
		costs.put("track_access", totalKm * unitCost("track_access"));
		return new Breakdown(parameters.currency(), costs, totalKm, hours, fleetSize);
	}

	/**
	 * The costs of the day as it was simulated.
	 *
	 * @param trips  the outcome of every trip of the timetable
	 * @param energy the energy measured, by trip id
	 * @return empty when the cost parameters have no price for traction energy and diesel fuel
	 */
	public Optional<Breakdown> computeSimulated(List<PunctualityAnalysis.TripOutcome> trips,
			Map<String, EnergyLedger.TripEnergy> energy) {
		if (!parameters.categories().containsKey(ENERGY_PRICE) || !parameters.categories().containsKey(FUEL_PRICE)) {
			return Optional.empty();
		}
		Map<String, PunctualityAnalysis.TripOutcome> outcomes = new HashMap<>();
		trips.forEach(trip -> outcomes.put(trip.trip(), trip));
		double hours = 0;
		double totalKm = 0;
		double kilowattHours = 0;
		double litres = 0;
		double electricKmMeasured = 0;
		double dieselKmMeasured = 0;
		double electricKmCharged = 0;
		double dieselKmCharged = 0;
		Set<Id<Vehicle>> used = new HashSet<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				double routeKm = routeLengthKm(route);
				double plannedSeconds = route.getStops().getLast().getArrivalOffset().seconds();
				for (Departure departure : route.getDepartures().values()) {
					PunctualityAnalysis.TripOutcome outcome = outcomes.get(departure.getId().toString());
					boolean completed = outcome != null && outcome.status() == PunctualityAnalysis.TripStatus.COMPLETED;
					EnergyLedger.TripEnergy measured = completed ? energy.get(departure.getId().toString()) : null;
					boolean diesel = isDiesel(departure.getVehicleId());
					used.add(departure.getVehicleId());
					totalKm += routeKm;
					hours += (completed ? outcome.actualArrival() - outcome.plannedDeparture() : plannedSeconds) / 3600.0;
					if (measured == null) {
						electricKmCharged += diesel ? 0 : routeKm;
						dieselKmCharged += diesel ? routeKm : 0;
					} else if (diesel) {
						litres += measured.litres();
						dieselKmMeasured += routeKm;
					} else {
						kilowattHours += measured.drawnKilowattHours();
						electricKmMeasured += routeKm;
					}
				}
			}
		}
		// trips without a measure are charged the mean consumption per kilometre of the trips that have one
		kilowattHours += electricKmMeasured == 0 ? 0 : electricKmCharged * kilowattHours / electricKmMeasured;
		litres += dieselKmMeasured == 0 ? 0 : dieselKmCharged * litres / dieselKmMeasured;

		Map<String, Double> costs = new LinkedHashMap<>();
		costs.put("staff", hours * unitCost("staff"));
		costs.put("electricity", kilowattHours * unitCost(ENERGY_PRICE));
		costs.put("diesel", litres * unitCost(FUEL_PRICE));
		costs.put("maintenance", totalKm * unitCost("maintenance"));
		costs.put("rolling_stock", used.size() * unitCost("rolling_stock"));
		costs.put("track_access", totalKm * unitCost("track_access"));
		return Optional.of(new Breakdown(parameters.currency(), costs, totalKm, hours, used.size()));
	}

	private boolean isDiesel(Id<Vehicle> vehicleId) {
		Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
		if (vehicle == null) {
			throw new IllegalArgumentException("Departure without vehicle: " + vehicleId);
		}
		// a type without the attribute runs electric
		return RailVehicleTypes.DIESEL.equals(vehicle.getType().getAttributes().getAttribute(RailVehicleTypes.TRACTION_ATTRIBUTE));
	}

	private double unitCost(String category) {
		CostParameters.Entry entry = parameters.category(category);
		if (!entry.isSet()) {
			throw new IllegalArgumentException(
				"Cost parameter '" + category + "' is not estimated yet (" + entry.source() + ")");
		}
		return entry.unitCost();
	}

	private double routeLengthKm(TransitRoute route) {
		NetworkRoute networkRoute = route.getRoute();
		double meters = linkLength(networkRoute.getStartLinkId());
		for (Id<Link> linkId : networkRoute.getLinkIds()) {
			meters += linkLength(linkId);
		}
		if (!networkRoute.getEndLinkId().equals(networkRoute.getStartLinkId())
				|| !networkRoute.getLinkIds().isEmpty()) {
			meters += linkLength(networkRoute.getEndLinkId());
		}
		return meters / 1000.0;
	}

	private double linkLength(Id<Link> linkId) {
		Link link = network.getLinks().get(linkId);
		if (link == null) {
			throw new IllegalArgumentException("Route references unknown link: " + linkId);
		}
		return link.getLength();
	}

	private String vehicleType(Id<Vehicle> vehicleId) {
		Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
		if (vehicle == null) {
			throw new IllegalArgumentException("Departure without vehicle: " + vehicleId);
		}
		return vehicle.getType().getId().toString();
	}

	private static int peakConcurrency(List<double[]> windows) {
		List<double[]> events = new ArrayList<>();
		for (double[] window : windows) {
			events.add(new double[] { window[0], 1 });
			events.add(new double[] { window[1], -1 });
		}
		events.sort((x, y) -> x[0] != y[0]
			? Double.compare(x[0], y[0])
			: Double.compare(x[1], y[1]));
		int current = 0;
		int peak = 0;
		for (double[] event : events) {
			current += (int) event[1];
			peak = Math.max(peak, current);
		}
		return peak;
	}
}
