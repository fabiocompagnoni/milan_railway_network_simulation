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
		return new Preflight(runs, costs, () -> freeBytes);
	}

	@Test
	void cleanConfigurationPasses() throws IOException {
		List<Preflight.Finding> findings = preflight(10_000_000_000L).check("baseline", 1);

		assertTrue(findings.isEmpty());
	}

	@Test
	void insufficientDiskSpaceBlocks() throws IOException {
		List<Preflight.Finding> findings = preflight(640_000_000L).check("baseline", 1);

		assertEquals(1, findings.size());
		assertTrue(findings.getFirst().blocking());
		assertTrue(findings.getFirst().message().contains("640 MB"));
	}

	@Test
	void diskRequirementScalesWithRunCount() throws IOException {
		assertTrue(preflight(3_000_000_000L).check("campaign", 2).isEmpty());
		assertFalse(preflight(3_000_000_000L).check("campaign", 3).isEmpty());
	}

	@Test
	void missingCostsOnlyWarns() throws IOException {
		Preflight preflight = new Preflight(Files.createDirectories(home.resolve("runs")),
			home.resolve("absent.json"), () -> 10_000_000_000L);

		List<Preflight.Finding> findings = preflight.check("baseline", 1);

		assertEquals(1, findings.size());
		assertFalse(findings.getFirst().blocking());
	}

	@Test
	void duplicateRunNameBlocks() throws IOException {
		Preflight preflight = preflight(10_000_000_000L);
		Files.createDirectories(home.resolve("runs").resolve("baseline"));

		List<Preflight.Finding> findings = preflight.check("baseline", 1);

		assertTrue(findings.stream().anyMatch(f -> f.blocking() && f.message().contains("baseline")));
	}

	@Test
	void blankRunNameBlocks() throws IOException {
		assertTrue(preflight(10_000_000_000L).check("  ", 1).getFirst().blocking());
	}
}
