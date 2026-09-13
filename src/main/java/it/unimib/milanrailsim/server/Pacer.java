package it.unimib.milanrailsim.server;

import java.util.function.LongSupplier;

/**
 * Holds the simulation to a chosen ratio of real time by sleeping between
 * steps: at speed 60 one real second covers one simulated minute. Speed 0
 * blocks the simulation thread until it changes; {@link Protocol#UNTHROTTLED}
 * never sleeps. The speed is set from another thread, so the pause is a
 * cooperative wait, not an interrupt.
 */
public final class Pacer {

	private final LongSupplier clockNanos;
	private final Sleeper sleeper;
	private final Object lock = new Object();
	private double speed;
	private double anchorSimTime;
	private long anchorNanos;
	private boolean anchored;

	/** How the pacer waits; injected so tests run without real time. */
	public interface Sleeper {
		void sleepNanos(long nanos) throws InterruptedException;
	}

	public Pacer(double initialSpeed) {
		this(initialSpeed, System::nanoTime, nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
	}

	Pacer(double initialSpeed, LongSupplier clockNanos, Sleeper sleeper) {
		this.speed = initialSpeed;
		this.clockNanos = clockNanos;
		this.sleeper = sleeper;
	}

	public void setSpeed(double factor) {
		synchronized (lock) {
			speed = factor;
			anchored = false;
			lock.notifyAll();
		}
	}

	public double speed() {
		synchronized (lock) {
			return speed;
		}
	}

	/** Blocks until simulated {@code time} may be shown at the current speed. */
	public void afterSimStep(double time) throws InterruptedException {
		while (true) {
			double current;
			synchronized (lock) {
				while (speed == 0) {
					lock.wait();
				}
				current = speed;
				if (current == Protocol.UNTHROTTLED) {
					return;
				}
				if (!anchored) {
					anchorSimTime = time;
					anchorNanos = clockNanos.getAsLong();
					anchored = true;
					return;
				}
			}
			long dueNanos = anchorNanos + (long) ((time - anchorSimTime) / current * 1e9);
			long wait = dueNanos - clockNanos.getAsLong();
			if (wait <= 0) {
				return;
			}
			sleeper.sleepNanos(Math.min(wait, 50_000_000L));
		}
	}
}
