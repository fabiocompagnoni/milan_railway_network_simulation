package it.unimib.milanrailsim.gui.config;

import java.nio.file.Path;

/** Folders the application owns under the user's home; created lazily by their users. */
public record AppPaths(Path root) {

	public static AppPaths defaults() {
		return new AppPaths(Path.of(System.getProperty("user.home"), "MilanRailSim"));
	}

	public Path runs() {
		return root.resolve("runs");
	}

	public Path config() {
		return root.resolve("config");
	}

	public Path gtfsDir() {
		return config().resolve("gtfs");
	}

	public Path costsFile() {
		return config().resolve("costs.json");
	}

	public Path fleetTypesFile() {
		return config().resolve("fleet", "types.json");
	}

	public Path lineAssignmentsFile() {
		return config().resolve("fleet", "assignments.json");
	}

	public Path fleetPhotos() {
		return config().resolve("fleet", "photos");
	}

	public Path tileCache() {
		return root.resolve("cache", "tiles");
	}
}
