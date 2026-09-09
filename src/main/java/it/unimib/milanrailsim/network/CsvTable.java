package it.unimib.milanrailsim.network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader for the GTFS CSV dialect used by the Trenord feed: comma
 * separated, no quoting. Any quote character fails fast rather than being
 * silently misparsed, in case a future feed version starts quoting fields.
 */
public final class CsvTable {

	private CsvTable() {
	}

	public static List<Map<String, String>> read(Path file) {
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("Empty CSV file: " + file);
		}
		String[] header = splitStrict(lines.get(0), file, 1);
		List<Map<String, String>> rows = new ArrayList<>(lines.size() - 1);
		for (int i = 1; i < lines.size(); i++) {
			String line = lines.get(i);
			String[] fields = splitStrict(line, file, i + 1);
			if (fields.length != header.length) {
				throw new IllegalArgumentException(
					"Row %d of %s has %d fields, header has %d".formatted(i + 1, file, fields.length, header.length));
			}
			Map<String, String> row = LinkedHashMap.newLinkedHashMap(header.length);
			for (int c = 0; c < header.length; c++) {
				row.put(header[c], fields[c]);
			}
			rows.add(row);
		}
		return rows;
	}

	private static String[] splitStrict(String line, Path file, int lineNumber) {
		if (line.indexOf('"') >= 0) {
			throw new IllegalArgumentException(
				"Quote character at line %d of %s: quoting is not supported".formatted(lineNumber, file));
		}
		return line.split(",", -1);
	}
}
