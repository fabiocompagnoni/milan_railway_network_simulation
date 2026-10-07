package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.vehicles.Vehicle;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MeetCapacitiesTest {

	private final Network network = TestNetworks.threeStationLine();
	private final TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
	private final TransitSchedule schedule = factory.createTransitSchedule();

	private TransitStopFacility stop(String id) {
		TransitStopFacility facility = factory.createTransitStopFacility(
			Id.create(id, TransitStopFacility.class), new Coord(0, 0), false);
		schedule.addStopFacility(facility);
		return facility;
	}

	/** A route calling at the three stations with the given dwell windows (seconds after departure). */
	private void addRoute(String lineId, List<TransitStopFacility> stops, double[][] offsets, double departureTime) {
		List<TransitRouteStop> routeStops = new java.util.ArrayList<>();
		for (int i = 0; i < stops.size(); i++) {
			routeStops.add(factory.createTransitRouteStop(stops.get(i), offsets[i][0], offsets[i][1]));
		}
		TransitRoute route = factory.createTransitRoute(Id.create(lineId + "_1", TransitRoute.class),
			RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("stop_S1"), Id.createLinkId("stop_S3")), routeStops, "rail");
		Departure departure = factory.createDeparture(Id.create(lineId + "_d", Departure.class), departureTime);
		departure.setVehicleId(Id.create(lineId + "_v", Vehicle.class));
		route.addDeparture(departure);
		TransitLine line = factory.createTransitLine(Id.create(lineId, TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
	}

	private int capacity(String station) {
		Link stop = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId(station)));
		return ((Number) stop.getAttributes().getAttribute("railsimTrainCapacity")).intValue();
	}

	@Test
	void stationWhereTwoTrainsDwellTogetherGetsTwoTracks() {
		StationStopLinks.addStopLinks(network);
		TransitStopFacility s1 = stop("S1");
		TransitStopFacility s2 = stop("S2");
		TransitStopFacility s3 = stop("S3");
		// eastbound dwells at S2 from 08:06 to 08:08, westbound from 08:07 to 08:09: a planned meet
		addRoute("E", List.of(s1, s2, s3), new double[][] { { 0, 60 }, { 360, 480 }, { 720, 720 } }, 8 * 3600);
		addRoute("W", List.of(s3, s2, s1), new double[][] { { 0, 60 }, { 420, 540 }, { 720, 720 } }, 8 * 3600);

		MeetCapacities.apply(schedule, network);

		assertEquals(2, capacity("S2"));
		assertEquals(1, capacity("S1"));
	}

	@Test
	void trainsThatDoNotOverlapLeaveCapacityAlone() {
		StationStopLinks.addStopLinks(network);
		TransitStopFacility s1 = stop("S1");
		TransitStopFacility s2 = stop("S2");
		TransitStopFacility s3 = stop("S3");
		addRoute("E", List.of(s1, s2, s3), new double[][] { { 0, 60 }, { 360, 480 }, { 720, 720 } }, 8 * 3600);
		addRoute("W", List.of(s3, s2, s1), new double[][] { { 0, 60 }, { 360, 480 }, { 720, 720 } }, 9 * 3600);

		MeetCapacities.apply(schedule, network);

		assertEquals(1, capacity("S2"));
	}

	@Test
	void surveyedStationsAreNotChanged() {
		StationStopLinks.addStopLinks(network);
		Link stop = network.getLinks().get(StationStopLinks.stopLinkId(Id.createNodeId("S2")));
		stop.getAttributes().removeAttribute("dataStatus");
		TransitStopFacility s1 = stop("S1");
		TransitStopFacility s2 = stop("S2");
		TransitStopFacility s3 = stop("S3");
		addRoute("E", List.of(s1, s2, s3), new double[][] { { 0, 60 }, { 360, 480 }, { 720, 720 } }, 8 * 3600);
		addRoute("W", List.of(s3, s2, s1), new double[][] { { 0, 60 }, { 420, 540 }, { 720, 720 } }, 8 * 3600);

		MeetCapacities.apply(schedule, network);

		assertEquals(1, capacity("S2"));
	}
}
