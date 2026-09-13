package it.unimib.milanrailsim.network.micro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MicroNodeTest {

	private static final String NODE = """
		{
			"node": "test",
			"title": "Test node",
			"status": "test",
			"sources": ["synthetic"],
			"stations": [
				{
					"id": "A", "name": "A", "kind": "terminal", "platformLengthM": null,
					"groups": [
						{"id": "a_main", "kind": "terminal", "tracks": [{"ref": "1", "direction": null}, {"ref": "2", "direction": null}],
							"connections": {"north": ["segment:A_B:f1"]}},
						{"id": "a_shared", "kind": "terminal", "capacity": 3, "otherOperatorsShare": 1,
							"connections": {"north": ["segment:A_B:f1", "meso:X"]}}
					],
					"throats": [
						{"id": "a_throat", "side": "north", "resource": "a_throat", "lengthM": 300, "speedKmh": 30, "switches": 4,
							"groups": ["a_main", "a_shared"]}
					],
					"sidings": {"tracks": 2}
				},
				{
					"id": "B", "name": "B", "kind": "through", "platformLengthM": 250,
					"groups": [
						{"id": "b_f1", "kind": "through", "tracks": [{"ref": "1", "direction": "north"}, {"ref": "2", "direction": "south"}],
							"connections": {"south": ["segment:A_B:f1"], "north": ["meso:C"]}}
					],
					"throats": []
				}
			],
			"segments": [
				{"from": "A", "to": "B", "bundles": {"f1": {
					"north": {"wayIds": [1, 2], "lengthM": 1500},
					"south": {"wayIds": [], "lengthM": 1520},
					"speedProfile": [{"kmh": 30, "lengthM": 300}, {"kmh": 90, "lengthM": 1200}]}}}
			],
			"lines": {
				"S1": {"bundle": "f1", "stations": {"A": ["a_main", "a_shared"], "B": ["b_f1"]}},
				"*terminal": {"bundle": null, "stations": {"A": ["a_shared"]}}
			},
			"openQuestions": []
		}
		""";

	@Test
	void readsStationsGroupsSegmentsAndLines(@TempDir Path dir) throws IOException {
		MicroNode node = MicroNode.read(write(dir, NODE));

		assertEquals("test", node.id());
		assertEquals(List.of("A", "B"), node.stations().stream().map(MicroNode.Station::id).toList());
		MicroNode.Station a = node.station("A");
		assertEquals(MicroNode.StationKind.TERMINAL, a.kind());
		assertEquals(2, a.group("a_main").tracks().size());
		assertEquals(3, a.group("a_shared").capacity());
		assertEquals(1, a.group("a_shared").otherOperatorsShare());
		assertEquals(List.of(new MicroNode.Connection(MicroNode.ConnectionKind.SEGMENT, "A_B", "f1")),
			a.group("a_main").connections().get(MicroNode.Direction.NORTH));
		assertEquals(List.of("a_main", "a_shared"), a.throats().getFirst().groups());
		assertTrue(a.sidings().isPresent());
		assertTrue(node.station("B").sidings().isEmpty());
		assertEquals(250, node.station("B").platformLengthM().orElseThrow());
		assertTrue(a.platformLengthM().isEmpty());

		MicroNode.Segment segment = node.segments().getFirst();
		assertEquals(1500, segment.bundles().get("f1").north().lengthM());
		assertEquals(List.of(1L, 2L), segment.bundles().get("f1").north().wayIds());
		assertEquals(2, segment.bundles().get("f1").speedProfile().size());

		assertEquals(List.of("a_main", "a_shared"), node.line("S1").stations().get("A"));
		assertEquals("f1", node.line("S1").bundle().orElseThrow());
	}

	@Test
	void namedTracksAndCapacityGroupsExposeTheSameCount(@TempDir Path dir) throws IOException {
		MicroNode node = MicroNode.read(write(dir, NODE));

		assertEquals(2, node.station("A").group("a_main").trackCount());
		assertEquals(2, node.station("A").group("a_shared").trackCount());
	}

	@Test
	void preferenceFallsBackToWildcardRules(@TempDir Path dir) throws IOException {
		MicroNode node = MicroNode.read(write(dir, NODE));

		assertEquals(List.of("a_main", "a_shared"), node.preferredGroups("S1", "A", true));
		assertEquals(List.of("a_shared"), node.preferredGroups("R99", "A", true));
		assertTrue(node.preferredGroups("R99", "A", false).isEmpty());
	}

	@Test
	void locatesOtherStopsOnASideOfAStation(@TempDir Path dir) throws IOException {
		MicroNode node = MicroNode.read(write(dir, NODE));

		assertEquals(MicroNode.Direction.NORTH, node.sideOf("A", "X").orElseThrow());
		assertEquals(MicroNode.Direction.NORTH, node.sideOf("A", "B").orElseThrow());
		assertEquals(MicroNode.Direction.SOUTH, node.sideOf("B", "A").orElseThrow());
		assertEquals(MicroNode.Direction.NORTH, node.sideOf("B", "C").orElseThrow());
		assertTrue(node.sideOf("B", "Z").isEmpty());
	}

	@Test
	void rejectsLineReferencingUnknownGroup(@TempDir Path dir) throws IOException {
		String broken = NODE.replace("\"B\": [\"b_f1\"]", "\"B\": [\"nope\"]");

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> MicroNode.read(write(dir, broken)));
		assertTrue(error.getMessage().contains("nope"));
	}

	@Test
	void rejectsGroupWithNeitherTracksNorCapacity(@TempDir Path dir) throws IOException {
		String broken = NODE.replace("\"capacity\": 3, ", "");

		assertThrows(IllegalArgumentException.class, () -> MicroNode.read(write(dir, broken)));
	}

	@Test
	void rejectsSegmentBetweenStationsOutsideTheNode(@TempDir Path dir) throws IOException {
		String broken = NODE.replace("\"from\": \"A\", \"to\": \"B\"", "\"from\": \"A\", \"to\": \"Z\"");

		assertThrows(IllegalArgumentException.class, () -> MicroNode.read(write(dir, broken)));
	}

	@Test
	void rejectsConnectionToUnknownBundle(@TempDir Path dir) throws IOException {
		String broken = NODE.replace("\"south\": [\"segment:A_B:f1\"]", "\"south\": [\"segment:A_B:f9\"]");

		assertThrows(IllegalArgumentException.class, () -> MicroNode.read(write(dir, broken)));
	}

	@Test
	void readsEveryNodeOfADirectory(@TempDir Path dir) throws IOException {
		write(dir, NODE);
		Files.writeString(dir.resolve("other.json"), NODE.replace("\"node\": \"test\"", "\"node\": \"other\"")
			.replace("\"id\": \"A\"", "\"id\": \"A2\"").replace("\"A\"", "\"A2\"").replace("A_B", "A2_B"));
		Files.writeString(dir.resolve("README.md"), "ignored");
		Files.writeString(dir.resolve("sidings.json"), "{\"title\": \"not a node\"}");

		List<MicroNode> nodes = MicroNode.readAll(dir);

		assertEquals(List.of("other", "test"), nodes.stream().map(MicroNode::id).toList());
	}

	@Test
	void projectNodesAreConsistent() {
		Path nodes = Path.of("data", "nodes");
		assumeTrue(Files.isDirectory(nodes));

		List<MicroNode> read = MicroNode.readAll(nodes);

		assertEquals(List.of("cadorna-bovisa", "forlanini", "garibaldi", "greco-pirelli", "lambrate", "rho-fiera", "rogoredo", "saronno"),
			read.stream().map(MicroNode::id).toList());
		MicroNode cadorna = read.getFirst();
		assertEquals(List.of("cadorna_s3", "cadorna_saronno", "cadorna_shared"), cadorna.preferredGroups("S3", "S01066", true));
		assertEquals(10, cadorna.station("S01066").groups().stream().mapToInt(MicroNode.Group::trackCount).sum());
	}

	private static Path write(Path dir, String json) throws IOException {
		Path file = dir.resolve("test.json");
		Files.writeString(file, json);
		return file;
	}
}
