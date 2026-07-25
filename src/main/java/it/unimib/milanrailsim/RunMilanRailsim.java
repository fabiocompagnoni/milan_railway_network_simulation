package it.unimib.milanrailsim;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;

import ch.sbb.matsim.contrib.railsim.RailsimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;

/**
 * Entry point of the project: loads a railsim scenario and runs the simulation.
 * <p>
 * Adapted from {@code ch.sbb.matsim.contrib.railsim.RunRailsimExample}, whose default
 * config points to a scenario absent from the repository ({@code microOlten}); here the
 * default resolves to a scenario that exists in this project.
 * <p>
 * Usage: {@code mvn exec:java} for the default scenario, or
 * {@code mvn exec:java -Dexec.args="path/to/config.xml"} for a specific one.
 */
public final class RunMilanRailsim {

	private static final String DEFAULT_CONFIG = "scenarios/smoke/config.xml";

	private RunMilanRailsim() {
	}

	public static void main(String[] args) {
		String configFilename = args.length > 0 ? args[0] : DEFAULT_CONFIG;

		Config config = ConfigUtils.loadConfig(configFilename);
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Controler controler = new Controler(scenario);

		// railsim replaces the mobsim for the 'rail' mode; both bindings are required.
		controler.addOverridingModule(new RailsimModule());
		controler.configureQSimComponents(components ->
			new RailsimQSimModule().configure(components));

		controler.run();
	}
}
