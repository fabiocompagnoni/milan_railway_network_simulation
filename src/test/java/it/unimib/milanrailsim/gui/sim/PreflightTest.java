package it.unimib.milanrailsim.gui.sim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreflightTest {

	@TempDir
	Path home;

	private Preflight preflight(long freeBytes) throws IOException {
		Path runs = Files.createDirectories(home.resolve("runs"));
		Path costs = Files.writeString(home.resolve("costs.json"), "{}");
		return new Preflight(runs, costs, machine(freeBytes));
	}

	private Preflight.Machine machine(long freeBytes) throws IOException {
		Path java = home.resolve("java");
		if (!Files.exists(java)) {
			Files.createFile(java);
		}
		return new Preflight.Machine(() -> freeBytes, java);
	}

	@Test
	void missingEngineLauncherBlocks() throws IOException {
		Path absent = home.resolve("runtime").resolve("bin").resolve("java");
		Preflight preflight = new Preflight(Files.createDirectories(home.resolve("runs")),
			Files.writeString(home.resolve("costs.json"), "{}"), new Preflight.Machine(() -> 10_000_000_000L, absent));

		List<Preflight.Finding> findings = preflight.check("baseline");

		assertEquals(1, findings.size());
		assertTrue(findings.getFirst().blocking());
		assertTrue(findings.getFirst().message().contains(absent.toString()));
	}

	@Test
	void cleanConfigurationPasses() throws IOException {
		List<Preflight.Finding> findings = preflight(10_000_000_000L).check("baseline");

		assertTrue(findings.isEmpty());
	}

	@Test
	void insufficientDiskSpaceBlocks() throws IOException {
		List<Preflight.Finding> findings = preflight(640_000_000L).check("baseline");

		assertEquals(1, findings.size());
		assertTrue(findings.getFirst().blocking());
		assertTrue(findings.getFirst().message().contains("640 MB"));
	}

	@Test
	void missingCostsOnlyWarns() throws IOException {
		Preflight preflight = new Preflight(Files.createDirectories(home.resolve("runs")),
			home.resolve("absent.json"), machine(10_000_000_000L));

		List<Preflight.Finding> findings = preflight.check("baseline");

		assertEquals(1, findings.size());
		assertFalse(findings.getFirst().blocking());
	}

	@Test
	void duplicateRunNameBlocks() throws IOException {
		Preflight preflight = preflight(10_000_000_000L);
		Files.createDirectories(home.resolve("runs").resolve("baseline"));

		List<Preflight.Finding> findings = preflight.check("baseline");

		assertTrue(findings.stream().anyMatch(f -> f.blocking() && f.message().contains("baseline")));
	}

	@Test
	void blankRunNameBlocks() throws IOException {
		assertTrue(preflight(10_000_000_000L).check("  ").getFirst().blocking());
	}
}
