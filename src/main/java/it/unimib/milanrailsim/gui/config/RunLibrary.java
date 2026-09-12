package it.unimib.milanrailsim.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The archived runs under one folder. A run is a sub-folder holding the
 * {@code scenario.json} it was launched with; the engine adds a progress
 * marker while it works and the analysis writes {@code manifest.json} when done.
 */
public final class RunLibrary {

	/** Written by the engine and refreshed while it runs; a stale marker means the process died. */
	public static final String PROGRESS_MARKER = "in-progress";
	static final Duration STALE_AFTER = Duration.ofMinutes(2);

	public enum Status {
		RUNNING("In corso"), COMPLETED("Completato"), INTERRUPTED("Interrotto"), UNREADABLE("Illeggibile");

		private final String label;

		Status(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** The headline figures the analysis writes for a completed run. */
	public record Manifest(String scenario, LocalDateTime created, int stopVisits, int anomalies,
			double meanArrivalDelaySeconds, double totalCost, String currency) {
	}

	/** {@code spec} is empty for runs archived from the command line; {@code manifest} until the analysis ran. */
	public record Entry(String name, Path dir, Status status, Optional<ScenarioSpec> spec, Optional<Manifest> manifest) {

		public String scenarioLabel() {
			return spec.map(s -> s.type().label()).or(() -> manifest.map(Manifest::scenario)).orElse("—");
		}
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Path runsDir;

	public RunLibrary(Path runsDir) {
		this.runsDir = runsDir;
	}

	public Path dir() {
		return runsDir;
	}

	/** Newest first; folders that are neither a launched nor an archived run are reported unreadable. */
	public List<Entry> scan() {
		if (!Files.isDirectory(runsDir)) {
			return List.of();
		}
		List<Entry> entries = new ArrayList<>();
		try (Stream<Path> folders = Files.list(runsDir)) {
			for (Path folder : folders.filter(Files::isDirectory).toList()) {
				entries.add(entry(folder));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list " + runsDir, e);
		}
		entries.sort(Comparator.comparing(RunLibrary::created).reversed());
		return List.copyOf(entries);
	}

	public Entry entry(Path folder) {
		Optional<ScenarioSpec> spec = read(folder.resolve("scenario.json"), ScenarioSpec::read);
		Optional<Manifest> manifest = read(folder.resolve("manifest.json"), RunLibrary::readManifest);
		Status status;
		if (manifest.isPresent()) {
			status = Status.COMPLETED;
		} else if (Files.exists(folder.resolve(PROGRESS_MARKER))) {
			status = markerAge(folder.resolve(PROGRESS_MARKER)).compareTo(STALE_AFTER) < 0 ? Status.RUNNING : Status.INTERRUPTED;
		} else if (spec.isPresent()) {
			status = Status.INTERRUPTED;
		} else {
			status = Status.UNREADABLE;
		}
		return new Entry(folder.getFileName().toString(), folder, status, spec, manifest);
	}

	public void delete(Entry entry) {
		try {
			Files.walkFileTree(entry.dir(), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot delete run " + entry.dir(), e);
		}
	}

	/**
	 * Copies the run's files into {@code destination/<run name>} without transforming them.
	 *
	 * @param include keeps a file when it returns true for its path relative to the run folder
	 * @return number of files copied
	 */
	public int export(Entry entry, Path destination, Predicate<Path> include) {
		Path target = destination.resolve(entry.name());
		int[] copied = { 0 };
		try {
			Files.walkFileTree(entry.dir(), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					Path relative = entry.dir().relativize(file);
					if (include.test(relative)) {
						Files.createDirectories(target.resolve(relative).getParent());
						Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
						copied[0]++;
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot export run " + entry.dir(), e);
		}
		return copied[0];
	}

	private static <T> Optional<T> read(Path file, Function<Path, T> reader) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(reader.apply(file));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static Manifest readManifest(Path file) {
		try {
			JsonNode root = JSON.readTree(file.toFile());
			return new Manifest(root.path("scenario").asText(), LocalDateTime.parse(root.path("created").asText()),
				root.path("stopVisits").asInt(), root.path("anomalies").asInt(),
				root.path("meanArrivalDelaySeconds").asDouble(), root.path("totalCost").asDouble(),
				root.path("currency").asText("EUR"));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
	}

	private static Duration markerAge(Path marker) {
		try {
			return Duration.between(Files.getLastModifiedTime(marker).toInstant(), Instant.now());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + marker, e);
		}
	}

	private static LocalDateTime created(Entry entry) {
		return entry.manifest().map(Manifest::created).orElseGet(() -> {
			try {
				return LocalDateTime.ofInstant(Files.getLastModifiedTime(entry.dir()).toInstant(),
					ZoneId.systemDefault());
			} catch (IOException e) {
				return LocalDateTime.MIN;
			}
		});
	}
}
