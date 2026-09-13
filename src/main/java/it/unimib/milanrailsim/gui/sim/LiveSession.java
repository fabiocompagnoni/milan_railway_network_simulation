package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.Message;
import it.unimib.milanrailsim.server.Protocol.Summary;
import it.unimib.milanrailsim.server.RailsimJob;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;

/**
 * One run driven from the application: the engine process, its client and
 * the live state the map and controls observe. Frames are kept as the latest
 * one plus its arrival instant, so the map can extrapolate between them.
 * All properties change on the JavaFX thread.
 */
public final class LiveSession {

	/** Default heap share for the engine: the simulation is the memory-hungry part. */
	private static final int ENGINE_HEAP_PERCENT = 60;

	private final Path runDir;
	private final ObjectProperty<Frame> frame = new SimpleObjectProperty<>();
	private final DoubleProperty simulatedTime = new SimpleDoubleProperty();
	private final IntegerProperty activeTrains = new SimpleIntegerProperty();
	private final StringProperty phase = new SimpleStringProperty("Avvio del motore");
	private final DoubleProperty speed = new SimpleDoubleProperty();
	private final BooleanProperty finished = new SimpleBooleanProperty();
	private final StringProperty error = new SimpleStringProperty();
	private final ObjectProperty<Summary> summary = new SimpleObjectProperty<>();
	private Frame previousFrame;
	private long previousArrivalNanos;
	private long frameArrivalNanos;
	private EngineProcess engine;
	private SimulationClient client;

	private LiveSession(Path runDir) {
		this.runDir = runDir;
	}

	/** Spawns the engine and connects; throws if the engine dies before announcing itself. */
	public static LiveSession start(RailsimJob.Inputs inputs, double initialSpeed) {
		LiveSession session = new LiveSession(inputs.runDir());
		session.speed.set(initialSpeed);
		session.engine = EngineProcess.start(inputs, ENGINE_HEAP_PERCENT, initialSpeed);
		session.client = new SimulationClient(session.engine.endpoint(), session::onMessage, session::onClosed);
		return session;
	}

	private void onMessage(Message message) {
		switch (message.type()) {
			case Message.FRAME -> {
				long arrival = System.nanoTime();
				Platform.runLater(() -> {
					previousFrame = frame.get();
					previousArrivalNanos = frameArrivalNanos;
					frameArrivalNanos = arrival;
					frame.set(message.frame());
					simulatedTime.set(message.frame().time());
					activeTrains.set(message.frame().trains().size());
				});
			}
			case Message.PROGRESS -> Platform.runLater(() -> {
				phase.set(message.phase());
				if (message.time() > 0) {
					simulatedTime.set(message.time());
				}
				activeTrains.set(message.activeTrains());
			});
			case Message.DONE -> Platform.runLater(() -> {
				phase.set("Completata");
				summary.set(message.summary());
				finished.set(true);
			});
			case Message.ERROR -> Platform.runLater(() -> {
				error.set(message.message());
				finished.set(true);
			});
			default -> {
			}
		}
	}

	private void onClosed() {
		Platform.runLater(() -> {
			if (!finished.get()) {
				error.set("Il motore si è interrotto senza completare il run. Vedi engine.log nella cartella del run.");
				finished.set(true);
			}
		});
	}

	/** The two frames the map is moving between and how far it has got, 0 to 1. */
	public record Playback(Frame from, Frame to, double fraction) {

		public double time() {
			return from == null ? to.time() : from.time() + (to.time() - from.time()) * fraction;
		}
	}

	/**
	 * What to draw now: the previous frame advanced towards the latest one in
	 * proportion to the real time elapsed since it arrived, measured against the
	 * interval between the two arrivals. Frames are shown one interval late, so
	 * the map never has to guess where a train will be.
	 */
	public Playback playback() {
		Frame current = frame.get();
		if (current == null) {
			return null;
		}
		if (previousFrame == null || speed.get() == 0) {
			return new Playback(previousFrame, current, 1);
		}
		long interval = Math.max(1, frameArrivalNanos - previousArrivalNanos);
		double fraction = Math.min(1, (double) (System.nanoTime() - frameArrivalNanos) / interval);
		return new Playback(previousFrame, current, fraction);
	}

	public void setSpeed(double factor) {
		speed.set(factor);
		client.setSpeed(factor);
	}

	/** Asks the engine to stop; the run stays archived as interrupted. */
	public void stop() {
		client.stop();
	}

	public void close() {
		client.close();
		engine.destroy();
	}

	public Path runDir() {
		return runDir;
	}

	public ObjectProperty<Frame> frame() {
		return frame;
	}

	public DoubleProperty simulatedTime() {
		return simulatedTime;
	}

	public IntegerProperty activeTrains() {
		return activeTrains;
	}

	public StringProperty phase() {
		return phase;
	}

	public DoubleProperty speed() {
		return speed;
	}

	public BooleanProperty finished() {
		return finished;
	}

	public StringProperty error() {
		return error;
	}

	/** How the run ended; set together with {@link #finished()} on a completed run. */
	public ObjectProperty<Summary> summary() {
		return summary;
	}
}
