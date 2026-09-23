package it.unimib.milanrailsim.gui.sim;

import it.unimib.milanrailsim.server.Protocol.Frame;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the map view observes of a run in progress, whether the engine is
 * producing the frames now ({@link LiveSession}) or they are read back from
 * a recording ({@link ReplaySession}). All properties change on the JavaFX
 * thread.
 */
public interface Session {

	/** The two frames the map is moving between and how far it has got, 0 to 1. */
	record Playback(Frame from, Frame to, double fraction) {

		public double time() {
			return from == null ? to.time() : from.time() + (to.time() - from.time()) * fraction;
		}
	}

	/** A recording can be positioned anywhere within its span; a live run cannot. */
	interface Timeline {

		double start();

		double end();

		void seek(double time);
	}

	Path runDir();

	/** What to draw now, or null before the first frame. */
	Playback playback();

	/** Simulated seconds per real second; 0 pauses. */
	void setSpeed(double factor);

	DoubleProperty speed();

	StringProperty phase();

	/** Name of the phase in which frames flow; the others are preparation or wrap-up. */
	String runningPhase();

	/** What the work banner says while the running phase has not begun. */
	String preparationTitle();

	IntegerProperty activeTrains();

	/** True once nothing more will come: the run is over, failed or was closed. */
	BooleanProperty finished();

	StringProperty error();

	Optional<Timeline> timeline();

	/** Ends the session from the user's side. */
	void stop();

	void close();
}
