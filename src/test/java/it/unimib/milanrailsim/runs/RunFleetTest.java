package it.unimib.milanrailsim.runs;

import it.unimib.milanrailsim.network.RailVehicleTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunFleetTest {

	private static final String TRIPS_HEADER = "trip,line,route,vehicle,origin,destination,planned_departure_s,planned_arrival_s,"
		+ "actual_arrival_s,arrival_delay_s,stops_planned,stops_served,status,km,drawn_kwh,regenerated_kwh,litres\n";

	/** A run folder holding the two files every analysed run has: its trips and the vehicles of its timetable. */
	private static Path run(Path dir, String name, Map<String, String> typeOfVehicle, String trips) throws IOException {
		Path run = Files.createDirectories(dir.resolve(name).resolve("scenario")).getParent();
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		typeOfVehicle.forEach((vehicle, typeId) -> {
			VehicleType type = vehicles.getVehicleTypes().get(Id.create(typeId, VehicleType.class));
			if (type == null) {
				type = VehicleUtils.createVehicleType(Id.create(typeId, VehicleType.class));
				if (typeId.equals("tsr")) {
					type.getAttributes().putAttribute(RailVehicleTypes.NAME_ATTRIBUTE, "TSR");
				}
				vehicles.addVehicleType(type);
			}
			vehicles.addVehicle(VehicleUtils.createVehicle(Id.create(vehicle, Vehicle.class), type));
		});
		new MatsimVehicleWriter(vehicles).writeFile(run.resolve("scenario/transitVehicles.xml").toString());
		Files.writeString(run.resolve("trips.csv"), TRIPS_HEADER + trips);
		return run;
	}

	@Test
	void theFleetOfARunIsReadFromItsTripsAndVehicles(@TempDir Path dir) throws IOException {
		Path run = run(dir, "real", Map.of("S1_circ_1", "tsr", "R1_circ_1", "atr125"), """
			t1,S1,S1_1,S1_circ_1,A,B,28800,29400,29460,60,2,2,completed,10,,,
			t2,R1,R1_1,R1_circ_1,C,D,30000,30600,,,2,0,never_departed,,,,
			""");

		RunFleet fleet = RunFleet.read(run).orElseThrow();

		assertEquals(Map.of("tsr", 1, "atr125", 1), fleet.use().byType());
		assertEquals("TSR", fleet.typeName("tsr"));
		assertEquals("ATR 125 Stadler GTW", fleet.typeName("atr125"), "a run written before names were recorded takes them from the catalogue");
		assertEquals(8, fleet.use().hours().getFirst().hour());
	}

	@Test
	void aRunWithoutItsTripsHasNoFleet(@TempDir Path dir) throws IOException {
		Path run = run(dir, "old", Map.of("S1_circ_1", "tsr"), "");
		Files.delete(run.resolve("trips.csv"));

		assertTrue(RunFleet.read(run).isEmpty());
	}

	@Test
	void theComparisonListsEveryTypeOfEitherRunAndTheirTotal(@TempDir Path dir) throws IOException {
		RunFleet reference = RunFleet.read(run(dir, "real", Map.of("S1_circ_1", "tsr", "R1_circ_1", "atr125"), """
			t1,S1,S1_1,S1_circ_1,A,B,28800,29400,29460,60,2,2,completed,10,,,
			t2,R1,R1_1,R1_circ_1,C,D,30000,30600,30600,0,2,2,completed,10,,,
			""")).orElseThrow();
		RunFleet scenario = RunFleet.read(run(dir, "dense", Map.of("S1_circ_1", "tsr", "S1_circ_2", "tsr", "S1_circ_3", "taf"), """
			t1,S1,S1_1,S1_circ_1,A,B,28800,29400,29460,60,2,2,completed,10,,,
			t3,S1,S1_1,S1_circ_2,A,B,29700,30300,30300,0,2,2,completed,10,,,
			t4,S1,S1_1,S1_circ_3,A,B,30600,31200,31200,0,2,2,completed,10,,,
			""")).orElseThrow();

		FleetComparison comparison = FleetComparison.of(reference, scenario);

		assertEquals(List.of(
			new FleetComparison.Row("tsr", "TSR", 1, 2),
			new FleetComparison.Row("taf", "TAF", 0, 1),
			new FleetComparison.Row("atr125", "ATR 125 Stadler GTW", 1, 0)), comparison.rows(), "in the order of the catalogue");
		assertEquals(1, comparison.rows().getFirst().difference());
		assertEquals(-1, comparison.rows().getLast().difference());
		assertEquals(2, comparison.referenceTrains());
		assertEquals(3, comparison.runTrains());
		assertEquals(1, comparison.difference());
	}
}
