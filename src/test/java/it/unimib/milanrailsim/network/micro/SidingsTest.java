package it.unimib.milanrailsim.network.micro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SidingsTest {

	@Test
	void readsTheThreshold(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sidings.json");
		Files.writeString(file, """
			{"longLayoverThresholdMin": 60, "locations": [{"station": "A", "tracks": 8}]}
			""");

		assertEquals(3600, Sidings.read(file).maxLayoverSeconds());
	}

	@Test
	void rejectsFileWithoutThreshold(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sidings.json");
		Files.writeString(file, "{\"locations\": []}");

		assertThrows(IllegalArgumentException.class, () -> Sidings.read(file));
	}

	@Test
	void withoutSidingsEveryLayoverStaysOnThePlatform() {
		assertEquals(Double.POSITIVE_INFINITY, Sidings.none().maxLayoverSeconds());
	}

	@Test
	void projectSidingsBoundLayoversToOneHour() {
		Path file = Path.of("data", "nodes", "sidings.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file));

		assertEquals(3600, Sidings.read(file).maxLayoverSeconds());
	}
}
