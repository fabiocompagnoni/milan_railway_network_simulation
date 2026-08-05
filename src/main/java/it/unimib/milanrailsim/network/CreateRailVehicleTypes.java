package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the railsim vehicle types to the scenario vehicles file. Vehicle
 * instances per departure are added later by the transit schedule generator.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.network.CreateRailVehicleTypes}
 * with optional arg {@code [outputFile]}.
 */
public final class CreateRailVehicleTypes {

	private static final Logger log = LogManager.getLogger(CreateRailVehicleTypes.class);

	private static final String DEFAULT_OUTPUT = "scenarios/milan/vehicles.xml";

	private CreateRailVehicleTypes() {
	}

	public static void main(String[] args) {
		run(Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT));
	}

	static void run(Path outputFile) {
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		for (VehicleType type : RailVehicleTypes.all()) {
			vehicles.addVehicleType(type);
		}
		try {
			Files.createDirectories(outputFile.toAbsolutePath().getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output directory for " + outputFile, e);
		}
		new MatsimVehicleWriter(vehicles).writeFile(outputFile.toString());
		log.info("Wrote {} vehicle types to {}", vehicles.getVehicleTypes().size(), outputFile);
	}
}
