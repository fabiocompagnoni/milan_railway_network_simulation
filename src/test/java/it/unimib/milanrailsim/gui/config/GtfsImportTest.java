package it.unimib.milanrailsim.gui.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GtfsImportTest {

	private static final Path MINIMAL = Path.of("src/test/resources/gtfs-minimal");

	@TempDir
	Path dir;

	@Test
	void inspectsAValidFeed() {
		GtfsImport.FeedInfo info = GtfsImport.inspect(MINIMAL);

		assertEquals(LocalDate.of(2026, 9, 16), info.firstDay());
		assertEquals(LocalDate.of(2026, 9, 17), info.lastDay());
		assertEquals(2, info.routes());
	}

	@Test
	void namesTheMissingFile() throws IOException {
		Path broken = Files.createDirectory(dir.resolve("broken"));
		Files.copy(MINIMAL.resolve("stops.txt"), broken.resolve("stops.txt"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GtfsImport.inspect(broken));

		assertTrue(error.getMessage().contains("routes.txt"));
	}

	@Test
	void installsAZipReplacingThePreviousFeed() throws IOException {
		Path zip = dir.resolve("feed.zip");
		try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipped = new ZipOutputStream(out);
				Stream<Path> files = Files.list(MINIMAL)) {
			for (Path file : files.toList()) {
				zipped.putNextEntry(new ZipEntry("feed/" + file.getFileName()));
				zipped.write(Files.readAllBytes(file));
				zipped.closeEntry();
			}
		}
		Path target = Files.createDirectories(dir.resolve("gtfs"));
		Files.writeString(target.resolve("stale.txt"), "old");

		GtfsImport.install(zip, target);

		assertTrue(Files.exists(target.resolve("stop_times.txt")));
		assertFalse(Files.exists(target.resolve("stale.txt")));
		assertEquals(2, GtfsImport.inspect(target).routes());
	}

	@Test
	void invalidZipLeavesTheTargetUntouched() throws IOException {
		Path zip = dir.resolve("empty.zip");
		try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipped = new ZipOutputStream(out)) {
			zipped.putNextEntry(new ZipEntry("readme.txt"));
			zipped.closeEntry();
		}
		Path target = Files.createDirectories(dir.resolve("gtfs"));
		Files.writeString(target.resolve("keep.txt"), "old");

		assertThrows(IllegalArgumentException.class, () -> GtfsImport.install(zip, target));
		assertTrue(Files.exists(target.resolve("keep.txt")));
	}
}
