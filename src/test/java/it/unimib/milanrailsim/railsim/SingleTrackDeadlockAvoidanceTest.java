package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.qsimengine.TrainPosition;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailLink;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResource;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceState;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceType;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.network.NetworkUtils;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleTrackDeadlockAvoidanceTest {

	private record StubResource(Id<RailResource> id, List<RailLink> links) implements RailResource {
		@Override
		public Id<RailResource> getId() {
			return id;
		}

		@Override
		public ResourceType getType() {
			return ResourceType.fixedBlock;
		}

		@Override
		public List<RailLink> getLinks() {
			return links;
		}

		@Override
		public int getTotalCapacity() {
			return 1;
		}

		@Override
		public ResourceState getState(RailLink link) {
			return ResourceState.EMPTY;
		}
	}

	private record StubManager(Collection<RailResource> resources) implements RailResourceManager {
		@Override
		public Collection<RailResource> getResources() {
			return resources;
		}

		@Override
		public RailLink getLink(Id<Link> id) {
			return null;
		}

		@Override
		public double tryBlockLink(double time, RailLink link, int track, TrainPosition position) {
			return 0;
		}

		@Override
		public boolean hasCapacity(double time, Id<Link> link, int track, TrainPosition position) {
			return true;
		}

		@Override
		public void setCapacity(Id<Link> link, int newCapacity) {
		}

		@Override
		public boolean isBlockedBy(RailLink link, TrainPosition position) {
			return false;
		}

		@Override
		public void releaseLink(double time, RailLink link, MobsimDriverAgent driver) {
		}

		@Override
		public boolean checkReroute(double time, RailLink start, RailLink end, List<RailLink> subRoute,
				List<RailLink> detour, TrainPosition position) {
			return true;
		}
	}

	@Test
	void keepsOnlyResourcesHoldingBothDirectionsOfALink() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
		Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(200, 0));
		Link there = NetworkUtils.createAndAddLink(network, Id.createLinkId("a_b"), a, b, 100, 10, 1, 1);
		Link back = NetworkUtils.createAndAddLink(network, Id.createLinkId("b_a"), b, a, 100, 10, 1, 1);
		Link oneWay = NetworkUtils.createAndAddLink(network, Id.createLinkId("b_c"), b, c, 100, 10, 1, 1);
		Link approach = NetworkUtils.createAndAddLink(network, Id.createLinkId("c_a"), c, a, 100, 10, 1, 1);
		RailResource singleTrack = new StubResource(Id.create("block", RailResource.class),
			List.of(new RailLink(there, back), new RailLink(back, there)));
		RailResource section = new StubResource(Id.create("b_c", RailResource.class), List.of(new RailLink(oneWay, null)));
		RailResource throat = new StubResource(Id.create("throat", RailResource.class),
			List.of(new RailLink(approach, null), new RailLink(oneWay, null)));

		RailResourceManager view = SingleTrackDeadlockAvoidance.twoWayOnly(
			new StubManager(List.of(singleTrack, section, throat)), network);

		assertEquals(List.of(singleTrack), List.copyOf(view.getResources()));
		assertTrue(SingleTrackDeadlockAvoidance.isTwoWay(singleTrack, network));
		assertFalse(SingleTrackDeadlockAvoidance.isTwoWay(section, network));
		assertFalse(SingleTrackDeadlockAvoidance.isTwoWay(throat, network));
	}
}
