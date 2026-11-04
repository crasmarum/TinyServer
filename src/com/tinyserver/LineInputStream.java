package com.tinyserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Byte-oriented input for an HTTP connection: header lines are read as bytes up to LF (a trailing CR is dropped) and
 * decoded as UTF-8; a body is read as exactly Content-Length raw bytes. Unlike a Reader, nothing is decoded or read
 * ahead past what is asked for (beyond the byte buffer underneath), so binary bodies such as file uploads arrive intact
 * and the next request on a kept-alive connection starts where this one ended.
 */
public class LineInputStream extends FilterInputStream {
	/** longest header or request line accepted */
	public static final int MAX_LINE = 65536;

	/** the socket {@code in} reads from, for body deadlines; null when there is none */
	private final Socket socket;

	public LineInputStream(InputStream in) {
		this(in, null);
	}

	/** reads {@code in}, which comes from {@code socket}: {@link #readFully(int, long, int)} then keeps its deadlines */
	public LineInputStream(InputStream in, Socket socket) {
		super(in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 16384));
		this.socket = socket;
	}

	/** the next line without its line ending, or null at the end of the stream before any byte */
	public String readLine() throws IOException {
		byte[] bytes = readLineAsBytes();
		return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
	}

	public byte[] readLineAsBytes() throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\n') {
				break;
			}
			buf.write(c);
			if (buf.size() > MAX_LINE) {
				throw new RequestTooBigException("Line longer than " + MAX_LINE + " bytes.");
			}
		}
		if (c == -1 && buf.size() == 0) {
			return null;
		}
		byte[] b = buf.toByteArray();
		int n = b.length;
		if (n > 0 && b[n - 1] == '\r') {
			n--;
		}
		return n == b.length ? b : Arrays.copyOf(b, n);
	}

	/** exactly n bytes, or an IOException if the stream ends first */
	public byte[] readFully(int n) throws IOException {
		return read(n, false, 0, 1);
	}

	/**
	 * Exactly n bytes, arriving at {@code minRate} bytes a second or faster after a grace period: byte k must be in by
	 * {@code graceEndNanos} (a {@link System#nanoTime()} value) + k / minRate, else {@link BodyTimeoutException}. Like
	 * Apache's mod_reqtimeout MinRate, the deadline moves with the data, so a client that stalls or trickles is cut off
	 * soon after the grace period (not when its whole body would have been due), and one that keeps up is never cut.
	 * Before each read the socket timeout is set to the time left (at most the socket's own timeout, which is put back
	 * afterwards). Without a socket this is {@link #readFully(int)}.
	 */
	public byte[] readFully(int n, long graceEndNanos, int minRate) throws IOException {
		return read(n, socket != null && minRate > 0, graceEndNanos, Math.max(minRate, 1));
	}

	/**
	 * Into one array of exactly n bytes (readNBytes collects chunks and copies them, holding twice the body at the
	 * end); at most 64 KB a read.
	 */
	private byte[] read(int n, boolean timed, long graceEndNanos, int minRate) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		int idleMs = timed ? socket.getSoTimeout() : 0;
		try {
			while (off < n) {
				if (timed) {
					long due = graceEndNanos + (off + 1L) * 1_000_000_000L / minRate;   // when byte off + 1 is due
					long leftNs = due - System.nanoTime();
					if (leftNs <= 0) {
						throw new BodyTimeoutException(off, n);
					}
					long leftMs = (leftNs + 999_999) / 1_000_000;   // rounded up: 0 would mean no timeout at all
					socket.setSoTimeout((int) Math.min(leftMs, idleMs > 0 ? idleMs : Integer.MAX_VALUE));
				}
				int r;
				try {
					r = in.read(b, off, Math.min(n - off, 65536));
				} catch (SocketTimeoutException e) {
					if (timed) {
						throw new BodyTimeoutException(off, n);
					}
					throw e;
				}
				if (r < 0) {
					throw new IOException("Unexpected EO stream.");
				}
				off += r;
			}
		} finally {
			if (timed) {
				try {
					socket.setSoTimeout(idleMs);
				} catch (SocketException closed) {
				}
			}
		}
		return b;
	}
}
