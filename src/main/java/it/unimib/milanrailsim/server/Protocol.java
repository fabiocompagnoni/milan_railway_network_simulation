package it.unimib.milanrailsim.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.List;

/**
 * Wire format between the simulation process and its client: one JSON object per line, discriminated by {@code type}. Fields that do not apply are absent.
 */
public final class Protocol {

	/**
	 * Client → engine. {@code token} accompanies {@link #HELLO}; {@code speed} accompanies
	 * {@link #SPEED}: simulated seconds per real second, 0 pauses, {@link #UNTHROTTLED} runs flat out.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Command(String type, Double speed, String token) {
		public static final String HELLO = "hello";
		public static final String SPEED = "speed";
		public static final String STOP = "stop";

		public static Command hello(String token) {
			return new Command(HELLO, null, token);
		}

		public static Command speed(double factor) {
			return new Command(SPEED, factor, null);
		}

		public static Command stop() {
			return new Command(STOP, null, null);
		}
	}

	public static final double UNTHROTTLED = -1;

	/** Position and motion of one train at the frame's time; {@code position} is metres from the start of {@code link}. */
	public record TrainState(String id, String line, String link, double position, double speed, double acceleration,
			double delay) {
	}

	/** Every train active at simulated {@code time}, in seconds since midnight. */
	public record Frame(double time, List<TrainState> trains) {
	}

	/** Engine → client. Exactly the fields of the given {@code type} are set. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Message(String type, Integer port, String token, Double time, Integer activeTrains, String phase,
			Frame frame, String runDir, String message) {
		public static final String READY = "ready";
		public static final String PROGRESS = "progress";
		public static final String FRAME = "frame";
		public static final String DONE = "done";
		public static final String ERROR = "error";

		public static Message ready(int port, String token) {
			return new Message(READY, port, token, null, null, null, null, null, null);
		}

		public static Message progress(double time, int activeTrains, String phase) {
			return new Message(PROGRESS, null, null, time, activeTrains, phase, null, null, null);
		}

		public static Message frame(Frame frame) {
			return new Message(FRAME, null, null, null, null, null, frame, null, null);
		}

		public static Message done(String runDir) {
			return new Message(DONE, null, null, null, null, null, null, runDir, null);
		}

		public static Message error(String message) {
			return new Message(ERROR, null, null, null, null, null, null, null, message);
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private Protocol() {
	}

	public static String encode(Object message) {
		try {
			return JSON.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Cannot encode " + message, e);
		}
	}

	public static <T> T decode(String line, Class<T> type) {
		try {
			return JSON.readValue(line, type);
		} catch (JsonProcessingException e) {
			throw new UncheckedIOException("Malformed message: " + line, e);
		}
	}
}