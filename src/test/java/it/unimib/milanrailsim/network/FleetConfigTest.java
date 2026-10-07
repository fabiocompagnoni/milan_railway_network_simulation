package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FleetConfigTest {

	private static FleetConfig.TrainType custom(String id) {
		return new FleetConfig.TrainType(id, "Test", 80, 200, 120, 0.9, 0.5, FleetConfig.Traction.ELECTRIC,
			Map.of("seats", "test"), Set.of("length"));
	}

	@Test
	void defaultsCarryNineTypesWithEstimatedDeceleration() {
		FleetConfig fleet = FleetConfig.defaults();

		assertEquals(9, fleet.types().size());
		assertTrue(fleet.types().stream().allMatch(type -> type.isEstimated("deceleration")));
		assertFalse(fleet.type("caravaggio_521").isEstimated("acceleration"));
		assertEquals(FleetConfig.Traction.DIESEL, fleet.type("atr125").traction());
	}

	@Test
	void withReplacesOrAppends() {
		FleetConfig fleet = FleetConfig.defaults().with(custom("tsr")).with(custom("nuovo"));

		assertEquals(10, fleet.types().size());
		assertEquals("Test", fleet.type("tsr").name());
	}

	@Test
	void rejectsDuplicateIds() {
		assertThrows(IllegalArgumentException.class,
			() -> new FleetConfig(List.of(custom("a"), custom("a"))));
	}

	@Test
	void roundTripsThroughJson(@TempDir Path dir) {
		Path file = dir.resolve("types.json");
		FleetConfig.defaults().without("taf").write(file);

		assertEquals(FleetConfig.defaults().without("taf"), FleetConfig.read(file));
	}
}
