package it.unimib.milanrailsim.schedule;

/**
 * Assigns rail vehicle types to GTFS route departures, and marks routes
 * excluded from the simulated schedule (e.g. no documented rolling stock).
 */
public interface VehicleAssignment {

	boolean isExcluded(String routeShortName);

	String vehicleTypeId(String routeShortName, int departureIndex);
}
