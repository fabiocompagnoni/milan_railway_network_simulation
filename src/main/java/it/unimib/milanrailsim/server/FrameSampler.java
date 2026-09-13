package it.unimib.milanrailsim.server;

import ch.sbb.matsim.contrib.railsim.eventhandlers.RailsimTrainStateEventHandler;
import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.matsim.api.core.v01.events.VehicleAbortsEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.VehicleAbortsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns the dense railsim state events into one frame every {@code interval}
 * simulated seconds, holding the latest state of every train still in
 * traffic. The client extrapolates positions between frames from speed and
 * acceleration, so the interval trades bandwidth for accuracy, not smoothness.
 */
public final class FrameSampler
		implements RailsimTrainStateEventHandler, VehicleLeavesTrafficEventHandler, VehicleAbortsEventHandler {

	private static final String CIRCULATION_INFIX = "_circ_";

	private final double interval;
	private final Consumer<Frame> sink;
	private final Map<String, TrainState> latest = new LinkedHashMap<>();
	private double nextFrameTime;

	/**
	 * @param interval simulated seconds between frames
	 * @param sink receives each frame on the simulation thread
	 */
	public FrameSampler(double interval, Consumer<Frame> sink) {
		this.interval = interval;
		this.sink = sink;
	}

	@Override
	public void handleEvent(RailsimTrainStateEvent event) {
		String id = event.getVehicleId().toString();
		latest.put(id, new TrainState(id, line(id), event.getHeadLink().toString(), event.getHeadPosition(),
			event.getSpeed(), event.getAcceleration(), event.getDelay()));
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		latest.remove(event.getVehicleId().toString());
	}

	/** Trains the mobsim gives up on at the end of the day leave the map too. */
	@Override
	public void handleEvent(VehicleAbortsEvent event) {
		latest.remove(event.getVehicleId().toString());
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

	/** Vehicles are named {@code <line>_circ_<n>} by the circulation builder. */
	static String line(String vehicleId) {
		int infix = vehicleId.indexOf(CIRCULATION_INFIX);
		return infix < 0 ? vehicleId : vehicleId.substring(0, infix);
	}
}
