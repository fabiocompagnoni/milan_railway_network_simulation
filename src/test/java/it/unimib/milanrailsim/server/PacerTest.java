package it.unimib.milanrailsim.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PacerTest {

	private final long[] now = { 0 };
	private final List<Long> sleeps = new ArrayList<>();

	private Pacer pacer(double speed) {
		return new Pacer(speed, () -> now[0], nanos -> {
			sleeps.add(nanos);
			now[0] += nanos;
		});
	}

	@Test
	void sleepsSoThatSimulatedTimeMatchesRealTimeTimesSpeed() throws InterruptedException {
		Pacer pacer = pacer(10);

		pacer.afterSimStep(0);
		pacer.afterSimStep(20);

		// 20 simulated seconds at 10x are 2 real seconds, waited in 50 ms slices
		assertEquals(2_000_000_000L, sleeps.stream().mapToLong(Long::longValue).sum());
	}

	@Test
	void neverSleepsWhenUnthrottledOrBehindSchedule() throws InterruptedException {
		Pacer pacer = pacer(Protocol.UNTHROTTLED);
		pacer.afterSimStep(0);
		pacer.afterSimStep(3600);
		assertTrue(sleeps.isEmpty());

		Pacer slow = pacer(1);
		slow.afterSimStep(0);
		now[0] += 10_000_000_000L;
		slow.afterSimStep(5);
		assertTrue(sleeps.isEmpty());
	}

	@Test
	void zeroSpeedBlocksUntilResumed() throws InterruptedException {
		Pacer pacer = pacer(0);
		CountDownLatch released = new CountDownLatch(1);
		Thread simulation = new Thread(() -> {
			try {
				pacer.afterSimStep(1);
				released.countDown();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		simulation.start();

		assertFalse(released.await(100, TimeUnit.MILLISECONDS));
		pacer.setSpeed(Protocol.UNTHROTTLED);
		assertTrue(released.await(2, TimeUnit.SECONDS));
	}
}
