package it.unimib.milanrailsim.runs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RunResultsTest {

	@TempDir
	Path dir;

	@Test
	void readsEveryPartWhenPresent() throws IOException {
		Files.writeString(dir.resolve("punctuality_by_line.csv"), """
			line,observations,mean_delay_s,median_delay_s,p95_delay_s,max_delay_s
			S1,120,45,30,200,600
			""");
		Files.writeString(dir.resolve("punctuality.csv"), """
			vehicle,line,route,stop,planned_arrival_s,actual_arrival_s,arrival_delay_s,planned_departure_s,actual_departure_s,departure_delay_s
			S1_circ_1,S1,S1_1,S01645,28800,28860,60,28860,28900,40
			S1_circ_1,S1,S1_1,S01700,29400,,,29460,29500,40
			S1_circ_2,S1,S1_1,S01645,30000,30400,400,,,
			""");
		Files.writeString(dir.resolve("costs.json"), """
			{"currency": "EUR", "byCategory": {"staff": 100.0, "diesel": 20.5}, "trainKm": 12.5,
			 "trainHours": 1.5, "fleetSize": 2, "total": 120.5}
			""");

		RunResults results = RunResults.load(dir);

		assertEquals(45, results.byLine().orElseThrow().getFirst().meanDelay());
		assertEquals(3, results.visits().orElseThrow().size());
		assertTrue(Double.isNaN(results.visits().orElseThrow().get(1).actualArrival()));
		assertEquals(120.5, results.costs().orElseThrow().total());
		// two measured arrivals, one within 5 minutes
		assertEquals(50.0, results.punctuality(300).orElseThrow());
	}

	@Test
	void missingPartsAreEmptyNotErrors() {
		RunResults results = RunResults.load(dir);

		assertTrue(results.byLine().isEmpty());
		assertTrue(results.costs().isEmpty());
		assertTrue(results.chart(RunResults.DELAY_HISTOGRAM).isEmpty());
		assertTrue(results.punctuality(300).isEmpty());
	}
}
