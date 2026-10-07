package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.StationTracks;
import it.unimib.milanrailsim.network.micro.MicroNode;
import it.unimib.milanrailsim.network.micro.MicroNodeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutedSidesTest {

	private static final String NODE = """
		{
			"node": "fixture", "title": "Fixture node",
			"stations": [
				{"id": "S1", "name": "S1", "kind": "terminal", "platformLengthM": null,
					"groups": [{"id": "s1_main", "kind": "terminal", "tracks": [{"ref": "1", "direction": null}],
						"connections": {"north": ["segment:S1_S2:f1"]}}], "throats": []},
				{"id": "S2", "name": "S2", "kind": "through", "platformLengthM": null,
					"groups": [{"id": "s2_f1", "kind": "through", "tracks": [{"ref": "1", "direction": "north"}, {"ref": "2", "direction": "south"}],
						"connections": {"south": ["segment:S1_S2:f1"], "north": ["meso:S3"]}}], "throats": []}
			],
			"segments": [{"from": "S1", "to": "S2", "bundles": {"f1": {
				"north": {"wayIds": [], "lengthM": 2000}, "south": {"wayIds": [], "lengthM": 2000}, "speedProfile": []}}}],
			"lines": {"S1": {"bundle": "f1", "stations": {"S1": ["s1_main"], "S2": ["s2_f1"]}}}
		}
		""";

	@Test
	void namesTheDeclaredNeighbourAnExpressTrainRunsThrough(@TempDir Path dir) throws IOException {
		// S4 lies beyond S3, the only northern neighbour S2 declares: a train from S4 reaches S2 through S3
		Network network = TestNetworks.threeStationLine();
		network.addNode(network.getFactory().createNode(Id.createNodeId("S4"), new Coord(0, 0)));
		for (String[] pair : new String[][] { { "S3", "S4" }, { "S4", "S3" }, { "S3", "S2" }, { "S2", "S1" } }) {
			Link link = network.getFactory().createLink(Id.createLinkId(pair[0] + "_" + pair[1]),
				network.getNodes().get(Id.createNodeId(pair[0])), network.getNodes().get(Id.createNodeId(pair[1])));
			link.setLength(2000);
			link.setFreespeed(30);
			link.setAllowedModes(Set.of("rail"));
			network.addLink(link);
		}
		Path file = dir.resolve("fixture.json");
		Files.writeString(file, NODE);
		List<MicroNode> nodes = List.of(MicroNode.read(file));
		new MicroNodeBuilder(network).splice(nodes);
		StationStopLinks.addStopLinks(network, StationTracks.empty());
		RoutedSides sides = new RoutedSides(network, new LinkRouter(network), nodes);

		assertEquals(Optional.of("S3"), sides.neighbourTowards("S2", "S4"));
		assertEquals(Optional.of("S1"), sides.neighbourTowards("S2", "S1"), "a detailed neighbour is reached from its sidings");
		assertEquals(Optional.empty(), sides.neighbourTowards("S2", "nowhere"));
	}
}
