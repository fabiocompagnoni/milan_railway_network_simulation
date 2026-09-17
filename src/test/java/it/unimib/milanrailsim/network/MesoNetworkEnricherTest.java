package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MesoNetworkEnricherTest {

	private Network network;

	private static MesoMeasuresTable.Measure measure(Optional<Integer> tracks, Optional<Double> lengthM,
			Optional<Double> eqSpeedKmh) {
		return new MesoMeasuresTable.Measure("ignored", tracks, lengthM, eqSpeedKmh,
			Optional.of(120), "12 34");
	}

	@BeforeEach
	void createSkeletonPair() {
		network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("S1"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("S2"), new Coord(1400, 0));
		network.addNode(a);
		network.addNode(b);
		for (Node[] pair : new Node[][] { { a, b }, { b, a } }) {
			Link link = network.getFactory().createLink(
				Id.createLinkId(pair[0].getId() + "_" + pair[1].getId()), pair[0], pair[1]);
			link.setLength(1400);
			link.setFreespeed(10);
			link.setCapacity(3600);
			link.setNumberOfLanes(1);
			link.setAllowedModes(Set.of("rail"));
			link.getAttributes().putAttribute("railsimTrainCapacity", 1);
			link.getAttributes().putAttribute("dataStatus", "provisional");
			network.addLink(link);
		}
	}

	private Link link(String id) {
		return network.getLinks().get(Id.createLinkId(id));
	}

	@Test
	void doubleTrackHoldsOneTrainPerBlockSectionAndDirection() {
		// 8.3 km of double track: three spacings of 2.7 km per direction; a short section still holds one
		new MesoNetworkEnricher(Map.of(
			"S1_S2", measure(Optional.of(2), Optional.of(8300.0), Optional.of(110.0)),
			"S2_S1", measure(Optional.of(2), Optional.of(1500.0), Optional.of(110.0)))).enrich(network);

		assertEquals(3, link("S1_S2").getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(1, link("S2_S1").getAttributes().getAttribute("railsimTrainCapacity"));
	}

	@Test
	void quadrupleTrackBecomesCapacityTwoPerDirection() {
		// the measures CSV carries one row per DIRECTED link: both directions present
		new MesoNetworkEnricher(Map.of(
			"S1_S2", measure(Optional.of(4), Optional.of(1500.0), Optional.of(110.0)),
			"S2_S1", measure(Optional.of(4), Optional.of(1500.0), Optional.of(110.0)))).enrich(network);
		for (String id : new String[] { "S1_S2", "S2_S1" }) {
			Link l = link(id);
			assertEquals(2, l.getAttributes().getAttribute("railsimTrainCapacity"));
			assertEquals(1500.0, l.getLength());
			assertEquals(110.0 / 3.6, l.getFreespeed(), 1e-9);
			assertNull(l.getAttributes().getAttribute("dataStatus"));
			assertNull(l.getAttributes().getAttribute("railsimResourceId"));
			assertEquals("12 34", l.getAttributes().getAttribute("osmWayIds"));
		}
	}

	@Test
	void singleTrackSharesOneResourceAcrossDirections() {
		new MesoNetworkEnricher(Map.of(
			"S1_S2", measure(Optional.of(1), Optional.of(1500.0), Optional.of(90.0)),
			"S2_S1", measure(Optional.of(1), Optional.of(1500.0), Optional.of(90.0)))).enrich(network);
		assertEquals(1, link("S1_S2").getAttributes().getAttribute("railsimTrainCapacity"));
		String resource = (String) link("S1_S2").getAttributes().getAttribute("railsimResourceId");
		assertNotNull(resource);
		assertEquals(resource, link("S2_S1").getAttributes().getAttribute("railsimResourceId"));
	}

	@Test
	void partialMeasureKeepsProvisionalMark() {
		// tracks measured but no trustworthy speed: link improves but stays provisional,
		// and the placeholder freespeed is re-derived from the corrected length
		new MesoNetworkEnricher(Map.of("S1_S2",
			measure(Optional.of(4), Optional.of(1500.0), Optional.empty()))).enrich(network);
		Link l = link("S1_S2");
		assertEquals(2, l.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(1500.0, l.getLength());
		assertEquals(1500.0 / 120, l.getFreespeed(), 1e-9);
		assertEquals("provisional", l.getAttributes().getAttribute("dataStatus"));
	}

	@Test
	void unmeasuredLinkIsUntouched() {
		new MesoNetworkEnricher(Map.of()).enrich(network);
		Link l = link("S1_S2");
		assertEquals(1, l.getAttributes().getAttribute("railsimTrainCapacity"));
		assertEquals(1400.0, l.getLength());
		assertEquals("provisional", l.getAttributes().getAttribute("dataStatus"));
	}

	@Test
	void measureForUnknownLinkFailsFast() {
		MesoNetworkEnricher enricher = new MesoNetworkEnricher(Map.of("S9_S8",
			measure(Optional.of(2), Optional.of(1000.0), Optional.of(100.0))));
		assertThrows(IllegalArgumentException.class, () -> enricher.enrich(network));
	}
}
