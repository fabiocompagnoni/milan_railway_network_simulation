package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CostParametersTest {

	@TempDir
	Path dir;

	private Path write(String json) throws IOException {
		Path file = dir.resolve("costs.json");
		Files.writeString(file, json);
		return file;
	}

	@Test
	void loadsCategoriesWithUnitAndSource() throws IOException {
		Path file = write("""
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": "stima Fabio 2026-09"},
				"diesel": {"unitCost": 3.2, "unit": "train_km", "source": "stima Fabio 2026-09"}
			}}""");
		CostParameters parameters = CostParameters.load(file);
		assertEquals("EUR", parameters.currency());
		assertEquals(140.0, parameters.category("staff").unitCost());
		assertEquals("train_km", parameters.category("diesel").unit());
	}

	@Test
	void rejectsNegativeUnitCost() throws IOException {
		Path file = write("""
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": -1.0, "unit": "train_hour", "source": "x"}
			}}""");
		assertThrows(IllegalArgumentException.class, () -> CostParameters.load(file));
	}

	@Test
	void rejectsBlankSource() throws IOException {
		Path file = write("""
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": " "}
			}}""");
		assertThrows(IllegalArgumentException.class, () -> CostParameters.load(file));
	}

	@Test
	void unknownCategoryFailsFast() throws IOException {
		Path file = write("""
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 140.0, "unit": "train_hour", "source": "x"}
			}}""");
		CostParameters parameters = CostParameters.load(file);
		assertThrows(IllegalArgumentException.class, () -> parameters.category("helicopters"));
	}

	@Test
	void zeroCostLoadsButIsNotUsable() throws IOException {
		// the committed config ships zeros until Fabio fills real estimates
		Path file = write("""
			{"currency": "EUR", "categories": {
				"staff": {"unitCost": 0.0, "unit": "train_hour", "source": "da stimare"}
			}}""");
		CostParameters.Entry staff = CostParameters.load(file).category("staff");
		assertFalse(staff.isSet());
	}
}
