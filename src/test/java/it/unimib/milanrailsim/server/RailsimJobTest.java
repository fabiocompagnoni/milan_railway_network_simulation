package it.unimib.milanrailsim.server;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.api.core.v01.Coord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RailsimJobTest {

	@Test
	void lastPlannedArrivalIsTheLatestDepartureplusItsRunningTime() {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();
		TransitStopFacility a = factory.createTransitStopFacility(Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
		TransitStopFacility b = factory.createTransitStopFacility(Id.create("B", TransitStopFacility.class), new Coord(1, 0), false);
		List<TransitRouteStop> stops = List.of(factory.createTransitRouteStop(a, 0, 0), factory.createTransitRouteStop(b, 1800, 1800));
		TransitRoute route = factory.createTransitRoute(Id.create("r", TransitRoute.class), null, stops, "rail");
		route.addDeparture(factory.createDeparture(Id.create("early", Departure.class), 8 * 3600));
		route.addDeparture(factory.createDeparture(Id.create("late", Departure.class), 25 * 3600));
		TransitLine line = factory.createTransitLine(Id.create("S1", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);

		assertEquals(25 * 3600 + 1800, RailsimJob.lastPlannedArrival(schedule));
	}
}
