package it.unimib.milanrailsim.gui.config;

import java.nio.file.Path;

/** Input files of the scenario the application works on. */
public record ScenarioFiles(Path network, Path transitSchedule, Path linkGeometry, Path gtfsDir, String crs) {

	/** The committed Milan scenario, resolved against the working directory. */
	public static ScenarioFiles milan() {
		Path scenario = Path.of("scenarios", "milan");
		return new ScenarioFiles(
			scenario.resolve("network-with-stations.xml"),
			scenario.resolve("transitSchedule.xml"),
			scenario.resolve("link-geometry.csv"),
			Path.of("orari_trenord"),
			"EPSG:32632");
	}
}
