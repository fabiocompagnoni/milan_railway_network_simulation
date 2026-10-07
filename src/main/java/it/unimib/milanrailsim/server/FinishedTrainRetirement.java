package it.unimib.milanrailsim.server;

import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimAfterSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.AgentCounter;

/**
 * Lets the mobsim end when the last train has finished its circulation.
 * <p>
 * Workaround for a gap in railsim: on arrival at the end of the last trip the
 * driver enters an endless activity but {@code RailsimEngine} never hands it
 * back to the activity engine, which is where MATSim takes a sleeping agent
 * out of the living count. The count therefore never drops and the QSim runs
 * to its configured end time over an empty network. Retiring each finished
 * driver here, as the activity engine would, lets the run stop at the last
 * arrival; the end time stays as the cap for trains that never finish.
 */
final class FinishedTrainRetirement implements MobsimInitializedListener, MobsimAfterSimStepListener {

	private final FrameSampler sampler;
	private AgentCounter agents;
	private int retired;

	FinishedTrainRetirement(FrameSampler sampler) {
		this.sampler = sampler;
	}

	@Override
	public void notifyMobsimInitialized(MobsimInitializedEvent e) {
		agents = ((QSim) e.getQueueSimulation()).getAgentCounter();
	}

	@Override
	public void notifyMobsimAfterSimStep(MobsimAfterSimStepEvent e) {
		while (retired < sampler.arrivedTrains()) {
			agents.decLiving();
			retired++;
		}
	}
}
