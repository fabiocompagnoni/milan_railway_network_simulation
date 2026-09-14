package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.qsimengine.TrainPosition;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailLink;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResource;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
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

class StationTrackResourcesTest {

	/** Records the track argument of the last capacity query. */
	private static final class RecordingManager implements RailResourceManager {
		int lastTrack = Integer.MIN_VALUE;

		@Override
		public boolean hasCapacity(double time, Id<Link> link, int track, TrainPosition position) {
			lastTrack = track;
			return true;
		}

		@Override
		public double tryBlockLink(double time, RailLink link, int track, TrainPosition position) {
			lastTrack = track;
			return 0;
		}

		@Override
		public Collection<RailResource> getResources() {
			return List.of();
		}

		@Override
		public RailLink getLink(Id<Link> id) {
			return null;
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

	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		Node a = network.getFactory().createNode(Id.createNodeId("A"), new Coord(0, 0));
		Node b = network.getFactory().createNode(Id.createNodeId("B"), new Coord(1000, 0));
		network.addNode(a);
		network.addNode(b);
		// A_B and B_A share one single-track resource; C_D is one direction of a double track
		Link section = network.getFactory().createLink(Id.createLinkId("A_B"), a, b);
		section.getAttributes().putAttribute("railsimResourceId", "block_A_B");
		network.addLink(section);
		Link opposite = network.getFactory().createLink(Id.createLinkId("B_A"), b, a);
		opposite.getAttributes().putAttribute("railsimResourceId", "block_A_B");
		network.addLink(opposite);
		Link oneWay = network.getFactory().createLink(Id.createLinkId("C_D"), a, b);
		network.addLink(oneWay);
		Link stop = network.getFactory().createLink(Id.createLinkId("stop_A"), a, a);
		stop.getAttributes().putAttribute("stationLink", true);
		network.addLink(stop);
		return network;
	}

	@Test
	void oneWayLinksAskForAnyFreeTrack() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network());

		resources.hasCapacity(0, Id.createLinkId("C_D"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK, delegate.lastTrack);
	}

	@Test
	void stationLinksAskForAnyFreeTrack() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network());

		resources.hasCapacity(0, Id.createLinkId("stop_A"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK, delegate.lastTrack);
	}

	@Test
	void singleTrackSectionsKeepTheNonBlockingRule() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network());

		resources.hasCapacity(0, Id.createLinkId("A_B"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK_NON_BLOCKING, delegate.lastTrack);
	}
}
