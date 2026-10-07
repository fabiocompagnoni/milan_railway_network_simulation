package it.unimib.milanrailsim.results;

import java.time.Duration;

/**
 * How the simulation itself ended, as seen by the engine: trains that
 * completed their circulation, trains the mobsim aborted, trains still on the
 * network when the run was cut, the simulated second it was cut at and how
 * long the whole run took on the wall clock. Recorded in the manifest next to
 * the punctuality figures, which alone say nothing about trains that never
 * arrived.
 */
public record RunOutcome(int arrived, int aborted, int stalled, double simulatedEndSeconds, Duration wallClock) {
}
