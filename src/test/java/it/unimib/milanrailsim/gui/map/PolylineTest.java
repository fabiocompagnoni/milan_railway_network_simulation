package it.unimib.milanrailsim.gui.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolylineTest {

	@Test
	void parsesSemicolonSeparatedPoints() {
		Polyline polyline = Polyline.parse("1.5 2;3 4.25;5 6");

		assertEquals(3, polyline.size());
		assertEquals(1.5, polyline.x(0));
		assertEquals(4.25, polyline.y(1));
		assertEquals(6, polyline.y(2));
	}

	@Test
	void rejectsMalformedPoint() {
		assertThrows(IllegalArgumentException.class, () -> Polyline.parse("1 2;3"));
	}

	@Test
	void rejectsFewerThanTwoPoints() {
		assertThrows(IllegalArgumentException.class, () -> Polyline.parse("1 2"));
	}
}
