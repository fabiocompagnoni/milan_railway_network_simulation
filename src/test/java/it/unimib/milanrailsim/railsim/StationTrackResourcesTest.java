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

	/** A train whose head and tail sit on the given links, with the given route ahead. */
	private static TrainPosition train(Network network, String headLink, String tailLink, String... route) {
		return train(network, headLink, tailLink, null, java.util.Set.of(), route);
	}

	/** @param stops route links the train stops on; {@code nextStopLink} is the one it stops on next */
	private static TrainPosition train(Network network, String headLink, String tailLink, String nextStopLink,
			java.util.Set<String> stops, String... route) {
		return new TrainPosition() {
			@Override
			public MobsimDriverAgent getDriver() {
				return null;
			}

			@Override
			public ch.sbb.matsim.contrib.railsim.qsimengine.RailsimTransitDriverAgent getPt() {
				return null;
			}

			@Override
			public ch.sbb.matsim.contrib.railsim.qsimengine.TrainInfo getTrain() {
				return null;
			}

			@Override
			public Id<Link> getHeadLink() {
				return Id.createLinkId(headLink);
			}

			@Override
			public Id<Link> getTailLink() {
				return Id.createLinkId(tailLink);
			}

			@Override
			public double getHeadPosition() {
				return 0;
			}

			@Override
			public double getTailPosition() {
				return 0;
			}

			@Override
			public double getDelay() {
				return 0;
			}

			@Override
			public int getRouteIndex() {
				return 0;
			}

			@Override
			public int getRouteSize() {
				return route.length;
			}

			@Override
			public RailLink getRoute(int idx) {
				return new RailLink(network.getLinks().get(Id.createLinkId(route[idx])), null);
			}

			@Override
			public List<RailLink> getRoute(int from, int to) {
				return List.of();
			}

			@Override
			public List<RailLink> getRouteUntilNextStop() {
				return List.of();
			}

			@Override
			public boolean isStop(Id<Link> link) {
				return stops.contains(link.toString());
			}

			@Override
			public org.matsim.pt.transitSchedule.api.TransitStopFacility getNextStop() {
				if (nextStopLink == null) {
					return null;
				}
				org.matsim.pt.transitSchedule.api.TransitStopFacility facility = new org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl()
					.createTransitStopFacility(Id.create(nextStopLink, org.matsim.pt.transitSchedule.api.TransitStopFacility.class),
						new Coord(0, 0), false);
				facility.setLinkId(Id.createLinkId(nextStopLink));
				return facility;
			}
		};
	}

	@Test
	void detoursWaitUntilTheTailIsOnTheCurrentRoute() {
		Network network = network();
		StationTrackResources resources = new StationTrackResources(new RecordingManager(), network, java.util.Map.of());

		assertFalse(resources.checkReroute(0, null, null, List.of(), List.of(), train(network, "C_D", "A_B", "C_D", "stop_A")),
			"the tail is still on the previous route");
		assertTrue(resources.checkReroute(0, null, null, List.of(), List.of(), train(network, "C_D", "C_D", "C_D", "stop_A")));
	}

	@Test
	void noDetourThatWouldDropAStopBeyondTheNextOne() {
		Network network = network();
		StationTrackResources resources = new StationTrackResources(new RecordingManager(), network, java.util.Map.of());
		RailLink section = new RailLink(network.getLinks().get(Id.createLinkId("C_D")), null);
		RailLink stop = new RailLink(network.getLinks().get(Id.createLinkId("stop_A")), null);

		assertFalse(resources.checkReroute(0, null, null, List.of(section, stop), List.of(),
			train(network, "C_D", "C_D", "A_B", java.util.Set.of("A_B", "stop_A"), "C_D", "A_B", "stop_A")),
			"the detour covers stop_A while the train still heads for A_B");
		assertTrue(resources.checkReroute(0, null, null, List.of(section), List.of(),
			train(network, "C_D", "C_D", "stop_A", java.util.Set.of("stop_A"), "C_D", "stop_A")),
			"a detour that touches no stop is fine");
	}

	@Test
	void aDetourAroundTheNextStopMustReachAPlatformOfItsArea() {
		Network network = network();
		Id<org.matsim.pt.transitSchedule.api.TransitStopArea> area = Id.create("A", org.matsim.pt.transitSchedule.api.TransitStopArea.class);
		StationTrackResources resources = new StationTrackResources(new RecordingManager(), network,
			java.util.Map.of(Id.createLinkId("stop_A"), area, Id.createLinkId("A_B"), area));
		RailLink stop = new RailLink(network.getLinks().get(Id.createLinkId("stop_A")), null);
		RailLink other = new RailLink(network.getLinks().get(Id.createLinkId("A_B")), null);
		RailLink elsewhere = new RailLink(network.getLinks().get(Id.createLinkId("C_D")), null);
		TrainPosition train = train(network, "C_D", "C_D", "stop_A", java.util.Set.of("stop_A"), "C_D", "stop_A");

		assertTrue(resources.checkReroute(0, null, null, List.of(stop), List.of(other), train), "railsim can remap the stop");
		assertFalse(resources.checkReroute(0, null, null, List.of(stop), List.of(elsewhere), train),
			"no platform of the stop's area on the detour: the stop would be skipped");
	}

	@Test
	void noDetourWhileStandingOnAStationLoop() {
		Network network = network();
		StationTrackResources resources = new StationTrackResources(new RecordingManager(), network, java.util.Map.of());

		assertFalse(resources.checkReroute(0, null, null, List.of(), List.of(), train(network, "stop_A", "stop_A", "stop_A", "C_D")));
	}

	@Test
	void oneWayLinksAskForAnyFreeTrack() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network(), java.util.Map.of());

		resources.hasCapacity(0, Id.createLinkId("C_D"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK, delegate.lastTrack);
	}

	@Test
	void stationLinksAskForAnyFreeTrack() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network(), java.util.Map.of());

		resources.hasCapacity(0, Id.createLinkId("stop_A"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK, delegate.lastTrack);
	}

	@Test
	void singleTrackSectionsKeepTheNonBlockingRule() {
		RecordingManager delegate = new RecordingManager();
		StationTrackResources resources = new StationTrackResources(delegate, network(), java.util.Map.of());

		resources.hasCapacity(0, Id.createLinkId("A_B"), RailResourceManager.ANY_TRACK_NON_BLOCKING, null);

		assertEquals(RailResourceManager.ANY_TRACK_NON_BLOCKING, delegate.lastTrack);
	}
}
