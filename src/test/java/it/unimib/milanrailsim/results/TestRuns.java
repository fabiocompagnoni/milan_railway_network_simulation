package it.unimib.milanrailsim.results;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared fixture: line S1, one 10 km route A->B (B arrival +600 s), writable as a fake MATSim run. */
final class TestRuns {

	record Fixture(TransitSchedule schedule, Vehicles vehicles, Network network, TransitRoute route) {
	}

	private TestRuns() {
	}

	static Fixture tenKilometreFixture() {
		Network network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("A"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("B"), new Coord(10_000, 0));
		network.addNode(a);
		network.addNode(b);
		Link ab = network.getFactory().createLink(Id.createLinkId("A_B"), a, b);
		ab.setLength(10_000);
		network.addLink(ab);

		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();
		TransitStopFacility stopA = factory.createTransitStopFacility(
			Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
		TransitStopFacility stopB = factory.createTransitStopFacility(
			Id.create("B", TransitStopFacility.class), new Coord(10_000, 0), false);
		schedule.addStopFacility(stopA);
		schedule.addStopFacility(stopB);
		TransitRoute route = factory.createTransitRoute(Id.create("S1_1", TransitRoute.class),
			RouteUtils.createNetworkRoute(List.of(Id.createLinkId("A_B"))),
			List.of(factory.createTransitRouteStop(stopA, 0, 0),
				factory.createTransitRouteStop(stopB, 600, 600)), "rail");
		TransitLine line = factory.createTransitLine(Id.create("S1", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		return new Fixture(schedule, VehicleUtils.createVehiclesContainer(), network, route);
	}

	static void addTrip(Fixture fixture, String vehicleId, String typeId, double departureTime) {
		VehicleType type = fixture.vehicles().getVehicleTypes().get(Id.create(typeId, VehicleType.class));
		if (type == null) {
			type = VehicleUtils.createVehicleType(Id.create(typeId, VehicleType.class));
			fixture.vehicles().addVehicleType(type);
		}
		Departure departure = new TransitScheduleFactoryImpl()
			.createDeparture(Id.create(vehicleId, Departure.class), departureTime);
		departure.setVehicleId(Id.create(vehicleId, Vehicle.class));
		fixture.route().addDeparture(departure);
		fixture.vehicles().addVehicle(
			VehicleUtils.createVehicle(Id.create(vehicleId, Vehicle.class), type));
	}

	/** Writes the fixture as a minimal MATSim run directory (uncompressed files). */
	static void writeFakeRun(Fixture fixture, Path runDir, String runId, String eventsXml,
			String timeDistanceCsv) throws IOException {
		Files.createDirectories(runDir.resolve("ITERS/it.0"));
		new TransitScheduleWriter(fixture.schedule())
			.writeFile(runDir.resolve(runId + ".output_transitSchedule.xml").toString());
		new MatsimVehicleWriter(fixture.vehicles())
			.writeFile(runDir.resolve(runId + ".output_transitVehicles.xml").toString());
		new NetworkWriter(fixture.network())
			.write(runDir.resolve(runId + ".output_network.xml").toString());
		Files.writeString(runDir.resolve("ITERS/it.0/" + runId + ".0.events.xml"), eventsXml);
		Files.writeString(runDir.resolve("ITERS/it.0/" + runId + ".0.railsimTimeDistance.csv"),
			timeDistanceCsv);
	}
}
