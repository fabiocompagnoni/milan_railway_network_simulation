package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.RailsimJob;
import it.unimib.milanrailsim.server.RunSimulationServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The simulation engine as a child JVM: same runtime and class path as the
 * application, its own heap, announcing its loopback endpoint on stdout. Everything it
 * prints on either stream ends up in {@code engine.log} in the run folder.
 */
public final class EngineProcess {

	private static final Logger LOG = LogManager.getLogger(EngineProcess.class);

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
			javaExecutable().toString(),
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
			Path log = inputs.runDir().resolve("engine.log");
			Process process = new ProcessBuilder(command)
				.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
				.start();
			BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			Endpoint endpoint = readEndpoint(process, stdout);
			drain(stdout, log);
			return new EngineProcess(process, endpoint);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot start the simulation engine", e);
		}
	}

	private static Path javaExecutable() {
		boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
		return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
	}

	private static Endpoint readEndpoint(Process process, BufferedReader stdout) throws IOException {
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

	/**
	 * Keeps reading the engine's standard output for as long as it runs. MATSim
	 * logs there too, mostly when it writes its outputs at the end: left unread,
	 * the pipe fills up and the engine blocks on its next log line, apparently
	 * forever, between the last simulated second and the results.
	 */
	private static void drain(BufferedReader stdout, Path log) {
		Thread drainer = new Thread(() -> {
			try (BufferedWriter sink = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				String line;
				while ((line = stdout.readLine()) != null) {
					sink.write(line);
					sink.newLine();
					sink.flush();
				}
			} catch (IOException e) {
				LOG.warn("Stopped reading the engine's output: {}", e.getMessage());
			}
		}, "engine-stdout");
		drainer.setDaemon(true);
		drainer.start();
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
