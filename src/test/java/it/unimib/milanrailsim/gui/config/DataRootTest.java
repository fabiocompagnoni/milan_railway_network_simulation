package it.unimib.milanrailsim.gui.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataRootTest {

	@AfterEach
	void clearProperty() {
		System.clearProperty(DataRoot.PROPERTY);
	}

	@Test
	void theWorkingDirectoryIsTheRootWhenNothingIsConfigured() {
		// the tests run from the project folder, which holds the scenario
		assertEquals(Path.of("").toAbsolutePath(), DataRoot.resolve());
	}

	@Test
	void theConfiguredFolderWinsWhenItHoldsTheData(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir.resolve("scenarios/milan"));
		Files.createDirectories(dir.resolve("data/nodes"));
		System.setProperty(DataRoot.PROPERTY, dir.toString());

		assertEquals(dir.toAbsolutePath().normalize(), DataRoot.resolve());
	}

	@Test
	void aFolderWithoutTheDataIsRefusedNamingWhatIsMissing(@TempDir Path dir) {
		System.setProperty(DataRoot.PROPERTY, dir.toString());

		IllegalStateException refused = assertThrows(IllegalStateException.class, DataRoot::resolve);
		assertTrue(refused.getMessage().contains(dir.resolve("scenarios").resolve("milan").toString()));
	}
}
