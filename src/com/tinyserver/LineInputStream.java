package com.tinyserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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

	public LineInputStream(InputStream in) {
		super(in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 16384));
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
		byte[] b = in.readNBytes(n);
		if (b.length < n) {
			throw new IOException("Unexpected EO stream.");
		}
		return b;
	}
}
