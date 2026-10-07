package it.unimib.milanrailsim.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

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

	/**
	 * Position and motion of one train at the frame's time; {@code position} is metres from the start of {@code link}.
	 *
	 * @param destination station the trip in progress ends at; absent between trips and in older recordings
	 * @param nextStop    station of the next call of the trip
	 * @param power       kW an electric train exchanges with the line, negative when braking; absent for
	 *                    diesel trains and between trips
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TrainState(String id, String line, String link, double position, double speed, double acceleration,
			double delay, String destination, String nextStop, Double power) {

		public TrainState(String id, String line, String link, double position, double speed, double acceleration,
				double delay) {
			this(id, line, link, position, speed, acceleration, delay, null, null, null);
		}
	}

	/**
	 * The energy of the day at a frame's time.
	 *
	 * @param lineKilowatt  power drawn from the substations
	 * @param kilowattHours energy drawn from the substations since the start of the day
	 * @param litres        fuel burnt since the start of the day
	 */
	public record Energy(double lineKilowatt, double kilowattHours, double litres) {
	}

	/**
	 * Every train active at simulated {@code time}, in seconds since midnight.
	 *
	 * @param energy          absent when the run meters no energy, and in older recordings
	 * @param meanDelayByLine seconds of delay per line, averaged over the arrivals at a stop since the start
	 *                        of the day, an early arrival counting as zero; absent in older recordings
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Frame(double time, List<TrainState> trains, Energy energy, Map<String, Integer> meanDelayByLine) {

		public Frame(double time, List<TrainState> trains) {
			this(time, trains, null, null);
		}
	}

	/**
	 * How the run ended: trains that completed their circulation, trains the
	 * mobsim aborted, trains still on the network when the simulation stopped,
	 * and the simulated time it stopped at.
	 */
	public record Summary(int arrived, int aborted, int stalled, double endTime) {
	}

	/** Engine → client. Exactly the fields of the given {@code type} are set. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Message(String type, Integer port, String token, Double time, Integer activeTrains, String phase,
			Frame frame, String runDir, String message, Summary summary) {
		public static final String READY = "ready";
		public static final String PROGRESS = "progress";
		public static final String FRAME = "frame";
		public static final String DONE = "done";
		public static final String ERROR = "error";

		public static Message ready(int port, String token) {
			return new Message(READY, port, token, null, null, null, null, null, null, null);
		}

		public static Message progress(double time, int activeTrains, String phase) {
			return new Message(PROGRESS, null, null, time, activeTrains, phase, null, null, null, null);
		}

		public static Message frame(Frame frame) {
			return new Message(FRAME, null, null, null, null, null, frame, null, null, null);
		}

		public static Message done(String runDir, Summary summary) {
			return new Message(DONE, null, null, null, null, null, null, runDir, null, summary);
		}

		public static Message error(String message) {
			return new Message(ERROR, null, null, null, null, null, null, null, message, null);
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