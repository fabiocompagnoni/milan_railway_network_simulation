package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.qsimengine.TrainPosition;
import ch.sbb.matsim.contrib.railsim.qsimengine.deadlocks.SimpleDeadlockAvoidance;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailLink;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResource;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * railsim's deadlock avoidance confined to the resources trains can meet on
 * head to head: those holding a link and its opposite, which are the
 * single-track blocks and the in/out pairs of terminal platforms.
 * <p>
 * {@link SimpleDeadlockAvoidance} treats every resource of capacity 1 as a
 * conflict point and lets a train reserve the ones ahead on its route. On a
 * one-way section that only makes the second of two trains reserve the section
 * in front of the first, which then cannot leave the station: a deadlock the
 * avoidance created. Its start-up analysis also routes every entry/exit pair of
 * each multi-link resource through the network and compares all pairs, which
 * on a throat spanning a hundred approach links takes hours. Both are limited
 * to the two-way resources; one-way links, platforms and throats are governed
 * by capacity and the non-blocking areas alone.
 */
public final class SingleTrackDeadlockAvoidance extends SimpleDeadlockAvoidance {

	private final Network network;
	private final Map<Id<RailResource>, Boolean> twoWay = new ConcurrentHashMap<>();

	@Inject
	public SingleTrackDeadlockAvoidance(Network network, EventsManager eventsManager) {
		super(network, eventsManager);
		this.network = network;
	}

	@Override
	public void initResources(RailResourceManager rrm) {
		super.initResources(twoWayOnly(rrm, network));
	}

	@Override
	public boolean checkLink(double time, RailLink link, TrainPosition position) {
		return !isTwoWay(link.getResource()) || super.checkLink(time, link, position);
	}

	@Override
	public boolean isReserved(RailResource resource) {
		return isTwoWay(resource) && super.isReserved(resource);
	}

	/** A view of the manager whose resource listing keeps only the two-way resources. */
	static RailResourceManager twoWayOnly(RailResourceManager rrm, Network network) {
		return new TwoWayOnly(rrm, network);
	}

	/** Whether the resource holds some link together with its opposite direction. */
	static boolean isTwoWay(RailResource resource, Network network) {
		if (resource == null) {
			return false;
		}
		List<? extends Link> links = resource.getLinks().stream().map(link -> network.getLinks().get(link.getLinkId())).toList();
		for (Link link : links) {
			for (Link other : links) {
				if (other.getFromNode().equals(link.getToNode()) && other.getToNode().equals(link.getFromNode())
						&& !link.getFromNode().equals(link.getToNode())) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isTwoWay(RailResource resource) {
		return resource != null && twoWay.computeIfAbsent(resource.getId(), id -> isTwoWay(resource, network));
	}

	private record TwoWayOnly(RailResourceManager delegate, Network network) implements RailResourceManager {

		@Override
		public Collection<RailResource> getResources() {
			return delegate.getResources().stream().filter(resource -> isTwoWay(resource, network)).toList();
		}

		@Override
		public RailLink getLink(Id<Link> id) {
			return delegate.getLink(id);
		}

		@Override
		public double tryBlockLink(double time, RailLink link, int track, TrainPosition position) {
			return delegate.tryBlockLink(time, link, track, position);
		}

		@Override
		public boolean hasCapacity(double time, Id<Link> link, int track, TrainPosition position) {
			return delegate.hasCapacity(time, link, track, position);
		}

		@Override
		public void setCapacity(Id<Link> link, int newCapacity) {
			delegate.setCapacity(link, newCapacity);
		}

		@Override
		public boolean isBlockedBy(RailLink link, TrainPosition position) {
			return delegate.isBlockedBy(link, position);
		}

		@Override
		public void releaseLink(double time, RailLink link, MobsimDriverAgent driver) {
			delegate.releaseLink(time, link, driver);
		}

		@Override
		public boolean checkReroute(double time, RailLink start, RailLink end, List<RailLink> subRoute,
				List<RailLink> detour, TrainPosition position) {
			return delegate.checkReroute(time, start, end, subRoute, detour, position);
		}
	}
}
