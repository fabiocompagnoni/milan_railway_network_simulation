package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.RailsimJob;
import it.unimib.milanrailsim.server.RunSimulationServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The simulation engine as a child JVM: same class path as the application,
 * its own heap, announcing its loopback endpoint on stdout. Its log goes to
 * {@code engine.log} in the run folder.
 */
public final class EngineProcess {

	public record Endpoint(int port, String token) {
	}

	private final Process process;
	private final Endpoint endpoint;

	private EngineProcess(Process process, Endpoint endpoint) {
		this.process = process;
		this.endpoint = endpoint;
	}

	/** @param heapPercent share of physical memory the engine may use */
	public static EngineProcess start(RailsimJob.Inputs inputs, int heapPercent, double initialSpeed) {
		List<String> command = new ArrayList<>(List.of(
			ProcessHandle.current().info().command().orElse("java"),
			"-XX:MaxRAMPercentage=" + heapPercent,
			"-cp", System.getProperty("java.class.path"),
			RunSimulationServer.class.getName(),
			"--run", inputs.runDir().toString(),
			"--config", inputs.configTemplate().toString(),
			"--network", inputs.engineNetwork().toString(),
			"--gtfs", inputs.gtfsDir().toString(),
			"--fleet", inputs.fleetFile().toString(),
			"--assignments", inputs.assignmentsFile().toString(),
			"--costs", inputs.costsFile().toString(),
			"--speed", String.valueOf(initialSpeed)));
		if (inputs.stationTracksFile() != null) {
			command.addAll(List.of("--station-tracks", inputs.stationTracksFile().toString()));
		}
		if (inputs.microNodesDir() != null) {
			command.addAll(List.of("--nodes", inputs.microNodesDir().toString()));
		}
		try {
			Files.createDirectories(inputs.runDir());
			Process process = new ProcessBuilder(command)
				.redirectError(inputs.runDir().resolve("engine.log").toFile())
				.start();
			return new EngineProcess(process, readEndpoint(process));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot start the simulation engine", e);
		}
	}

	private static Endpoint readEndpoint(Process process) throws IOException {
		BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		String line;
		while ((line = stdout.readLine()) != null) {
			if (line.startsWith("{")) {
				Message ready = Protocol.decode(line, Message.class);
				if (Message.READY.equals(ready.type())) {
					return new Endpoint(ready.port(), ready.token());
				}
			}
		}
		process.destroy();
		throw new IOException("The engine exited before announcing its endpoint; see engine.log in the run folder");
	}

	public Endpoint endpoint() {
		return endpoint;
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	public void destroy() {
		process.destroy();
	}
}
