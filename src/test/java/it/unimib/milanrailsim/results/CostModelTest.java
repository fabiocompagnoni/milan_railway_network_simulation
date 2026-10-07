package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
				"track_access": {"unitCost": 2.5, "unit": "train_km", "source": "test"}
			}}""");
		return CostParameters.load(file);
	}

	@Test
	void computesCategoryCostsFromScheduleQuantities(@TempDir Path dir) throws IOException {
		TestRuns.addTrip(fixture, "t1", "tsr", 8 * 3600);
		TestRuns.addTrip(fixture, "t2", "tsr", 9 * 3600);
		TestRuns.addTrip(fixture, "t3", "atr125", 8 * 3600);

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
