package it.unimib.milanrailsim.schedule;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Set;

/** Synthetic networks matching the gtfs-minimal fixture, shared by schedule tests. */
final class TestNetworks {

	private TestNetworks() {
	}

	/** Stations S1, S2, S3 connected by one-way rail links, as served by the fixture trips. */
	static Network threeStationLine() {
		Network network = NetworkUtils.createNetwork();
		for (String station : List.of("S1", "S2", "S3")) {
			network.addNode(network.getFactory().createNode(Id.createNodeId(station), new Coord(0, 0)));
		}
		addRail(network, "S1", "S2");
		addRail(network, "S2", "S3");
		return network;
	}

	private static void addRail(Network network, String from, String to) {
		Link link = network.getFactory().createLink(Id.createLinkId(from + "_" + to),
			network.getNodes().get(Id.createNodeId(from)), network.getNodes().get(Id.createNodeId(to)));
		link.setLength(2000);
		link.setFreespeed(30);
		link.setAllowedModes(Set.of("rail"));
		network.addLink(link);
	}
}
