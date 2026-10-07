package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MesoMeasuresTableTest {

	@TempDir
	Path dir;

	private static final String HEADER = "link_id,from,to,status,n_tracks,passenger_lines_mode,"
		+ "osm_min_m,osm_max_m,beeline_m,ratio,eq_speed_kmh,untagged_pct,"
		+ "gtfs_min_s,gtfs_trips,implied_kmh,gtfs_routes,osm_way_ids\n";

	private Path write(String rows) throws IOException {
		Path file = dir.resolve("measures.csv");
		Files.writeString(file, HEADER + rows);
		return file;
	}

	@Test
	void loadsMeasuredRow() throws IOException {
		Path file = write("S1_S2,S1,S2,ok,4,4,1500.0,1550.0,1400.0,1.0714,110.0,0.0,120,68,45.0,S1,12 34\n");
		Map<String, MesoMeasuresTable.Measure> table = MesoMeasuresTable.load(file).byLinkId();
		MesoMeasuresTable.Measure m = table.get("S1_S2");
		assertEquals(4, m.tracksTotal().orElseThrow());
		assertEquals(1500.0, m.lengthM().orElseThrow());
		assertEquals(110.0, m.eqSpeedKmh().orElseThrow());
		assertEquals("12 34", m.osmWayIds());
	}

	@Test
	void fallsBackToPathCountWhenPassengerLinesMissing() throws IOException {
		Path file = write("S1_S2,S1,S2,ok,2,,1500.0,1550.0,1400.0,1.0714,,0.0,120,68,45.0,S1,12\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertEquals(2, m.tracksTotal().orElseThrow());
		assertTrue(m.eqSpeedKmh().isEmpty());
	}

	@Test
	void inflatedPathCountNearYardsIsNotTrusted() throws IOException {
		// no declared passenger_lines and 16 measured paths (station yard):
		// the fallback only trusts plain single/double track counts
		Path file = write("S1_S2,S1,S2,ok,16,,1500.0,1550.0,1400.0,1.0714,110.0,0.0,120,68,45.0,S1,12\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertTrue(m.tracksTotal().isEmpty());
	}

	@Test
	void implausibleRatioInvalidatesLength() throws IOException {
		// ratio 4.5 (> 4.0): the path is a detour, its length must not be trusted
		Path file = write("S1_S2,S1,S2,ok,2,2,6300.0,6400.0,1400.0,4.5,110.0,0.0,120,68,45.0,S1,12\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertTrue(m.lengthM().isEmpty());
	}

	@Test
	void pathSlightlyShorterThanBeelineIsTrusted() throws IOException {
		// ratio 0.9: the station point lies off the track axis, the path is real
		Path file = write("S1_S2,S1,S2,ok,2,2,1260.0,1300.0,1400.0,0.9,110.0,0.0,120,68,45.0,S1,12\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertEquals(1260.0, m.lengthM().orElseThrow());
	}

	@Test
	void pathFarShorterThanBeelineIsRejected() throws IOException {
		Path file = write("S1_S2,S1,S2,ok,2,2,1000.0,1100.0,1400.0,0.71,110.0,0.0,120,68,45.0,S1,12\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertTrue(m.lengthM().isEmpty());
	}

	@Test
	void nonOkRowsCarryNoValues() throws IOException {
		Path file = write("S1_S2,S1,S2,no_path,,,,,,,,,,,,,\n");
		MesoMeasuresTable.Measure m = MesoMeasuresTable.load(file).byLinkId().get("S1_S2");
		assertTrue(m.tracksTotal().isEmpty());
		assertTrue(m.lengthM().isEmpty());
		assertTrue(m.eqSpeedKmh().isEmpty());
	}

	@Test
	void rejectsDuplicateLinkIds() throws IOException {
		Path file = write("S1_S2,S1,S2,ok,2,2,1500.0,1550.0,1400.0,1.07,110.0,0.0,120,68,45.0,S1,12\n"
			+ "S1_S2,S1,S2,ok,2,2,1500.0,1550.0,1400.0,1.07,110.0,0.0,120,68,45.0,S1,12\n");
		assertThrows(IllegalArgumentException.class, () -> MesoMeasuresTable.load(file));
	}
}
