package com.tinyserver;

import java.io.IOException;

/**
 * A request refused before any of its body was read: 405 (no servlet takes a body at that path) or 503 (no room in
 * the {@link BodyBudget}). The server answers, then reads and drops a little of the body so the answer is not lost to
 * the reset a close with unread data causes, and closes the connection.
 */
public class BodyNotReadException extends IOException {

	private static final long serialVersionUID = 1L;

	final int code;
	/** header lines for the answer, each ending in CRLF, or "" */
	final String extraHeaders;
	final long contentLength;

	BodyNotReadException(int code, String msg, String extraHeaders, long contentLength) {
		super(msg);
		this.code = code;
		this.extraHeaders = extraHeaders;
		this.contentLength = contentLength;
	}
}
