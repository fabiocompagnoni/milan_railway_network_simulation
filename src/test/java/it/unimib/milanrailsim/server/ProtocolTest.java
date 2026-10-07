package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.server.Protocol.Command;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

	@Test
	void framesRoundTrip() {
		Frame frame = new Frame(28800, List.of(new TrainState("S1_circ_3", "S1", "S01645_S01700", 312.4, 22.1, 0, 45)));

		String line = Protocol.encode(Message.frame(frame));
		Message decoded = Protocol.decode(line, Message.class);

		assertEquals(Message.FRAME, decoded.type());
		assertEquals(frame, decoded.frame());
		assertFalse(line.contains("\n"));
	}

	@Test
	void absentFieldsAreOmitted() {
		String line = Protocol.encode(Message.progress(3600, 12, "Simulazione"));

		assertFalse(line.contains("frame"));
		assertFalse(line.contains("runDir"));
		assertEquals(12, Protocol.decode(line, Message.class).activeTrains());
	}

	@Test
	void commandsRoundTrip() {
		Command decoded = Protocol.decode(Protocol.encode(Command.speed(100)), Command.class);

		assertEquals(Command.SPEED, decoded.type());
		assertEquals(100, decoded.speed());
		assertNull(Protocol.decode(Protocol.encode(Command.stop()), Command.class).speed());
	}

	@Test
	void unknownFieldsAreTolerated() {
		Command decoded = Protocol.decode("{\"type\":\"stop\",\"future\":1}", Command.class);

		assertEquals(Command.STOP, decoded.type());
	}
}
