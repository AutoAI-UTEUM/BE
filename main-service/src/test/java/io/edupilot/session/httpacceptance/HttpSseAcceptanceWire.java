package io.edupilot.session.httpacceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A real HTTP/1.1 socket reader, with no Spring emitter or lifecycle callback access. */
public final class HttpSseAcceptanceWire implements AutoCloseable {
	private static final Duration DEADLINE = Duration.ofSeconds(15);
	private final Socket socket;
	private final String contentType;
	private final List<Event> history = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<Event> arrived = new LinkedBlockingQueue<>();
	private final CountDownLatch ended = new CountDownLatch(1);
	private final AtomicReference<Throwable> readFailure = new AtomicReference<>();
	private final Thread reader;
	private volatile boolean localClose;

	public static HttpSseAcceptanceWire open(int port, String path, String syntheticJwt) throws IOException {
		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
			socket.setSoTimeout(30000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
				+ "\r\nAuthorization: Bearer " + syntheticJwt
				+ "\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
			String status = line(input);
			if (status == null || !status.startsWith("HTTP/1.1 200")) {
				throw new IOException("Synthetic SSE open status: " + status);
			}
			String contentType = null;
			boolean chunked = false;
			for (String header; (header = line(input)) != null && !header.isEmpty();) {
				String lower = header.toLowerCase(Locale.ROOT);
				if (lower.startsWith("content-type:")) contentType = header.substring(header.indexOf(':') + 1).trim();
				if (lower.startsWith("transfer-encoding:")) chunked = lower.contains("chunked");
			}
			return new HttpSseAcceptanceWire(socket, contentType, chunked ? new Chunks(input) : input);
		} catch (IOException | RuntimeException failure) {
			socket.close();
			throw failure;
		}
	}

	private HttpSseAcceptanceWire(Socket socket, String contentType, InputStream body) {
		this.socket = socket;
		this.contentType = contentType;
		this.reader = Thread.ofVirtual().name("owned-http-sse-wire-" + socket.getLocalPort()).start(() -> {
			try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
				String event = null;
				List<String> data = new ArrayList<>();
				for (String current; (current = lines.readLine()) != null;) {
					if (current.isEmpty()) {
						if (event != null) {
							Event receipt = new Event(event, String.join("\n", data));
							history.add(receipt);
							arrived.add(receipt);
						}
						event = null;
						data.clear();
					} else if (current.startsWith("event:")) event = current.substring(6).stripLeading();
					else if (current.startsWith("data:")) data.add(current.substring(5).stripLeading());
				}
			} catch (Throwable failure) {
				if (!localClose) readFailure.set(failure);
			} finally { ended.countDown(); }
		});
	}

	public String contentType() { return contentType; }

	public void awaitEvent(String expectedName, String syntheticMarker) throws InterruptedException {
		long deadline = System.nanoTime() + DEADLINE.toNanos();
		while (System.nanoTime() < deadline) {
			Event event = arrived.poll(Math.min(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 1, 250),
				TimeUnit.MILLISECONDS);
			if (event == null) {
				assertThat(readFailure.get()).as("Actual HTTP SSE reader failure").isNull();
				assertThat(ended.getCount()).as("SSE ended before expected " + expectedName).isGreaterThan(0);
				continue;
			}
			if (event.name().equals(expectedName)) {
				assertThat(event.data()).contains(syntheticMarker);
				return;
			}
		}
		throw new AssertionError("Actual HTTP SSE did not receive " + expectedName + " before deadline");
	}

	public boolean awaitEnd() throws InterruptedException {
		boolean result = ended.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
		assertThat(readFailure.get()).as("Actual HTTP SSE terminal framing").isNull();
		return result;
	}

	public List<String> eventNames() { return history.stream().map(Event::name).toList(); }
	public String allData() { return String.join("\n", history.stream().map(Event::data).toList()); }

	public void resetPeer() throws IOException {
		localClose = true;
		socket.setSoLinger(true, 0);
		socket.close();
	}

	public boolean awaitReaderStopped() throws InterruptedException {
		reader.join(Duration.ofSeconds(5));
		return !reader.isAlive();
	}

	@Override
	public void close() throws IOException {
		localClose = true;
		socket.close();
	}

	private record Event(String name, String data) { }

	private static String line(InputStream input) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int next; (next = input.read()) != -1;) {
			if (next == '\n') return line.toString(StandardCharsets.US_ASCII).replace("\r", "");
			line.write(next);
			if (line.size() > 16384) throw new IOException("Owned HTTP header exceeded bound");
		}
		return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
	}

	private static final class Chunks extends InputStream {
		private final InputStream source;
		private int remaining;
		private boolean started;
		private boolean finished;
		private Chunks(InputStream source) { this.source = source; }

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
		}

		@Override
		public int read(byte[] target, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (finished) return -1;
			if (remaining == 0) {
				if (started && !"".equals(line(source))) throw new IOException("Invalid owned HTTP chunk terminator");
				String size = line(source);
				if (size == null) throw new EOFException("Missing owned HTTP chunk size");
				int extension = size.indexOf(';');
				try { remaining = Integer.parseInt(extension < 0 ? size : size.substring(0, extension), 16); }
				catch (NumberFormatException failure) { throw new IOException("Invalid owned HTTP chunk size", failure); }
				started = true;
				if (remaining == 0) {
					for (String trailer; (trailer = line(source)) != null && !trailer.isEmpty();) { }
					finished = true;
					return -1;
				}
			}
			int count = source.read(target, offset, Math.min(length, remaining));
			if (count == -1) throw new EOFException("Truncated owned HTTP chunk");
			remaining -= count;
			return count;
		}

		@Override
		public void close() throws IOException { source.close(); }
	}
}
