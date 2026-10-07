package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.gui.sim.Session.Playback;
import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplaySessionTest {

	private static final long SECOND = 1_000_000_000L;

	private long now;

	private ReplaySession session(double... times) {
		List<Frame> frames = java.util.Arrays.stream(times)
			.mapToObj(time -> new Frame(time, List.of(new TrainState("t", "S1", "a_b", 0, 10, 0, 0))))
			.toList();
		ReplaySession session = new ReplaySession(Path.of("run"), () -> now);
		session.load(frames);
		return session;
	}

	@Test
	void theClockAdvancesWithRealTimeAtTheChosenSpeed() {
		ReplaySession session = session(100, 105, 110);
		session.setSpeed(10);

		now += SECOND / 4;

		assertEquals(102.5, session.currentTime(), 1e-9);
		Playback playback = session.playback();
		assertEquals(100, playback.from().time());
		assertEquals(105, playback.to().time());
		assertEquals(0.5, playback.fraction(), 1e-9);
	}

	@Test
	void pausingFreezesTheClockAndResumingContinuesFromThere() {
		ReplaySession session = session(0, 5, 10);
		session.setSpeed(1);
		now += 2 * SECOND;
		session.setSpeed(0);
		now += 60 * SECOND;

		assertEquals(2, session.currentTime(), 1e-9);

		session.setSpeed(1);
		now += SECOND;
		assertEquals(3, session.currentTime(), 1e-9);
	}

	@Test
	void seekingMovesAnywhereAndTheFrameSearchFollows() {
		ReplaySession session = session(0, 5, 10, 15, 20);
		session.setSpeed(1);
		session.seek(17);

		Playback playback = session.playback();
		assertEquals(15, playback.from().time());
		assertEquals(20, playback.to().time());

		session.seek(3);
		assertEquals(0, session.playback().from().time(), "going backwards restarts the search from the first frame");
	}

	@Test
	void reachingTheEndPausesAndSaysSo() {
		ReplaySession session = session(0, 5);
		session.setSpeed(Protocol.UNTHROTTLED);
		now += SECOND;

		Playback playback = session.playback();

		assertEquals(5, playback.to().time());
		assertEquals(0, session.speed().get());
		assertEquals("Fine della registrazione", session.phase().get());
		assertFalse(session.finished().get(), "the recording stays open to be rewound");

		session.seek(0);
		assertEquals(ReplaySession.PLAYING, session.phase().get());
	}

	@Test
	void anEmptyRecordingIsAnError() {
		ReplaySession session = new ReplaySession(Path.of("run"), () -> now);
		session.load(List.of());

		assertTrue(session.finished().get());
		assertNotNull(session.error().get());
		assertNull(session.playback());
	}
}
