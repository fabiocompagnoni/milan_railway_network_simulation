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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
	private final Map<Id<Link>, Set<Id<TransitStopArea>>> areasOfLink;
	private final Function<TrainPosition, Set<Id<Link>>> callsOfTrip;

	@Inject
	public StationTrackResources(RailResourceManagerImpl delegate, QSim qsim) {
		this(delegate, qsim.getScenario().getNetwork(), stopAreas(qsim.getScenario().getTransitSchedule()),
			StationTrackResources::callsOfCurrentTrip);
	}

	/**
	 * @param areasOfLink the stop areas each platform link belongs to, for the links that have any
	 * @param callsOfTrip the platform links a train calls at on the trip it is running
	 */
	StationTrackResources(RailResourceManager delegate, Network network, Map<Id<Link>, Set<Id<TransitStopArea>>> areasOfLink,
			Function<TrainPosition, Set<Id<Link>>> callsOfTrip) {
		this.delegate = delegate;
		this.areasOfLink = Map.copyOf(areasOfLink);
		this.callsOfTrip = callsOfTrip;
		this.anyTrackLinks = network.getLinks().values().stream()
			.filter(link -> link.getAttributes().getAttribute("stationLink") != null || ownsItsResource(link))
			.map(Link::getId)
			.collect(Collectors.toUnmodifiableSet());
		this.loopLinks = network.getLinks().values().stream()
			.filter(link -> link.getFromNode().equals(link.getToNode()))
			.map(Link::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	/** A platform serves every line calling there, so one link belongs to as many areas as lines and directions use it. */
	private static Map<Id<Link>, Set<Id<TransitStopArea>>> stopAreas(TransitSchedule schedule) {
		Map<Id<Link>, Set<Id<TransitStopArea>>> areas = new HashMap<>();
		for (TransitStopFacility facility : schedule.getFacilities().values()) {
			if (facility.getStopAreaId() != null) {
				areas.computeIfAbsent(facility.getLinkId(), link -> new HashSet<>()).add(facility.getStopAreaId());
			}
		}
		return areas;
	}

	/**
	 * The calls of the trip in progress, not of the vehicle's whole day: a
	 * platform the vehicle calls at on a later trip is just a track while it
	 * runs through the station now, and a detour off it must stay possible.
	 */
	private static Set<Id<Link>> callsOfCurrentTrip(TrainPosition position) {
		if (position.getPt() == null) {
			return Set.of();
		}
		return position.getPt().getTransitRoute().getStops().stream()
			.map(stop -> stop.getStopFacility().getLinkId())
			.collect(Collectors.toUnmodifiableSet());
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
	 * <p>
	 * Refused as well is a detour over a platform the trip calls at later, as
	 * when a train runs through a station, reverses further on and comes back
	 * to end there: the platform would be on the route twice, and railsim
	 * looks for the next stop from the start of the route, so that on the way
	 * back it would find the stop behind the train and fail
	 * ({@code TrainState.getRouteUntilNextStop}; seen at Pavia).
	 */
	boolean keepsStops(List<RailLink> subRoute, List<RailLink> detour, TrainPosition position) {
		Id<Link> nextStopLink = position.getNextStop() == null ? null : position.getNextStop().getLinkId();
		// railsim only knows the next stop; the later calls of the trip come from its route, so a
		// detour decided one station early cannot drop the terminus behind it (seen at Garibaldi)
		Set<Id<Link>> calls = callsOfTrip.apply(position);
		if (detour.stream().anyMatch(link -> calls.contains(link.getLinkId()) && !subRoute.contains(link))) {
			return false;
		}
		boolean aroundNextStop = false;
		for (RailLink link : subRoute) {
			if (link.getLinkId().equals(nextStopLink)) {
				aroundNextStop = true;
			} else if (position.isStop(link.getLinkId()) || calls.contains(link.getLinkId())) {
				return false;
			}
		}
		if (!aroundNextStop) {
			return true;
		}
		Id<TransitStopArea> area = position.getNextStop().getStopAreaId();
		Set<Id<TransitStopArea>> areas = area != null ? Set.of(area) : areasOfLink.getOrDefault(nextStopLink, Set.of());
		return detour.stream()
			.anyMatch(link -> areasOfLink.getOrDefault(link.getLinkId(), Set.of()).stream().anyMatch(areas::contains));
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
