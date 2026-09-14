package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PunctualityAnalysisTest {

	private static final double DEPARTURE_TIME = 8 * 3600;

	/** Line S1, one route A -> B: A dep +60, B arr +300 dep +360. */
	private TransitSchedule schedule() {
		return schedule("A", "B");
	}

	private TransitSchedule schedule(String firstStop, String lastStop) {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();
		TransitStopFacility a = factory.createTransitStopFacility(
			Id.create(firstStop, TransitStopFacility.class), new Coord(0, 0), false);
		TransitStopFacility b = factory.createTransitStopFacility(
			Id.create(lastStop, TransitStopFacility.class), new Coord(1000, 0), false);
		schedule.addStopFacility(a);
		schedule.addStopFacility(b);

		TransitRoute route = factory.createTransitRoute(Id.create("S1_1", TransitRoute.class), null,
			List.of(factory.createTransitRouteStop(a, 0, 60),
				factory.createTransitRouteStop(b, 300, 360)), "rail");
		Departure departure = factory.createDeparture(Id.create("d1", Departure.class), DEPARTURE_TIME);
		departure.setVehicleId(Id.create("V1", Vehicle.class));
		route.addDeparture(departure);

		TransitLine line = factory.createTransitLine(Id.create("S1", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		return schedule;
	}

	private static VehicleArrivesAtFacilityEvent arrival(String vehicle, double time, String stop) {
		return new VehicleArrivesAtFacilityEvent(time, Id.create(vehicle, Vehicle.class),
			Id.create(stop, TransitStopFacility.class), 0);
	}

	private static VehicleDepartsAtFacilityEvent departure(String vehicle, double time, String stop) {
		return new VehicleDepartsAtFacilityEvent(time, Id.create(vehicle, Vehicle.class),
			Id.create(stop, TransitStopFacility.class), 0);
	}

	@Test
	void measuresArrivalAndDepartureDelaysPerStop() {
		PunctualityAnalysis analysis = new PunctualityAnalysis(schedule());

		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 10, "A"));
		analysis.handleEvent(departure("V1", DEPARTURE_TIME + 60 + 20, "A"));
		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 300 + 90, "B"));
		analysis.handleEvent(departure("V1", DEPARTURE_TIME + 360 + 80, "B"));

		List<PunctualityAnalysis.StopVisit> visits = analysis.visits();
		assertEquals(2, visits.size());
		PunctualityAnalysis.StopVisit first = visits.get(0);
		assertEquals("S1", first.line());
		assertEquals("A", first.stop());
		assertEquals(10, first.arrivalDelaySeconds());
		assertEquals(20, first.departureDelaySeconds());
		assertEquals(90, visits.get(1).arrivalDelaySeconds());
		assertEquals(80, visits.get(1).departureDelaySeconds());
		assertEquals(0, analysis.anomalyCount());
	}

	@Test
	void reportsVehiclesThatNeverCompletedTheirPlan() {
		PunctualityAnalysis analysis = new PunctualityAnalysis(schedule());

		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 10, "A"));
		analysis.handleEvent(departure("V1", DEPARTURE_TIME + 70, "A"));

		List<PunctualityAnalysis.Unfinished> unfinished = analysis.unfinished();
		assertEquals(1, unfinished.size());
		assertEquals("V1", unfinished.getFirst().vehicle());
		assertEquals("S1", unfinished.getFirst().line());
		assertEquals("A", unfinished.getFirst().lastStop());
		assertEquals(1, unfinished.getFirst().remainingStops());

		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 400, "B"));
		assertTrue(analysis.unfinished().isEmpty(), "reaching the terminus completes the plan");
	}

	@Test
	void anotherPlatformOfThePlannedStationIsThePlannedStop() {
		PunctualityAnalysis analysis = new PunctualityAnalysis(schedule("A.p1|A|S1|through", "B"));

		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 10, "A.p2|A|S1|through"));
		analysis.handleEvent(departure("V1", DEPARTURE_TIME + 70, "A.p2|A|S1|through"));

		assertEquals(0, analysis.anomalyCount());
		assertEquals(1, analysis.visits().size());
		assertEquals("A.p2|A|S1|through", analysis.visits().getFirst().stop(), "the platform actually used");
		assertEquals(10, analysis.visits().getFirst().arrivalDelaySeconds());
	}

	@Test
	void ignoresAndCountsVehiclesOutsideTheSchedule() {
		PunctualityAnalysis analysis = new PunctualityAnalysis(schedule());

		analysis.handleEvent(arrival("ghost", DEPARTURE_TIME, "A"));

		assertTrue(analysis.visits().isEmpty());
		assertEquals(1, analysis.anomalyCount());
	}

	@Test
	void unexpectedStopIsCountedAndAnalysisContinues() {
		PunctualityAnalysis analysis = new PunctualityAnalysis(schedule());

		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 10, "X"));
		analysis.handleEvent(arrival("V1", DEPARTURE_TIME + 15, "A"));

		assertEquals(1, analysis.anomalyCount());
		assertEquals(1, analysis.visits().size());
		assertEquals(15, analysis.visits().get(0).arrivalDelaySeconds());
	}
}
