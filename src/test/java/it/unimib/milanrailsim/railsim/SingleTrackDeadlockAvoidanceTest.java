package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.qsimengine.TrainPosition;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailLink;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResource;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceTestSupport;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceState;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceType;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;

import java.lang.reflect.Proxy;
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

	/** A train on a fixed route, standing at its first link. */
	private static TrainPosition train(RailLink... route) {
		MobsimDriverAgent driver = (MobsimDriverAgent) Proxy.newProxyInstance(MobsimDriverAgent.class.getClassLoader(),
			new Class<?>[] { MobsimDriverAgent.class }, (proxy, method, args) -> switch (method.getName()) {
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> "driver";
				default -> null;
			});
		return new TrainPosition() {
			@Override
			public MobsimDriverAgent getDriver() {
				return driver;
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
				return route[0].getLinkId();
			}

			@Override
			public Id<Link> getTailLink() {
				return route[0].getLinkId();
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
				return 1;
			}

			@Override
			public int getRouteSize() {
				return route.length;
			}

			@Override
			public RailLink getRoute(int idx) {
				return route[idx];
			}

			@Override
			public List<RailLink> getRoute(int from, int to) {
				return List.of(route).subList(from, to);
			}

			@Override
			public List<RailLink> getRouteUntilNextStop() {
				return List.of(route);
			}

			@Override
			public boolean isStop(Id<Link> link) {
				return false;
			}

			@Override
			public org.matsim.pt.transitSchedule.api.TransitStopFacility getNextStop() {
				return null;
			}
		};
	}

	@Test
	void aTrainEntersASingleTrackBlockOnlyIfTheCrossingStationKeepsATrackForTheMeet() {
		// W -single- S -single- E: S is the crossing station with two tracks
		Network network = NetworkUtils.createNetwork();
		Node w = NetworkUtils.createAndAddNode(network, Id.createNodeId("W"), new Coord(0, 0));
		Node s = NetworkUtils.createAndAddNode(network, Id.createNodeId("S"), new Coord(1000, 0));
		Node e = NetworkUtils.createAndAddNode(network, Id.createNodeId("E"), new Coord(2000, 0));
		Link stopW = loop(network, w, "stop_W", 2);
		Link stopS = loop(network, s, "stop_S", 2);
		Link stopE = loop(network, e, "stop_E", 2);
		Link ws = NetworkUtils.createAndAddLink(network, Id.createLinkId("W_S"), w, s, 1000, 10, 1, 1);
		Link sw = NetworkUtils.createAndAddLink(network, Id.createLinkId("S_W"), s, w, 1000, 10, 1, 1);
		Link se = NetworkUtils.createAndAddLink(network, Id.createLinkId("S_E"), s, e, 1000, 10, 1, 1);
		Link es = NetworkUtils.createAndAddLink(network, Id.createLinkId("E_S"), e, s, 1000, 10, 1, 1);
		RailLink atW = new RailLink(stopW, null);
		RailLink atS = new RailLink(stopS, null);
		RailLink atE = new RailLink(stopE, null);
		RailLink east1 = new RailLink(ws, sw);
		RailLink west1 = new RailLink(sw, ws);
		RailLink east2 = new RailLink(se, es);
		RailLink west2 = new RailLink(es, se);
		RailResource blockWS = RailResourceTestSupport.fixedBlock("block_W_S", List.of(east1, west1));
		RailResourceTestSupport.fixedBlock("block_S_E", List.of(east2, west2));
		RailResource stationS = RailResourceTestSupport.fixedBlock("stop_S", List.of(atS));
		RailResourceTestSupport.fixedBlock("stop_W", List.of(atW));
		RailResourceTestSupport.fixedBlock("stop_E", List.of(atE));
		SingleTrackDeadlockAvoidance avoidance = new SingleTrackDeadlockAvoidance(network, EventsUtils.createEventsManager());
		TrainPosition first = train(atW, east1, atS, east2, atE);
		TrainPosition second = train(atW, east1, atS, east2, atE);
		TrainPosition opposing = train(atE, west2, atS, west1, atW);

		assertTrue(avoidance.checkLink(0, east1, first), "an empty crossing station admits the first train");
		avoidance.onReserve(0, blockWS, first);
		assertTrue(avoidance.checkLink(1, east1, first), "a train holding the block is never held back in it");
		avoidance.onReserve(1, stationS, first);
		avoidance.onRelease(2, blockWS, first.getDriver());

		assertFalse(avoidance.checkLink(3, east1, second),
			"a second train of the same direction would fill the station and leave no track for the meet");
		assertTrue(avoidance.checkLink(3, west2, opposing), "the opposing train takes the free track and the meet happens");

		avoidance.onRelease(4, stationS, first.getDriver());
		assertTrue(avoidance.checkLink(5, east1, second), "once the first train left, the second may follow");
	}

	@Test
	void theStationItselfKeepsATrackForTheMeetWhateverLinkTheTrainComesInThrough() {
		// D =double= S -single- E: S is entered over double track from D, so no block rule holds trains back there
		Network network = NetworkUtils.createNetwork();
		Node d = NetworkUtils.createAndAddNode(network, Id.createNodeId("D"), new Coord(0, 0));
		Node s = NetworkUtils.createAndAddNode(network, Id.createNodeId("S"), new Coord(1000, 0));
		Node e = NetworkUtils.createAndAddNode(network, Id.createNodeId("E"), new Coord(2000, 0));
		Link stopS = loop(network, s, "stop_S", 2);
		Link ds = NetworkUtils.createAndAddLink(network, Id.createLinkId("D_S"), d, s, 1000, 10, 1, 1);
		Link se = NetworkUtils.createAndAddLink(network, Id.createLinkId("S_E"), s, e, 1000, 10, 1, 1);
		Link es = NetworkUtils.createAndAddLink(network, Id.createLinkId("E_S"), e, s, 1000, 10, 1, 1);
		RailLink atS = new RailLink(stopS, null);
		RailLink fromD = new RailLink(ds, null);
		RailLink east = new RailLink(se, es);
		RailLink west = new RailLink(es, se);
		RailResourceTestSupport.fixedBlock("D_S", List.of(fromD));
		RailResourceTestSupport.fixedBlock("block_S_E", List.of(east, west));
		RailResource stationS = RailResourceTestSupport.fixedBlock("stop_S", List.of(atS));
		SingleTrackDeadlockAvoidance avoidance = new SingleTrackDeadlockAvoidance(network, EventsUtils.createEventsManager());
		TrainPosition first = train(fromD, atS, east);
		TrainPosition second = train(fromD, atS, east);
		TrainPosition opposing = train(west, atS);

		assertTrue(avoidance.checkLink(0, atS, first));
		avoidance.onReserve(0, stationS, first);
		assertTrue(avoidance.checkLink(1, atS, first), "a train holding the station is not questioned again");
		assertFalse(avoidance.checkLink(2, atS, second), "a second train from the same side would leave no track for the meet");
		assertTrue(avoidance.checkLink(2, atS, opposing), "the opposing train gets the free track");
	}

	private static Link loop(Network network, Node node, String id, int tracks) {
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), node, node, 50, 10, 1, 1);
		link.getAttributes().putAttribute("railsimTrainCapacity", tracks);
		return link;
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
