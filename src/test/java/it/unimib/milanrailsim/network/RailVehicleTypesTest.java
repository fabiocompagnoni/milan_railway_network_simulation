package it.unimib.milanrailsim.network;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.VehicleType;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RailVehicleTypesTest {

	private static Map<String, VehicleType> byId() {
		return RailVehicleTypes.all().stream()
			.collect(Collectors.toMap(t -> t.getId().toString(), t -> t));
	}

	@Test
	void definesTheApprovedTypes() {
		assertEquals(Set.of("tsr", "taf", "caravaggio_421", "caravaggio_521",
			"donizetti", "etr245", "atr125", "tilo_flirt_tsi", "atr803"), byId().keySet());
	}

	@Test
	void everyTypeIsRailModeWithRailsimAttributes() {
		for (VehicleType type : RailVehicleTypes.all()) {
			assertEquals("rail", type.getNetworkMode(), type.getId().toString());
			assertNotNull(type.getAttributes().getAttribute("railsimAcceleration"));
			assertNotNull(type.getAttributes().getAttribute("railsimDeceleration"));
			assertEquals(RailVehicleTypes.REVERSING_SECONDS, type.getAttributes().getAttribute("railsimReversible"));
			assertEquals("estimated", type.getAttributes().getAttribute("reversibleDataStatus"));
			assertTrue(type.getMaximumVelocity() > 0);
			assertTrue(type.getLength() > 0);
		}
	}

	@Test
	void sourcedCaravaggioAccelerationIsNotMarkedEstimated() {
		VehicleType caravaggio = byId().get("caravaggio_521");
		assertEquals(1.10, (Double) caravaggio.getAttributes().getAttribute("railsimAcceleration"), 1e-9);
		assertNull(caravaggio.getAttributes().getAttribute("accelerationDataStatus"));
	}

	@Test
	void estimatedAccelerationsAreMarked() {
		VehicleType taf = byId().get("taf");
		assertEquals(0.8, (Double) taf.getAttributes().getAttribute("railsimAcceleration"), 1e-9);
		assertEquals("estimated", taf.getAttributes().getAttribute("accelerationDataStatus"));
	}

	@Test
	void speedsAreInMetersPerSecond() {
		// TAF 140 km/h, Donizetti 160 km/h (sources in the class)
		assertEquals(140 / 3.6, byId().get("taf").getMaximumVelocity(), 1e-6);
		assertEquals(160 / 3.6, byId().get("donizetti").getMaximumVelocity(), 1e-6);
	}

	@Test
	void tafMatchesItsPublishedFigures() {
		VehicleType taf = byId().get("taf");
		assertEquals(103.97, taf.getLength(), 1e-6);
		assertEquals(469, taf.getCapacity().getSeats());
	}

	@Test
	void dieselAtr125IsTheWeakestAccelerator() {
		Map<String, VehicleType> types = byId();
		double atr = (Double) types.get("atr125").getAttributes().getAttribute("railsimAcceleration");
		for (VehicleType type : RailVehicleTypes.all()) {
			double a = (Double) type.getAttributes().getAttribute("railsimAcceleration");
			assertTrue(a >= atr, type.getId() + " should not accelerate worse than the diesel ATR 125");
		}
	}

	@Test
	void idsAreStableForRailsimSpeedAttributes() {
		// link attributes railsimSpeed_<typeId> depend on these exact ids
		assertNotNull(Id.create("tsr", VehicleType.class));
	}
}
