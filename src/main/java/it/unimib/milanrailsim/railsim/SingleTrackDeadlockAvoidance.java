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
import java.util.Set;
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

	/** Stands for the link a train arrived through when it started its trip in the station. */
	private static final Id<Link> STARTED_HERE = Id.createLinkId("started-here");

	private final Network network;
	private final Map<Id<RailResource>, Boolean> twoWay = new ConcurrentHashMap<>();
	private final Map<Id<RailResource>, Boolean> stationLoop = new ConcurrentHashMap<>();
	/** Trains holding each two-way resource, so a train already in a block is never refused its next link of it. */
	private final Map<Id<RailResource>, Set<MobsimDriverAgent>> blockHolders = new ConcurrentHashMap<>();
	/** Trains holding or committed to each crossing station, with the link each arrives through. */
	private final Map<Id<RailResource>, Map<MobsimDriverAgent, Id<Link>>> stationOccupants = new ConcurrentHashMap<>();

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
		if (isTwoWay(link.getResource())) {
			return super.checkLink(time, link, position) && crossingTrackFree(link, position);
		}
		return !isStationLoop(link.getResource()) || roomForTheMeet(link.getResource(), position);
	}

	@Override
	public void onReserve(double time, RailResource resource, TrainPosition position) {
		super.onReserve(time, resource, position);
		if (isTwoWay(resource)) {
			blockHolders.computeIfAbsent(resource.getId(), id -> ConcurrentHashMap.newKeySet()).add(position.getDriver());
		} else if (isStationLoop(resource)) {
			stationOccupants.computeIfAbsent(resource.getId(), id -> new ConcurrentHashMap<>())
				.put(position.getDriver(), arrivalLink(position, resource));
		}
	}

	@Override
	public void onRelease(double time, RailResource resource, MobsimDriverAgent driver) {
		super.onRelease(time, resource, driver);
		Set<MobsimDriverAgent> holders = blockHolders.get(resource.getId());
		if (holders != null) {
			holders.remove(driver);
		}
		Map<MobsimDriverAgent, Id<Link>> occupants = stationOccupants.get(resource.getId());
		if (occupants != null) {
			occupants.remove(driver);
		}
	}

	/**
	 * The dispatcher's consent to enter a single-track block: the crossing
	 * station at its far end must have a track for the train, and a further
	 * free track whenever a train of the same direction is already there or
	 * heading there, so the opposing train can still get in and the meet can
	 * happen. Without it two trains of one direction filled the station and
	 * the opposing one waited in the next block for ever (Villasanta, run of
	 * 2026-09-18). A train already holding the block keeps going; a block
	 * ending at a detailed station, whose throat and platforms railsim
	 * arbitrates, is not held back.
	 */
	private boolean crossingTrackFree(RailLink link, TrainPosition position) {
		RailResource block = link.getResource();
		if (blockHolders.getOrDefault(block.getId(), Set.of()).contains(position.getDriver())) {
			return true;
		}
		int index = indexOnRoute(position, link);
		if (index < 0) {
			return true;
		}
		int last = index;
		while (last + 1 < position.getRouteSize() && position.getRoute(last + 1).getResource() == block) {
			last++;
		}
		if (last + 1 >= position.getRouteSize()) {
			return true;
		}
		RailResource station = position.getRoute(last + 1).getResource();
		if (station == null || !isStationLoop(station)) {
			return true;
		}
		return roomForTheMeet(station, position.getRoute(last).getLinkId(), position);
	}

	/**
	 * The same consent at the station itself, whichever link the train comes
	 * in through: a station reached over double track from one side and single
	 * track from the other (Villasanta, run of 2026-09-18 after the block rule)
	 * filled up with trains of one direction all the same.
	 */
	private boolean roomForTheMeet(RailResource station, TrainPosition position) {
		Map<MobsimDriverAgent, Id<Link>> occupants = stationOccupants.getOrDefault(station.getId(), Map.of());
		if (occupants.containsKey(position.getDriver())) {
			return true;
		}
		return roomForTheMeet(station, arrivalLink(position, station), position);
	}

	private boolean roomForTheMeet(RailResource station, Id<Link> arrivalLink, TrainPosition position) {
		Map<MobsimDriverAgent, Id<Link>> occupants = stationOccupants.getOrDefault(station.getId(), Map.of());
		int free = station.getTotalCapacity() - occupants.size();
		boolean sameDirectionThere = occupants.containsValue(arrivalLink);
		return free >= (sameDirectionThere ? 2 : 1);
	}

	/** The link the train runs to reach the resource, or {@link #STARTED_HERE} when its trip begins on it. */
	private static Id<Link> arrivalLink(TrainPosition position, RailResource resource) {
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (position.getRoute(i).getResource() == resource) {
				return i > 0 ? position.getRoute(i - 1).getLinkId() : STARTED_HERE;
			}
		}
		return STARTED_HERE;
	}

	private static int indexOnRoute(TrainPosition position, RailLink link) {
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (position.getRoute(i).getLinkId().equals(link.getLinkId())) {
				return i;
			}
		}
		return -1;
	}

	/** A mesoscopic station: one loop link, both directions calling on it. */
	private boolean isStationLoop(RailResource resource) {
		return resource != null && stationLoop.computeIfAbsent(resource.getId(), id -> {
			if (resource.getLinks().size() != 1) {
				return false;
			}
			Link link = network.getLinks().get(resource.getLinks().getFirst().getLinkId());
			return link != null && link.getFromNode().equals(link.getToNode());
		});
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
