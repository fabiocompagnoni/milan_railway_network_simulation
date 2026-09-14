package it.unimib.milanrailsim.gui.view;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StopLabelsTest {

	private final StopLabels labels = new StopLabels(Map.of("S01326", "Milano Greco Pirelli"));

	@Test
	void platformFacilitiesShowStationAndTrack() {
		assertEquals("Milano Greco Pirelli · p2", labels.stop("S01326.p2|S01326|R38|terminal"));
		assertEquals("Milano Greco Pirelli · greco_cintura.1", labels.stop("S01326.greco_cintura.1|S01326|S9|through"));
	}

	@Test
	void plainStopsShowTheStationNameOrFallBackToTheId() {
		assertEquals("Milano Greco Pirelli", labels.stop("S01326"));
		assertEquals("S09999", labels.stop("S09999"));
	}
}
