package it.unimib.milanrailsim.results;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeRunTest {

	@TempDir
	Path dir;

	private static final String EVENTS = """
		<?xml version="1.0" encoding="utf-8"?>
		<events version="1.0">
			<event time="28830.0" type="VehicleArrivesAtFacility" vehicle="t1" facility="A" delay="0.0"/>
			<event time="28845.0" type="VehicleDepartsAtFacility" vehicle="t1" facility="A" delay="0.0"/>
			<event time="29500.0" type="VehicleArrivesAtFacility" vehicle="t1" facility="B" delay="0.0"/>
			<event time="29510.0" type="VehicleDepartsAtFacility" vehicle="t1" facility="B" delay="0.0"/>
		</events>
		""";

	private static final String TIME_DISTANCE = """
		vehicle_id,line_id,route_id,departure_id,time,distance,type,link_id,stop_id
		t1,S1,S1_1,t1,28800.0,0.0,target,A_B,A
		t1,S1,S1_1,t1,29500.0,10000.0,target,A_B,B
		""";

	@Test
	void archivesAFullRunAnalysis() throws IOException {
		TestRuns.Fixture fixture = TestRuns.tenKilometreFixture();
		TestRuns.addTrip(fixture, "t1", "tsr", 8 * 3600);
		Path fakeRun = dir.resolve("output");
		TestRuns.writeFakeRun(fixture, fakeRun, "fake", EVENTS, TIME_DISTANCE);
		Path runsBase = dir.resolve("runs");

		Path archived = AnalyzeRun.run(fakeRun, "test-scenario", runsBase,
			Path.of("config/costs.json"), "S1");

		assertTrue(archived.getFileName().toString().endsWith("-test-scenario"));
		String manifest = Files.readString(archived.resolve("manifest.json"));
		assertTrue(manifest.contains("\"scenario\" : \"test-scenario\""));
		assertTrue(manifest.contains("\"anomalies\" : 0"));
		assertTrue(manifest.contains("\"unfinishedTrains\" : 0"));
		assertFalse(manifest.contains("trainsArrived"), "no outcome without an engine driving the run");
		assertEquals(1, Files.readAllLines(archived.resolve("unfinished.csv")).size(), "header only");

		List<String> punctuality = Files.readAllLines(archived.resolve("punctuality.csv"));
		assertEquals(3, punctuality.size()); // header + stops A and B
		assertTrue(punctuality.get(1).startsWith("t1,S1,S1_1,t1,0,B,A,28800"), punctuality.get(1));

		List<String> trips = Files.readAllLines(archived.resolve("trips.csv"));
		assertEquals(2, trips.size(), "header and the one trip");
		assertTrue(trips.get(1).startsWith("t1,S1,S1_1,t1,A,B,") && trips.get(1).endsWith(",2,2,completed,,,,"),
			"no energy columns for a run read back from its output: " + trips.get(1));
		assertFalse(Files.exists(archived.resolve("energy.json")));
		assertEquals(2, Files.readAllLines(archived.resolve("indicators_by_line.csv")).size());
		assertEquals(3, Files.readAllLines(archived.resolve("stations.csv")).size(), "header, A and B");
		assertEquals(2, Files.readAllLines(archived.resolve("trains.csv")).size());
		assertEquals(List.of("vehicle,type,line,trips", "t1,tsr,S1,1"), Files.readAllLines(archived.resolve("fleet.csv")));
		assertEquals(List.of("type,name,trains", "tsr,tsr,1", "total,Totale,1"),
			Files.readAllLines(archived.resolve("fleet_by_type.csv")), "a type with no recorded name keeps its id");
		assertEquals(List.of("line,type,trains", "S1,tsr,1"), Files.readAllLines(archived.resolve("fleet_by_line.csv")));
		assertEquals(List.of("hour,type,peak,active", "8,tsr,1,1", "8,total,1,1"),
			Files.readAllLines(archived.resolve("trains_by_hour.csv")));
		assertTrue(Files.size(archived.resolve("charts/trains_by_hour.png")) > 1000);
		assertTrue(Files.size(archived.resolve("charts/fleet_by_type.png")) > 1000);
		List<String> hourly = Files.readAllLines(archived.resolve("stations_hourly.csv"));
		assertTrue(hourly.contains("A,B,8,1,1,30,30,100.0"), hourly.toString());
		String indicators = Files.readString(archived.resolve("indicators.json"));
		assertTrue(indicators.contains("\"regularityPercent\" : 100.0"), indicators);
		assertTrue(indicators.contains("\"tripsScheduled\" : 1"), indicators);

		assertEquals(2, Files.readAllLines(archived.resolve("punctuality_by_line.csv")).size());
		assertTrue(Files.readString(archived.resolve("costs.json")).contains("staff"));
		for (String chart : List.of("delay_histogram", "delay_by_hour", "space_time", "cost_breakdown")) {
			assertTrue(Files.size(archived.resolve("charts/" + chart + ".png")) > 1000, chart);
		}
		assertFalse(Files.exists(archived.resolve("raw")), "the outputs are never copied");
	}

	@Test
	void recordsTheOutcomeAndLeavesOutputsInPlaceWhenTheyAreInsideTheArchive() throws IOException {
		TestRuns.Fixture fixture = TestRuns.tenKilometreFixture();
		TestRuns.addTrip(fixture, "t1", "tsr", 8 * 3600);
		Path runDir = dir.resolve("run");
		Path output = runDir.resolve("output");
		TestRuns.writeFakeRun(fixture, output, "fake", EVENTS, TIME_DISTANCE);
		List<String> phases = new java.util.ArrayList<>();

		AnalyzeRun.analyze(new AnalyzeRun.Request(RunData.fromOutput(output), RunArchive.at(runDir), "real",
			Path.of("config/costs.json"), "S1", new RunOutcome(7, 1, 2, 100_000, java.time.Duration.ofSeconds(90)),
			phases::add));

		String manifest = Files.readString(runDir.resolve("manifest.json"));
		assertTrue(manifest.contains("\"trainsArrived\" : 7"));
		assertTrue(manifest.contains("\"trainsStalled\" : 2"));
		assertTrue(manifest.contains("\"wallClockSeconds\" : 90"));
		assertTrue(phases.getFirst().startsWith("Analisi"));
		assertEquals("Archiviazione", phases.getLast());
	}
}
