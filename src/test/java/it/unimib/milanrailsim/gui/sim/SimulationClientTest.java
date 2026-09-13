package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.SimulationServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SimulationClientTest {

	@Test
	void receivesMessagesAndDrivesTheSpeed() throws Exception {
		CountDownLatch resumed = new CountDownLatch(1);
		SimulationServer.Job job = (pacer, out) -> {
			pacer.afterSimStep(0);
			resumed.countDown();
			out.send(Message.frame(new Frame(0, List.of())));
			out.send(Message.done("run"));
		};
		SimulationServer server = new SimulationServer(job, 0);
		ByteArrayOutputStream announced = new ByteArrayOutputStream();
		Thread serving = new Thread(() -> server.serve(new PrintStream(announced, true, StandardCharsets.UTF_8)));
		serving.setDaemon(true);
		serving.start();
		while (announced.size() == 0) {
			Thread.sleep(10);
		}
		Message ready = Protocol.decode(announced.toString(StandardCharsets.UTF_8).trim(), Message.class);

		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch closed = new CountDownLatch(1);
		try (SimulationClient client = new SimulationClient(new EngineProcess.Endpoint(ready.port(), ready.token()),
				message -> received.add(message.type()), closed::countDown)) {
			// the engine starts paused: nothing arrives until the client sets a speed
			assertFalse(resumed.await(200, TimeUnit.MILLISECONDS));
			client.setSpeed(Protocol.UNTHROTTLED);
			assertTrue(closed.await(5, TimeUnit.SECONDS));
		}

		assertEquals(List.of(Message.FRAME, Message.DONE), received);
	}
}
