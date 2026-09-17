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
import java.util.HashSet;
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

	/** Stands for the neighbour a train comes from when its trip starts in the station, or goes to when it ends there. */
	static final String NOWHERE = "nowhere";
	private static final Pattern SECTION = Pattern.compile("([^_.]+)_([^_.]+)(\\.entry|\\.exit)?");
	private static final Pattern APPROACH_IN = Pattern.compile(".*\\.(?:north|south)\\.([^.]+)\\.in");
	private static final Pattern APPROACH_OUT = Pattern.compile(".*\\.(?:north|south)\\.([^.]+)\\.out");

	/** A train's call at a crossing station: where it comes from and where it goes on. */
	record Passage(String from, String to) {

		/** Two trains meet when one arrives from where the other departs to: they swap sections at the station. */
		boolean meets(Passage other) {
			return (!from.equals(NOWHERE) && from.equals(other.to)) || (!to.equals(NOWHERE) && to.equals(other.from));
		}
	}

	private final Network network;
	private final Map<Id<RailResource>, Boolean> twoWay = new ConcurrentHashMap<>();
	/** The station each resource is a track of: a mesoscopic stop loop or a platform of a detailed station. */
	private final Map<Id<RailResource>, Optional<String>> stationOf = new ConcurrentHashMap<>();
	private final Map<String, Integer> stationTracks = new ConcurrentHashMap<>();
	private final Map<String, Boolean> stationOnSingleTrack = new ConcurrentHashMap<>();
	/** Trains holding each two-way resource, so a train already in a block is never refused its next link of it. */
	private final Map<Id<RailResource>, Set<MobsimDriverAgent>> blockHolders = new ConcurrentHashMap<>();
	/** Trains holding each station track, so a train bound for a taken terminal platform is held before the block. */
	private final Map<Id<RailResource>, Set<MobsimDriverAgent>> trackHolders = new ConcurrentHashMap<>();
	/** Trains holding or committed to a track of each crossing station, with their passage through it. */
	private final Map<String, Map<MobsimDriverAgent, Passage>> stationOccupants = new ConcurrentHashMap<>();

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
		stationOf(resource).ifPresent(station -> {
			trackHolders.computeIfAbsent(resource.getId(), id -> ConcurrentHashMap.newKeySet()).add(position.getDriver());
			stationOccupants.computeIfAbsent(station, id -> new ConcurrentHashMap<>())
				.put(position.getDriver(), passageThrough(position, station));
		});
	}

	@Override
	public void onRelease(double time, RailResource resource, MobsimDriverAgent driver) {
		super.onRelease(time, resource, driver);
		Set<MobsimDriverAgent> holders = blockHolders.get(resource.getId());
		if (holders != null) {
			holders.remove(driver);
		}
		Set<MobsimDriverAgent> onTrack = trackHolders.get(resource.getId());
		if (onTrack != null) {
			onTrack.remove(driver);
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
			RailResource track = position.getRoute(i).getResource();
			Optional<String> station = stationOf(track);
			if (station.isPresent()) {
				Passage passage = passageThrough(position, station.get());
				// a train ending its trip on a taken terminal platform cannot be diverted: it waits before the block,
				// or the train on the platform could never leave through it (Cremona, run of 2026-09-18)
				if (passage.to().equals(NOWHERE) && trackHolders.getOrDefault(track.getId(), Set.of()).stream()
						.anyMatch(holder -> holder != position.getDriver())) {
					return false;
				}
				return roomForTheMeet(station.get(), passage, position);
			}
		}
		return true;
	}

	/**
	 * The same consent at the station itself, whichever link the train comes
	 * in through: a station reached over double track from one side and single
	 * track from the other (Villasanta, run of 2026-09-18 after the block rule)
	 * filled up with trains of one direction all the same. A detailed station
	 * counts its platform tracks together.
	 */
	private boolean roomForTheMeet(String station, TrainPosition position) {
		Map<MobsimDriverAgent, Passage> occupants = stationOccupants.getOrDefault(station, Map.of());
		if (occupants.containsKey(position.getDriver())) {
			return true;
		}
		return roomForTheMeet(station, passageThrough(position, station), position);
	}

	/**
	 * The last free track of a crossing station is kept for the meet: a train
	 * may take it only if it is the crossing partner of a train already there,
	 * one arriving from the section the other leaves through. Otherwise it
	 * waits behind, as with the dispatcher's consent. At Varese Nord three
	 * trains bound for Malnate, one out of the yard, filled the three tracks
	 * while the RE1 from Malnate stood in the section they all needed (run of
	 * 2026-09-18). Stations away from single track are not concerned.
	 */
	private boolean roomForTheMeet(String station, Passage passage, TrainPosition position) {
		Map<MobsimDriverAgent, Passage> occupants = stationOccupants.getOrDefault(station, Map.of());
		int free = stationTracks.getOrDefault(station, 1) - occupants.size();
		if (occupants.isEmpty() || free >= 2 || !onSingleTrack(station)) {
			return free >= 1;
		}
		return free == 1 && occupants.values().stream().anyMatch(passage::meets);
	}

	/** Where the train comes from and goes on when it calls at the station, {@link #NOWHERE} when its trip starts or ends there. */
	private Passage passageThrough(TrainPosition position, String station) {
		int first = -1;
		int last = -1;
		for (int i = Math.max(0, position.getRouteIndex() - 1); i < position.getRouteSize(); i++) {
			if (stationOf(position.getRoute(i).getResource()).filter(station::equals).isPresent()) {
				if (first < 0) {
					first = i;
				}
				last = i;
			} else if (first >= 0) {
				break;
			}
		}
		if (first < 0) {
			return new Passage(NOWHERE, NOWHERE);
		}
		String from = first > 0 ? neighbourBehind(position.getRoute(first - 1).getLinkId()) : NOWHERE;
		String to = NOWHERE;
		for (int i = last + 1; i < Math.min(last + 4, position.getRouteSize()); i++) {
			String ahead = neighbourAhead(position.getRoute(i).getLinkId());
			if (ahead != null) {
				to = ahead;
				break;
			}
		}
		return new Passage(from, to);
	}

	/**
	 * The station a link comes from: {@code N_S} for a mesoscopic section or
	 * its stubs, the neighbour named in a detailed station's approach link
	 * ({@code S.p1.north.N.in}), the link itself otherwise.
	 */
	static String neighbourBehind(Id<Link> link) {
		String id = link.toString();
		Matcher section = SECTION.matcher(id);
		if (section.matches()) {
			return section.group(1);
		}
		Matcher approach = APPROACH_IN.matcher(id);
		return approach.matches() ? approach.group(1) : id;
	}

	/** The station a link leads to, or null for a link inside the station (a turnback, a platform half). */
	static String neighbourAhead(Id<Link> link) {
		String id = link.toString();
		Matcher section = SECTION.matcher(id);
		if (section.matches()) {
			return section.group(2);
		}
		Matcher approach = APPROACH_OUT.matcher(id);
		if (approach.matches()) {
			return approach.group(1);
		}
		return id.startsWith("stop_") || id.contains(".p") ? null : id;
	}

	/** Whether any section into the station is single track: both its directions under one resource. */
	private boolean onSingleTrack(String station) {
		return stationOnSingleTrack.computeIfAbsent(station, s -> network.getLinks().values().stream().anyMatch(link -> {
			Matcher section = SECTION.matcher(link.getId().toString());
			if (!section.matches() || !section.group(2).equals(s) || section.group(3) != null) {
				return false;
			}
			Object resource = link.getAttributes().getAttribute("railsimResourceId");
			Link back = network.getLinks().get(Id.createLinkId(section.group(2) + "_" + section.group(1)));
			return resource != null && back != null && resource.equals(back.getAttributes().getAttribute("railsimResourceId"));
		}));
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
		Set<String> ids = new HashSet<>();
		links.forEach(link -> ids.add(link.getId().toString()));
		for (Link link : links) {
			// between two detailed stations the directions no longer share nodes: their ids still pair up
			Matcher section = SECTION.matcher(link.getId().toString());
			if (section.matches() && section.group(3) == null && ids.contains(section.group(2) + "_" + section.group(1))) {
				return true;
			}
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
