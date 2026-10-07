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
							"connections": {"south": ["segment:A_B:f1"], "north": ["meso:C"]}},
						{"id": "b_x", "kind": "through", "tracks": [{"ref": "3", "direction": "north"}],
							"connections": {"south": ["segment:A_B:f1"], "north": ["meso:X"]}}
					], "throats": []}
			],
			"segments": [{"from": "A", "to": "B", "bundles": {"f1": {
				"north": {"wayIds": [], "lengthM": 1500}, "south": {"wayIds": [], "lengthM": 1500}, "speedProfile": []}}}],
			"lines": {
				"S1": {"bundle": "f1", "stations": {"A": ["a_main", "a_spare"], "B": ["b_f1"]}},
				"S2": {"bundle": "f1", "stations": {"A": ["a_main"], "B": ["b_x", "b_f1"]}}
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
		return trip(id, "S1", departureMinutes, stops);
	}

	private static TripCalls trip(String id, String line, int departureMinutes, String... stops) {
		List<Call> calls = new java.util.ArrayList<>();
		for (int i = 0; i < stops.length; i++) {
			int time = (departureMinutes + 10 * i) * 60;
			calls.add(new Call(stops[i], time, i == 0 ? time : time + 60));
		}
		return new TripCalls(id, line, calls);
	}

	@Test
	void aTrainLeavingForTheSidingsFreesItsPlatformAndDoesNotPassItOn() {
		// t1 arrives at A at 8:20 and leaves for the sidings; t2 departs A at 11:00 from the sidings
		TripCalls arriving = trip("t1", 8 * 60, "C", "B", "A").via(false, true);
		TripCalls departing = trip("t2", 11 * 60, "A", "B", "C").via(true, false);
		TripCalls between = trip("t3", 8 * 60 + 25, "A", "B", "C");

		Plan plan = planner.plan(List.of(List.of(arriving, departing), List.of(between)));

		assertEquals(Id.createLinkId("A.p1.in"), plan.platform("t1", 2).orElseThrow());
		assertEquals(Id.createLinkId("A.p1.in"), plan.platform("t3", 0).orElseThrow(),
			"the platform is free again right after t1 left it");
		assertEquals(0, plan.conflicts());
	}

	@Test
	void aFixedTrackIsTakenWhileFreeAndTheNextPreferenceWhenOccupied(@TempDir Path dir) throws IOException {
		// S1 is planned on track 2 at A; a second train at the same minute falls back to the spare group
		Path file = dir.resolve("fixed.json");
		Files.writeString(file, NODE.replace("\"A\": [\"a_main\", \"a_spare\"]", "\"A\": [\"a_main/2\", \"a_spare\"]"));
		PlatformPlanner fixed = new PlatformPlanner(List.of(MicroNode.read(file)), 15 * 60);

		Plan plan = fixed.plan(List.of(
			List.of(trip("t1", 480, "A", "B", "C")),
			List.of(trip("t2", 490, "A", "B", "C")),
			List.of(trip("t3", 480, "A", "C"))));

		assertEquals(Id.createLinkId("A.p2.in"), plan.platform("t1", 0).orElseThrow());
		assertEquals(Id.createLinkId("A.p2.in"), plan.platform("t2", 0).orElseThrow(), "no rotation on a fixed track");
		assertEquals(Id.createLinkId("A.p3.in"), plan.platform("t3", 0).orElseThrow());
		assertEquals(0, plan.conflicts());
	}

	@Test
	void preferencesMayDependOnTheArrivalSide(@TempDir Path dir) throws IOException {
		// B has two bidirectional tracks; the plan puts trains from A (south) on track 2 and trains from C
		// (north) on track 1, the opposite of what the rotation alone would give
		Path file = dir.resolve("sides.json");
		Files.writeString(file, NODE
			.replace("{\"ref\": \"1\", \"direction\": \"north\"}, {\"ref\": \"2\", \"direction\": \"south\"}",
				"{\"ref\": \"1\", \"direction\": null}, {\"ref\": \"2\", \"direction\": null}")
			.replace("\"B\": [\"b_f1\"]}}", "\"B\": {\"from_south\": [\"b_f1/2\"], \"from_north\": [\"b_f1/1\"]}}}"));
		PlatformPlanner sided = new PlatformPlanner(List.of(MicroNode.read(file)), 15 * 60);

		Plan plan = sided.plan(List.of(List.of(trip("north", 480, "A", "B", "C")), List.of(trip("south", 500, "C", "B", "A")),
			List.of(trip("startsNorth", 600, "B", "C"))));

		assertEquals(Id.createLinkId("B.p2.north"), plan.platform("north", 1).orElseThrow());
		assertEquals(Id.createLinkId("B.p1.south"), plan.platform("south", 1).orElseThrow());
		assertEquals(Id.createLinkId("B.p2.north"), plan.platform("startsNorth", 0).orElseThrow(),
			"a train starting northbound takes the track of trains arriving from the south");
	}

	@Test
	void groupsConnectingBothNeighboursAreTheAlternativesOfACall() {
		assertEquals(List.of("b_f1"), planner.groupsConnecting("B", "A", "C"));
		assertEquals(List.of("b_x"), planner.groupsConnecting("B", "A", "X"));
		assertEquals(List.of("b_f1", "b_x"), planner.groupsConnecting("B", "A", null), "a trip ending here may use either");
		assertTrue(planner.groupsConnecting("C", "B", null).isEmpty(), "C is not a detailed station");
	}

	@Test
	void aGroupNotConnectedToTheTripsNeighboursIsSkipped() {
		// S2 prefers b_x at B, but b_x leads to X, not to C where the trip goes next
		Plan plan = planner.plan(List.of(List.of(trip("t1", "S2", 8 * 60, "A", "B", "C"))));

		assertEquals(Id.createLinkId("B.p1"), plan.platform("t1", 1).orElseThrow());
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
