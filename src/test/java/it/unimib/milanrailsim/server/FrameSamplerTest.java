package it.unimib.milanrailsim.server;

import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import it.unimib.milanrailsim.server.Protocol.Frame;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleAbortsEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrameSamplerTest {

	private final List<Frame> frames = new ArrayList<>();
	private final FrameSampler sampler = new FrameSampler(5, Map.of("S1_circ_1", 2, "RE4_circ_2", 1), frames::add);

	private static RailsimTrainStateEvent state(double time, String vehicle, String link, double position, double speed) {
		return new RailsimTrainStateEvent(time, time, Id.create(vehicle, Vehicle.class), Id.createLinkId(link),
			position, Id.createLinkId(link), Math.max(0, position - 100), speed, 0, speed, 30);
	}

	private static TransitDriverStartsEvent tripStarts(double time, String vehicle, String trip) {
		return new TransitDriverStartsEvent(time, Id.create("pt_" + vehicle, Person.class), Id.create(vehicle, Vehicle.class),
			Id.create("S1", TransitLine.class), Id.create("S1_1", TransitRoute.class), Id.create(trip, Departure.class));
	}

	private static PersonArrivalEvent driverArrives(double time, String vehicle) {
		return new PersonArrivalEvent(time, Id.create("pt_" + vehicle, Person.class), Id.createLinkId("A_B"), "rail");
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
	void trainsDropOutAfterTheLastTripOfTheirCirculation() {
		sampler.handleEvent(tripStarts(0, "S1_circ_1", "d1"));
		sampler.handleEvent(state(0, "S1_circ_1", "A_B", 10, 10));
		sampler.handleEvent(driverArrives(3, "S1_circ_1"));
		sampler.onSimStep(3);
		assertEquals(1, frames.getFirst().trains().size(), "one trip left: the train waits on its platform");

		sampler.handleEvent(tripStarts(4, "S1_circ_1", "d2"));
		sampler.handleEvent(driverArrives(8, "S1_circ_1"));
		sampler.onSimStep(8);

		assertTrue(frames.get(1).trains().isEmpty());
		assertEquals(0, sampler.activeTrains());
		assertEquals(1, sampler.arrivedTrains());
		assertEquals(0, sampler.abortedTrains());
	}

	@Test
	void abortedTrainsAreCountedApartFromArrivals() {
		sampler.handleEvent(state(0, "S1_circ_1", "A_B", 10, 10));
		sampler.handleEvent(state(0, "S1_circ_2", "A_B", 10, 10));
		sampler.handleEvent(new VehicleAbortsEvent(3, Id.create("S1_circ_1", Vehicle.class), Id.createLinkId("A_B")));

		assertEquals(1, sampler.abortedTrains());
		assertEquals(0, sampler.arrivedTrains());
		assertEquals(1, sampler.activeTrains());
	}
}
