package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CostModelTest {

	private Network network;
	private Vehicles vehicles;
	private TransitSchedule schedule;
	private TransitRoute route;

	@BeforeEach
	void tenKilometreRoute() {
		network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("A"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("B"), new Coord(10_000, 0));
		network.addNode(a);
		network.addNode(b);
		Link ab = network.getFactory().createLink(Id.createLinkId("A_B"), a, b);
		ab.setLength(10_000);
		network.addLink(ab);

		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		schedule = factory.createTransitSchedule();
		TransitStopFacility stopA = factory.createTransitStopFacility(
			Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
		TransitStopFacility stopB = factory.createTransitStopFacility(
			Id.create("B", TransitStopFacility.class), new Coord(10_000, 0), false);
		schedule.addStopFacility(stopA);
		schedule.addStopFacility(stopB);
		route = factory.createTransitRoute(Id.create("S1_1", TransitRoute.class),
			RouteUtils.createNetworkRoute(List.of(Id.createLinkId("A_B")), network),
			List.of(factory.createTransitRouteStop(stopA, 0, 0),
				factory.createTransitRouteStop(stopB, 600, 600)), "rail");
		TransitLine line = factory.createTransitLine(Id.create("S1", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);

		vehicles = VehicleUtils.createVehiclesContainer();
	}

	private void addTrip(String vehicleId, String typeId, double departureTime) {
		VehicleType type = vehicles.getVehicleTypes().get(Id.create(typeId, VehicleType.class));
		if (type == null) {
			type = VehicleUtils.createVehicleType(Id.create(typeId, VehicleType.class));
			vehicles.addVehicleType(type);
		}
		Departure departure = new TransitScheduleFactoryImpl()
			.createDeparture(Id.create(vehicleId, Departure.class), departureTime);
		departure.setVehicleId(Id.create(vehicleId, Vehicle.class));
		route.addDeparture(departure);
		vehicles.addVehicle(VehicleUtils.createVehicle(Id.create(vehicleId, Vehicle.class), type));
	}

	private CostParameters parameters(Path dir) throws IOException {
		Path file = dir.resolve("costs.json");
		Files.writeString(file, """
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": "test"},
				"electricity": {"unitCost": 2.0, "unit": "train_km", "source": "test"},
				"diesel": {"unitCost": 3.0, "unit": "train_km", "source": "test"},
				"maintenance": {"unitCost": 1.0, "unit": "train_km", "source": "test"},
				"rolling_stock": {"unitCost": 650.0, "unit": "train_day", "source": "test"},
				"track_access": {"unitCost": 2.5, "unit": "train_km", "source": "test"}
			}}""");
		return CostParameters.load(file);
	}

	@Test
	void computesCategoryCostsFromScheduleQuantities(@org.junit.jupiter.api.io.TempDir Path dir)
			throws IOException {
		addTrip("t1", "tsr", 8 * 3600);
		addTrip("t2", "tsr", 9 * 3600);
		addTrip("t3", "atr125", 8 * 3600);

		CostModel.Breakdown breakdown = new CostModel(schedule, vehicles, network, parameters(dir)).compute();
		Map<String, Double> costs = breakdown.byCategory();

		// 3 trips x 10 km; electric 20 km, diesel 10 km; 3 x 600 s = 0.5 h
		assertEquals(0.5 * 140.0, costs.get("staff"), 1e-6);
		assertEquals(20 * 2.0, costs.get("electricity"), 1e-6);
		assertEquals(10 * 3.0, costs.get("diesel"), 1e-6);
		assertEquals(30 * 1.0, costs.get("maintenance"), 1e-6);
		assertEquals(30 * 2.5, costs.get("track_access"), 1e-6);
		// t1/t3 overlap (2 concurrent trains), t2 alone: fleet of 2
		assertEquals(2 * 650.0, costs.get("rolling_stock"), 1e-6);
		assertEquals(30.0, breakdown.trainKm(), 1e-6);
		assertEquals("EUR", breakdown.currency());
	}

	@Test
	void refusesUnsetCostParameters(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
		addTrip("t1", "tsr", 8 * 3600);
		Path file = dir.resolve("costs.json");
		Files.writeString(file, """
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 0.0, "unit": "train_hour", "source": "da stimare"}
			}}""");

		CostModel model = new CostModel(schedule, vehicles, network, CostParameters.load(file));

		assertThrows(IllegalArgumentException.class, model::compute);
	}
}
