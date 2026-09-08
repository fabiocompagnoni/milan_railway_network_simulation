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

		List<String> punctuality = Files.readAllLines(archived.resolve("punctuality.csv"));
		assertEquals(3, punctuality.size()); // header + stops A and B
		assertTrue(punctuality.get(1).startsWith("t1,S1,S1_1,A,28800"));

		assertEquals(2, Files.readAllLines(archived.resolve("punctuality_by_line.csv")).size());
		assertTrue(Files.readString(archived.resolve("costs.json")).contains("staff"));
		for (String chart : List.of("delay_histogram", "delay_by_hour", "space_time", "cost_breakdown")) {
			assertTrue(Files.size(archived.resolve("charts/" + chart + ".png")) > 1000, chart);
		}
		assertTrue(Files.exists(archived.resolve("raw/fake.0.events.xml")));
	}
}
