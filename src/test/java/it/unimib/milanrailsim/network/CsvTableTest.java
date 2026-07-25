package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CsvTableTest {

	@TempDir
	Path dir;

	private Path write(String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.writeString(file, content);
		return file;
	}

	@Test
	void readsHeaderKeyedRows() throws IOException {
		Path file = write("stops.txt", "stop_id,stop_name\nS1,Milano\nS2,Monza\n");
		List<Map<String, String>> rows = CsvTable.read(file);
		assertEquals(2, rows.size());
		assertEquals("Milano", rows.get(0).get("stop_name"));
		assertEquals("S2", rows.get(1).get("stop_id"));
	}

	@Test
	void rejectsQuoteCharacter() throws IOException {
		Path file = write("bad.txt", "a,b\n\"x,y\",z\n");
		assertThrows(IllegalArgumentException.class, () -> CsvTable.read(file));
	}

	@Test
	void rejectsWrongFieldCount() throws IOException {
		Path file = write("bad.txt", "a,b\n1,2,3\n");
		assertThrows(IllegalArgumentException.class, () -> CsvTable.read(file));
	}

	@Test
	void rejectsEmptyFile() throws IOException {
		Path file = write("empty.txt", "");
		assertThrows(IllegalArgumentException.class, () -> CsvTable.read(file));
	}

	@Test
	void rejectsBlankDataLine() throws IOException {
		Path file = write("bad.txt", "a,b\n1,2\n\n3,4\n");
		assertThrows(IllegalArgumentException.class, () -> CsvTable.read(file));
	}
}
