package com.tinyserver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-wide memory budget for request bodies, in bytes. A POST reserves {@link #chargeFor} its Content-Length before
 * any of its body is read and gives it back once its servlet has returned, so however many bodies arrive at once, the
 * ones being read or served hold at most {@link #total()} bytes. The charge is {@link #COPIES} times the length: the
 * raw bytes, the decoded String (up to 2 bytes a char), the char[] content and the String a servlet makes from it are
 * alive at the same time; {@link #FORM_COPIES} for a urlencoded form, whose percent-decoding needs more for a moment.
 *
 * <p>Bodies over {@link #SMALL_MAX} bytes may together hold at most {@link #largeCap()} (3/4 of the budget), so the
 * rest is always there for small requests (moves, chat, JSON) even while big uploads fill their share. A request that
 * does not fit waits; every release wakes all waiters and each checks again, so a big body waiting never holds up a
 * small one behind it. A lock and condition rather than synchronized, so a waiting virtual thread does not pin its
 * carrier. One client (an IPv4 address or IPv6 /64) may hold at most half of the large share, so it takes at least two
 * addresses to keep big uploads out by announcing bodies and sending them slowly.
 *
 * <p>Not counted: what a servlet allocates while it works (a decoded image, a JSON tree), responses, and the buffers
 * of each connection. Those share the other 3/4 of the heap.
 */
final class BodyBudget {
	/** copies of a body alive at once while it is read and served; the charge is this times its length */
	static final int COPIES = 5;
	/** the same for a urlencoded form: decoding non-ASCII parameters peaks at about 10 times the body (measured) */
	static final int FORM_COPIES = 11;
	/** most requests waiting for room at once; more are refused at once (each release wakes every waiter) */
	static final int MAX_WAITERS = 64;
	/** bodies up to this many bytes are small: they may use the whole budget, the large ones only 3/4 of it */
	static final long SMALL_MAX = 64 * 1024;

	private final long total, largeCap;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition released = lock.newCondition();
	private long used, usedLarge, peak, busy, slow;
	private int waiting;
	/** large charges held per client key; an entry goes when its client holds none */
	private final Map<String, Long> largeByClient = new HashMap<>();

	/** a budget of {@code total} bytes; at least 4 KiB */
	BodyBudget(long total) {
		this.total = Math.max(total, 4096);
		this.largeCap = this.total - this.total / 4;
	}

	/** heap/4, the budget when the config does not set body_budget */
	static long defaultTotal() {
		return Runtime.getRuntime().maxMemory() / 4;
	}

	static boolean isLarge(long contentLength) {
		return contentLength > SMALL_MAX;
	}

	/**
	 * What a body of {@code contentLength} bytes reserves: {@link #COPIES} times its length ({@link #FORM_COPIES} for
	 * a urlencoded {@code form}), but never more than its pool can give, so the biggest allowed body can always be
	 * admitted: it runs once its pool is empty.
	 */
	long chargeFor(long contentLength, boolean form) {
		int copies = form ? FORM_COPIES : COPIES;
		long c = contentLength > Long.MAX_VALUE / copies ? Long.MAX_VALUE : contentLength * copies;
		return Math.min(c, isLarge(contentLength) ? largeCap : total);
	}

	long chargeFor(long contentLength) {
		return chargeFor(contentLength, false);
	}

	/**
	 * Reserves {@code charge} bytes, waiting up to {@code waitMs} for room. Returns false if there was none by then, or
	 * at once if {@link #MAX_WAITERS} are waiting already; the caller then answers 503 and must not call
	 * {@link #release}.
	 */
	boolean acquire(long charge, boolean large, long waitMs) throws InterruptedException {
		return acquire(charge, large, waitMs, null);
	}

	/**
	 * As above, for a large body of {@code client} (a key from ProbeFilter.clientKey, or null for no per-client
	 * limit): refused at once if that client already holds a large reservation and this one would take it past half
	 * of the large share. A single body is always allowed, so the biggest one can still run.
	 */
	boolean acquire(long charge, boolean large, long waitMs, String client) throws InterruptedException {
		long nanos = TimeUnit.MILLISECONDS.toNanos(waitMs);
		String key = large ? client : null;
		lock.lock();
		try {
			if (overClientCap(charge, key) || (!fits(charge, large) && waiting >= MAX_WAITERS)) {
				busy++;
				return false;
			}
			waiting++;
			try {
				while (!fits(charge, large)) {
					if (nanos <= 0 || overClientCap(charge, key)) {
						busy++;
						return false;
					}
					nanos = released.awaitNanos(nanos);
				}
				if (overClientCap(charge, key)) {   // the client got another reservation while this one waited
					busy++;
					return false;
				}
			} finally {
				waiting--;
			}
			used += charge;
			if (large) {
				usedLarge += charge;
			}
			if (key != null) {
				largeByClient.merge(key, charge, Long::sum);
			}
			peak = Math.max(peak, used);
			return true;
		} finally {
			lock.unlock();
		}
	}

	private boolean overClientCap(long charge, String key) {
		if (key == null) {
			return false;
		}
		long held = largeByClient.getOrDefault(key, 0L);
		return held > 0 && held + charge > largeCap / 2;
	}

	private boolean fits(long charge, boolean large) {
		return used + charge <= total && (!large || usedLarge + charge <= largeCap);
	}

	/** gives back what one granted {@link #acquire} reserved; exactly once per grant */
	void release(long charge, boolean large) {
		release(charge, large, null);
	}

	/** as above, for a reservation acquired with a {@code client} key */
	void release(long charge, boolean large, String client) {
		lock.lock();
		try {
			used -= charge;
			if (large) {
				usedLarge -= charge;
				if (client != null) {
					largeByClient.computeIfPresent(client, (k, v) -> v - charge <= 0 ? null : v - charge);
				}
			}
			released.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/** a body that came in too slowly (answered 408), for the report */
	void countSlow() {
		lock.lock();
		try {
			slow++;
		} finally {
			lock.unlock();
		}
	}

	long total() {
		return total;
	}

	long largeCap() {
		return largeCap;
	}

	long used() {
		lock.lock();
		try {
			return used;
		} finally {
			lock.unlock();
		}
	}

	/** large charges held by {@code client}, for tests */
	long heldBy(String client) {
		lock.lock();
		try {
			return largeByClient.getOrDefault(client, 0L);
		} finally {
			lock.unlock();
		}
	}

	long usedLarge() {
		lock.lock();
		try {
			return usedLarge;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * One log line about the time since the last call, or null if nothing worth reporting happened (no 503, no 408,
	 * peak under half the budget). Starts the next period.
	 */
	String report() {
		lock.lock();
		try {
			String line = null;
			if (busy > 0 || slow > 0 || peak * 2 >= total) {
				line = "Body budget: peak " + mb(peak) + " of " + mb(total) + ", now " + mb(used) + " (large " + mb(usedLarge)
						+ "); refused busy (503): " + busy + ", too slow (408): " + slow;
			}
			peak = used;
			busy = 0;
			slow = 0;
			return line;
		} finally {
			lock.unlock();
		}
	}

	/** what a startup log line says about the budget, with a warning when a max_req_len body can only run alone */
	String describe(long maxRequestLength) {
		long biggest = Math.max(maxRequestLength - 1, 1);   // Content-Length must be below max_req_len
		long c = chargeFor(biggest), pool = isLarge(biggest) ? largeCap : total;
		String s = "Body budget: " + mb(total) + " (bodies over " + SMALL_MAX / 1024 + " KB: at most " + mb(largeCap)
				+ " together); a " + mb(biggest) + " body reserves " + mb(c) + ", so " + Math.max(1, pool / c) + " at once";
		if (biggest * COPIES > pool) {
			s += ". WARNING: a body near max_req_len needs " + mb(biggest * COPIES) + " and runs only alone;"
					+ " raise -Xmx or body_budget, or lower max_req_len";
		} else if (isLarge(biggest) && c > largeCap / 2) {
			s += ". WARNING: one body near max_req_len takes over half the share for big bodies, so one client can"
					+ " keep other big uploads out; raise -Xmx or body_budget, or lower max_req_len";
		}
		return s;
	}

	static String mb(long bytes) {
		return bytes >= 10L << 20 ? (bytes >> 20) + " MB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
	}
}
