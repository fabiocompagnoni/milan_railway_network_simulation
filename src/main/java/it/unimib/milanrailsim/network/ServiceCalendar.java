package it.unimib.milanrailsim.network;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which GTFS services run on a given date. The Trenord feed defines
 * services exclusively through calendar_dates.txt (no calendar.txt), so the
 * base set is empty and exception_type=1 rows are the only source of activity.
 */
public final class ServiceCalendar {

	private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private ServiceCalendar() {
	}

	public static Set<String> activeServiceIds(List<Map<String, String>> calendarDateRows, LocalDate date) {
		String gtfsDate = date.format(GTFS_DATE);
		Set<String> added = new HashSet<>();
		Set<String> removed = new HashSet<>();
		for (Map<String, String> row : calendarDateRows) {
			if (!gtfsDate.equals(row.get("date"))) {
				continue;
			}
			String serviceId = row.get("service_id");
			switch (row.get("exception_type")) {
				case "1" -> added.add(serviceId);
				case "2" -> removed.add(serviceId);
				default -> throw new IllegalArgumentException(
					"Unknown exception_type '" + row.get("exception_type") + "' for service " + serviceId);
			}
		}
		added.removeAll(removed);
		return added;
	}
}
