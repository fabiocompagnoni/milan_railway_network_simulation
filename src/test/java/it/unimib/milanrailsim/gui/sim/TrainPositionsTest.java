package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.gui.map.Polyline;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrainPositionsTest {

	private static final double EPSILON = 1e-9;
	/** Two segments: 100 m east, then 100 m north. */
	private static final Polyline LINK = Polyline.parse("0 0;100 0;100 100");
	private final TrainPositions positions = new TrainPositions(Map.of("A_B", LINK),
		Map.of("B", new double[] { 100, 100 }));

	private static Frame frame(double time, String link, double position) {
		return new Frame(time, List.of(new TrainState("S1_circ_1", "S1", link, position, 10, 0, 0)));
	}

	@Test
	void interpolatesAlongTheLinkBetweenTwoFrames() {
		// from 50 m to 150 m: halfway is 100 m, the corner of the alignment
		TrainPositions.Placement placement = positions.between(frame(0, "A_B", 50), frame(5, "A_B", 150), 0.5).getFirst();

		assertEquals(100, placement.x(), EPSILON);
		assertEquals(0, placement.y(), EPSILON);
	}

	@Test
	void trainThatChangedLinkIsDrawnAtTheLaterPosition() {
		TrainPositions.Placement placement = positions.between(frame(0, "other", 900), frame(5, "A_B", 150), 0.2).getFirst();

		assertEquals(100, placement.x(), EPSILON);
		assertEquals(50, placement.y(), EPSILON);
	}

	@Test
	void trainOnAStationLoopSitsOnTheStation() {
		TrainPositions.Placement placement = positions.between(null, frame(5, "stop_B", 120), 0).getFirst();

		assertEquals(100, placement.x(), EPSILON);
		assertEquals(100, placement.y(), EPSILON);
	}

	@Test
	void clampsBeyondTheEndOfTheLink() {
		TrainPositions.Placement placement = positions.between(null, frame(5, "A_B", 999), 0).getFirst();

		assertEquals(100, placement.x(), EPSILON);
		assertEquals(100, placement.y(), EPSILON);
	}

	@Test
	void skipsTrainsWithoutAnyGeometry() {
		assertTrue(positions.between(null, frame(0, "unknown", 0), 0).isEmpty());
		assertTrue(positions.between(null, frame(0, "stop_unknown", 0), 0).isEmpty());
	}
}
