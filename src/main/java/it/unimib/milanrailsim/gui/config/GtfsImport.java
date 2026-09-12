package it.unimib.milanrailsim.gui.config;

import it.unimib.milanrailsim.network.CsvTable;
import it.unimib.milanrailsim.network.ServiceCalendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates a GTFS feed (folder or zip) and installs it as the app's timetable source. */
public final class GtfsImport {

	/** The files the engine reads; agency.txt is optional and only names the operator. */
	static final List<String> REQUIRED_FILES = List.of(
		"routes.txt", "trips.txt", "stops.txt", "stop_times.txt", "calendar_dates.txt");

	/** What a feed contains, shown before and after installing it. */
	public record FeedInfo(String agency, LocalDate firstDay, LocalDate lastDay, int routes, int stops) {
	}

	private GtfsImport() {
	}

	/** @throws IllegalArgumentException if a required file is missing, naming it */
	public static FeedInfo inspect(Path feedDir) {
		for (String required : REQUIRED_FILES) {
			if (!Files.isRegularFile(feedDir.resolve(required))) {
				throw new IllegalArgumentException("Feed non valido: manca " + required + ".");
			}
		}
		Path agencyFile = feedDir.resolve("agency.txt");
		List<Map<String, String>> agency = Files.isRegularFile(agencyFile) ? CsvTable.read(agencyFile) : List.of();
		NavigableSet<LocalDate> dates = ServiceCalendar.availableDates(CsvTable.read(feedDir.resolve("calendar_dates.txt")));
		if (dates.isEmpty()) {
			throw new IllegalArgumentException("Feed non valido: calendar_dates.txt non definisce alcun giorno di servizio.");
		}
		return new FeedInfo(agency.isEmpty() ? "—" : agency.getFirst().get("agency_name"), dates.first(), dates.last(),
			CsvTable.read(feedDir.resolve("routes.txt")).size(), CsvTable.read(feedDir.resolve("stops.txt")).size());
	}

	/** Copies the feed's text files into {@code target}, replacing any feed already there. */
	public static void install(Path source, Path target) {
		try {
			Path staging = Files.createTempDirectory("gtfs-import");
			if (Files.isDirectory(source)) {
				try (Stream<Path> files = Files.list(source)) {
					for (Path file : files.filter(f -> f.toString().endsWith(".txt")).toList()) {
						Files.copy(file, staging.resolve(file.getFileName().toString()));
					}
				}
			} else {
				unzip(source, staging);
			}
			inspect(staging);
			if (Files.isDirectory(target)) {
				try (Stream<Path> files = Files.list(target)) {
					for (Path file : files.toList()) {
						Files.delete(file);
					}
				}
			}
			Files.createDirectories(target);
			try (Stream<Path> files = Files.list(staging)) {
				for (Path file : files.toList()) {
					Files.move(file, target.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.delete(staging);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot install feed " + source, e);
		}
	}

	private static void unzip(Path zip, Path into) throws IOException {
		try (InputStream input = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(input)) {
			for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
				String name = Path.of(entry.getName()).getFileName().toString();
				if (!entry.isDirectory() && name.endsWith(".txt")) {
					Files.copy(entries, into.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
