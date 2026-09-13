package it.unimib.milanrailsim.server;

import it.unimib.milanrailsim.server.Protocol.Frame;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Frames of a run on disk, one JSON line each, gzipped: what the replay plays back. */
public final class FrameRecorder implements AutoCloseable {

	public static final String FILE_NAME = "frames.jsonl.gz";

	private final BufferedWriter writer;

	public FrameRecorder(Path file) {
		try {
			Files.createDirectories(file.getParent());
			writer = new BufferedWriter(new OutputStreamWriter(
				new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot record frames to " + file, e);
		}
	}

	public void record(Frame frame) {
		try {
			writer.write(Protocol.encode(frame));
			writer.newLine();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot record frame at " + frame.time(), e);
		}
	}

	@Override
	public void close() {
		try {
			writer.close();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot close frame recording", e);
		}
	}

	public static List<Frame> read(Path file) {
		List<Frame> frames = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				frames.add(Protocol.decode(line, Frame.class));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read frames from " + file, e);
		}
		return List.copyOf(frames);
	}
}
