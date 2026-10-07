package it.unimib.milanrailsim.gui.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the project data lives: the folder holding {@code scenarios/},
 * {@code data/}, {@code orari_trenord/} and the other read-only inputs, laid
 * out as in the repository. In development it is the working directory; the
 * packaged application receives it from its launcher through the
 * {@value #PROPERTY} system property. Everything the user produces goes to
 * {@link AppPaths} instead, never here.
 */
public final class DataRoot {

	public static final String PROPERTY = "milanrailsim.data";

	private DataRoot() {
	}

	/**
	 * @return the data root, absolute
	 * @throws IllegalStateException when the folder lacks the scenario or the node declarations
	 */
	public static Path resolve() {
		String configured = System.getProperty(PROPERTY);
		Path root = (configured == null ? Path.of("") : Path.of(configured)).toAbsolutePath().normalize();
		for (Path required : new Path[] { root.resolve("scenarios").resolve("milan"), root.resolve("data").resolve("nodes") }) {
			if (!Files.isDirectory(required)) {
				throw new IllegalStateException("Project data not found: missing " + required
					+ (configured == null ? " (start from the project folder or set -D" + PROPERTY + ")" : ""));
			}
		}
		return root;
	}
}
