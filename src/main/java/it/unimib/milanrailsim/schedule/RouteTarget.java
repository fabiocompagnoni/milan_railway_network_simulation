package it.unimib.milanrailsim.schedule;

/**
 * The longest wait between two trains to allow on a route of a line, in both
 * directions. A route is the pair of termini its trips run between, in either
 * order.
 *
 * @param one   stop id of one terminus
 * @param other stop id of the other terminus
 */
public record RouteTarget(String line, String one, String other, int targetMinutes) {

	/** Whether this and another target name the same route, whichever terminus comes first. */
	public boolean sameRoute(RouteTarget target) {
		return line.equals(target.line) && (one.equals(target.one) && other.equals(target.other)
			|| one.equals(target.other) && other.equals(target.one));
	}
}
