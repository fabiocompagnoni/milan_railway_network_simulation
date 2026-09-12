package it.unimib.milanrailsim.gui.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Checks a run can start: disk space for every run it spawns, a costs source, a usable run name. */
public final class Preflight {

	/** A baseline run archive weighs about a gigabyte; the margin covers analysis files. */
	static final long BYTES_PER_RUN = 1_200_000_000L;

	public record Finding(boolean blocking, String message) {
	}

	private final Path runsDir;
	private final Path costsFile;
	private final LongSupplier freeBytes;

	public Preflight(Path runsDir, Path costsFile) {
		this(runsDir, costsFile, () -> usableSpace(runsDir));
	}

	Preflight(Path runsDir, Path costsFile, LongSupplier freeBytes) {
		this.runsDir = runsDir;
		this.costsFile = costsFile;
		this.freeBytes = freeBytes;
	}

	public List<Finding> check(String runName, int runCount) {
		List<Finding> findings = new ArrayList<>();
		if (runName == null || runName.isBlank()) {
			findings.add(new Finding(true, "Dai un nome al run."));
		} else if (Files.exists(runsDir.resolve(runName))) {
			findings.add(new Finding(true, "Esiste già un run chiamato «" + runName + "». Scegli un altro nome."));
		}
		long required = BYTES_PER_RUN * runCount;
		long free = freeBytes.getAsLong();
		if (free < required) {
			findings.add(new Finding(true, "Spazio su disco insufficiente: " + megabytes(free) + " liberi, ne servono almeno "
				+ megabytes(required) + " per " + (runCount == 1 ? "questo run" : runCount + " run")
				+ ". Libera spazio in " + runsDir + " e riprova."));
		}
		if (!Files.exists(costsFile)) {
			findings.add(new Finding(false, "Nessuna sorgente costi caricata: il computo costi di questo run sarà incompleto. "
				+ "Configura i costi dalle Impostazioni."));
		}
		return List.copyOf(findings);
	}

	private static long usableSpace(Path dir) {
		try {
			Path existing = dir;
			while (!Files.exists(existing)) {
				existing = existing.getParent();
			}
			return Files.getFileStore(existing).getUsableSpace();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read free space of " + dir, e);
		}
	}

	private static String megabytes(long bytes) {
		long megabytes = bytes / 1_000_000;
		return megabytes >= 10_000 ? String.format("%.1f GB", megabytes / 1000.0) : megabytes + " MB";
	}
}
