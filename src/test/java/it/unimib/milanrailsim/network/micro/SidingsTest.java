package it.unimib.milanrailsim.network.micro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SidingsTest {

	@Test
	void readsThresholdAndStations(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sidings.json");
		Files.writeString(file, """
			{"longLayoverThresholdMin": 60, "locations": [{"station": "A", "tracks": 8}, {"station": "B", "tracks": null}]}
			""");

		Sidings sidings = Sidings.read(file);

		assertEquals(Set.of("A", "B"), sidings.stations());
		assertEquals(3600, sidings.maxLayoverSeconds("A"));
		assertEquals(Double.POSITIVE_INFINITY, sidings.maxLayoverSeconds("C"));
	}

	@Test
	void rejectsFileWithoutThreshold(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sidings.json");
		Files.writeString(file, "{\"locations\": []}");

		assertThrows(IllegalArgumentException.class, () -> Sidings.read(file));
	}

	@Test
	void projectSidingsCoverCadorna() {
		Path file = Path.of("data", "nodes", "sidings.json");
		assumeTrue(Files.exists(file));

		Sidings sidings = Sidings.read(file);

		assertEquals(3600, sidings.maxLayoverSeconds("S01066"));
		assertEquals(Double.POSITIVE_INFINITY, Sidings.none().maxLayoverSeconds("S01066"));
	}

	private static void assumeTrue(boolean condition) {
		org.junit.jupiter.api.Assumptions.assumeTrue(condition);
	}
}
