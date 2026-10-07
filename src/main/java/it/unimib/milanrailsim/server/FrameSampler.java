package it.unimib.milanrailsim.server;

import ch.sbb.matsim.contrib.railsim.eventhandlers.RailsimTrainStateEventHandler;
import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleAbortsEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleAbortsEventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns the dense railsim state events into one frame every {@code interval}
 * simulated seconds, holding the latest state of every train still in
 * service. The client extrapolates positions between frames from speed and
 * acceleration, so the interval trades bandwidth for accuracy, not smoothness.
 * <p>
 * A train leaves the frames when its driver arrives from the last trip of the
 * circulation: one driver serves every trip of a vehicle and arrives at the
 * end of each, so the trips started are counted against those planned. The
 * vehicle itself stays parked on its last link and never leaves traffic.
 */
public final class FrameSampler
		implements RailsimTrainStateEventHandler, TransitDriverStartsEventHandler, PersonArrivalEventHandler,
		VehicleAbortsEventHandler {

	private static final String CIRCULATION_INFIX = "_circ_";

	private final double interval;
	private final Map<String, Integer> plannedTrips;
	private final Consumer<Frame> sink;
	private final Map<String, TrainState> latest = new LinkedHashMap<>();
	private final Map<String, String> vehicleOfDriver = new HashMap<>();
	private final Map<String, Integer> startedTrips = new HashMap<>();
	private double nextFrameTime;
	private int arrived;
	private int aborted;

	/**
	 * @param interval     simulated seconds between frames
	 * @param plannedTrips departures of the timetable per vehicle id
	 * @param sink         receives each frame on the simulation thread
	 */
	public FrameSampler(double interval, Map<String, Integer> plannedTrips, Consumer<Frame> sink) {
		this.interval = interval;
		this.plannedTrips = Map.copyOf(plannedTrips);
		this.sink = sink;
	}

	@Override
	public void handleEvent(RailsimTrainStateEvent event) {
		String id = event.getVehicleId().toString();
		latest.put(id, new TrainState(id, line(id), event.getHeadLink().toString(), event.getHeadPosition(),
			event.getSpeed(), event.getAcceleration(), event.getDelay()));
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		String vehicle = event.getVehicleId().toString();
		vehicleOfDriver.put(event.getDriverId().toString(), vehicle);
		startedTrips.merge(vehicle, 1, Integer::sum);
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		String vehicle = vehicleOfDriver.get(event.getPersonId().toString());
		if (vehicle == null || !startedTrips.get(vehicle).equals(plannedTrips.get(vehicle))) {
			return;
		}
		if (latest.remove(vehicle) != null) {
			arrived++;
		}
	}

	/** Trains the mobsim gives up on at the end of the day leave the map too. */
	@Override
	public void handleEvent(VehicleAbortsEvent event) {
		if (latest.remove(event.getVehicleId().toString()) != null) {
			aborted++;
		}
	}

	/** Called once per simulation step; emits a frame whenever the sampling interval has elapsed. */
	public void onSimStep(double time) {
		if (time < nextFrameTime) {
			return;
		}
		nextFrameTime = time + interval;
		sink.accept(new Frame(time, List.copyOf(new ArrayList<>(latest.values()))));
	}

	public int activeTrains() {
		return latest.size();
	}

	/** Trains that completed their circulation. */
	public int arrivedTrains() {
		return arrived;
	}

	/** Trains the mobsim aborted, typically stuck ones at the end of the simulated day. */
	public int abortedTrains() {
		return aborted;
	}

	/** Vehicles are named {@code <line>_circ_<n>} by the circulation builder. */
	static String line(String vehicleId) {
		int infix = vehicleId.indexOf(CIRCULATION_INFIX);
		return infix < 0 ? vehicleId : vehicleId.substring(0, infix);
	}
}
