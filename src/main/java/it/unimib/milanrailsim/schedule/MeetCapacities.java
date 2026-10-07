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
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sizes station links for the meets the timetable plans: wherever two trains
 * are scheduled to dwell at the same station at the same time, the station
 * must have at least that many tracks, whatever the survey says so far. On
 * single-track lines this is what makes a station a crossing point. Only
 * provisional stop links are touched; surveyed counts stand.
 */
public final class MeetCapacities {

	private static final Logger log = LogManager.getLogger(MeetCapacities.class);

	private MeetCapacities() {
	}

	public static void apply(TransitSchedule schedule, Network network) {
		Map<String, List<double[]>> dwellsByStation = new HashMap<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					for (TransitRouteStop stop : route.getStops()) {
						double arrival = departure.getDepartureTime() + stop.getArrivalOffset().seconds();
						double leave = departure.getDepartureTime() + stop.getDepartureOffset().seconds();
						dwellsByStation.computeIfAbsent(stop.getStopFacility().getId().toString(), key -> new ArrayList<>())
							.add(new double[] { arrival, leave });
					}
				}
			}
		}
		int raised = 0;
		for (Map.Entry<String, List<double[]>> entry : dwellsByStation.entrySet()) {
			Link stop = network.getLinks().get(StationStopLinks.stopLinkId(Id.create(entry.getKey(), Node.class)));
			if (stop == null || stop.getAttributes().getAttribute("dataStatus") == null) {
				continue;
			}
			int required = TerminalCapacities.peakOverlap(entry.getValue());
			int current = ((Number) stop.getAttributes().getAttribute("railsimTrainCapacity")).intValue();
			if (required > current) {
				stop.getAttributes().putAttribute("railsimTrainCapacity", required);
				raised++;
			}
		}
		log.info("Raised {} stop links to the simultaneous dwells the timetable plans", raised);
	}
}
