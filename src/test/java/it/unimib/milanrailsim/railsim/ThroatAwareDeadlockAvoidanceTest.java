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

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThroatAwareDeadlockAvoidanceTest {

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
	void hidesResourcesWhoseLinksBelongToAThroat() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
		Link section = NetworkUtils.createAndAddLink(network, Id.createLinkId("section"), a, b, 100, 10, 1, 1);
		Link approach = NetworkUtils.createAndAddLink(network, Id.createLinkId("approach"), b, a, 100, 10, 1, 1);
		approach.getAttributes().putAttribute(ThroatAwareDeadlockAvoidance.THROAT_ATTRIBUTE, "north");
		RailResource singleTrack = new StubResource(Id.create("section", RailResource.class), List.of(new RailLink(section, null)));
		RailResource throat = new StubResource(Id.create("throat", RailResource.class), List.of(new RailLink(approach, null)));

		RailResourceManager view = ThroatAwareDeadlockAvoidance.withoutThroats(new StubManager(List.of(singleTrack, throat)), network);

		assertEquals(List.of(singleTrack), List.copyOf(view.getResources()));
	}
}
