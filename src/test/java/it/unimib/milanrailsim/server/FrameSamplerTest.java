package it.unimib.milanrailsim.server;

import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import it.unimib.milanrailsim.server.Protocol.Frame;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameSamplerTest {

	private final List<Frame> frames = new ArrayList<>();
	private final FrameSampler sampler = new FrameSampler(5, frames::add);

	private static RailsimTrainStateEvent state(double time, String vehicle, String link, double position, double speed) {
		return new RailsimTrainStateEvent(time, time, Id.create(vehicle, Vehicle.class), Id.createLinkId(link),
			position, Id.createLinkId(link), Math.max(0, position - 100), speed, 0, speed, 30);
	}

	@Test
	void emitsOneFrameEverySamplingInterval() {
		for (int t = 0; t < 12; t++) {
			sampler.handleEvent(state(t, "S1_circ_1", "A_B", t * 10, 10));
			sampler.onSimStep(t);
		}

		assertEquals(3, frames.size());
		assertEquals(List.of(0.0, 5.0, 10.0), frames.stream().map(Frame::time).toList());
		assertEquals(100, frames.get(2).trains().getFirst().position());
	}

	@Test
	void framesCarryTheLatestStateOfEveryActiveTrain() {
		sampler.handleEvent(state(0, "S1_circ_1", "A_B", 10, 10));
		sampler.handleEvent(state(0, "RE4_circ_2", "C_D", 20, 20));
		sampler.handleEvent(state(1, "S1_circ_1", "A_B", 25, 12));
		sampler.onSimStep(1);

		Frame frame = frames.getFirst();
		assertEquals(2, frame.trains().size());
		assertEquals(25, frame.trains().getFirst().position());
		assertEquals("S1", frame.trains().getFirst().line());
		assertEquals("RE4", frame.trains().get(1).line());
	}

	@Test
	void trainsLeavingTrafficDropOut() {
		sampler.handleEvent(state(0, "S1_circ_1", "A_B", 10, 10));
		sampler.handleEvent(new VehicleLeavesTrafficEvent(3, Id.createPersonId("d"), Id.createLinkId("A_B"),
			Id.create("S1_circ_1", Vehicle.class), "rail", 1.0));
		sampler.onSimStep(3);

		assertTrue(frames.getFirst().trains().isEmpty());
		assertEquals(0, sampler.activeTrains());
	}
}
