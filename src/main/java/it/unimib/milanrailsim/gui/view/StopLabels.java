package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.runs.DelaySummaries;

import java.util.Map;

/** Human names for the stop facilities of a run: station name, plus the platform where the stop was one. */
final class StopLabels {

	private final Map<String, String> stationNames;

	/** @param stationNames GTFS stop names by stop id */
	StopLabels(Map<String, String> stationNames) {
		this.stationNames = Map.copyOf(stationNames);
	}

	String station(String stationId) {
		return stationNames.getOrDefault(stationId, stationId);
	}

	/** {@code S01326.p2|S01326|R38|terminal} reads as "Milano Greco Pirelli · p2"; a plain station id as its name. */
	String stop(String facilityId) {
		String station = DelaySummaries.stationOf(facilityId);
		int separator = facilityId.indexOf('|');
		if (separator < 0) {
			return station(station);
		}
		String track = facilityId.substring(0, separator);
		if (track.startsWith(station + ".")) {
			track = track.substring(station.length() + 1);
		}
		return station(station) + " · " + track;
	}
}
