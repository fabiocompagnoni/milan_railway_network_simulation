package it.unimib.milanrailsim.gui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunLibraryTest {

	private static final String MANIFEST = """
		{"scenario": "reale", "created": "2026-09-12T10:00:00", "stopVisits": 1768, "anomalies": 3,
		 "meanArrivalDelaySeconds": 134.5, "totalCost": 48260.0, "currency": "EUR"}
		""";

	@TempDir
	Path runs;

	private Path run(String name) throws IOException {
		return Files.createDirectories(runs.resolve(name));
	}

	@Test
	void emptyOrMissingFolderYieldsNoRuns() {
		assertTrue(new RunLibrary(runs.resolve("absent")).scan().isEmpty());
		assertTrue(new RunLibrary(runs).scan().isEmpty());
	}

	@Test
	void completedRunCarriesItsManifestAndSpec() throws IOException {
		Path dir = run("reale_2026-09-16");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST);
		ScenarioSpec.real("reale_2026-09-16", LocalDate.of(2026, 9, 16), null).write(dir.resolve("scenario.json"));

		RunLibrary.Entry entry = new RunLibrary(runs).scan().getFirst();

		assertEquals(RunLibrary.Status.COMPLETED, entry.status());
		assertEquals(3, entry.manifest().orElseThrow().anomalies());
		assertEquals("Reale", entry.scenarioLabel());
	}

	@Test
	void freshMarkerMeansRunningStaleMeansInterrupted() throws IOException {
		Path running = run("a");
		Files.writeString(running.resolve(RunLibrary.PROGRESS_MARKER), "");
		Path dead = run("b");
		Path marker = Files.writeString(dead.resolve(RunLibrary.PROGRESS_MARKER), "");
		Files.setLastModifiedTime(marker, FileTime.from(Instant.now().minus(RunLibrary.STALE_AFTER.multipliedBy(2))));

		RunLibrary library = new RunLibrary(runs);

		assertEquals(RunLibrary.Status.RUNNING, library.entry(running).status());
		assertEquals(RunLibrary.Status.INTERRUPTED, library.entry(dead).status());
	}

	@Test
	void launchedRunWithoutMarkerOrManifestIsInterrupted() throws IOException {
		Path dir = run("c");
		ScenarioSpec.real("c", LocalDate.of(2026, 9, 16), null).write(dir.resolve("scenario.json"));

		assertEquals(RunLibrary.Status.INTERRUPTED, new RunLibrary(runs).entry(dir).status());
	}

	@Test
	void corruptManifestIsUnreadable() throws IOException {
		Path dir = run("d");
		Files.writeString(dir.resolve("manifest.json"), "{not json");

		assertEquals(RunLibrary.Status.UNREADABLE, new RunLibrary(runs).entry(dir).status());
	}

	@Test
	void exportCopiesOnlyTheSelectedFiles(@TempDir Path destination) throws IOException {
		Path dir = run("e");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST);
		Files.writeString(dir.resolve("punctuality.csv"), "a,b");
		Files.createDirectories(dir.resolve("charts"));
		Files.writeString(dir.resolve("charts").resolve("x.png"), "png");
		RunLibrary library = new RunLibrary(runs);

		int copied = library.export(library.entry(dir), destination, path -> path.toString().endsWith(".csv"));

		assertEquals(1, copied);
		assertTrue(Files.exists(destination.resolve("e").resolve("punctuality.csv")));
		assertFalse(Files.exists(destination.resolve("e").resolve("manifest.json")));
	}

	@Test
	void deleteRemovesTheWholeFolder() throws IOException {
		Path dir = run("f");
		Files.createDirectories(dir.resolve("raw"));
		Files.writeString(dir.resolve("raw").resolve("events.xml"), "x");
		RunLibrary library = new RunLibrary(runs);

		library.delete(library.entry(dir));

		assertFalse(Files.exists(dir));
		assertEquals(List.of(), library.scan());
	}
}
