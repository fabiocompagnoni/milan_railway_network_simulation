package it.unimib.milanrailsim.server;

import ch.sbb.matsim.contrib.railsim.eventhandlers.RailsimTrainStateEventHandler;
import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import it.unimib.milanrailsim.results.EnergyMeter;
import it.unimib.milanrailsim.results.StopFacilities;
import it.unimib.milanrailsim.server.Protocol.Frame;
import it.unimib.milanrailsim.server.Protocol.TrainState;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleAbortsEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleAbortsEventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * <p>
 * While a train runs a trip its state carries where the trip ends and its
 * next call, counted from the stops it has left.
 */
public final class FrameSampler
		implements RailsimTrainStateEventHandler, TransitDriverStartsEventHandler, PersonArrivalEventHandler,
		VehicleAbortsEventHandler, VehicleDepartsAtFacilityEventHandler, VehicleArrivesAtFacilityEventHandler {

	private static final String CIRCULATION_INFIX = "_circ_";

	/**
	 * What the sampler needs of the timetable.
	 *
	 * @param tripsPerVehicle departures per vehicle id
	 * @param callsOfTrip     stations a trip calls at, in order, by departure id
	 */
	public record Timetable(Map<String, Integer> tripsPerVehicle, Map<String, List<String>> callsOfTrip) {

		public static Timetable of(TransitSchedule schedule) {
			Map<String, Integer> trips = new HashMap<>();
			Map<String, List<String>> calls = new HashMap<>();
			for (TransitLine line : schedule.getTransitLines().values()) {
				for (TransitRoute route : line.getRoutes().values()) {
					List<String> stations = route.getStops().stream()
						.map(stop -> StopFacilities.stationOf(stop.getStopFacility().getId().toString())).toList();
					for (Departure departure : route.getDepartures().values()) {
						trips.merge(departure.getVehicleId().toString(), 1, Integer::sum);
						calls.put(departure.getId().toString(), stations);
					}
				}
			}
			return new Timetable(trips, calls);
		}
	}

	/** The trip a train is running: its calls and how many of them it has left. */
	private static final class Progress {
		private final List<String> calls;
		private int departed;

		private Progress(List<String> calls) {
			this.calls = calls;
		}

		private String destination() {
			return calls.getLast();
		}

		private String nextStop() {
			return calls.get(Math.min(departed, calls.size() - 1));
		}
	}

	/** The arrivals of a line at its stops so far, an early one counting as on time. */
	private static final class Arrivals {
		private double delaySeconds;
		private int count;
	}

	private final Map<String, Arrivals> arrivalsByLine = new HashMap<>();
	private final double interval;
	private final Timetable timetable;
	private final Consumer<Frame> sink;
	private final Map<String, TrainState> latest = new LinkedHashMap<>();
	private final Map<String, String> vehicleOfDriver = new HashMap<>();
	private final Map<String, Integer> startedTrips = new HashMap<>();
	private final Map<String, Progress> progress = new HashMap<>();
	private double nextFrameTime;
	private double simulatedTime;
	private int arrived;
	private int aborted;

	/**
	 * @param interval simulated seconds between frames
	 * @param sink     receives each frame on the simulation thread
	 */
	public FrameSampler(double interval, Timetable timetable, Consumer<Frame> sink) {
		this.interval = interval;
		this.timetable = timetable;
		this.sink = sink;
	}

	@Override
	public void handleEvent(RailsimTrainStateEvent event) {
		String id = event.getVehicleId().toString();
		// the map cannot show finer than a metre or a tenth of a metre per second, and the recording halves in size
		latest.put(id, new TrainState(id, line(id), event.getHeadLink().toString(), Math.round(event.getHeadPosition()),
			rounded(event.getSpeed()), rounded(event.getAcceleration()), Math.round(event.getDelay())));
	}

	private static double rounded(double value) {
		return Math.round(value * 10) / 10.0;
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		String vehicle = event.getVehicleId().toString();
		vehicleOfDriver.put(event.getDriverId().toString(), vehicle);
		startedTrips.merge(vehicle, 1, Integer::sum);
		List<String> calls = timetable.callsOfTrip().get(event.getDepartureId().toString());
		if (calls != null && !calls.isEmpty()) {
			progress.put(vehicle, new Progress(calls));
		}
	}

	@Override
	public void handleEvent(VehicleDepartsAtFacilityEvent event) {
		Progress trip = progress.get(event.getVehicleId().toString());
		if (trip != null) {
			trip.departed++;
		}
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		String vehicle = vehicleOfDriver.get(event.getPersonId().toString());
		if (vehicle == null) {
			return;
		}
		progress.remove(vehicle);
		if (startedTrips.get(vehicle).equals(timetable.tripsPerVehicle().get(vehicle)) && latest.remove(vehicle) != null) {
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

	@Override
	public void handleEvent(VehicleArrivesAtFacilityEvent event) {
		Arrivals arrivals = arrivalsByLine.computeIfAbsent(line(event.getVehicleId().toString()), key -> new Arrivals());
		arrivals.delaySeconds += Math.max(0, event.getDelay());
		arrivals.count++;
	}

	/** Called once per simulation step of a run that meters no energy; emits a frame whenever the sampling interval has elapsed. */
	public void onSimStep(double time) {
		if (isFrameDue(time)) {
			sink.accept(new Frame(time, latest.values().stream().map(state -> onItsTrip(state, Map.of())).toList(), null,
				meanDelayByLine()));
		}
	}

	/** As {@link #onSimStep(double)}, with the energy the meter read last. */
	public void onSimStep(double time, EnergyMeter.Reading energy) {
		if (isFrameDue(time)) {
			sink.accept(new Frame(time, latest.values().stream().map(state -> onItsTrip(state, energy.kilowattByVehicle())).toList(),
				new Protocol.Energy(Math.round(energy.totals().lineKilowatt()), Math.round(energy.totals().drawnKilowattHours()),
					Math.round(energy.totals().litres())), meanDelayByLine()));
		}
	}

	private Map<String, Integer> meanDelayByLine() {
		Map<String, Integer> means = new TreeMap<>();
		arrivalsByLine.forEach((line, arrivals) -> means.put(line, (int) Math.round(arrivals.delaySeconds / arrivals.count)));
		return means;
	}

	private boolean isFrameDue(double time) {
		simulatedTime = time;
		if (time < nextFrameTime) {
			return false;
		}
		nextFrameTime = time + interval;
		return true;
	}

	private TrainState onItsTrip(TrainState state, Map<String, Double> kilowattByVehicle) {
		Progress trip = progress.get(state.id());
		Double kilowatt = kilowattByVehicle.get(state.id());
		return new TrainState(state.id(), state.line(), state.link(), state.position(), state.speed(), state.acceleration(),
			state.delay(), trip == null ? null : trip.destination(), trip == null ? null : trip.nextStop(),
			kilowatt == null ? null : (double) Math.round(kilowatt));
	}

	public int activeTrains() {
		return latest.size();
	}

	/** The last simulated second seen: where the day ended, once the mobsim is over. */
	public double simulatedTime() {
		return simulatedTime;
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
