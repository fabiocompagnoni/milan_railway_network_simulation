package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.FrameRecorder;
import it.unimib.milanrailsim.server.Protocol;
import it.unimib.milanrailsim.server.Protocol.Frame;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * An archived run played back from its recorded frames. The simulated clock
 * advances with real time at the chosen speed and can be moved anywhere in
 * the recording; the map draws the frame the clock is in, interpolated
 * towards the next one like a live run.
 */
public final class ReplaySession implements Session, Session.Timeline {

	public static final String PLAYING = "Riproduzione";
	private static final String LOADING = "Carico la registrazione";
	private static final String ENDED = "Fine della registrazione";
	/** What "max" means for a recording: a whole day in about half a minute. */
	private static final double FASTEST = 3600;
	private static final double INITIAL_SPEED = 60;

	private final Path runDir;
	private final LongSupplier nanos;
	private final DoubleProperty speed = new SimpleDoubleProperty();
	private final StringProperty phase = new SimpleStringProperty(LOADING);
	private final IntegerProperty activeTrains = new SimpleIntegerProperty();
	private final BooleanProperty finished = new SimpleBooleanProperty();
	private final StringProperty error = new SimpleStringProperty();
	private List<Frame> frames = List.of();
	private double timeAtMark;
	private long nanosAtMark;
	private int index;

	ReplaySession(Path runDir, LongSupplier nanos) {
		this.runDir = runDir;
		this.nanos = nanos;
	}

	/** Reads the recording on a background thread; the session plays as soon as it is loaded. */
	public static ReplaySession open(Path runDir) {
		ReplaySession session = new ReplaySession(runDir, System::nanoTime);
		CompletableFuture.supplyAsync(() -> FrameRecorder.read(runDir.resolve(FrameRecorder.FILE_NAME)))
			.whenComplete((frames, failure) -> Platform.runLater(() -> {
				if (failure != null) {
					session.error.set("Registrazione non leggibile: " + failure.getCause().getMessage());
					session.finished.set(true);
				} else {
					session.load(frames);
				}
			}));
		return session;
	}

	void load(List<Frame> loaded) {
		if (loaded.isEmpty()) {
			error.set("La registrazione di questo run è vuota.");
			finished.set(true);
			return;
		}
		frames = loaded;
		timeAtMark = loaded.getFirst().time();
		nanosAtMark = nanos.getAsLong();
		phase.set(PLAYING);
		setSpeed(INITIAL_SPEED);
	}

	@Override
	public Playback playback() {
		if (frames.isEmpty()) {
			return null;
		}
		double time = currentTime();
		if (time >= end() && speed.get() > 0) {
			setSpeed(0);
			phase.set(ENDED);
		}
		index = frameIndexAt(time);
		Frame from = frames.get(index);
		if (index + 1 >= frames.size()) {
			return new Playback(from, from, 1);
		}
		Frame to = frames.get(index + 1);
		double fraction = (time - from.time()) / (to.time() - from.time());
		activeTrains.set(to.trains().size());
		return new Playback(from, to, Math.clamp(fraction, 0, 1));
	}

	/** The simulated time the clock is at now: where it was marked plus the real time since, scaled. */
	double currentTime() {
		double elapsed = (nanos.getAsLong() - nanosAtMark) / 1e9 * speed.get();
		return Math.min(timeAtMark + elapsed, end());
	}

	/** The last frame at or before {@code time}; frames are sorted and the clock mostly moves forward. */
	private int frameIndexAt(double time) {
		int i = index < frames.size() && frames.get(index).time() <= time ? index : 0;
		while (i + 1 < frames.size() && frames.get(i + 1).time() <= time) {
			i++;
		}
		return i;
	}

	@Override
	public void setSpeed(double factor) {
		timeAtMark = currentTime();
		nanosAtMark = nanos.getAsLong();
		speed.set(factor == Protocol.UNTHROTTLED ? FASTEST : factor);
		if (factor > 0 && ENDED.equals(phase.get())) {
			phase.set(PLAYING);
		}
	}

	@Override
	public void seek(double time) {
		timeAtMark = Math.clamp(time, start(), end());
		nanosAtMark = nanos.getAsLong();
		if (ENDED.equals(phase.get()) && timeAtMark < end()) {
			phase.set(PLAYING);
		}
	}

	@Override
	public double start() {
		return frames.isEmpty() ? 0 : frames.getFirst().time();
	}

	@Override
	public double end() {
		return frames.isEmpty() ? 0 : frames.getLast().time();
	}

	@Override
	public Optional<Timeline> timeline() {
		return Optional.of(this);
	}

	@Override
	public String runningPhase() {
		return PLAYING;
	}

	@Override
	public String preparationTitle() {
		return "Apro la registrazione";
	}

	/** Closing the replay is the only way it ends from the user's side. */
	@Override
	public void stop() {
		finished.set(true);
	}

	@Override
	public void close() {
		finished.set(true);
	}

	@Override
	public Path runDir() {
		return runDir;
	}

	@Override
	public DoubleProperty speed() {
		return speed;
	}

	@Override
	public StringProperty phase() {
		return phase;
	}

	@Override
	public IntegerProperty activeTrains() {
		return activeTrains;
	}

	@Override
	public BooleanProperty finished() {
		return finished;
	}

	@Override
	public StringProperty error() {
		return error;
	}
}
