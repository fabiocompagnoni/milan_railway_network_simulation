package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApplyMesoMeasuresTest {

	@TempDir
	Path dir;

	@Test
	void enrichesSkeletonAndWritesMesoNetwork() throws IOException {
		Path skeleton = dir.resolve("skeleton.xml");
		Path csv = dir.resolve("measures.csv");
		Path output = dir.resolve("out/network.xml");

		Network net = NetworkUtils.createNetwork();
		Node a = net.getFactory().createNode(Id.createNodeId("S1"), new Coord(0, 0));
		Node b = net.getFactory().createNode(Id.createNodeId("S2"), new Coord(1400, 0));
		net.addNode(a);
		net.addNode(b);
		Link l = net.getFactory().createLink(Id.createLinkId("S1_S2"), a, b);
		l.setLength(1400);
		l.setFreespeed(10);
		l.setCapacity(3600);
		l.setNumberOfLanes(1);
		l.setAllowedModes(Set.of("rail"));
		l.getAttributes().putAttribute("railsimTrainCapacity", 1);
		l.getAttributes().putAttribute("dataStatus", "provisional");
		net.addLink(l);
		new NetworkWriter(net).write(skeleton.toString());

		Files.writeString(csv, "link_id,from,to,status,n_tracks,passenger_lines_mode,"
			+ "osm_min_m,osm_max_m,beeline_m,ratio,eq_speed_kmh,untagged_pct,"
			+ "gtfs_min_s,gtfs_trips,implied_kmh,gtfs_routes,osm_way_ids\n"
			+ "S1_S2,S1,S2,ok,4,4,1500.0,1550.0,1400.0,1.0714,110.0,0.0,120,68,45.0,S1,12 34\n");

		ApplyMesoMeasures.run(skeleton, csv, output);

		Network reread = NetworkUtils.readNetwork(output.toString());
		Link enriched = reread.getLinks().get(Id.createLinkId("S1_S2"));
		assertEquals(1500.0, enriched.getLength());
		assertEquals(110.0 / 3.6, enriched.getFreespeed(), 1e-6);
		assertEquals(2, enriched.getAttributes().getAttribute("railsimTrainCapacity"));
		assertNull(enriched.getAttributes().getAttribute("dataStatus"));
		assertEquals("12 34", enriched.getAttributes().getAttribute("osmWayIds"));
	}
}
