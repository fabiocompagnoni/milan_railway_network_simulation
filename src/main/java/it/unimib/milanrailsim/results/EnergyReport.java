package it.unimib.milanrailsim.results;

import it.unimib.milanrailsim.results.EnergyLedger.Totals;
import it.unimib.milanrailsim.results.EnergyLedger.TripEnergy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The energy of a run as the files of its archive: totals, by line, and the load by minute. */
final class EnergyReport {

	private EnergyReport() {
	}

	static Map<String, Object> json(EnergyLedger.Use use) {
		Totals totals = use.totals();
		TripEnergy electric = totals.electric();
		TripEnergy diesel = totals.diesel();
		Map<String, Object> json = new LinkedHashMap<>();
		Map<String, Object> electricJson = new LinkedHashMap<>();
		electricJson.put("drawnFromSubstationsKilowattHours", electric.drawnKilowattHours());
		electricJson.put("demandWithoutRecoveryKilowattHours", electric.demandKilowattHours());
		electricJson.put("regeneratedKilowattHours", electric.regeneratedKilowattHours());
		electricJson.put("reusedByOtherTrainsKilowattHours", electric.givenKilowattHours());
		electricJson.put("lostInBrakingKilowattHours", electric.lostKilowattHours());
		electricJson.put("trainKilometres", electric.kilometres());
		electricJson.put("tonneKilometres", electric.tonneKilometres());
		electricJson.put("kilowattHoursPerTrainKilometre", ratio(electric.drawnKilowattHours(), electric.kilometres()));
		electricJson.put("kilowattHoursPerTonneKilometre", ratio(electric.drawnKilowattHours(), electric.tonneKilometres()));
		electricJson.put("kilowattHoursPerTonneKilometreWithoutRecovery",
			ratio(electric.demandKilowattHours(), electric.tonneKilometres()));
		json.put("electric", electricJson);
		Map<String, Object> dieselJson = new LinkedHashMap<>();
		dieselJson.put("litres", diesel.litres());
		dieselJson.put("trainKilometres", diesel.kilometres());
		dieselJson.put("tonneKilometres", diesel.tonneKilometres());
		dieselJson.put("litresPerTrainKilometre", ratio(diesel.litres(), diesel.kilometres()));
		json.put("diesel", dieselJson);
		use.peak().ifPresent(peak -> {
			Map<String, Object> peakJson = new LinkedHashMap<>();
			peakJson.put("minuteOfDay", peak.minuteOfDay());
			peakJson.put("lineKilowatt", peak.lineKilowatt());
			peakJson.put("trainsInService", peak.trainsInService());
			json.put("peakMinute", peakJson);
		});
		return json;
	}

	static List<String> byLineCsv(EnergyLedger.Use use) {
		List<String> lines = new ArrayList<>();
		lines.add("line,traction,train_km,tonne_km,drawn_kwh,demand_without_recovery_kwh,regenerated_kwh,reused_by_others_kwh,"
			+ "lost_kwh,litres,kwh_per_train_km,litres_per_train_km");
		use.byLine().forEach((line, totals) -> {
			if (totals.electric().kilometres() > 0) {
				TripEnergy of = totals.electric();
				lines.add(String.join(",", line, "electric", number(of.kilometres()), number(of.tonneKilometres()),
					number(of.drawnKilowattHours()), number(of.demandKilowattHours()), number(of.regeneratedKilowattHours()),
					number(of.givenKilowattHours()), number(of.lostKilowattHours()), "",
					number(ratio(of.drawnKilowattHours(), of.kilometres())), ""));
			}
			if (totals.diesel().kilometres() > 0) {
				TripEnergy of = totals.diesel();
				lines.add(String.join(",", line, "diesel", number(of.kilometres()), number(of.tonneKilometres()),
					"", "", "", "", "", number(of.litres()), "", number(ratio(of.litres(), of.kilometres()))));
			}
		});
		return lines;
	}

	static List<String> profileCsv(EnergyLedger.Use use) {
		List<String> lines = new ArrayList<>();
		lines.add("minute_of_day,time,line_kw,recovered_kw,lost_kw,diesel_litres_per_hour,trains_in_service");
		for (EnergyLedger.Minute minute : use.profile()) {
			lines.add(String.join(",", Integer.toString(minute.minuteOfDay()),
				String.format(Locale.ROOT, "%02d:%02d", minute.minuteOfDay() / 60, minute.minuteOfDay() % 60),
				number(minute.lineKilowatt()), number(minute.recoveredKilowatt()), number(minute.lostKilowatt()),
				number(minute.litresPerHour()), Integer.toString(minute.trainsInService())));
		}
		return lines;
	}

	private static double ratio(double value, double over) {
		return over == 0 ? 0 : value / over;
	}

	static String number(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}
}
