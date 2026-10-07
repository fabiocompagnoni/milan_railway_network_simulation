package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.network.RailVehicleTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.VehicleType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CostModelTest {

	private TestRuns.Fixture fixture;

	@BeforeEach
	void tenKilometreRoute() {
		fixture = TestRuns.tenKilometreFixture();
	}

	private CostParameters parameters(Path dir) throws IOException {
		Path file = dir.resolve("costs.json");
		Files.writeString(file, """
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": "test"},
				"electricity": {"unitCost": 2.0, "unit": "train_km", "source": "test"},
				"diesel": {"unitCost": 3.0, "unit": "train_km", "source": "test"},
				"maintenance": {"unitCost": 1.0, "unit": "train_km", "source": "test"},
				"rolling_stock": {"unitCost": 650.0, "unit": "train_day", "source": "test"},
				"track_access": {"unitCost": 2.5, "unit": "train_km", "source": "test"},
				"traction_energy": {"unitCost": 0.1, "unit": "kwh", "source": "test"},
				"diesel_fuel": {"unitCost": 1.0, "unit": "litre", "source": "test"}
			}}""");
		return CostParameters.load(file);
	}

	/** Two electric trips and a diesel one; the traction is an attribute of the vehicle type, as the fleet writes it. */
	private void threeTrips() {
		TestRuns.addTrip(fixture, "t1", "tsr", 8 * 3600);
		TestRuns.addTrip(fixture, "t2", "tsr", 9 * 3600);
		TestRuns.addTrip(fixture, "t3", "atr803", 8 * 3600);
		fixture.vehicles().getVehicleTypes().get(Id.create("atr803", VehicleType.class))
			.getAttributes().putAttribute(RailVehicleTypes.TRACTION_ATTRIBUTE, RailVehicleTypes.DIESEL);
	}

	private static PunctualityAnalysis.TripOutcome outcome(String trip, double departure, double delay,
			PunctualityAnalysis.TripStatus status) {
		return new PunctualityAnalysis.TripOutcome(trip, "S1", "S1_1", trip, "A", "B", departure, departure + 600,
			status == PunctualityAnalysis.TripStatus.COMPLETED ? departure + 600 + delay : Double.NaN, 2, 2, status);
	}

	@Test
	void simulatedCostsCountDelaysAndTheEnergyMeasuredAndChargeTripsNotRunAtTheirTimetableValue(@TempDir Path dir)
			throws IOException {
		threeTrips();
		List<PunctualityAnalysis.TripOutcome> trips = List.of(
			outcome("t1", 8 * 3600, 300, PunctualityAnalysis.TripStatus.COMPLETED),
			outcome("t2", 9 * 3600, 0, PunctualityAnalysis.TripStatus.NEVER_DEPARTED),
			outcome("t3", 8 * 3600, 0, PunctualityAnalysis.TripStatus.COMPLETED));
		Map<String, EnergyLedger.TripEnergy> energy = Map.of(
			"t1", new EnergyLedger.TripEnergy("S1", true, 100, 0, 0, 0, 0, 0, 10, 2000),
			"t3", new EnergyLedger.TripEnergy("S1", false, 0, 0, 0, 0, 0, 20, 10, 1400));

		CostModel.Breakdown breakdown = new CostModel(fixture.schedule(), fixture.vehicles(),
			fixture.network(), parameters(dir)).computeSimulated(trips, energy).orElseThrow();
		Map<String, Double> costs = breakdown.byCategory();

		// t1 ran 600 + 300 s, t2 is charged its 600 s, t3 ran 600 s
		assertEquals(2100 / 3600.0 * 140.0, costs.get("staff"), 1e-6);
		// t1 drew 100 kWh; t2 did not run and is charged the 10 kWh/km of the trips that did, over its 10 km
		assertEquals((100 + 100) * 0.1, costs.get("electricity"), 1e-6);
		assertEquals(20 * 1.0, costs.get("diesel"), 1e-6);
		assertEquals(30 * 1.0, costs.get("maintenance"), 1e-6);
		assertEquals(30 * 2.5, costs.get("track_access"), 1e-6);
		assertEquals(3 * 650.0, costs.get("rolling_stock"), 1e-6, "one daily cost per train the timetable uses");
		assertEquals(3, breakdown.fleetSize());
		assertEquals(2100 / 3600.0, breakdown.trainHours(), 1e-9);
	}

	@Test
	void simulatedCostsNeedTheEnergyPrices(@TempDir Path dir) throws IOException {
		threeTrips();
		Path file = dir.resolve("old.json");
		Files.writeString(file, """
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": "test"}
			}}""");

		assertTrue(new CostModel(fixture.schedule(), fixture.vehicles(), fixture.network(), CostParameters.load(file))
			.computeSimulated(List.of(), Map.of()).isEmpty(), "a cost file written before the energy prices existed");
	}

	@Test
	void computesCategoryCostsFromScheduleQuantities(@TempDir Path dir) throws IOException {
		threeTrips();

		CostModel.Breakdown breakdown = new CostModel(fixture.schedule(), fixture.vehicles(),
			fixture.network(), parameters(dir)).compute();
		Map<String, Double> costs = breakdown.byCategory();

		// 3 trips x 10 km; electric 20 km, diesel 10 km; 3 x 600 s = 0.5 h
		assertEquals(0.5 * 140.0, costs.get("staff"), 1e-6);
		assertEquals(20 * 2.0, costs.get("electricity"), 1e-6);
		assertEquals(10 * 3.0, costs.get("diesel"), 1e-6);
		assertEquals(30 * 1.0, costs.get("maintenance"), 1e-6);
		assertEquals(30 * 2.5, costs.get("track_access"), 1e-6);
		// t1/t3 overlap (2 concurrent trains), t2 alone: fleet of 2
		assertEquals(2 * 650.0, costs.get("rolling_stock"), 1e-6);
		assertEquals(30.0, breakdown.trainKm(), 1e-6);
		assertEquals("EUR", breakdown.currency());
	}

	@Test
	void refusesUnsetCostParameters(@TempDir Path dir) throws IOException {
		TestRuns.addTrip(fixture, "t1", "tsr", 8 * 3600);
		Path file = dir.resolve("costs.json");
		Files.writeString(file, """
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 0.0, "unit": "train_hour", "source": "da stimare"}
			}}""");

		CostModel model = new CostModel(fixture.schedule(), fixture.vehicles(),
			fixture.network(), CostParameters.load(file));

		assertThrows(IllegalArgumentException.class, model::compute);
	}
}
