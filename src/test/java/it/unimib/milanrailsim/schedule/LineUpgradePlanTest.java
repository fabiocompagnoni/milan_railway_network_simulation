package it.unimib.milanrailsim.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LineUpgradePlanTest {

	private static final Path COMMITTED = Path.of("data/scenarios/potenziamento-per-linea.json");

	@Test
	void readsTheCommittedScenario() {
		LineUpgradePlan plan = LineUpgradePlan.read(COMMITTED);

		assertEquals(3600, plan.serviceGapSeconds());
		assertEquals(8, plan.minTrips());
		assertEquals(4, plan.minTripsPerDirection());
		assertEquals(3, plan.minTargetMinutes());
		assertEquals(30, plan.maxTargetMinutes());
		assertEquals(15, plan.proposedTargetMinutes());
		assertEquals(new DensificationPlan.Tunnel("S01648", 180), plan.tunnel());
	}

	@Test
	void aMissingFieldIsNamed(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("plan.json");
		Files.writeString(file, Files.readString(COMMITTED).replace("\"minTripsPerDirection\"", "\"other\""));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LineUpgradePlan.read(file));

		assertTrue(error.getMessage().contains("minTripsPerDirection"), error.getMessage());
	}

	@Test
	void aProposedTargetOutsideTheRangeIsRefused(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("plan.json");
		Files.writeString(file, Files.readString(COMMITTED).replace("\"proposed\": 15", "\"proposed\": 40"));

		assertThrows(IllegalArgumentException.class, () -> LineUpgradePlan.read(file));
	}
}
