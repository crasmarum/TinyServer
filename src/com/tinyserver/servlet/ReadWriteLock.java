package com.tinyserver.servlet;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thin wrapper around {@link ReentrantReadWriteLock} preserving the original
 * acquire/release API so existing callers don't need to change.
 *
 * <p>Why the rewrite: the previous hand-rolled implementation used
 * {@code synchronized} methods plus {@code Object.wait()} for blocking,
 * both of which pin a virtual thread to its carrier on Java 21 (the JEP 491
 * fix only ships in Java 24+). Under load that capped server concurrency at
 * the carrier-thread count rather than the virtual-thread count — every
 * request VT going through {@code authenticate()} would acquire+release
 * read and write locks here, and the contention serialized the whole server
 * on a single monitor. {@link ReentrantReadWriteLock} uses
 * {@link java.util.concurrent.locks.LockSupport#park LockSupport.park} which
 * is Loom-aware, so blocking VTs are unmounted from their carriers and
 * concurrency scales with VT count instead of CPU count.</p>
 *
 * <p>Behaviour differences from the hand-rolled lock:</p>
 * <ul>
 *   <li>Read and write are reentrant within the same thread (same as before).</li>
 *   <li>{@code releaseRead} / {@code releaseWrite} on a thread that doesn't
 *       hold the corresponding lock now no-ops via a swallowed
 *       {@link IllegalMonitorStateException}, matching the old tolerant
 *       behaviour.</li>
 *   <li>Upgrade read→write is still unsupported (would deadlock — same as
 *       before, just enforced by the JDK lock rather than an explicit throw).</li>
 *   <li>Downgrade write→read is now permitted (JDK semantics). The old
 *       implementation threw on this; no caller in the tree relies on the
 *       old throw.</li>
 * </ul>
 */
public class ReadWriteLock {
	public static String READ_RIGHT = "read_r";
	public static String WRITE_RIGHT = "write_r";

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Acquire a read lock, blocking until it can be granted. Interruptible
	 * so callers waiting at shutdown can be woken cleanly.
	 */
	public void acquireRead() throws InterruptedException {
		lock.readLock().lockInterruptibly();
	}

	/**
	 * Source-aware variant kept for API compatibility. The {@code source}
	 * argument was used by the original implementation to fire listener
	 * events on contention; that path was already disabled (the listener
	 * list was commented out) so the argument is now ignored.
	 */
	public void acquireRead(Object source) throws InterruptedException {
		lock.readLock().lockInterruptibly();
	}

	public void releaseRead() {
		try {
			lock.readLock().unlock();
		} catch (IllegalMonitorStateException ignored) {
			// Caller didn't hold the read lock. The previous implementation
			// silently no-op'd this; preserve that to avoid surprising any
			// release-without-acquire code paths.
		}
	}

	public void acquireWrite() throws InterruptedException {
		lock.writeLock().lockInterruptibly();
	}

	public void acquireWrite(Object source) throws InterruptedException {
		lock.writeLock().lockInterruptibly();
	}

	public void releaseWrite() {
		try {
			lock.writeLock().unlock();
		} catch (IllegalMonitorStateException ignored) {
			// See releaseRead — same tolerance for release-without-acquire.
		}
	}

	/**
	 * Returns {@link #WRITE_RIGHT}, {@link #READ_RIGHT}, or {@code null}
	 * depending on what the calling thread currently holds. Preserved for
	 * API compatibility; nothing in the tree calls it.
	 */
	public String getCurrentRight() {
		if (lock.isWriteLockedByCurrentThread()) {
			return WRITE_RIGHT;
		}
		if (lock.getReadHoldCount() > 0) {
			return READ_RIGHT;
		}
		return null;
	}
}
