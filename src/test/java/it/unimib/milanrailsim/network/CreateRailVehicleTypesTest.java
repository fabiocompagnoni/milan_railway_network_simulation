package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CreateRailVehicleTypesTest {

	@TempDir
	Path dir;

	@Test
	void writesVehicleTypesReadableByMatsim() {
		Path output = dir.resolve("nested/vehicles.xml");

		CreateRailVehicleTypes.run(output);

		assertTrue(Files.exists(output));
		Vehicles reread = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(reread).readFile(output.toString());
		assertEquals(8, reread.getVehicleTypes().size());
		VehicleType taf = reread.getVehicleTypes().get(Id.create("taf", VehicleType.class));
		assertEquals("rail", taf.getNetworkMode());
		assertEquals(0.8, (Double) taf.getAttributes().getAttribute("railsimAcceleration"), 1e-9);
		assertEquals("estimated", taf.getAttributes().getAttribute("accelerationDataStatus"));
	}
}
