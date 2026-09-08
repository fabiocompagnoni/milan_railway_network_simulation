package it.unimib.milanrailsim.results;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Operating costs of a scenario, from schedule quantities times the unit
 * costs of {@link CostParameters}: crew hours (staff), train-kilometres
 * split by traction (electricity/diesel, plus maintenance and track access),
 * and the minimum fleet able to cover the timetable (rolling stock, peak
 * concurrent trips per type). An unset unit cost stops the computation:
 * no figure is ever produced from a placeholder.
 */
public final class CostModel {

	/** The only diesel type of the fleet; every other type runs electric. */
	private static final String DIESEL_TYPE = "atr125";

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
					if (DIESEL_TYPE.equals(type)) {
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
