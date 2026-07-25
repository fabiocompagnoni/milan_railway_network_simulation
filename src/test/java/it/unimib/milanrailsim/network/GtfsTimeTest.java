package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GtfsTimeTest {

	@Test
	void parsesPlainTime() {
		assertEquals(8 * 3600 + 5 * 60 + 30, GtfsTime.parseSeconds("08:05:30"));
	}

	@Test
	void parsesTimeAfterMidnight() {
		assertEquals(25 * 3600 + 10 * 60, GtfsTime.parseSeconds("25:10:00"));
	}

	@Test
	void rejectsMalformedTime() {
		assertThrows(IllegalArgumentException.class, () -> GtfsTime.parseSeconds("8h05"));
		assertThrows(IllegalArgumentException.class, () -> GtfsTime.parseSeconds(""));
		assertThrows(IllegalArgumentException.class, () -> GtfsTime.parseSeconds("08:65:00"));
	}
}
