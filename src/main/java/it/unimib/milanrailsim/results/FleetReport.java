package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.network.RailVehicleTypes;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The fleet of a run as the files of its archive: by train, by type, by line and by hour. */
public final class FleetReport {

	/** Marks, in the type column, the row adding up every type. */
	public static final String TOTAL = "total";

	private FleetReport() {
	}

	/** The type id of every vehicle, by vehicle id. */
	public static Map<String, String> typeOfVehicle(Vehicles vehicles) {
		Map<String, String> types = new HashMap<>();
		vehicles.getVehicles().forEach((id, vehicle) -> types.put(id.toString(), vehicle.getType().getId().toString()));
		return types;
	}

	/** The name of every vehicle type, by type id; a type written before names were recorded keeps its id. */
	public static Map<String, String> typeNames(Vehicles vehicles) {
		Map<String, String> names = new HashMap<>();
		vehicles.getVehicleTypes().forEach((id, type) -> {
			Object name = type.getAttributes().getAttribute(RailVehicleTypes.NAME_ATTRIBUTE);
			names.put(id.toString(), name == null ? id.toString() : name.toString());
		});
		return names;
	}

	static List<String> trainsCsv(FleetUse fleet) {
		List<String> lines = new ArrayList<>();
		lines.add("vehicle,type,line,trips");
		fleet.trains().forEach(train -> lines.add(String.join(",", train.vehicle(), train.type(), train.line(),
			Integer.toString(train.trips()))));
		return lines;
	}

	static List<String> byTypeCsv(FleetUse fleet, Map<String, String> typeNames) {
		List<String> lines = new ArrayList<>();
		lines.add("type,name,trains");
		fleet.byType().forEach((type, trains) -> lines.add(String.join(",", type, typeNames.getOrDefault(type, type),
			Integer.toString(trains))));
		lines.add(String.join(",", TOTAL, "Totale", Integer.toString(fleet.trains().size())));
		return lines;
	}

	static List<String> byLineCsv(FleetUse fleet) {
		List<String> lines = new ArrayList<>();
		lines.add("line,type,trains");
		fleet.byLineAndType().forEach((line, byType) -> byType.forEach((type, trains) ->
			lines.add(String.join(",", line, type, Integer.toString(trains)))));
		return lines;
	}

	/**
	 * One row per hour and type, then the total of the hour. {@code peak} is the
	 * share of the type among the trains running at the busiest instant of the
	 * hour, so the types add up to the total; {@code active} counts the trains
	 * that ran at any time in the hour.
	 */
	static List<String> byHourCsv(FleetUse fleet) {
		List<String> lines = new ArrayList<>();
		lines.add("hour,type,peak,active");
		for (FleetUse.Hour hour : fleet.hours()) {
			for (String type : new TreeSet<>(hour.activeByType().keySet())) {
				lines.add(String.join(",", Integer.toString(hour.hour()), type,
					Integer.toString(hour.peakByType().getOrDefault(type, 0)), Integer.toString(hour.activeByType().get(type))));
			}
			lines.add(String.join(",", Integer.toString(hour.hour()), TOTAL, Integer.toString(hour.peak()),
				Integer.toString(hour.active())));
		}
		return lines;
	}
}
