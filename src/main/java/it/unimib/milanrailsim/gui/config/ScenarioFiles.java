package it.unimib.milanrailsim.gui.config;

import java.nio.file.Path;

/**
 * Input files of the scenario the application works on.
 *
 * @param network       the network with station links, drawn by the map
 * @param engineNetwork the network with the micro nodes spliced in, the engine's input
 * @param microNodes    the declarations of those micro nodes
 */
public record ScenarioFiles(Path network, Path engineNetwork, Path microNodes, Path transitSchedule, Path linkGeometry,
		Path gtfsDir, String crs) {

	/** The committed Milan scenario, resolved against the working directory. */
	public static ScenarioFiles milan() {
		Path scenario = Path.of("scenarios", "milan");
		return new ScenarioFiles(
			scenario.resolve("network-with-stations.xml"),
			scenario.resolve("network-micro.xml"),
			Path.of("data", "nodes"),
			scenario.resolve("transitSchedule.xml"),
			scenario.resolve("link-geometry.csv"),
			Path.of("orari_trenord"),
			"EPSG:32632");
	}
}
