package it.unimib.milanrailsim.gui.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Checks a run can start: disk space, a costs source, a usable run name, the engine launcher. */
public final class Preflight {

	/** A baseline run archive weighs about a gigabyte; the margin covers analysis files. */
	static final long BYTES_PER_RUN = 1_200_000_000L;

	public record Finding(boolean blocking, String message) {
	}

	/** What the checks need to know about the host: free space for the runs and the java command the engine starts with. */
	record Machine(LongSupplier freeBytes, Path engineLauncher) {
	}

	private final Path runsDir;
	private final Path costsFile;
	private final Machine machine;

	public Preflight(Path runsDir, Path costsFile) {
		this(runsDir, costsFile, new Machine(() -> usableSpace(runsDir), EngineProcess.javaExecutable()));
	}

	Preflight(Path runsDir, Path costsFile, Machine machine) {
		this.runsDir = runsDir;
		this.costsFile = costsFile;
		this.machine = machine;
	}

	public List<Finding> check(String runName) {
		List<Finding> findings = new ArrayList<>();
		if (runName == null || runName.isBlank()) {
			findings.add(new Finding(true, "Dai un nome al run."));
		} else if (Files.exists(runsDir.resolve(runName))) {
			findings.add(new Finding(true, "Esiste già un run chiamato «" + runName + "». Scegli un altro nome."));
		}
		if (!Files.isRegularFile(machine.engineLauncher())) {
			findings.add(new Finding(true, "Il motore di simulazione non può partire: manca " + machine.engineLauncher()
				+ ". L'installazione è incompleta: reinstalla l'applicazione."));
		}
		long free = machine.freeBytes().getAsLong();
		if (free < BYTES_PER_RUN) {
			findings.add(new Finding(true, "Spazio su disco insufficiente: " + megabytes(free) + " liberi, ne servono almeno "
				+ megabytes(BYTES_PER_RUN) + " per questo run. Libera spazio in " + runsDir + " e riprova."));
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
