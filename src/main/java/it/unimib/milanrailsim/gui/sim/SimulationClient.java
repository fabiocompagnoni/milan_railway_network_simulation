package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Command;
import it.unimib.milanrailsim.server.Protocol.Message;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Talks to one engine over its loopback socket. Messages arrive on a reader
 * thread and are handed to {@code onMessage} there; the caller decides how to
 * reach the JavaFX thread. Commands can be sent from any thread.
 */
public final class SimulationClient implements AutoCloseable {

	private final Socket socket;
	private final BufferedWriter out;
	private final Thread reader;

	/** Connects, authenticates and starts reading; {@code onClosed} fires once the engine stops talking. */
	public SimulationClient(EngineProcess.Endpoint endpoint, Consumer<Message> onMessage, Runnable onClosed) {
		try {
			socket = new Socket(InetAddress.getLoopbackAddress(), endpoint.port());
			out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			send(Command.hello(endpoint.token()));
			reader = new Thread(() -> read(in, onMessage, onClosed), "engine-messages");
			reader.setDaemon(true);
			reader.start();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot connect to the simulation engine", e);
		}
	}

	private void read(BufferedReader in, Consumer<Message> onMessage, Runnable onClosed) {
		try {
			String line;
			while ((line = in.readLine()) != null) {
				onMessage.accept(Protocol.decode(line, Message.class));
			}
		} catch (IOException e) {
			// the socket was closed, by us or by the engine exiting
		}
		onClosed.run();
	}

	public void setSpeed(double factor) {
		send(Command.speed(factor));
	}

	public void stop() {
		send(Command.stop());
	}

	private synchronized void send(Command command) {
		try {
			out.write(Protocol.encode(command));
			out.newLine();
			out.flush();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot send " + command.type() + " to the engine", e);
		}
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot close the engine connection", e);
		}
	}
}
