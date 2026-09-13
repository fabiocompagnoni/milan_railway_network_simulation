package it.unimib.milanrailsim.network;

import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;

/** Railsim vehicle types built from the fleet catalogue; estimates are flagged in the attributes. */
public final class RailVehicleTypes {

	private RailVehicleTypes() {
	}

	static final double REVERSING_SECONDS = 60.0;

	public static List<VehicleType> all() {
		return from(FleetConfig.defaults());
	}

	public static List<VehicleType> from(FleetConfig fleet) {
		return fleet.types().stream().map(RailVehicleTypes::type).toList();
	}

	private static VehicleType type(FleetConfig.TrainType train) {
		VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create(train.id(), VehicleType.class));
		vehicleType.setNetworkMode("rail");
		vehicleType.setLength(train.lengthMeters());
		vehicleType.setMaximumVelocity(train.vmaxKmh() / 3.6);
		vehicleType.getCapacity().setSeats(train.seats());
		vehicleType.getCapacity().setStandingRoom(0);
		vehicleType.getAttributes().putAttribute("railsimAcceleration", train.accelerationMps2());
		vehicleType.getAttributes().putAttribute("railsimDeceleration", train.decelerationMps2());
		// every unit of the fleet has a cab at both ends, so it reverses on a terminal platform;
		// the seconds to change ends are a placeholder until surveyed
		vehicleType.getAttributes().putAttribute("railsimReversible", REVERSING_SECONDS);
		vehicleType.getAttributes().putAttribute("reversibleDataStatus", "estimated");
		if (train.isEstimated("acceleration")) {
			vehicleType.getAttributes().putAttribute("accelerationDataStatus", "estimated");
		}
		if (train.isEstimated("deceleration")) {
			vehicleType.getAttributes().putAttribute("decelerationDataStatus", "estimated");
		}
		return vehicleType;
	}
}
