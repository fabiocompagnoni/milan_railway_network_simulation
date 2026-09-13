package it.unimib.milanrailsim.network.micro;

import it.unimib.milanrailsim.network.micro.MicroNode.Direction;
import it.unimib.milanrailsim.network.micro.MicroNode.Group;
import it.unimib.milanrailsim.network.micro.MicroNode.Station;
import it.unimib.milanrailsim.network.micro.MicroNode.Track;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.Locale;

/**
 * Naming of the network elements a micro node produces, shared by the
 * builder that creates them and the timetable that refers to them.
 */
public final class MicroIds {

	private MicroIds() {
	}

	/** {@code <station>.p<ref>} for named tracks, {@code <station>.<group>.<n>} for anonymous capacity. */
	public static String trackId(Station station, Group group, Track track) {
		return group.hasNamedTracks()
			? station.id() + ".p" + track.ref()
			: station.id() + "." + group.id() + "." + track.ref();
	}

	/**
	 * The platform link a train travelling in {@code travel} occupies on a
	 * track: the track itself when directional, {@code .in} on a one-sided
	 * terminal track, {@code .north}/{@code .south} on a bidirectional through
	 * track.
	 */
	public static Id<Link> platformLink(String trackId, Group group, Track track, Direction travel) {
		if (track.direction() != null) {
			return Id.createLinkId(trackId);
		}
		if (group.connections().size() == 1) {
			return Id.createLinkId(trackId + ".in");
		}
		return Id.createLinkId(trackId + "." + name(travel == null ? Direction.NORTH : travel));
	}

	public static String junction(Station station, Direction side) {
		return station.id() + "." + name(side);
	}

	public static String name(Direction direction) {
		return direction.name().toLowerCase(Locale.ROOT);
	}
}
