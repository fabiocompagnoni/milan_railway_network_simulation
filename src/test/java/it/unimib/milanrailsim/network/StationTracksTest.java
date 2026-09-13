package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StationTracksTest {

	@TempDir
	Path dir;

	private StationTracks table(String content) throws IOException {
		return StationTracks.read(Files.writeString(dir.resolve("tracks.csv"), content));
	}

	@Test
	void surveyedCountWinsOverOsmPlatforms() throws IOException {
		StationTracks tracks = table("""
			stop_id,nome,corse_giorno,banchine_OSM_da_correggere,linee,binari_reali
			S01066,Milano Cadorna,483,7,"R16,R17,S3,S4",10
			""");

		assertEquals(new StationTracks.Tracks(10, false), tracks.of("S01066").orElseThrow());
	}

	@Test
	void osmPlatformsAreProvisionalWhenTheSurveyIsBlank() throws IOException {
		StationTracks tracks = table("""
			stop_id,nome,corse_giorno,banchine_OSM_da_correggere,linee,binari_reali
			S09999,Brescia,208,8,"R1,R3",
			S01087,Meda,6,2,S2
			""");

		assertEquals(new StationTracks.Tracks(8, true), tracks.of("S09999").orElseThrow());
		assertEquals(new StationTracks.Tracks(2, true), tracks.of("S01087").orElseThrow());
		assertTrue(tracks.of("S00000").isEmpty());
	}

	@Test
	void toleratesACarriageReturnInsideTheHeader() throws IOException {
		StationTracks tracks = table("stop_id,nome,corse_giorno,banchine_OSM_da_correggere,linee\r,binari_reali\n"
			+ "S01645,Milano Porta Garibaldi,332,3,\"S5,S6\",20\n");

		assertEquals(20, tracks.of("S01645").orElseThrow().count());
	}

	@Test
	void rejectsATableWithoutTheExpectedColumns() throws IOException {
		assertThrows(IllegalArgumentException.class, () -> table("stop_id,nome\nS1,X\n"));
	}
}
