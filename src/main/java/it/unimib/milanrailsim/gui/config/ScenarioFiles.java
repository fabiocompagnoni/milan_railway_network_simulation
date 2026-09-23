package it.unimib.milanrailsim.gui.config;

import java.nio.file.Path;

/**
 * Input files of the scenario the application works on, all under one
 * {@link DataRoot} and read-only: the application never writes here.
 *
 * @param network       the network with station links, drawn by the map
 * @param engineNetwork the network with the micro nodes spliced in, the engine's input
 * @param engineConfig  the MATSim configuration template the engine starts from
 * @param microNodes    the declarations of those micro nodes
 * @param stationTracks the survey of station tracks, or a missing file when the stations are sized from their sections
 * @param gtfsDir       the timetable feed committed with the scenario
 * @param defaultCosts  the cost parameters the user's copy is seeded from
 * @param linkMeasures  the measured sections shown in the settings
 */
public record ScenarioFiles(Path network, Path engineNetwork, Path engineConfig, Path microNodes, Path transitSchedule,
		Path linkGeometry, Path stationTracks, Path gtfsDir, Path defaultCosts, Path linkMeasures, String crs) {

	/** The committed Milan scenario under {@code root}, laid out as in the repository. */
	public static ScenarioFiles milan(Path root) {
		Path scenario = root.resolve("scenarios").resolve("milan");
		return new ScenarioFiles(
			scenario.resolve("network-with-stations.xml"),
			scenario.resolve("network-micro.xml"),
			scenario.resolve("config.xml"),
			root.resolve("data").resolve("nodes"),
			scenario.resolve("transitSchedule.xml"),
			scenario.resolve("link-geometry.csv"),
			root.resolve("docs").resolve("network").resolve("misure").resolve("binari-stazioni.csv"),
			root.resolve("orari_trenord"),
			root.resolve("config").resolve("costs.json"),
			root.resolve("data").resolve("osm").resolve("2026-08-05-network-sweep").resolve("link_measures.csv"),
			"EPSG:32632");
	}
}
