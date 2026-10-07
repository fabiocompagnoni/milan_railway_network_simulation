package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.server.Protocol.Command;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SimulationServerTest {

	/** A job that emits three frames, honouring the pacer and the stop flag like the real one. */
	private static final SimulationServer.Job FAKE_ENGINE = (pacer, out) -> {
		out.send(Message.progress(0, 0, "Simulazione"));
		for (int t = 0; t < 3; t++) {
			if (out.stopRequested()) {
				return;
			}
			pacer.afterSimStep(t * 5);
			out.send(Message.frame(new Frame(t * 5, List.of(new TrainState("S1_circ_1", "S1", "A_B", t, 1, 0, 0)))));
		}
		out.send(Message.done("run", new Protocol.Summary(1, 0, 0, 3600)));
	};

	private record Endpoint(int port, String token) {
	}

	private static Endpoint start(SimulationServer server) throws Exception {
		ByteArrayOutputStream announced = new ByteArrayOutputStream();
		Thread serving = new Thread(() -> server.serve(new PrintStream(announced, true, StandardCharsets.UTF_8)));
		serving.setDaemon(true);
		serving.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (announced.size() == 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		Message ready = Protocol.decode(announced.toString(StandardCharsets.UTF_8).trim(), Message.class);
		assertEquals(Message.READY, ready.type());
		return new Endpoint(ready.port(), ready.token());
	}

	@Test
	void streamsMessagesToAnAuthenticatedClient() throws Exception {
		Endpoint endpoint = start(new SimulationServer(FAKE_ENGINE, Protocol.UNTHROTTLED));
		try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out.write(Protocol.encode(Command.hello(endpoint.token())));
			out.newLine();
			out.flush();

			List<String> types = new ArrayList<>();
			String line;
			while ((line = in.readLine()) != null) {
				types.add(Protocol.decode(line, Message.class).type());
			}
			assertEquals(List.of(Message.PROGRESS, Message.FRAME, Message.FRAME, Message.FRAME, Message.DONE), types);
		}
	}

	@Test
	void rejectsAWrongToken() throws Exception {
		Endpoint endpoint = start(new SimulationServer(FAKE_ENGINE, Protocol.UNTHROTTLED));
		try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out.write(Protocol.encode(Command.hello("wrong")));
			out.newLine();
			out.flush();

			assertNull(in.readLine());
		}
	}

	@Test
	void stopCommandEndsAPausedRun() throws Exception {
		Endpoint endpoint = start(new SimulationServer(FAKE_ENGINE, 0));
		try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out.write(Protocol.encode(Command.hello(endpoint.token())));
			out.newLine();
			out.write(Protocol.encode(Command.stop()));
			out.newLine();
			out.flush();

			List<String> types = new ArrayList<>();
			String line;
			while ((line = in.readLine()) != null) {
				types.add(Protocol.decode(line, Message.class).type());
			}
			assertFalse(types.contains(Message.DONE));
		}
	}
}
