package com.tinyserver;

import java.io.IOException;

/**
 * A request body that did not arrive in time: after a grace period it must keep arriving at body_min_rate (see
 * {@link LineInputStream#readFully(int, long, int)}). Answered 408, counted against the client by ProbeFilter only if
 * it sent next to nothing ({@link #stalled()}): a slow phone is not a scanner, a client that announces a body and then
 * sends none is holding budget. Not a SocketTimeoutException, so it is never mistaken for an idle keep-alive socket.
 */
public class BodyTimeoutException extends IOException {

	private static final long serialVersionUID = 1L;

	final long got, expected;

	BodyTimeoutException(long got, long expected) {
		super("Request body too slow: " + got + " of " + expected + " bytes arrived in time.");
		this.got = got;
		this.expected = expected;
	}

	/** under 1 KB of the body arrived (or less than all of a smaller one): a stall, not a slow link */
	boolean stalled() {
		return got < Math.min(expected, 1024);
	}
}
