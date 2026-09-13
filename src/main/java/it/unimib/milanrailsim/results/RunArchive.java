package it.unimib.milanrailsim.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Stream;

/** One folder per analyzed run: manifest, analysis files, charts and raw copies. */
public final class RunArchive {

	private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

	private final Path runDir;

	private RunArchive(Path runDir) {
		this.runDir = runDir;
	}

	public static RunArchive create(Path baseDir, String scenario) {
		return at(baseDir.resolve(LocalDateTime.now().format(RUN_STAMP) + "-" + scenario));
	}

	/** Archives into an existing run folder, e.g. one the application launched the run from. */
	public static RunArchive at(Path runDir) {
		try {
			Files.createDirectories(runDir.resolve("charts"));
			Files.createDirectories(runDir.resolve("raw"));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create run archive " + runDir, e);
		}
		return new RunArchive(runDir);
	}

	public Path dir() {
		return runDir;
	}

	public Path chart(String name) {
		return runDir.resolve("charts/" + name + ".png");
	}

	public void writeJson(String fileName, Map<String, Object> content) {
		try {
			new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
				.writeValue(runDir.resolve(fileName).toFile(), content);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + fileName + " in " + runDir, e);
		}
	}

	public void writeLines(String fileName, Iterable<String> lines) {
		try {
			Files.write(runDir.resolve(fileName), lines);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write " + fileName + " in " + runDir, e);
		}
	}

	/** Copies the simulation outputs (root artifacts and iteration files) into raw/. */
	public void copyRaw(Path sourceRunDir) {
		copyMatching(sourceRunDir, "*.output_*");
		copyMatching(sourceRunDir.resolve("ITERS/it.0"), "*");
	}

	private void copyMatching(Path sourceDir, String glob) {
		if (!Files.isDirectory(sourceDir)) {
			return;
		}
		try (Stream<Path> files = Files.list(sourceDir)) {
			for (Path file : files.filter(Files::isRegularFile)
					.filter(file -> sourceDir.getFileSystem()
						.getPathMatcher("glob:" + glob).matches(file.getFileName()))
					.toList()) {
				Files.copy(file, runDir.resolve("raw").resolve(file.getFileName()),
					StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot copy raw files from " + sourceDir, e);
		}
	}
}
