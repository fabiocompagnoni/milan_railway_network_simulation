package it.unimib.milanrailsim.network;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GTFS time-of-day parsing. GTFS times are relative to the service day and may
 * exceed 24:00:00 for trips running past midnight.
 */
final class GtfsTime {

	private static final Pattern FORMAT = Pattern.compile("(\\d+):([0-5]\\d):([0-5]\\d)");

	private GtfsTime() {
	}

	static int parseSeconds(String hhmmss) {
		Matcher matcher = FORMAT.matcher(hhmmss);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Not a GTFS time: '" + hhmmss + "'");
		}
		int hours = Integer.parseInt(matcher.group(1));
		int minutes = Integer.parseInt(matcher.group(2));
		int seconds = Integer.parseInt(matcher.group(3));
		return hours * 3600 + minutes * 60 + seconds;
	}
}
