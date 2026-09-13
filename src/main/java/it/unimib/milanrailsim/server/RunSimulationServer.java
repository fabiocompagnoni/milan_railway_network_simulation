package it.unimib.milanrailsim.server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point of the simulation process the application spawns per run.
 * <p>
 * Arguments as {@code --name value} pairs: {@code run} (folder with scenario.json),
 * {@code config} (railsim config template), {@code network} (mesoscopic network),
 * {@code gtfs}, {@code fleet}, {@code assignments}, {@code costs}, and optionally
 * {@code station-tracks} (terminal platform survey) and {@code speed} (initial pacing,
 * default unthrottled). Prints one JSON line with
 * port and token on stdout, then serves the connecting client until the run ends.
 */
public final class RunSimulationServer {

	private RunSimulationServer() {
	}

	public static void main(String[] args) {
		Map<String, String> options = parse(args);
		RailsimJob.Inputs inputs = new RailsimJob.Inputs(
			Path.of(require(options, "run")),
			Path.of(require(options, "config")),
			Path.of(require(options, "network")),
			Path.of(require(options, "gtfs")),
			Path.of(require(options, "fleet")),
			Path.of(require(options, "assignments")),
			Path.of(require(options, "costs")),
			options.containsKey("station-tracks") ? Path.of(options.get("station-tracks")) : null,
			options.containsKey("nodes") ? Path.of(options.get("nodes")) : null);
		double speed = options.containsKey("speed") ? Double.parseDouble(options.get("speed")) : Protocol.UNTHROTTLED;
		new SimulationServer(new RailsimJob(inputs), speed).serve(System.out);
	}

	private static Map<String, String> parse(String[] args) {
		Map<String, String> options = new HashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			if (!args[i].startsWith("--")) {
				throw new IllegalArgumentException("Expected --name value pairs, got " + args[i]);
			}
			options.put(args[i].substring(2), args[i + 1]);
		}
		return options;
	}

	private static String require(Map<String, String> options, String name) {
		String value = options.get(name);
		if (value == null) {
			throw new IllegalArgumentException("Missing --" + name);
		}
		return value;
	}
}
