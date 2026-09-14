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
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopArea;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets one-way links fill every track of their resource. Railsim keeps one
 * track of a resource free for the opposite direction whenever a train already
 * entered through the same link ({@code ANY_TRACK_NON_BLOCKING}). That fits a
 * single-track section shared by both directions, and nothing else here: a
 * station loop is one link for both directions, so the rule would leave a
 * two-track crossing station able to hold one train and planned meets would
 * deadlock; a double-track section is one link per direction, so the rule
 * would halve it to one train per link. Links flagged {@code stationLink} and
 * links whose resource is theirs alone ask for any free track instead; a
 * resource shared with other links, such as a single-track block or a throat,
 * keeps railsim's rule.
 */
public final class StationTrackResources implements RailResourceManager {

	private final RailResourceManager delegate;
	private final Set<Id<Link>> anyTrackLinks;
	private final Set<Id<Link>> loopLinks;
	private final Map<Id<Link>, Id<TransitStopArea>> areaOfLink;

	@Inject
	public StationTrackResources(RailResourceManagerImpl delegate, QSim qsim) {
		this(delegate, qsim.getScenario().getNetwork(), stopAreas(qsim.getScenario().getTransitSchedule()));
	}

	/** @param areaOfLink the stop area each platform link belongs to, for the links that have one */
	StationTrackResources(RailResourceManager delegate, Network network, Map<Id<Link>, Id<TransitStopArea>> areaOfLink) {
		this.delegate = delegate;
		this.areaOfLink = Map.copyOf(areaOfLink);
		this.anyTrackLinks = network.getLinks().values().stream()
			.filter(link -> link.getAttributes().getAttribute("stationLink") != null || ownsItsResource(link))
			.map(Link::getId)
			.collect(Collectors.toUnmodifiableSet());
		this.loopLinks = network.getLinks().values().stream()
			.filter(link -> link.getFromNode().equals(link.getToNode()))
			.map(Link::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	private static Map<Id<Link>, Id<TransitStopArea>> stopAreas(TransitSchedule schedule) {
		Map<Id<Link>, Id<TransitStopArea>> areas = new HashMap<>();
		for (TransitStopFacility facility : schedule.getFacilities().values()) {
			if (facility.getStopAreaId() != null) {
				areas.put(facility.getLinkId(), facility.getStopAreaId());
			}
		}
		return areas;
	}

	/** A link without a declared resource, or whose resource carries its own id, is the only link of that resource. */
	private static boolean ownsItsResource(Link link) {
		Object resource = link.getAttributes().getAttribute("railsimResourceId");
		return resource == null || resource.toString().equals(link.getId().toString());
	}

	private int trackFor(Id<Link> link, int track) {
		return track == ANY_TRACK_NON_BLOCKING && anyTrackLinks.contains(link) ? ANY_TRACK : track;
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

	/**
	 * A detour replaces the train's route and keeps only the previous one for
	 * the tail to finish. A train that departs from a platform with its tail
	 * still on the approach link has its tail on that previous route; a second
	 * detour before the tail catches up would drop it and railsim could no
	 * longer place the tail. Nor is a train standing on a station loop given a
	 * detour: leaving a loop it may be reversing, and railsim rebuilds head and
	 * tail from the route at that moment, which a detour swapped in at the same
	 * moment corrupts. Both keep their planned route; the next section offers
	 * the detour again.
	 */
	@Override
	public boolean checkReroute(double time, RailLink start, RailLink end, List<RailLink> subRoute,
			List<RailLink> detour, TrainPosition position) {
		return tailOnRoute(position) && !loopLinks.contains(position.getHeadLink()) && keepsStops(subRoute, detour, position)
			&& delegate.checkReroute(time, start, end, subRoute, detour, position);
	}

	/**
	 * railsim remaps only the train's next stop onto a detour, and only onto a
	 * platform of that stop's area. A detour decided while the train still heads
	 * for the stop before, covering the platform of the one after, would drop
	 * that later stop from the route; a detour around the next stop that reaches
	 * no platform of its area would drop the next stop itself. Either way the
	 * train would run through the station: such detours are refused and the
	 * planned platform is waited for instead.
	 */
	boolean keepsStops(List<RailLink> subRoute, List<RailLink> detour, TrainPosition position) {
		Id<Link> nextStopLink = position.getNextStop() == null ? null : position.getNextStop().getLinkId();
		boolean aroundNextStop = false;
		for (RailLink link : subRoute) {
			if (position.isStop(link.getLinkId())) {
				if (!link.getLinkId().equals(nextStopLink)) {
					return false;
				}
				aroundNextStop = true;
			}
		}
		if (!aroundNextStop) {
			return true;
		}
		Id<TransitStopArea> area = areaOfLink.get(nextStopLink);
		return area != null && detour.stream().anyMatch(link -> area.equals(areaOfLink.get(link.getLinkId())));
	}

	static boolean tailOnRoute(TrainPosition position) {
		Id<Link> tail = position.getTailLink();
		if (tail == null) {
			return true;
		}
		for (int i = 0; i < position.getRouteSize(); i++) {
			if (position.getRoute(i).getLinkId().equals(tail)) {
				return true;
			}
		}
		return false;
	}
}
