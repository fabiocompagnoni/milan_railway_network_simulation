package it.unimib.milanrailsim.network;

import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;

/**
 * Railsim vehicle types of the model. Figures and sources are documented in
 * docs/network/infrastruttura-nodo-milano.md ("Parco rotabile"); values
 * without a published source are marked {@code estimated} in the attributes.
 */
final class RailVehicleTypes {

	private static final double ESTIMATED_DECELERATION = 0.5;

	private RailVehicleTypes() {
	}

	static List<VehicleType> all() {
		return List.of(
			type("tsr", 104.98, 436, 140, 1.0, true),
			type("taf", 103.97, 469, 140, 0.8, true),
			type("caravaggio_421", 109.6, 466, 160, 1.10, false),
			type("caravaggio_521", 136.8, 598, 160, 1.10, false),
			type("donizetti", 84.2, 262, 160, 1.0, true),
			type("etr245", 82.2, 230, 160, 1.0, true),
			type("atr125", 77.33, 231, 140, 0.6, true));
	}

	private static VehicleType type(String id, double lengthMeters, int seats, double vmaxKmh,
			double acceleration, boolean accelerationEstimated) {
		VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class));
		vehicleType.setNetworkMode("rail");
		vehicleType.setLength(lengthMeters);
		vehicleType.setMaximumVelocity(vmaxKmh / 3.6);
		vehicleType.getCapacity().setSeats(seats);
		vehicleType.getCapacity().setStandingRoom(0);
		vehicleType.getAttributes().putAttribute("railsimAcceleration", acceleration);
		vehicleType.getAttributes().putAttribute("railsimDeceleration", ESTIMATED_DECELERATION);
		vehicleType.getAttributes().putAttribute("decelerationDataStatus", "estimated");
		if (accelerationEstimated) {
			vehicleType.getAttributes().putAttribute("accelerationDataStatus", "estimated");
		}
		return vehicleType;
	}
}
