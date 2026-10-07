package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergyModelTest {

	private static final EnergyModel MODEL = EnergyModel.read(Path.of("data/scenarios/consumi-energetici.json"));
	private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
	private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 3);
	private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);

	private static int at(int hour, int minute) {
		return hour * 3600 + minute * 60;
	}

	@Test
	void readsTheProjectParameters() {
		assertEquals(0.80, MODEL.tractionEfficiency());
		assertEquals(10_000, MODEL.recoveryRadiusMetres());
		assertEquals(325, MODEL.train("caravaggio_521").orElseThrow().emptyMassTonnes());
		assertEquals(5, MODEL.train("caravaggio_521").orElseThrow().cars());
		assertTrue(MODEL.train("etr245").isEmpty(), "a type without a mass has no consumption");
	}

	@Test
	void seatsFillByDayAndPlannedDeparture() {
		assertEquals(1.0, MODEL.load(MONDAY, at(6, 30)));
		assertEquals(1.0, MODEL.load(MONDAY, at(17, 0)));
		assertEquals(0.5, MODEL.load(MONDAY, at(9, 30)), "the peak ends at 9:30");
		assertEquals(0.5, MODEL.load(MONDAY, at(6, 10)));
		assertEquals(0.2, MODEL.load(MONDAY, at(5, 59)));
		assertEquals(0.2, MODEL.load(MONDAY, at(21, 1)));
		assertEquals(0.5, MODEL.load(SATURDAY, at(8, 0)), "no peak on Saturday");
		assertEquals(0.4, MODEL.load(SUNDAY, at(8, 0)));
		assertEquals(0.2, MODEL.load(SUNDAY, at(22, 0)), "early and late hours count on every day");
		assertEquals(0.2, MODEL.load(MONDAY, at(24, 30)), "past midnight of the service day");
	}

	@Test
	void resistanceGrowsWithTheSquareOfSpeed() {
		// 200 t at standstill: 2.4 N/kN of a weight of 1961.33 kN
		assertEquals(2.4 * 200 * 9.80665, MODEL.resistanceNewton(200_000, 0), 1e-6);
		// at 100 km/h: 2.4 + 0.00077 * 100^2 = 10.1 N/kN
		assertEquals(10.1 * 200 * 9.80665, MODEL.resistanceNewton(200_000, 100 / 3.6), 1e-6);
	}

	@Test
	void anElectricTrainDrawsItsWheelPowerOverTheEfficiencyPlusTheAuxiliaries() {
		// 200 t accelerating at 0.5 m/s^2 at 20 m/s: inertia 200000 * 1.06 * 0.5 = 106000 N, resistance at 72 km/h
		double resistance = MODEL.resistanceNewton(200_000, 20);
		EnergyModel.Power power = MODEL.electricPower(200_000, 4, 20, 0.5);

		assertEquals(((106_000 + resistance) * 20 / 0.80 + 4 * 30_000) / 1000, power.demandKilowatt(), 1e-6);
		assertEquals(0, power.regeneratedKilowatt());
	}

	@Test
	void aBrakingElectricTrainGivesBackWhatItsBrakesHoldLessTheLosses() {
		double resistance = MODEL.resistanceNewton(200_000, 20);
		EnergyModel.Power power = MODEL.electricPower(200_000, 4, 20, -0.5);

		// the brakes hold the inertia less what resistance already takes
		assertEquals((106_000 - resistance) * 20 * 0.80 / 1000, power.regeneratedKilowatt(), 1e-6);
		assertEquals(120, power.demandKilowatt(), 1e-6, "only the auxiliaries");
	}

	@Test
	void aStandingTrainDrawsOnlyItsAuxiliaries() {
		EnergyModel.Power power = MODEL.electricPower(200_000, 4, 0, 0);

		assertEquals(120, power.demandKilowatt(), 1e-9);
		assertEquals(0, power.regeneratedKilowatt());
	}

	@Test
	void aDieselTrainBurnsLitresForItsWheelPowerAndAuxiliaries() {
		double resistance = MODEL.resistanceNewton(136_000, 20);
		double wheelKilowatt = (136_000 * 1.06 * 0.3 + resistance) * 20 / 1000;

		// the calibration factor scales the whole fuel figure to the measured consumption of the ATR 125
		assertEquals(0.41 * (wheelKilowatt / 0.76 + 4 * 30) / 0.40 / 10, MODEL.dieselLitresPerHour(136_000, 4, 20, 0.3), 1e-6);
		assertEquals(0.41 * 4 * 30 / 0.40 / 10, MODEL.dieselLitresPerHour(136_000, 4, 20, -0.5), 1e-9,
			"braking burns for the auxiliaries only");
	}
}
