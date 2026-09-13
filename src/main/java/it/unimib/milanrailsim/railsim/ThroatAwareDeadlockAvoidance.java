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

/**
 * railsim's deadlock avoidance, minus its start-up analysis of station throats.
 * <p>
 * {@link SimpleDeadlockAvoidance} routes every entry/exit pair of each
 * multi-link resource through the whole network and then compares all pairs
 * with each other, whatever the resource's capacity: quadratic in routes and
 * quartic in links, so a throat spanning a hundred approach links (Garibaldi)
 * takes hours and tens of gigabytes. At run time the avoidance only acts on
 * resources of capacity 1, and throats are built with a higher capacity, so
 * the table would never be consulted: the throats are hidden from the analysis
 * and single-track sections keep their protection.
 */
public final class ThroatAwareDeadlockAvoidance extends SimpleDeadlockAvoidance {

	static final String THROAT_ATTRIBUTE = "microThroat";

	private final Network network;

	@Inject
	public ThroatAwareDeadlockAvoidance(Network network, EventsManager eventsManager) {
		super(network, eventsManager);
		this.network = network;
	}

	@Override
	public void initResources(RailResourceManager rrm) {
		super.initResources(withoutThroats(rrm, network));
	}

	/** A view of the manager whose resource listing omits station throats. */
	static RailResourceManager withoutThroats(RailResourceManager rrm, Network network) {
		return new WithoutThroats(rrm, network);
	}

	private static boolean isThroat(RailResource resource, Network network) {
		return resource.getLinks().stream()
			.map(link -> network.getLinks().get(link.getLinkId()))
			.anyMatch(link -> link.getAttributes().getAttribute(THROAT_ATTRIBUTE) != null);
	}

	private record WithoutThroats(RailResourceManager delegate, Network network) implements RailResourceManager {

		@Override
		public Collection<RailResource> getResources() {
			return delegate.getResources().stream().filter(resource -> !isThroat(resource, network)).toList();
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
