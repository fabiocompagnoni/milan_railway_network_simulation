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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private static final Pattern SECTION = Pattern.compile("([^_.]+)_[^_.]+(\\.entry)?");
	private static final Pattern APPROACH = Pattern.compile(".*\\.(?:north|south)\\.([^.]+)\\.in");

	private final Network network;
	private final Map<Id<RailResource>, Boolean> twoWay = new ConcurrentHashMap<>();
	/** The station each resource is a track of: a mesoscopic stop loop or a platform of a detailed station. */
	private final Map<Id<RailResource>, Optional<String>> stationOf = new ConcurrentHashMap<>();
	private final Map<String, Integer> stationTracks = new ConcurrentHashMap<>();
	/** Trains holding each two-way resource, so a train already in a block is never refused its next link of it. */
	private final Map<Id<RailResource>, Set<MobsimDriverAgent>> blockHolders = new ConcurrentHashMap<>();
	/** Trains holding or committed to a track of each crossing station, with the neighbour each arrives from. */
	private final Map<String, Map<MobsimDriverAgent, String>> stationOccupants = new ConcurrentHashMap<>();

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
		return stationOf(link.getResource()).map(station -> roomForTheMeet(station, position)).orElse(true);
	}

	@Override
	public void onReserve(double time, RailResource resource, TrainPosition position) {
		super.onReserve(time, resource, position);
		if (isTwoWay(resource)) {
			blockHolders.computeIfAbsent(resource.getId(), id -> ConcurrentHashMap.newKeySet()).add(position.getDriver());
			return;
		}
		stationOf(resource).ifPresent(station -> stationOccupants.computeIfAbsent(station, id -> new ConcurrentHashMap<>())
			.put(position.getDriver(), arrivalNeighbour(position, resource)));
	}

	@Override
	public void onRelease(double time, RailResource resource, MobsimDriverAgent driver) {
		super.onRelease(time, resource, driver);
		Set<MobsimDriverAgent> holders = blockHolders.get(resource.getId());
		if (holders != null) {
			holders.remove(driver);
		}
		stationOf(resource).map(stationOccupants::get).ifPresent(occupants -> occupants.remove(driver));
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
		// the station at the far end: its stop loop, or the first platform after the approach links of a detailed one
		for (int i = last + 1; i < Math.min(last + 4, position.getRouteSize()); i++) {
			Optional<String> station = stationOf(position.getRoute(i).getResource());
			if (station.isPresent()) {
				return roomForTheMeet(station.get(), neighbourBehind(position.getRoute(last).getLinkId()), position);
			}
		}
		return true;
	}

	/**
	 * The same consent at the station itself, whichever link the train comes
	 * in through: a station reached over double track from one side and single
	 * track from the other (Villasanta, run of 2026-09-18 after the block rule)
	 * filled up with trains of one direction all the same. A detailed station
	 * counts its platform tracks together, so two trains of one direction do
	 * not take both tracks of a two-track crossing station.
	 */
	private boolean roomForTheMeet(String station, TrainPosition position) {
		Map<MobsimDriverAgent, String> occupants = stationOccupants.getOrDefault(station, Map.of());
		if (occupants.containsKey(position.getDriver())) {
			return true;
		}
		return roomForTheMeet(station, arrivalNeighbourOfStation(position, station), position);
	}

	private boolean roomForTheMeet(String station, String arrivalNeighbour, TrainPosition position) {
		Map<MobsimDriverAgent, String> occupants = stationOccupants.getOrDefault(station, Map.of());
		int free = stationTracks.getOrDefault(station, 1) - occupants.size();
		boolean sameDirectionThere = occupants.containsValue(arrivalNeighbour);
		return free >= (sameDirectionThere ? 2 : 1);
	}

	/** The neighbour the train comes from when it reaches the resource, or {@link #STARTED_HERE} when its trip begins on it. */
	private static String arrivalNeighbour(TrainPosition position, RailResource resource) {
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (position.getRoute(i).getResource() == resource) {
				return i > 0 ? neighbourBehind(position.getRoute(i - 1).getLinkId()) : STARTED_HERE.toString();
			}
		}
		return STARTED_HERE.toString();
	}

	private String arrivalNeighbourOfStation(TrainPosition position, String station) {
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (stationOf(position.getRoute(i).getResource()).filter(station::equals).isPresent()) {
				return i > 0 ? neighbourBehind(position.getRoute(i - 1).getLinkId()) : STARTED_HERE.toString();
			}
		}
		return STARTED_HERE.toString();
	}

	/**
	 * The station a link comes from: {@code N_S} for a mesoscopic section, the
	 * neighbour named in a detailed station's approach link
	 * ({@code S.p1.north.N.in}), the link itself otherwise.
	 */
	static String neighbourBehind(Id<Link> link) {
		String id = link.toString();
		Matcher section = SECTION.matcher(id);
		if (section.matches()) {
			return section.group(1);
		}
		Matcher approach = APPROACH.matcher(id);
		return approach.matches() ? approach.group(1) : id;
	}

	private static int indexOnRoute(TrainPosition position, RailLink link) {
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (position.getRoute(i).getLinkId().equals(link.getLinkId())) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * The station whose track the resource is: a mesoscopic stop loop (all its
	 * tracks in one resource) or one platform of a detailed station, whose
	 * tracks are counted over the network once.
	 */
	private Optional<String> stationOf(RailResource resource) {
		if (resource == null) {
			return Optional.empty();
		}
		return stationOf.computeIfAbsent(resource.getId(), id -> {
			if (resource.getLinks().size() > 2) {
				return Optional.empty();
			}
			Link link = network.getLinks().get(resource.getLinks().getFirst().getLinkId());
			if (link == null) {
				return Optional.empty();
			}
			if (link.getFromNode().equals(link.getToNode()) && resource.getLinks().size() == 1) {
				String station = link.getId().toString().replace("stop_", "");
				stationTracks.put(station, resource.getTotalCapacity());
				return Optional.of(station);
			}
			Object station = link.getAttributes().getAttribute("microStation");
			if (station == null || link.getAttributes().getAttribute("microTrack") == null) {
				return Optional.empty();
			}
			stationTracks.computeIfAbsent(station.toString(), s -> (int) network.getLinks().values().stream()
				.filter(l -> s.equals(l.getAttributes().getAttribute("microStation")) && l.getAttributes().getAttribute("microTrack") != null)
				.map(l -> l.getAttributes().getAttribute("railsimResourceId"))
				.distinct().count());
			return Optional.of(station.toString());
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
