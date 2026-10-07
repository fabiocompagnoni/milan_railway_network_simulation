package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.PunctualityAnalysis.TripOutcome;
import it.unimib.milanrailsim.results.PunctualityAnalysis.TripStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyReportTest {

	private static TripOutcome trip(String id, TripStatus status) {
		return new TripOutcome(id, "S1", "S1_1", id, "A", "B", 0, 600, status == TripStatus.COMPLETED ? 600 : Double.NaN, 2, 2, status);
	}

	@Test
	void theSummaryCountsTheCompletedTripsTheMeterLeftOut() {
		EnergyLedger.Use use = new EnergyLedger.Use(
			Map.of("metered", new EnergyLedger.TripEnergy("S1", true, 100, 0, 0, 0, 0, 0, 10, 2000)), List.of());

		Map<String, Object> json = EnergyReport.json(use, List.of(trip("metered", TripStatus.COMPLETED),
			// a train type with no mass in the model runs unmetered; a trip that never left has nothing to meter
			trip("noMass", TripStatus.COMPLETED), trip("neverLeft", TripStatus.NEVER_DEPARTED)));

		assertEquals(1, json.get("completedTripsNotMetered"));
	}
}
