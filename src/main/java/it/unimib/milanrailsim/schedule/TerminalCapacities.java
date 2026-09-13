package it.unimib.milanrailsim.schedule;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sizes terminal station links for the timetable: between trips (and after
 * the last one) a circulation physically occupies its terminus, so each stop
 * link must hold the peak number of parked trains plus one operating slot.
 * Derived from the schedule itself — the physical minimum for the timetable
 * to be feasible — and kept provisional until real track counts replace it.
 */
public final class TerminalCapacities {

	private static final Logger log = LogManager.getLogger(TerminalCapacities.class);

	private TerminalCapacities() {
	}

	public static void apply(TransitSchedule schedule, Network network) {
		Map<Id<Vehicle>, List<double[]>> tripWindows = new HashMap<>();
		Map<Id<Vehicle>, List<String>> tripTermini = new HashMap<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				double duration = route.getStops().getLast().getArrivalOffset().seconds();
				String origin = route.getStops().getFirst().getStopFacility().getId().toString();
				String terminus = route.getStops().getLast().getStopFacility().getId().toString();
				for (Departure departure : route.getDepartures().values()) {
					tripWindows.computeIfAbsent(departure.getVehicleId(), key -> new ArrayList<>())
						.add(new double[] { departure.getDepartureTime(),
							departure.getDepartureTime() + duration });
					tripTermini.computeIfAbsent(departure.getVehicleId(), key -> new ArrayList<>())
						.add(origin + ">" + terminus);
				}
			}
		}

		// parked intervals per station: between consecutive trips and after the last
		Map<String, List<double[]>> parkedByStation = new HashMap<>();
		tripWindows.forEach((vehicleId, windows) -> {
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < windows.size(); i++) {
				order.add(i);
			}
			order.sort(Comparator.comparingDouble(i -> windows.get(i)[0]));
			for (int k = 0; k < order.size(); k++) {
				double end = windows.get(order.get(k))[1];
				String terminus = tripTermini.get(vehicleId).get(order.get(k)).split(">")[1];
				double until = k + 1 < order.size()
					? windows.get(order.get(k + 1))[0]
					: Double.POSITIVE_INFINITY;
				parkedByStation.computeIfAbsent(terminus, key -> new ArrayList<>())
					.add(new double[] { end, until });
			}
		});

		int raised = 0;
		for (Map.Entry<String, List<double[]>> entry : parkedByStation.entrySet()) {
			int peak = peakOverlap(entry.getValue());
			Link stop = network.getLinks()
				.get(StationStopLinks.stopLinkId(Id.create(entry.getKey(), Node.class)));
			if (stop == null) {
				continue;
			}
			int current = ((Number) stop.getAttributes().getAttribute("railsimTrainCapacity")).intValue();
			int required = peak + 1;
			if (required > current && stop.getAttributes().getAttribute("dataStatus") != null) {
				stop.getAttributes().putAttribute("railsimTrainCapacity", required);
				raised++;
			}
		}
		log.info("Raised {} terminal stop links to their timetable-derived capacity", raised);
	}

	/** Maximum number of {@code [start, end]} windows open at the same instant. */
	static int peakOverlap(List<double[]> windows) {
		List<double[]> events = new ArrayList<>();
		for (double[] window : windows) {
			events.add(new double[] { window[0], 1 });
			if (window[1] != Double.POSITIVE_INFINITY) {
				events.add(new double[] { window[1], -1 });
			}
		}
		events.sort(Comparator.comparingDouble(event -> event[0]));
		int current = 0;
		int peak = 0;
		for (double[] event : events) {
			current += (int) event[1];
			peak = Math.max(peak, current);
		}
		return peak;
	}
}
