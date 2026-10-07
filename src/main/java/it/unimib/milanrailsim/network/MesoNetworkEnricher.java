package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.Map;
import java.util.TreeSet;

/**
 * Promotes the provisional skeleton to a mesoscopic railsim network by applying
 * OSM measures per link (railsim network specification, mesoscopic level):
 * an even total of N tracks becomes capacity N/2 per direction (independent
 * resources, left-hand running with dedicated tracks per direction); a single
 * shared track keeps capacity 1 with one {@code railsimResourceId} spanning
 * both directions, so the engine arbitrates the contention.
 * <p>
 * The {@code dataStatus="provisional"} mark is removed only when length, speed
 * and capacity are all measured; partially measured links improve but stay
 * marked (correctness rule: no invented value may pass as real).
 */
final class MesoNetworkEnricher {

	private static final Logger log = LogManager.getLogger(MesoNetworkEnricher.class);

	private static final String OSM_EXTRACT_DATE = "2026-08-05";

	private final Map<String, MesoMeasuresTable.Measure> measuresByLinkId;

	MesoNetworkEnricher(Map<String, MesoMeasuresTable.Measure> measuresByLinkId) {
		this.measuresByLinkId = measuresByLinkId;
	}

	void enrich(Network network) {
		int complete = 0;
		int partial = 0;
		for (Map.Entry<String, MesoMeasuresTable.Measure> entry : measuresByLinkId.entrySet()) {
			Link link = network.getLinks().get(Id.createLinkId(entry.getKey()));
			if (link == null) {
				throw new IllegalArgumentException("Measure for unknown link: " + entry.getKey());
			}
			boolean fullyMeasured = apply(link, entry.getValue());
			if (fullyMeasured) {
				complete++;
			} else {
				partial++;
			}
		}
		log.info("Meso enrichment: {} links fully measured, {} partially (still provisional)", complete, partial);
	}

	private boolean apply(Link link, MesoMeasuresTable.Measure measure) {
		boolean lengthApplied = false;
		boolean speedApplied = false;
		boolean capacityApplied = false;
		if (measure.lengthM().isPresent()) {
			link.setLength(measure.lengthM().get());
			lengthApplied = true;
		}
		if (measure.eqSpeedKmh().isPresent()) {
			link.setFreespeed(measure.eqSpeedKmh().get() / 3.6);
			speedApplied = true;
		} else if (lengthApplied && measure.gtfsMinSeconds().isPresent()) {
			// keep freespeed consistent with the corrected length; still a
			// derived placeholder, so the link stays provisional
			link.setFreespeed(measure.lengthM().get() / measure.gtfsMinSeconds().get());
		}
		if (measure.tracksTotal().isPresent()) {
			applyCapacity(link, measure.tracksTotal().get());
			capacityApplied = true;
		}
		if (!measure.osmWayIds().isBlank()) {
			link.getAttributes().putAttribute("osmWayIds", measure.osmWayIds());
			link.getAttributes().putAttribute("osmExtractDate", OSM_EXTRACT_DATE);
		}
		boolean fullyMeasured = lengthApplied && speedApplied && capacityApplied;
		if (fullyMeasured) {
			link.getAttributes().removeAttribute("dataStatus");
		}
		return fullyMeasured;
	}

	/**
	 * A double-track section holds one train per block section and direction,
	 * not one train per direction: automatic block (BAcc, sections of 900 to
	 * 1350 m on RFI and Ferrovienord lines) admits a following train as soon
	 * as the section behind the first is clear. One train every two sections
	 * of 1350 m, the section it occupies and the one keeping it apart from the
	 * train ahead, gives {@code length / 2700 m} trains per track, at least
	 * one. With one train per direction, sections of 30 to 50 km were queueing
	 * the whole line behind a single train (run of 2026-09-18).
	 */
	static final double BLOCK_SPACING_M = 2700.0;

	private void applyCapacity(Link link, int tracksTotal) {
		link.getAttributes().putAttribute("tracksTotal", tracksTotal);
		if (tracksTotal == 1) {
			link.getAttributes().putAttribute("railsimTrainCapacity", 1);
			link.getAttributes().putAttribute("railsimResourceId", sharedResourceId(link));
			return;
		}
		int tracksPerDirection = tracksTotal / 2;
		if (tracksTotal % 2 != 0) {
			log.warn("Link {}: odd track total {} — using {} per direction, extra track ignored",
				link.getId(), tracksTotal, tracksPerDirection);
		}
		link.getAttributes().putAttribute("railsimTrainCapacity", tracksPerDirection * blockSections(link.getLength()));
	}

	static int blockSections(double lengthM) {
		return Math.max(1, (int) Math.floor(lengthM / BLOCK_SPACING_M));
	}

	/** Same id for both directions of a station pair: sorted node ids. */
	private static String sharedResourceId(Link link) {
		TreeSet<String> nodes = new TreeSet<>();
		nodes.add(link.getFromNode().getId().toString());
		nodes.add(link.getToNode().getId().toString());
		return String.join("_", nodes);
	}
}
