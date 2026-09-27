package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.schedule.DensificationPlan.Intensity;
import it.unimib.milanrailsim.schedule.DensificationPlan.Relation;
import it.unimib.milanrailsim.schedule.DensificationPlan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class DensificationPlanTest {

	private static final Path COMMITTED = Path.of("data/scenarios/passante-alta-frequenza.json");
	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 23);
	private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 26);
	private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 27);

	private static int at(int hours, int minutes) {
		return hours * 3600 + minutes * 60;
	}

	@Test
	void readsTheCommittedScenario() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertEquals(List.of("bovisa-passante", "saronno-albairate", "cormano-cadorna", "monza-garibaldi"),
			plan.relations().stream().map(Relation::id).toList(), "in the order they are served");
		assertEquals("S01648", plan.tunnel().referenceStop());
		assertEquals(180, plan.tunnel().minHeadwaySeconds());
		assertEquals(3600, plan.serviceGapSeconds());
		assertEquals(300, plan.minSpacingSeconds());
		Relation passante = plan.relations().getFirst();
		assertEquals(List.of("S01642", "S01643"), passante.flow());
		assertEquals(new Service("S2", "S01109", "S01820"), passante.services().getFirst());
		assertEquals(List.of("S2", "S1", "S2", "S12", "S2", "S1", "S2", "S13"),
			passante.services().stream().map(Service::line).toList());
		assertTrue(passante.throughTunnel());
		assertEquals(Intensity.REDUCED, plan.relations().getLast().intensity());
	}

	@Test
	void peakHoursGetTheChosenTargetAndTheRestOfTheDayOneStepLonger() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertEquals(OptionalInt.of(600), plan.cadenceSeconds(WEDNESDAY, at(8, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(WEDNESDAY, at(8, 0), 15, Intensity.FULL));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(WEDNESDAY, at(11, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.empty(), plan.cadenceSeconds(WEDNESDAY, at(11, 0), 15, Intensity.FULL),
			"one step longer than fifteen minutes is the real timetable");
		assertEquals(OptionalInt.empty(), plan.cadenceSeconds(WEDNESDAY, at(22, 0), 10, Intensity.FULL), "outside every band");
		assertEquals(OptionalInt.of(600), plan.cadenceSeconds(WEDNESDAY, at(9, 29), 10, Intensity.FULL), "the upper bound is excluded");
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(WEDNESDAY, at(9, 30), 10, Intensity.FULL));
	}

	@Test
	void theWeekendIsServedLessAndSundayOnlyOnTheFullRelations() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(SATURDAY, at(8, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(SATURDAY, at(8, 0), 10, Intensity.REDUCED));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(SUNDAY, at(12, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.empty(), plan.cadenceSeconds(SUNDAY, at(12, 0), 10, Intensity.REDUCED));
		assertEquals(OptionalInt.empty(), plan.cadenceSeconds(SUNDAY, at(8, 0), 10, Intensity.FULL));
	}

	@Test
	void onlyTheTargetsOfTheScenarioAreAccepted() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertThrows(IllegalArgumentException.class, () -> plan.cadenceSeconds(WEDNESDAY, at(8, 0), 20, Intensity.FULL));
	}

	@Test
	void aGapTakesTheTripsThatKeepTheWaitWithinTheTargetButNotBelowTheMinimumSpacing() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertEquals(2, plan.tripsFitting(at(0, 30), 600), "thirty minutes at ten: two trips, one every ten");
		assertEquals(1, plan.tripsFitting(at(0, 30), 900));
		assertEquals(1, plan.tripsFitting(at(0, 11), 600), "the eleven minutes between two pairs of trains at Bovisa");
		assertEquals(0, plan.tripsFitting(at(0, 10), 600), "the wait is within the target already");
		assertEquals(0, plan.tripsFitting(at(0, 4), 600));
		assertEquals(1, plan.tripsFitting(at(0, 15), 600), "seven and a half minutes: above the five of the minimum");

		DensificationPlan wider = new DensificationPlan(plan.relations(), plan.tunnel(), 3600, 480, plan.dayProfiles());
		assertEquals(0, wider.tripsFitting(at(0, 15), 600), "seven and a half minutes would be below eight");
		assertEquals(2, wider.tripsFitting(at(0, 30), 600));
	}

	@Test
	void aRelationWithoutServicesOrWithAOneStopFlowIsRefusedByName(@TempDir Path dir) throws IOException {
		Path noServices = dir.resolve("no-services.json");
		Files.writeString(noServices, Files.readString(COMMITTED)
			.replace("{\"line\": \"S4\", \"from\": \"S01109\", \"to\": \"S01066\"}", ""));
		Path oneStop = dir.resolve("one-stop.json");
		Files.writeString(oneStop, Files.readString(COMMITTED).replace("[\"S01322\", \"S01325\"]", "[\"S01322\"]"));

		assertTrue(assertThrows(IllegalArgumentException.class, () -> DensificationPlan.read(noServices))
			.getMessage().contains("cormano-cadorna"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> DensificationPlan.read(oneStop))
			.getMessage().contains("monza-garibaldi"));
	}
}
