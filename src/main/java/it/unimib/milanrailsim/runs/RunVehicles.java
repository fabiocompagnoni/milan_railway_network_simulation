package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.results.FleetReport;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The vehicles of the timetable a run simulated: the type of each train and
 * the name of each type. The engine writes them in the run folder before the
 * first simulated second, so a run still in progress has them too.
 *
 * @param typeOfVehicle vehicle type id by vehicle id
 * @param typeNames     name of each vehicle type, by type id
 */
public record RunVehicles(Map<String, String> typeOfVehicle, Map<String, String> typeNames) {

	private static final String FILE = "scenario/transitVehicles.xml";

	/** @return empty when the run has not written its vehicles */
	public static Optional<RunVehicles> read(Path runDir) {
		Path file = runDir.resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(file.toString());
		Map<String, String> names = new HashMap<>();
		FleetConfig.defaults().types().forEach(type -> names.put(type.id(), type.name()));
		// the name the run recorded wins over the catalogue of today
		FleetReport.typeNames(vehicles).forEach((type, name) -> {
			if (!name.equals(type)) {
				names.put(type, name);
			}
		});
		return Optional.of(new RunVehicles(FleetReport.typeOfVehicle(vehicles), names));
	}

	/** The name of a type, or its id when the run and the catalogue have none. */
	public String typeName(String type) {
		return typeNames.getOrDefault(type, type);
	}
}
