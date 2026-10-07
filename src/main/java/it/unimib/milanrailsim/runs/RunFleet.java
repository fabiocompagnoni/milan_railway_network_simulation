package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.results.FleetReport;
import it.unimib.milanrailsim.results.FleetUse;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The fleet of an archived run, computed from the two files every analysed
 * run keeps: its trips with their outcome and the vehicles of its timetable.
 * Runs archived before the fleet files existed are read the same way.
 *
 * @param typeNames name of each vehicle type of the run, by type id
 */
public record RunFleet(FleetUse use, Map<String, String> typeNames) {

	private static final String TRIPS = "trips.csv";
	private static final String VEHICLES = "scenario/transitVehicles.xml";

	/** @return empty when the run lacks its trips or its vehicles */
	public static Optional<RunFleet> read(Path runDir) {
		Path trips = runDir.resolve(TRIPS);
		Path vehiclesFile = runDir.resolve(VEHICLES);
		if (!Files.isRegularFile(trips) || !Files.isRegularFile(vehiclesFile)) {
			return Optional.empty();
		}
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(vehiclesFile.toString());
		Map<String, String> names = new HashMap<>();
		FleetConfig.defaults().types().forEach(type -> names.put(type.id(), type.name()));
		// the name the run recorded wins over the catalogue of today
		FleetReport.typeNames(vehicles).forEach((type, name) -> {
			if (!name.equals(type)) {
				names.put(type, name);
			}
		});
		List<TripOutcome> outcomes = CsvTable.read(trips).stream().map(RunFleet::outcome).toList();
		return Optional.of(new RunFleet(FleetUse.of(outcomes, FleetReport.typeOfVehicle(vehicles)), names));
	}

	private static TripOutcome outcome(Map<String, String> row) {
		return new TripOutcome(row.get("trip"), row.get("line"), row.get("route"), row.get("vehicle"), row.get("origin"),
			row.get("destination"), Double.parseDouble(row.get("planned_departure_s")),
			Double.parseDouble(row.get("planned_arrival_s")), number(row.get("actual_arrival_s")),
			Integer.parseInt(row.get("stops_planned")), Integer.parseInt(row.get("stops_served")),
			TripStatus.valueOf(row.get("status").toUpperCase(Locale.ROOT)));
	}

	private static double number(String value) {
		return value == null || value.isBlank() ? Double.NaN : Double.parseDouble(value);
	}

	/** The name of a type, or its id when the run and the catalogue have none. */
	public String typeName(String type) {
		return typeNames.getOrDefault(type, type);
	}
}
