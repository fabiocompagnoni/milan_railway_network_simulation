package it.unimib.milanrailsim.railsim;

import ch.sbb.matsim.contrib.railsim.RailsimControllerListener;
import ch.sbb.matsim.contrib.railsim.config.RailsimConfigGroup;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.deadlocks.DeadlockAvoidance;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManager;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.RailResourceManagerImpl;
import com.google.inject.Singleton;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;

/** Installs railsim on a controler together with this project's extensions. */
public final class RailsimSetup {

	private RailsimSetup() {
	}

	public static void install(Controler controler) {
		controler.addOverridingModule(new RailsimCore());
		controler.configureQSimComponents(components -> new RailsimQSimModule().configure(components));
		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				bind(RailResourceManagerImpl.class).in(Singleton.class);
				bind(RailResourceManager.class).to(StationTrackResources.class).asEagerSingleton();
				bind(DeadlockAvoidance.class).to(SingleTrackDeadlockAvoidance.class).asEagerSingleton();
			}
		});
	}

	private static final class RailsimCore extends AbstractModule {

		@Override
		public void install() {
			installQSimModule(new RailsimQSimModule());
			ConfigUtils.addOrGetModule(getConfig(), RailsimConfigGroup.class);
			addControllerListenerBinding().to(RailsimControllerListener.class);
		}
	}
}