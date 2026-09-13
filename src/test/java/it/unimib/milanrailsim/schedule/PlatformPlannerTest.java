package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.schedule.PlatformPlanner.Call;
import it.unimib.milanrailsim.schedule.PlatformPlanner.Plan;
import it.unimib.milanrailsim.schedule.PlatformPlanner.TripCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlatformPlannerTest {

	private static final String NODE = """
		{
			"node": "test", "title": "Test node",
			"stations": [
				{"id": "A", "name": "A", "kind": "terminal", "platformLengthM": null,
					"groups": [
						{"id": "a_main", "kind": "terminal", "tracks": [{"ref": "1", "direction": null}, {"ref": "2", "direction": null}],
							"connections": {"north": ["segment:A_B:f1"]}},
						{"id": "a_spare", "kind": "terminal", "tracks": [{"ref": "3", "direction": null}],
							"connections": {"north": ["segment:A_B:f1"]}}
					], "throats": []},
				{"id": "B", "name": "B", "kind": "through", "platformLengthM": null,
					"groups": [
						{"id": "b_f1", "kind": "through", "tracks": [{"ref": "1", "direction": "north"}, {"ref": "2", "direction": "south"}],
							"connections": {"south": ["segment:A_B:f1"], "north": ["meso:C"]}}
					], "throats": []}
			],
			"segments": [{"from": "A", "to": "B", "bundles": {"f1": {
				"north": {"wayIds": [], "lengthM": 1500}, "south": {"wayIds": [], "lengthM": 1500}, "speedProfile": []}}}],
			"lines": {
				"S1": {"bundle": "f1", "stations": {"A": ["a_main", "a_spare"], "B": ["b_f1"]}}
			}
		}
		""";

	private PlatformPlanner planner;

	@BeforeEach
	void planner(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("test.json");
		Files.writeString(file, NODE);
		planner = new PlatformPlanner(List.of(MicroNode.read(file)), 15 * 60);
	}

	private static TripCalls trip(String id, int departureMinutes, String... stops) {
		List<Call> calls = new java.util.ArrayList<>();
		for (int i = 0; i < stops.length; i++) {
			int time = (departureMinutes + 10 * i) * 60;
			calls.add(new Call(stops[i], time, i == 0 ? time : time + 60));
		}
		return new TripCalls(id, "S1", calls);
	}

	@Test
	void throughTracksFollowTheTravelDirection() {
		Plan plan = planner.plan(List.of(List.of(trip("north", 480, "A", "B", "C")), List.of(trip("south", 500, "C", "B", "A"))));

		assertEquals(Id.createLinkId("B.p1"), plan.platform("north", 1).orElseThrow());
		assertEquals(Id.createLinkId("B.p2"), plan.platform("south", 1).orElseThrow());
		assertTrue(plan.platform("north", 2).isEmpty(), "C is a meso station");
	}

	@Test
	void terminalTracksRotateWithinTheFirstGroupWhileFree() {
		Plan plan = planner.plan(List.of(
			List.of(trip("t1", 480, "A", "B", "C")),
			List.of(trip("t2", 490, "A", "B", "C")),
			List.of(trip("t3", 500, "A", "B", "C"))));

		assertEquals(Id.createLinkId("A.p1.in"), plan.platform("t1", 0).orElseThrow());
		assertEquals(Id.createLinkId("A.p2.in"), plan.platform("t2", 0).orElseThrow());
		assertEquals(Id.createLinkId("A.p1.in"), plan.platform("t3", 0).orElseThrow());
		assertEquals(0, plan.conflicts());
	}

	@Test
	void aCirculationKeepsItsPlatformFromArrivalToTheNextDeparture() {
		TripCalls arrive = trip("in", 480, "C", "B", "A");
		TripCalls depart = trip("out", 520, "A", "B", "C");
		TripCalls other = trip("other", 505, "A", "B");

		Plan plan = planner.plan(List.of(List.of(arrive, depart), List.of(other)));

		Id<Link> platform = plan.platform("in", 2).orElseThrow();
		assertEquals(platform, plan.platform("out", 0).orElseThrow());
		assertNotEquals(platform, plan.platform("other", 0).orElseThrow(), "the platform is held until the departure");
	}

	@Test
	void spareGroupIsUsedWhenTheFirstIsFullAndConflictsAreCounted() {
		// four departures at the same minute from a terminal with 2 + 1 tracks; C is mesoscopic
		Plan plan = planner.plan(List.of(
			List.of(trip("t1", 480, "A", "C")),
			List.of(trip("t2", 480, "A", "C")),
			List.of(trip("t3", 480, "A", "C")),
			List.of(trip("t4", 480, "A", "C"))));

		assertEquals(Id.createLinkId("A.p3.in"), plan.platform("t3", 0).orElseThrow());
		assertEquals(1, plan.conflicts());
		assertTrue(plan.platform("t4", 0).orElseThrow().toString().startsWith("A.p"));
	}

	@Test
	void unknownLineFallsBackToAnyTrackAtTheStation() {
		TripCalls regional = new TripCalls("r", "R99", List.of(new Call("A", 480 * 60, 480 * 60), new Call("B", 490 * 60, 491 * 60)));

		Plan plan = planner.plan(List.of(List.of(regional)));

		assertTrue(plan.platform("r", 0).isPresent());
	}
}
