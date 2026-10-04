package it.unimib.milanrailsim.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The energy a train draws, from its motion: the power at the wheels is the
 * force that accelerates the train and overcomes the running resistance,
 * times the speed. railsim has no consumption model; this one is applied to
 * the speed and acceleration it simulates.
 * <p>
 * An electric train draws the wheel power over the traction efficiency, plus
 * its auxiliaries; when braking it returns the braking power less the losses.
 * A diesel train burns fuel for the wheel power and the auxiliaries and
 * recovers nothing. Parameters and their sources are in
 * {@code data/scenarios/consumi-energetici.json}; line gradients and curves
 * are not modelled.
 *
 * @param resistanceConstant  running resistance at standstill, N per kN of weight
 * @param resistanceQuadratic growth of the running resistance, N per kN per (km/h)²
 * @param rotatingMassFactor  inertia of the rotating masses, as a factor on the mass
 * @param dieselCalibration   factor on the fuel burnt, fitted on a measured consumption: railsim drives
 *                            every train at full acceleration and full braking, with no coasting, and a
 *                            diesel train recovers none of the energy that costs
 * @param loads               share of the seats taken, by kind of day and hour
 * @param trains              mass and cars of each vehicle type, by type id
 */
public record EnergyModel(double resistanceConstant, double resistanceQuadratic, double rotatingMassFactor,
		double tractionEfficiency, double recoveryEfficiency, double recoveryRadiusMetres, double auxiliaryKilowattPerCar,
		double dieselEngineEfficiency, double dieselTransmissionEfficiency, double dieselKilowattHoursPerLitre,
		double dieselCalibration, double kilogramsPerPassenger, Loads loads, Map<String, Train> trains) {

	private static final double GRAVITY = 9.80665;

	/** @param emptyMassTonnes mass in running order, without passengers */
	public record Train(double emptyMassTonnes, int cars) {
	}

	/** A window of the day, end excluded. */
	public record Hours(LocalTime from, LocalTime to) {

		boolean contains(int secondOfDay) {
			return secondOfDay >= from.toSecondOfDay() && secondOfDay < to.toSecondOfDay();
		}
	}

	/**
	 * Share of the seats taken; {@code earlyAndLate} applies before
	 * {@code earlyBefore} and after {@code lateAfter} on every day.
	 */
	public record Loads(double weekdayPeak, double weekdayOffPeak, double saturday, double sundayAndHolidays,
			double earlyAndLate, List<Hours> weekdayPeaks, LocalTime earlyBefore, LocalTime lateAfter) {
	}

	/**
	 * What an electric train exchanges with the line at one moment.
	 *
	 * @param demandKilowatt      drawn for traction and auxiliaries
	 * @param regeneratedKilowatt returned by the brakes, before anyone uses it
	 */
	public record Power(double demandKilowatt, double regeneratedKilowatt) {
	}

	public Optional<Train> train(String typeId) {
		return Optional.ofNullable(trains.get(typeId));
	}

	/**
	 * Share of the seats taken on a trip.
	 *
	 * @param plannedDepartureSeconds seconds since midnight of the service day; past 24 h for trips after midnight
	 */
	public double load(LocalDate serviceDay, int plannedDepartureSeconds) {
		int secondOfDay = plannedDepartureSeconds % (24 * 3600);
		boolean pastMidnight = plannedDepartureSeconds >= 24 * 3600;
		if (pastMidnight || secondOfDay < loads.earlyBefore().toSecondOfDay()
				|| secondOfDay > loads.lateAfter().toSecondOfDay()) {
			return loads.earlyAndLate();
		}
		if (serviceDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
			return loads.sundayAndHolidays();
		}
		if (serviceDay.getDayOfWeek() == DayOfWeek.SATURDAY) {
			return loads.saturday();
		}
		return loads.weekdayPeaks().stream().anyMatch(peak -> peak.contains(secondOfDay))
			? loads.weekdayPeak() : loads.weekdayOffPeak();
	}

	/** Mass of a train with the given share of its seats taken. */
	public double massKilograms(Train train, int seats, double load) {
		return train.emptyMassTonnes() * 1000 + load * seats * kilogramsPerPassenger;
	}

	/** Running resistance on level, straight track (binomial formula, cf. Dalla Chiara et al., Ingegneria Ferroviaria 4/2015). */
	public double resistanceNewton(double massKilograms, double speedMetresPerSecond) {
		double kilometresPerHour = speedMetresPerSecond * 3.6;
		double newtonPerKilonewton = resistanceConstant + resistanceQuadratic * kilometresPerHour * kilometresPerHour;
		return newtonPerKilonewton * massKilograms * GRAVITY / 1000;
	}

	public Power electricPower(double massKilograms, int cars, double speed, double acceleration) {
		double wheelKilowatt = wheelKilowatt(massKilograms, speed, acceleration);
		double auxiliaries = cars * auxiliaryKilowattPerCar;
		return wheelKilowatt >= 0
			? new Power(wheelKilowatt / tractionEfficiency + auxiliaries, 0)
			: new Power(auxiliaries, -wheelKilowatt * recoveryEfficiency);
	}

	public double dieselLitresPerHour(double massKilograms, int cars, double speed, double acceleration) {
		double wheelKilowatt = Math.max(0, wheelKilowatt(massKilograms, speed, acceleration));
		double engineKilowatt = wheelKilowatt / dieselTransmissionEfficiency + cars * auxiliaryKilowattPerCar;
		return dieselCalibration * engineKilowatt / dieselEngineEfficiency / dieselKilowattHoursPerLitre;
	}

	/** Power at the wheels: positive when the train is pulled, negative when its brakes hold it. */
	private double wheelKilowatt(double massKilograms, double speed, double acceleration) {
		if (speed <= 0) {
			return 0;
		}
		double force = massKilograms * rotatingMassFactor * acceleration + resistanceNewton(massKilograms, speed);
		return force * speed / 1000;
	}

	/** @throws IllegalArgumentException when a parameter is missing, naming it */
	public static EnergyModel read(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(file.toFile());
			JsonNode resistance = required(root, "resistance", file);
			JsonNode recovery = required(root, "recovery", file);
			JsonNode diesel = required(root, "diesel", file);
			JsonNode passengers = required(root, "passengers", file);
			JsonNode load = required(passengers, "load", file);
			List<Hours> peaks = new ArrayList<>();
			for (JsonNode peak : required(passengers, "weekdayPeaks", file)) {
				peaks.add(new Hours(LocalTime.parse(peak.get(0).asText()), LocalTime.parse(peak.get(1).asText())));
			}
			Map<String, Train> trains = new LinkedHashMap<>();
			required(root, "trains", file).properties().forEach(entry -> trains.put(entry.getKey(), new Train(
				required(entry.getValue(), "emptyMassTonnes", file).asDouble(), required(entry.getValue(), "cars", file).asInt())));
			return new EnergyModel(required(resistance, "constantNewtonPerKilonewton", file).asDouble(),
				required(resistance, "quadraticNewtonPerKilonewtonPerKmh2", file).asDouble(),
				required(root, "rotatingMassFactor", file).asDouble(), required(root, "tractionEfficiency", file).asDouble(),
				required(recovery, "efficiency", file).asDouble(), required(recovery, "radiusMetres", file).asDouble(),
				required(root, "auxiliaryKilowattPerCar", file).asDouble(),
				required(diesel, "engineEfficiency", file).asDouble(), required(diesel, "transmissionEfficiency", file).asDouble(),
				required(diesel, "kilowattHoursPerLitre", file).asDouble(), required(diesel, "calibration", file).asDouble(),
				required(passengers, "kilogramsPerPassenger", file).asDouble(),
				new Loads(required(load, "weekdayPeak", file).asDouble(), required(load, "weekdayOffPeak", file).asDouble(),
					required(load, "saturday", file).asDouble(), required(load, "sundayAndHolidays", file).asDouble(),
					required(load, "earlyAndLate", file).asDouble(), List.copyOf(peaks),
					LocalTime.parse(required(passengers, "earlyBefore", file).asText()),
					LocalTime.parse(required(passengers, "lateAfter", file).asText())),
				Map.copyOf(trains));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read energy model " + file, e);
		}
	}

	private static JsonNode required(JsonNode node, String field, Path file) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw new IllegalArgumentException("Energy model " + file + " lacks " + field);
		}
		return value;
	}
}
