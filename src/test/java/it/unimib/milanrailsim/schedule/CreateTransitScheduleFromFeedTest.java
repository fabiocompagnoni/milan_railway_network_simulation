package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CreateTransitScheduleFromFeedTest {

	@TempDir
	Path dir;

	@Test
	void writesScheduleVehiclesAndStationNetwork() {
		Path networkFile = dir.resolve("network.xml");
		new NetworkWriter(TestNetworks.threeStationLine()).write(networkFile.toString());
		Path out = dir.resolve("out");

		CreateTransitScheduleFromFeed.run(Path.of("src/test/resources/gtfs-minimal"),
			networkFile, LocalDate.of(2026, 9, 16), out);

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(out.resolve("transitSchedule.xml").toString());
		assertTrue(scenario.getTransitSchedule().getTransitLines()
			.containsKey(Id.create("S1", TransitLine.class)));

		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(out.resolve("transitVehicles.xml").toString());
		assertEquals(9, vehicles.getVehicleTypes().size());
		assertEquals(2, vehicles.getVehicles().size());

		Network network = NetworkUtils.readNetwork(out.resolve("network-with-stations.xml").toString());
		assertNotNull(network.getLinks().get(Id.createLinkId("stop_S1")));
	}
}
