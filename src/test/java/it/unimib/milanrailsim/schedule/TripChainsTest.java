package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.schedule.TripChains.Journey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TripChainsTest {

	private static final int TURNAROUND = 15 * 60;

	private static Journey journey(String id, String from, String to, int startMinutes, int endMinutes) {
		return new Journey(id, "S1", from, to, startMinutes * 60.0, endMinutes * 60.0);
	}

	private static List<List<String>> ids(List<List<Journey>> chains) {
		return chains.stream().map(chain -> chain.stream().map(Journey::tripId).toList()).toList();
	}

	@Test
	void returnTripAfterTheTurnaroundContinuesTheChain() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			journey("out", "A", "B", 480, 500),
			journey("back", "B", "A", 515, 535),
			journey("next", "A", "B", 600, 620)), TURNAROUND, stop -> Double.POSITIVE_INFINITY);

		assertEquals(List.of(List.of("out", "back", "next")), ids(chains));
	}

	@Test
	void tooShortTurnaroundStartsANewVehicle() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			journey("out", "A", "B", 480, 500),
			journey("back", "B", "A", 505, 525)), TURNAROUND, stop -> Double.POSITIVE_INFINITY);

		assertEquals(List.of(List.of("out"), List.of("back")), ids(chains));
	}

	@Test
	void layoverBeyondTheLimitOfTheTerminusBreaksTheChain() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			journey("out", "A", "B", 480, 500),
			journey("late", "B", "A", 600, 620)), TURNAROUND, stop -> stop.equals("B") ? 3600.0 : Double.POSITIVE_INFINITY);

		assertEquals(List.of(List.of("out"), List.of("late")), ids(chains));
	}

	@Test
	void earliestFreeVehicleTakesTheTrip() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			journey("first", "A", "B", 480, 500),
			journey("second", "A", "B", 490, 510),
			journey("back", "B", "A", 530, 550)), TURNAROUND, stop -> Double.POSITIVE_INFINITY);

		assertEquals(List.of(List.of("first", "back"), List.of("second")), ids(chains));
	}

	@Test
	void linesNeverShareVehicles() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			new Journey("a", "S1", "A", "B", 480 * 60, 500 * 60),
			new Journey("b", "S2", "B", "A", 530 * 60, 550 * 60)), TURNAROUND, stop -> Double.POSITIVE_INFINITY);

		assertEquals(2, chains.size());
	}
}
