package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServiceCalendarTest {

	private static Map<String, String> row(String serviceId, String date, String exceptionType) {
		return Map.of("service_id", serviceId, "date", date, "exception_type", exceptionType);
	}

	@Test
	void serviceAddedOnDateIsActive() {
		Set<String> active = ServiceCalendar.activeServiceIds(
			List.of(row("A", "20260916", "1"), row("B", "20260917", "1")),
			LocalDate.of(2026, 9, 16));
		assertEquals(Set.of("A"), active);
	}

	@Test
	void serviceRemovedOnDateIsNotActive() {
		Set<String> active = ServiceCalendar.activeServiceIds(
			List.of(row("A", "20260916", "1"), row("A", "20260916", "2")),
			LocalDate.of(2026, 9, 16));
		assertTrue(active.isEmpty());
	}

	@Test
	void rejectsUnknownExceptionType() {
		assertThrows(IllegalArgumentException.class, () -> ServiceCalendar.activeServiceIds(
			List.of(row("A", "20260916", "3")), LocalDate.of(2026, 9, 16)));
	}
}
