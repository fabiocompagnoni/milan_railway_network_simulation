package it.unimib.milanrailsim.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies the committed OSM measures table to the provisional skeleton and
 * writes the mesoscopic network (railsim network specification, meso level).
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.network.ApplyMesoMeasures}
 * with optional args {@code [skeletonFile] [measuresCsv] [outputFile]}.
 */
public final class ApplyMesoMeasures {

	private static final Logger log = LogManager.getLogger(ApplyMesoMeasures.class);

	private static final String DEFAULT_SKELETON = "scenarios/milan/network-skeleton.xml";
	private static final String DEFAULT_MEASURES = "data/osm/2026-08-05-network-sweep/link_measures.csv";
	private static final String DEFAULT_OUTPUT = "scenarios/milan/network.xml";

	private ApplyMesoMeasures() {
	}

	public static void main(String[] args) {
		Path skeleton = Path.of(args.length > 0 ? args[0] : DEFAULT_SKELETON);
		Path measures = Path.of(args.length > 1 ? args[1] : DEFAULT_MEASURES);
		Path output = Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT);
		run(skeleton, measures, output);
	}

	static void run(Path skeletonFile, Path measuresCsv, Path outputFile) {
		Network network = NetworkUtils.readNetwork(skeletonFile.toString());
		MesoMeasuresTable table = MesoMeasuresTable.load(measuresCsv);
		log.info("Loaded skeleton {} ({} links) and measures {} ({} rows)",
			skeletonFile, network.getLinks().size(), measuresCsv, table.byLinkId().size());

		new MesoNetworkEnricher(table.byLinkId()).enrich(network);

		try {
			Files.createDirectories(outputFile.toAbsolutePath().getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output directory for " + outputFile, e);
		}
		new NetworkWriter(network).write(outputFile.toString());
		log.info("Wrote mesoscopic network to {}", outputFile);
	}
}
