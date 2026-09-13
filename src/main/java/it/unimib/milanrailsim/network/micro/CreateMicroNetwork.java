package it.unimib.milanrailsim.network.micro;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

import java.nio.file.Path;
import java.util.List;

/**
 * Splices the declared micro nodes into the mesoscopic network and writes the
 * result, the network the timetable pipeline and the map work on.
 * <p>
 * Usage: {@code mvn exec:java -Dexec.mainClass=it.unimib.milanrailsim.network.micro.CreateMicroNetwork}
 * with optional args {@code [mesoNetwork] [nodesDir] [outputFile]}.
 */
public final class CreateMicroNetwork {

	private static final Logger log = LogManager.getLogger(CreateMicroNetwork.class);

	private static final String DEFAULT_MESO = "scenarios/milan/network.xml";
	private static final String DEFAULT_NODES = "data/nodes";
	private static final String DEFAULT_OUTPUT = "scenarios/milan/network-micro.xml";

	private CreateMicroNetwork() {
	}

	public static void main(String[] args) {
		Path meso = Path.of(args.length > 0 ? args[0] : DEFAULT_MESO);
		Path nodes = Path.of(args.length > 1 ? args[1] : DEFAULT_NODES);
		Path output = Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT);
		run(meso, nodes, output);
	}

	static void run(Path mesoFile, Path nodesDir, Path outputFile) {
		Network network = NetworkUtils.readNetwork(mesoFile.toString());
		List<MicroNode> nodes = MicroNode.readAll(nodesDir);
		int linksBefore = network.getLinks().size();

		new MicroNodeBuilder(network).splice(nodes);

		new NetworkWriter(network).write(outputFile.toString());
		log.info("Spliced {} micro nodes into {} ({} -> {} links), wrote {}", nodes.size(), mesoFile,
			linksBefore, network.getLinks().size(), outputFile);
	}
}
