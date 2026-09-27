package it.unimib.milanrailsim.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** One folder per analyzed run: manifest, analysis files and charts. */
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

}
