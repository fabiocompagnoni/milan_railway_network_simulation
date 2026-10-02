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

	/** Lines R1 and R5 are one pool of trains at terminus B and nowhere else. */
	private static final TripChains.Termini POOLED_AT_B = new TripChains.Termini() {

		@Override
		public double maxLayoverSeconds(String stop) {
			return Double.POSITIVE_INFINITY;
		}

		@Override
		public boolean shareStock(String stop, String line, String other) {
			return stop.equals("B") && List.of("R1", "R5").containsAll(List.of(line, other));
		}
	};

	@Test
	void linesSharingStockAtATerminusChainThere() {
		// the single stub of B holds one train: the R1 in at 6:16 must leave as the R5 of 6:43, the one in at 6:54 as the R1 of 7:06
		List<List<Journey>> chains = TripChains.chain(List.of(
			new Journey("in1", "R1", "A", "B", 330 * 60, 376 * 60),
			new Journey("r5", "R5", "B", "C", 403 * 60, 460 * 60),
			new Journey("in2", "R1", "A", "B", 370 * 60, 414 * 60),
			new Journey("out", "R1", "B", "A", 426 * 60, 470 * 60)), TURNAROUND / 3, POOLED_AT_B);

		assertEquals(List.of(List.of("in1", "r5"), List.of("in2", "out")), ids(chains));
	}

	@Test
	void sharedStockDoesNotReachOtherTermini() {
		List<List<Journey>> chains = TripChains.chain(List.of(
			new Journey("a", "R1", "B", "A", 480 * 60, 500 * 60),
			new Journey("b", "R5", "A", "C", 530 * 60, 550 * 60)), TURNAROUND, POOLED_AT_B);

		assertEquals(List.of(List.of("a"), List.of("b")), ids(chains));
	}
}
