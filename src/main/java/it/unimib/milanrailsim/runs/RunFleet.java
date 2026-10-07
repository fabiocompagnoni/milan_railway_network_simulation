package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.results.FleetUse;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;

import java.nio.file.Files;
import java.nio.file.Path;
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

	/** @return empty when the run lacks its trips or its vehicles */
	public static Optional<RunFleet> read(Path runDir) {
		Path trips = runDir.resolve(TRIPS);
		if (!Files.isRegularFile(trips)) {
			return Optional.empty();
		}
		return RunVehicles.read(runDir).map(vehicles -> {
			List<TripOutcome> outcomes = CsvTable.read(trips).stream().map(RunFleet::outcome).toList();
			return new RunFleet(FleetUse.of(outcomes, vehicles.typeOfVehicle()), vehicles.typeNames());
		});
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
