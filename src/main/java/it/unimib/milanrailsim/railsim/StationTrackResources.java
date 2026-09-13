package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.qsimengine.TrainPosition;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailLink;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResource;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManagerImpl;
import jakarta.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.mobsim.qsim.QSim;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets station links fill every platform track. Railsim keeps one track of a
 * resource free for the opposite direction whenever a train already entered
 * through the same link ({@code ANY_TRACK_NON_BLOCKING}); a station loop is a
 * single link for both directions, so that rule would leave a two-track
 * crossing station able to hold one train, and planned meets deadlock. On
 * links flagged {@code stationLink} any free track is requested instead;
 * sections keep railsim's rule.
 */
public final class StationTrackResources implements RailResourceManager {

	private final RailResourceManager delegate;
	private final Set<Id<Link>> stationLinks;

	@Inject
	public StationTrackResources(RailResourceManagerImpl delegate, QSim qsim) {
		this(delegate, qsim.getScenario().getNetwork());
	}

	StationTrackResources(RailResourceManager delegate, Network network) {
		this.delegate = delegate;
		this.stationLinks = network.getLinks().values().stream()
			.filter(link -> link.getAttributes().getAttribute("stationLink") != null)
			.map(Link::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	private int trackFor(Id<Link> link, int track) {
		return track == ANY_TRACK_NON_BLOCKING && stationLinks.contains(link) ? ANY_TRACK : track;
	}

	@Override
	public double tryBlockLink(double time, RailLink link, int track, TrainPosition position) {
		return delegate.tryBlockLink(time, link, trackFor(link.getLinkId(), track), position);
	}

	@Override
	public boolean hasCapacity(double time, Id<Link> link, int track, TrainPosition position) {
		return delegate.hasCapacity(time, link, trackFor(link, track), position);
	}

	@Override
	public Collection<RailResource> getResources() {
		return delegate.getResources();
	}

	@Override
	public RailLink getLink(Id<Link> id) {
		return delegate.getLink(id);
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
