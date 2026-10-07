package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.server.Protocol.Command;
import it.unimib.milanrailsim.server.Protocol.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Serves one simulation run to one client over a loopback socket. The port is
 * ephemeral and the first line the client sends must carry the token
 * announced on stdout, so no other local process can drive the engine.
 * Commands arrive on the socket thread and only touch the pacer or the stop
 * flag; the simulation itself runs on the main thread through the {@link Job}.
 */
public final class SimulationServer {

	private static final Logger log = LogManager.getLogger(SimulationServer.class);
	private static final int TOKEN_BYTES = 16;

	/** What the server runs once the client is connected. */
	public interface Job {
		/** Runs to completion, emitting messages through {@code out}; throws to report a failure. */
		void run(Pacer pacer, Emitter out) throws Exception;
	}

	/** Thread-safe outlet for engine messages. */
	public interface Emitter {
		void send(Message message);

		/** True once the client asked to stop; jobs poll it between steps. */
		boolean stopRequested();
	}

	private final Job job;
	private final Pacer pacer;

	public SimulationServer(Job job, double initialSpeed) {
		this.job = job;
		this.pacer = new Pacer(initialSpeed);
	}

	/** Announces the endpoint on {@code announce}, waits for the client, runs the job, then returns. */
	public void serve(PrintStream announce) {
		String token = HexFormat.of().formatHex(new SecureRandom().generateSeed(TOKEN_BYTES));
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			announce.println(Protocol.encode(Message.ready(server.getLocalPort(), token)));
			announce.flush();
			try (Socket client = server.accept()) {
				handle(client, token);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Simulation endpoint failed", e);
		}
	}

	private void handle(Socket client, String token) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
		String hello = in.readLine();
		if (hello == null || !token.equals(Protocol.decode(hello, Command.class).token())) {
			log.warn("Rejected client without a valid token");
			return;
		}
		SocketEmitter emitter = new SocketEmitter(out);
		Thread commands = new Thread(() -> readCommands(in, emitter), "engine-commands");
		commands.setDaemon(true);
		commands.start();
		try {
			job.run(pacer, emitter);
		} catch (Exception e) {
			log.error("Run failed", e);
			emitter.send(Message.error(e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}

	private void readCommands(BufferedReader in, SocketEmitter emitter) {
		try {
			String line;
			while ((line = in.readLine()) != null) {
				Command command = Protocol.decode(line, Command.class);
				switch (command.type()) {
					case Command.SPEED -> pacer.setSpeed(command.speed());
					case Command.STOP -> {
						emitter.requestStop();
						pacer.setSpeed(Protocol.UNTHROTTLED);
					}
					default -> log.warn("Ignored command {}", command.type());
				}
			}
		} catch (IOException e) {
			log.info("Client disconnected: {}", e.getMessage());
		}
		emitter.requestStop();
		pacer.setSpeed(Protocol.UNTHROTTLED);
	}

	private static final class SocketEmitter implements Emitter {

		private final BufferedWriter out;
		private volatile boolean stop;

		SocketEmitter(BufferedWriter out) {
			this.out = out;
		}

		@Override
		public synchronized void send(Message message) {
			try {
				out.write(Protocol.encode(message));
				out.newLine();
				out.flush();
			} catch (IOException e) {
				stop = true;
			}
		}

		@Override
		public boolean stopRequested() {
			return stop;
		}

		void requestStop() {
			stop = true;
		}
	}
}
