package com.tinyserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Output stream for answering an HTTP HEAD request: the response is produced exactly as for GET, and this stream
 * passes the status line and headers through but drops everything after the blank line that ends them. The client
 * therefore sees the same status, Content-Type and Content-Length as for GET, with no body (RFC 9110, 9.3.2).
 */
final class HeadOnlyOutputStream extends BufferedOutputStream {

	private boolean inBody = false;
	private int match = 0;   // progress through the header terminator "\r\n\r\n"

	HeadOnlyOutputStream(OutputStream out) {
		super(out);
	}

	@Override
	public synchronized void write(int b) throws IOException {
		if (inBody) {
			return;
		}
		super.write(b);
		advance((byte) b);
	}

	@Override
	public synchronized void write(byte[] b, int off, int len) throws IOException {
		int i = off, end = off + len;
		while (i < end && !inBody) {
			super.write(b[i]);
			advance(b[i]);
			i++;
		}
	}

	private void advance(byte b) {
		if (b == '\r') {
			match = (match == 2) ? 3 : 1;
		} else if (b == '\n' && (match == 1 || match == 3)) {
			match++;
			if (match == 4) {
				inBody = true;
			}
		} else {
			match = 0;
		}
	}
}
