package it.unimib.milanrailsim.schedule;

import it.unimib.milanrailsim.network.FleetConfig;
import it.unimib.milanrailsim.network.GtfsFeed;
import it.unimib.milanrailsim.network.RailVehicleTypes;
import it.unimib.milanrailsim.network.StationTracks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Builds the railsim timetable of one service day from the GTFS feed and the
 * mesoscopic network: trips, circulations, single-track blocks and terminal
 * capacities, written as {@code transitSchedule.xml}, {@code transitVehicles.xml}
 * and {@code network-with-stations.xml}.
 */
public final class SchedulePipeline {

	private static final Logger log = LogManager.getLogger(SchedulePipeline.class);

	/** Terminal turnaround: crews change ends and rotate (domain estimate, Fabio). */
	public static final int TURNAROUND_SECONDS = 15 * 60;

	private final GtfsFeed feed;
	private final Network network;
	private final FleetConfig fleet;
	private final RouteVehicleAssignment assignment;
	private final StationTracks stationTracks;

	public SchedulePipeline(GtfsFeed feed, Path networkFile, FleetConfig fleet, RouteVehicleAssignment assignment,
			StationTracks stationTracks) {
		this.feed = feed;
		this.network = NetworkUtils.readNetwork(networkFile.toString());
		this.fleet = fleet;
		this.assignment = assignment;
		this.stationTracks = stationTracks;
	}

	/**
	 * @param windowStartSeconds first departure admitted, seconds since midnight, or a negative value for no bound
	 * @param windowEndSeconds last departure admitted, or {@link Integer#MAX_VALUE} for no bound
	 */
	public void generate(LocalDate serviceDate, int windowStartSeconds, int windowEndSeconds, Path outputDir) {
		sizeStationsForTheWholeDay(serviceDate);
		TransitScheduleBuilder.Result result = builder(serviceDate)
			.withWindow(windowStartSeconds, windowEndSeconds)
			.build();
		Vehicles circulations = VehicleCirculations.apply(result.schedule(), result.vehicles(),
			TURNAROUND_SECONDS, assignment);
		SingleTrackBlocks.apply(network);

		try {
			Files.createDirectories(outputDir);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output directory " + outputDir, e);
		}
		new TransitScheduleWriter(result.schedule()).writeFile(outputDir.resolve("transitSchedule.xml").toString());
		new MatsimVehicleWriter(circulations).writeFile(outputDir.resolve("transitVehicles.xml").toString());
		new NetworkWriter(network).write(outputDir.resolve("network-with-stations.xml").toString());

		int departures = result.schedule().getTransitLines().values().stream()
			.flatMap(line -> line.getRoutes().values().stream())
			.mapToInt(route -> route.getDepartures().size()).sum();
		log.info("Wrote timetable for {} to {}: {} lines, {} departures, {} circulations", serviceDate, outputDir,
			result.schedule().getTransitLines().size(), departures, circulations.getVehicles().size());
	}

	/**
	 * Station capacities come from the whole day's timetable, whatever window is
	 * simulated: a crossing loop or a terminal siding exists regardless of the
	 * hours being run, and the surveyed counts are not touched.
	 */
	private void sizeStationsForTheWholeDay(LocalDate serviceDate) {
		TransitScheduleBuilder.Result wholeDay = builder(serviceDate).build();
		VehicleCirculations.apply(wholeDay.schedule(), wholeDay.vehicles(), TURNAROUND_SECONDS, assignment);
		MeetCapacities.apply(wholeDay.schedule(), network);
		TerminalCapacities.apply(wholeDay.schedule(), network);
	}

	private TransitScheduleBuilder builder(LocalDate serviceDate) {
		return new TransitScheduleBuilder(feed, network, serviceDate, assignment, RailVehicleTypes.from(fleet))
			.withStationTracks(stationTracks);
	}
}
