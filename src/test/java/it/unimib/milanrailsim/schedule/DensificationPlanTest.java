package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.schedule.DensificationPlan.Intensity;
import it.unimib.milanrailsim.schedule.DensificationPlan.Relation;
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

		assertEquals(5, plan.relations().size());
		assertEquals("S01648", plan.tunnel().referenceStop());
		assertEquals(180, plan.tunnel().minHeadwaySeconds());
		assertEquals(3600, plan.serviceGapSeconds());
		Relation monza = plan.relations().getLast();
		assertEquals(Intensity.REDUCED, monza.intensity());
		assertEquals(List.of("S8", "S11"), monza.lines());
		assertTrue(plan.relations().getFirst().throughTunnel());
	}

	@Test
	void peakHoursGetTheChosenCadenceAndTheRestOfTheDayOneStepSparser() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertEquals(OptionalInt.of(600), plan.cadenceSeconds(WEDNESDAY, at(8, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(WEDNESDAY, at(8, 0), 15, Intensity.FULL));
		assertEquals(OptionalInt.of(900), plan.cadenceSeconds(WEDNESDAY, at(11, 0), 10, Intensity.FULL));
		assertEquals(OptionalInt.empty(), plan.cadenceSeconds(WEDNESDAY, at(11, 0), 15, Intensity.FULL),
			"one step sparser than fifteen minutes is the real timetable");
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
	void onlyTheCadencesOfTheScenarioAreAccepted() {
		DensificationPlan plan = DensificationPlan.read(COMMITTED);

		assertThrows(IllegalArgumentException.class, () -> plan.cadenceSeconds(WEDNESDAY, at(8, 0), 20, Intensity.FULL));
	}

	@Test
	void aRelationWithoutLinesIsRefusedByName(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("broken.json");
		Files.writeString(file, Files.readString(COMMITTED).replace("\"lines\": [\"S19\"]", "\"lines\": []"));

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> DensificationPlan.read(file));
		assertTrue(refused.getMessage().contains("rogoredo-albairate"), refused.getMessage());
	}
}
