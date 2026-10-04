package it.unimib.milanrailsim.results;

import ch.sbb.matsim.contrib.railsim.eventhandlers.RailsimTrainStateEventHandler;
import ch.sbb.matsim.contrib.railsim.events.RailsimTrainStateEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Measures the energy of a run while it is simulated: at regular intervals it
 * samples the speed and acceleration railsim gives every train running a
 * trip, turns them into power with the {@link EnergyModel} and books them in
 * an {@link EnergyLedger}.
 * <p>
 * A train counts from the start of a trip to its arrival, auxiliaries
 * included: between trips it is taken as switched off. Its mass is the empty
 * mass plus the passengers the model assumes for the hour the trip is planned
 * to leave. Vehicle types the model has no mass for are left out, and named
 * once in the log.
 */
public final class EnergyMeter implements RailsimTrainStateEventHandler, TransitDriverStartsEventHandler, PersonArrivalEventHandler {

	private static final Logger log = LogManager.getLogger(EnergyMeter.class);

	/** What the meter knows of a trip before it runs. */
	private record Trip(String id, String line, double massKilograms, int cars, boolean electric) {
	}

	/** The last state railsim reported for a train. */
	private record State(double time, double speed, double acceleration, double targetSpeed, Coord position) {

		/** railsim holds the acceleration until the next state, or until the target speed is reached. */
		double speedAt(double now) {
			double reached = speed + acceleration * (now - time);
			double bounded = acceleration >= 0 ? Math.min(reached, Math.max(speed, targetSpeed))
				: Math.max(reached, Math.min(speed, targetSpeed));
			return Math.max(0, bounded);
		}

		double accelerationAt(double now) {
			return speedAt(now) == speed + acceleration * (now - time) ? acceleration : 0;
		}
	}

	/**
	 * The state of the meter at one sample.
	 *
	 * @param kilowattByVehicle power each electric train running a trip exchanges with the line, by vehicle id:
	 *                          positive when it draws, negative when it returns braking power
	 */
	public record Reading(EnergyLedger.Live totals, Map<String, Double> kilowattByVehicle) {
	}

	private Reading reading = new Reading(new EnergyLedger.Live(0, 0, 0), Map.of());
	private final EnergyModel model;
	private final Network network;
	private final double interval;
	private final EnergyLedger ledger;
	private final Map<String, Trip> trips = new HashMap<>();
	private final Map<String, String> vehicleOfDriver = new HashMap<>();
	private final Map<String, Trip> running = new HashMap<>();
	private final Map<String, State> states = new HashMap<>();
	private double nextSample;

	/**
	 * @param day      service day, which decides how full the trains are
	 * @param isDiesel whether a vehicle type, by id, runs on diesel
	 * @param interval simulated seconds between two samples
	 */
	public EnergyMeter(EnergyModel model, LocalDate day, TransitSchedule schedule, Vehicles vehicles, Network network,
			Predicate<String> isDiesel, double interval) {
		this.model = model;
		this.network = network;
		this.interval = interval;
		this.ledger = new EnergyLedger(model.recoveryRadiusMetres());
		Set<String> unknown = new HashSet<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					Vehicle vehicle = vehicles.getVehicles().get(departure.getVehicleId());
					VehicleType type = vehicle.getType();
					String typeId = type.getId().toString();
					model.train(typeId).ifPresentOrElse(train -> {
						double load = model.load(day, (int) departure.getDepartureTime());
						trips.put(departure.getId().toString(), new Trip(departure.getId().toString(), line.getId().toString(),
							model.massKilograms(train, type.getCapacity().getSeats(), load), train.cars(),
							!isDiesel.test(typeId)));
					}, () -> unknown.add(typeId));
				}
			}
		}
		if (!unknown.isEmpty()) {
			log.warn("No mass in the energy model for the vehicle types {}: their trips are left out of the energy figures", unknown);
		}
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		String vehicle = event.getVehicleId().toString();
		vehicleOfDriver.put(event.getDriverId().toString(), vehicle);
		Trip trip = trips.get(event.getDepartureId().toString());
		if (trip != null) {
			running.put(vehicle, trip);
		}
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		String vehicle = vehicleOfDriver.get(event.getPersonId().toString());
		if (vehicle != null) {
			running.remove(vehicle);
		}
	}

	@Override
	public void handleEvent(RailsimTrainStateEvent event) {
		Link head = network.getLinks().get(event.getHeadLink());
		states.put(event.getVehicleId().toString(), new State(event.getTime(), event.getSpeed(), event.getAcceleration(),
			event.getTargetSpeed(), head.getCoord()));
	}

	/** Called once per simulation step; samples the trains whenever the interval has elapsed. */
	public void onSimStep(double time) {
		if (time < nextSample) {
			return;
		}
		nextSample = time + interval;
		List<EnergyLedger.Sample> samples = new ArrayList<>();
		Map<String, Double> kilowattByVehicle = new HashMap<>();
		running.forEach((vehicle, trip) -> {
			State state = states.get(vehicle);
			if (state != null) {
				EnergyLedger.Sample sample = sample(trip, state, time);
				samples.add(sample);
				if (sample.electric()) {
					kilowattByVehicle.put(vehicle, sample.demandKilowatt() - sample.regeneratedKilowatt());
				}
			}
		});
		if (samples.isEmpty()) {
			reading = new Reading(ledger.live().idle(), Map.of());
			return;
		}
		ledger.tick(time, interval, samples);
		reading = new Reading(ledger.live(), kilowattByVehicle);
	}

	/** What the meter read at its last sample. */
	public Reading reading() {
		return reading;
	}

	private EnergyLedger.Sample sample(Trip trip, State state, double time) {
		double speed = state.speedAt(time);
		double acceleration = state.accelerationAt(time);
		double tonnes = trip.massKilograms() / 1000;
		if (trip.electric()) {
			EnergyModel.Power power = model.electricPower(trip.massKilograms(), trip.cars(), speed, acceleration);
			return new EnergyLedger.Sample(trip.id(), trip.line(), true, power.demandKilowatt(), power.regeneratedKilowatt(),
				0, tonnes, speed, state.position().getX(), state.position().getY());
		}
		return new EnergyLedger.Sample(trip.id(), trip.line(), false, 0, 0,
			model.dieselLitresPerHour(trip.massKilograms(), trip.cars(), speed, acceleration), tonnes, speed,
			state.position().getX(), state.position().getY());
	}

	/** The energy booked so far. */
	public EnergyLedger.Use use() {
		return ledger.use();
	}
}
