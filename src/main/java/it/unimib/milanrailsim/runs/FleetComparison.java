package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.network.FleetConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The trains a run uses against those of its reference day, type by type:
 * what a scenario needs beyond the real timetable. Both counts are the trains
 * of the model's circulations, where each line runs on its own stock, so the
 * difference is what compares; neither is the operator's actual roster.
 *
 * @param rows one per type either run uses, in the order of the rolling stock catalogue
 */
public record FleetComparison(List<Row> rows) {

	public record Row(String type, String name, int referenceTrains, int runTrains) {

		public int difference() {
			return runTrains - referenceTrains;
		}
	}

	public static FleetComparison of(RunFleet reference, RunFleet run) {
		Map<String, Integer> before = reference.use().byType();
		Map<String, Integer> after = run.use().byType();
		Set<String> types = new TreeSet<>(before.keySet());
		types.addAll(after.keySet());
		List<String> ordered = new ArrayList<>();
		FleetConfig.defaults().types().stream().map(FleetConfig.TrainType::id).filter(types::contains).forEach(ordered::add);
		types.stream().filter(type -> !ordered.contains(type)).forEach(ordered::add);
		return new FleetComparison(ordered.stream().map(type -> new Row(type,
			run.typeNames().containsKey(type) ? run.typeName(type) : reference.typeName(type),
			before.getOrDefault(type, 0), after.getOrDefault(type, 0))).toList());
	}

	public int referenceTrains() {
		return rows.stream().mapToInt(Row::referenceTrains).sum();
	}

	public int runTrains() {
		return rows.stream().mapToInt(Row::runTrains).sum();
	}

	/** Trains the run uses beyond its reference day; negative when it uses fewer. */
	public int difference() {
		return runTrains() - referenceTrains();
	}
}
