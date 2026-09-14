package it.unimib.milanrailsim.results;

/** Naming of the transit stop facilities the schedule builder creates. */
public final class StopFacilities {

	private StopFacilities() {
	}

	/**
	 * The station a stop facility belongs to: platforms of a detailed node are
	 * named {@code <track>|<station>|<line>|<kind>}, mesoscopic stops by the
	 * station id alone.
	 */
	public static String stationOf(String facilityId) {
		int separator = facilityId.indexOf('|');
		if (separator < 0) {
			return facilityId;
		}
		int end = facilityId.indexOf('|', separator + 1);
		return facilityId.substring(separator + 1, end < 0 ? facilityId.length() : end);
	}
}
