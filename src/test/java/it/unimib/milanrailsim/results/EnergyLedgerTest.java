package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.EnergyLedger.Sample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyLedgerTest {

	private static final double RADIUS = 10_000;
	/** One hour, so kilowatts read as kilowatt-hours. */
	private static final double HOUR = 3600;

	private static Sample electric(String trip, double demand, double regenerated, double x) {
		return new Sample(trip, "S1", true, demand, regenerated, 0, 200, 20, x, 0);
	}

	@Test
	void aTrainInTractionDrawsItsDemandFromTheLine() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		ledger.tick(0, HOUR, List.of(electric("t1", 1000, 0, 0)));

		EnergyLedger.TripEnergy trip = ledger.use().byTrip().get("t1");
		assertEquals(1000, trip.drawnKilowattHours());
		assertEquals(0, trip.regeneratedKilowattHours());
		assertEquals(72, trip.kilometres(), 1e-9, "20 m/s for an hour");
		assertEquals(72 * 200, trip.tonneKilometres(), 1e-9);
	}

	@Test
	void brakingEnergyFeedsTheTrainsOwnAuxiliariesFirst() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		ledger.tick(0, HOUR, List.of(electric("t1", 120, 500, 0)));

		EnergyLedger.TripEnergy trip = ledger.use().byTrip().get("t1");
		assertEquals(0, trip.drawnKilowattHours(), "the auxiliaries run on the braking energy");
		assertEquals(500, trip.regeneratedKilowattHours());
		assertEquals(380, trip.lostKilowattHours(), "nobody near to take the rest");
	}

	@Test
	void theRestGoesToTrainsInTractionWithinTheRadiusNearestFirst() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		ledger.tick(0, HOUR, List.of(
			electric("braking", 120, 500, 0),
			electric("far", 1000, 0, 12_000),
			electric("near", 100, 0, 2_000),
			electric("middle", 1000, 0, 6_000)));

		EnergyLedger.Use use = ledger.use();
		assertEquals(0, use.byTrip().get("near").drawnKilowattHours(), "wholly fed by the braking train");
		assertEquals(100, use.byTrip().get("near").receivedKilowattHours());
		assertEquals(720, use.byTrip().get("middle").drawnKilowattHours(), "1000 less the 280 left over");
		assertEquals(1000, use.byTrip().get("far").drawnKilowattHours(), "beyond the radius");
		assertEquals(0, use.byTrip().get("braking").lostKilowattHours());
		assertEquals(380, use.byTrip().get("braking").givenKilowattHours());
	}

	@Test
	void dieselTrainsBurnFuelAndTakeNoPartInTheRecovery() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		ledger.tick(0, HOUR, List.of(
			electric("braking", 0, 500, 0),
			new Sample("diesel", "S7", false, 0, 0, 80, 150, 20, 100, 0)));

		assertEquals(80, ledger.use().byTrip().get("diesel").litres());
		assertEquals(0, ledger.use().byTrip().get("diesel").receivedKilowattHours());
		assertEquals(500, ledger.use().byTrip().get("braking").lostKilowattHours());
	}

	@Test
	void thePowerDrawnFromTheLineIsAveragedByMinute() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		// 8:00:00 to 8:00:30 at 1000 kW, 8:00:30 to 8:01:00 at 3000 kW, then 8:01:00 to 8:01:30 at 600 kW
		ledger.tick(8 * 3600, 30, List.of(electric("t1", 1000, 0, 0)));
		ledger.tick(8 * 3600 + 30, 30, List.of(electric("t1", 3000, 0, 0)));
		ledger.tick(8 * 3600 + 60, 30, List.of(electric("t1", 600, 0, 0)));

		List<EnergyLedger.Minute> profile = ledger.use().profile();
		assertEquals(2, profile.size());
		assertEquals(8 * 60, profile.get(0).minuteOfDay());
		assertEquals(2000, profile.get(0).lineKilowatt(), 1e-9, "mean over the full minute");
		assertEquals(300, profile.get(1).lineKilowatt(), 1e-9, "600 kW for half of the minute");
		assertEquals(1, profile.get(0).trainsInService());
		assertEquals(2000, ledger.use().peak().orElseThrow().lineKilowatt(), 1e-9);
	}

	@Test
	void theLiveFiguresAreThePowerOfTheLastIntervalAndTheEnergySoFar() {
		EnergyLedger ledger = new EnergyLedger(RADIUS);

		ledger.tick(0, HOUR, List.of(electric("t1", 1000, 0, 0)));
		ledger.tick(HOUR, HOUR, List.of(electric("t1", 400, 0, 0)));

		assertEquals(400, ledger.live().lineKilowatt(), 1e-9);
		assertEquals(1400, ledger.live().drawnKilowattHours(), 1e-9);
		assertEquals(0, ledger.live().idle().lineKilowatt());
		assertEquals(1400, ledger.live().idle().drawnKilowattHours(), 1e-9);
	}
}
