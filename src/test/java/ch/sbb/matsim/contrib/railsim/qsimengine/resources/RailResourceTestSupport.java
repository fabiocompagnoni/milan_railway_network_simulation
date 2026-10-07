package ch.sbb.matsim.contrib.railsim.qsimengine.resources;

import org.matsim.api.core.v01.Id;

import java.util.List;

/**
 * Builds railsim resources for tests outside this package: the constructors
 * and the link-to-resource binding are package-private in railsim.
 */
public final class RailResourceTestSupport {

	private RailResourceTestSupport() {
	}

	/** A fixed-block resource over the links, bound to each of them, as railsim's manager does at start-up. */
	public static RailResource fixedBlock(String id, List<RailLink> links) {
		FixedBlockResource resource = new FixedBlockResource(Id.create(id, RailResource.class), links);
		links.forEach(link -> link.setResource(resource));
		return resource;
	}
}
