package com.tinyserver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.tinyserver.servlet.Log;

/**
 * The request-body memory budget (BodyBudget) and the body minimum rate (LineInputStream.readFully with a rate): pools,
 * waiting, no head-of-line blocking, the clamp for the biggest body, a stress run on virtual threads, and the deadline
 * over a real loopback socket (stall, trickle, fast body, timeout restored, buffered bytes). No JUnit (see
 * JsonParserTest); excluded from server.jar by name. Any failure exits non-zero.
 *
 * Run: javac -d out $(find . -name "*.java") && java -cp out com.tinyserver.BodyBudgetTest
 */
public class BodyBudgetTest {
	static int passed = 0, failed = 0;

	static void check(String label, boolean cond) {
		if (cond) passed++; else { failed++; System.out.println("FAIL " + label); }
	}

	public static void main(String[] args) {
		try {
			budget();
			stress();
			deadline();
		} catch (Throwable t) {
			t.printStackTrace(System.out);
			failed++;
		}
		System.out.println("BodyBudgetTest: " + passed + " passed, " + failed + " failed.");
		Log.stop();
		System.exit(failed == 0 ? 0 : 1);
	}

	static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	static void budget() throws Exception {
		BodyBudget b = new BodyBudget(4_000_000);
		check("large cap is 3/4: " + b.largeCap(), b.largeCap() == 3_000_000 && b.total() == 4_000_000);
		check("a 2 KB body reserves 5 x 2 KB", b.chargeFor(2000) == 10_000 && !BodyBudget.isLarge(2000));
		check("a 2 KB urlencoded form reserves 11 x 2 KB", b.chargeFor(2000, true) == 22_000);
		check("64 KB is small, 64 KB + 1 is large", !BodyBudget.isLarge(65536) && BodyBudget.isLarge(65537));
		check("a body bigger than the large pool is clamped to it", b.chargeFor(5_999_999) == 3_000_000);
		check("no overflow on an absurd length", b.chargeFor(Long.MAX_VALUE / 2) == 3_000_000);

		long big = b.chargeFor(150_000);
		check("150 KB reserves 750 KB", big == 750_000);
		for (int i = 0; i < 4; i++) {
			check("large grant " + i, b.acquire(big, true, 0));
		}
		check("the large pool is full: " + b.usedLarge(), b.used() == 3_000_000 && b.usedLarge() == 3_000_000);
		long t0 = System.nanoTime();
		boolean fifth = b.acquire(big, true, 300);
		long waited = ms(t0);
		check("a fifth large body waits, then is refused (" + waited + " ms)", !fifth && waited >= 250 && waited < 1000);
		check("a small body still fits beside a full large pool", b.acquire(10_000, false, 0));
		check("small bodies may use the rest of the budget", b.acquire(990_000, false, 0) && b.used() == 4_000_000);
		check("and no more", !b.acquire(1, false, 0));
		b.release(990_000, false);
		b.release(10_000, false);
		String rep = b.report();
		check("report after a refusal: " + rep, rep != null && rep.contains("busy (503): 2"));
		String next = b.report();
		check("the next period starts from what is held now (3 MB of 4, still reported): " + next, next != null && next.contains("busy (503): 0"));

		// a parked large waiter is woken by a release and does not block a small request behind it
		AtomicBoolean got = new AtomicBoolean();
		AtomicInteger wokeMs = new AtomicInteger(-1);
		Thread w = Thread.ofVirtual().start(() -> {
			try {
				long s = System.nanoTime();
				got.set(b.acquire(big, true, 5000));
				wokeMs.set((int) ms(s));
			} catch (InterruptedException e) {
			}
		});
		Thread.sleep(200);
		t0 = System.nanoTime();
		check("no head-of-line blocking: a small body is granted while a large one waits", b.acquire(5_000, false, 0) && ms(t0) < 50);
		b.release(5_000, false);
		long rel = System.nanoTime();
		b.release(big, true);
		w.join(2000);
		long afterRelease = ms(rel);
		check("the waiter got the room within " + afterRelease + " ms of a release", got.get() && afterRelease < 200 && wokeMs.get() >= 150);
		for (int i = 0; i < 4; i++) {
			b.release(big, true);
		}
		check("all given back: used 0", b.used() == 0 && b.usedLarge() == 0);

		// the clamp: the biggest allowed body runs once the large pool is empty
		check("a max-size body is granted when the pool is empty", b.acquire(b.chargeFor(5_999_999), true, 0));
		check("but nothing large beside it", !b.acquire(b.chargeFor(70_000), true, 0));
		check("while small ones still fit", b.acquire(b.chargeFor(1000), false, 0));
		b.release(b.chargeFor(1000), false);
		b.release(b.chargeFor(5_999_999), true);

		// a small waiter queued behind a large one gets room the large one cannot use (every release wakes all waiters)
		b.acquire(3_000_000, true, 0);
		b.acquire(1_000_000, false, 0);
		AtomicBoolean largeGot = new AtomicBoolean(), smallGot = new AtomicBoolean();
		AtomicInteger smallMs = new AtomicInteger(-1);
		Thread lw = Thread.ofVirtual().start(() -> { try { largeGot.set(b.acquire(big, true, 1500)); } catch (InterruptedException e) {} });
		Thread.sleep(100);
		long[] relAt = new long[1];
		Thread sw = Thread.ofVirtual().start(() -> {
			try { smallGot.set(b.acquire(10_000, false, 1500)); smallMs.set((int) ((System.nanoTime() - relAt[0]) / 1_000_000)); } catch (InterruptedException e) {}
		});
		Thread.sleep(100);
		relAt[0] = System.nanoTime();
		b.release(1_000_000, false);
		sw.join(3000);
		check("the small waiter behind a large one is served at the release (" + smallMs + " ms)", smallGot.get() && smallMs.get() >= 0 && smallMs.get() < 200);
		lw.join(3000);
		check("the large one, which never fitted, timed out", !largeGot.get());
		b.release(10_000, false);
		b.release(3_000_000, true);
		check("budget empty after the queue test", b.used() == 0);
		b.report();

		// a tiny budget still serves a small body (clamped to the whole budget)
		BodyBudget tiny = new BodyBudget(1);
		check("budget at least 4 KiB: " + tiny.total(), tiny.total() == 4096);
		check("a 2 KB body on a 4 KiB budget: clamped and granted", tiny.chargeFor(2000) == 4096 && tiny.acquire(4096, false, 0));
		tiny.release(4096, false);

		// interrupted while waiting: InterruptedException, nothing reserved
		b.acquire(3_000_000, true, 0);
		Thread it = Thread.ofVirtual().start(() -> {
			try {
				b.acquire(big, true, 5000);
				check("interrupt: should not be granted", false);
			} catch (InterruptedException e) {
				check("an interrupted wait throws", true);
			}
		});
		Thread.sleep(100);
		it.interrupt();
		it.join(2000);
		b.release(3_000_000, true);
		check("nothing reserved by the interrupted waiter", b.used() == 0);

		// at most MAX_WAITERS wait; the next one is refused at once instead of joining every release's wake-up
		b.acquire(3_000_000, true, 0);
		List<Thread> waiters = new ArrayList<>();
		AtomicInteger waitedOut = new AtomicInteger();
		for (int i = 0; i < BodyBudget.MAX_WAITERS; i++) {
			waiters.add(Thread.ofVirtual().start(() -> {
				try { if (!b.acquire(big, true, 1500)) waitedOut.incrementAndGet(); else b.release(big, true); } catch (InterruptedException e) {}
			}));
		}
		Thread.sleep(300);
		t0 = System.nanoTime();
		boolean extra = b.acquire(big, true, 1500);
		long extraMs = ms(t0);
		check("waiter " + (BodyBudget.MAX_WAITERS + 1) + " is refused at once (" + extraMs + " ms)", !extra && extraMs < 100);
		check("a small request that fits is not affected by the cap", b.acquire(10_000, false, 0));
		b.release(10_000, false);
		for (Thread w2 : waiters) w2.join();
		check("the " + BodyBudget.MAX_WAITERS + " waiters timed out normally: " + waitedOut, waitedOut.get() == BodyBudget.MAX_WAITERS);
		b.release(3_000_000, true);
		check("budget empty after the waiter test", b.used() == 0);
		b.report();

		// startup line: an app with 6 MB uploads on -Xmx400m (a ~100 MB budget), and 15 MB messages on a smaller heap
		String uploads = new BodyBudget(100L << 20).describe(6_000_000);
		check("6 MB uploads, 100 MB budget: " + uploads, uploads.contains("so 2 at once") && !uploads.contains("WARNING"));
		String chat = new BodyBudget(64L << 20).describe(15_000_000);
		check("15 MB messages on a 256 MB heap warn: " + chat, chat.contains("WARNING") && chat.contains("so 1 at once"));
		String quiet = new BodyBudget(100L << 20).report();
		check("an idle budget reports nothing", quiet == null);
	}

	/** many virtual threads acquire, hold, sometimes fail in between, release: the budget is never exceeded and ends at 0 */
	static void stress() throws Exception {
		BodyBudget b = new BodyBudget(2_000_000);
		AtomicInteger over = new AtomicInteger(), granted = new AtomicInteger(), refused = new AtomicInteger();
		List<Thread> ts = new ArrayList<>();
		for (int i = 0; i < 2000; i++) {
			final int seed = i;
			ts.add(Thread.ofVirtual().start(() -> {
				Random r = new Random(seed);
				long len = r.nextInt(4) == 0 ? 65_537 + r.nextInt(400_000) : 1 + r.nextInt(65_536);
				boolean large = BodyBudget.isLarge(len);
				long c = b.chargeFor(len);
				try {
					if (!b.acquire(c, large, r.nextInt(50))) {
						refused.incrementAndGet();
						return;
					}
				} catch (InterruptedException e) {
					return;
				}
				try {
					granted.incrementAndGet();
					if (b.used() > b.total() || b.usedLarge() > b.largeCap()) over.incrementAndGet();
					if (r.nextInt(5) == 0) throw new IllegalStateException("servlet failed");
					Thread.sleep(r.nextInt(3));
				} catch (InterruptedException | IllegalStateException e) {
				} finally {
					b.release(c, large);
				}
			}));
		}
		for (Thread t : ts) t.join();
		// with at most MAX_WAITERS waiting, most of 2000 simultaneous requests are refused at once; enough get through
		check("stress: " + granted + " granted, " + refused + " refused, never over budget", over.get() == 0 && granted.get() > 0
				&& granted.get() + refused.get() == 2000);
		check("stress: all given back", b.used() == 0 && b.usedLarge() == 0);
	}

	/** a connected pair over loopback: [0] the server side (SO_TIMEOUT 30 s, like the server), [1] the client */
	static Socket[] pair() throws IOException {
		try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
			Socket s = ss.accept();
			s.setSoTimeout(30000);
			return new Socket[] {s, c};
		}
	}

	static long deadlineIn(long ms) {
		return System.nanoTime() + ms * 1_000_000L;
	}

	static void deadline() throws Exception {
		// exact bytes, binary intact
		byte[] bin = new byte[3 << 20];
		for (int i = 0; i < bin.length; i++) bin[i] = (byte) (i * 31 + (i >> 8));
		Socket[] p = pair();
		Thread.ofVirtual().start(() -> { try { p[1].getOutputStream().write(bin); p[1].getOutputStream().flush(); } catch (IOException e) {} });
		LineInputStream in = new LineInputStream(p[0].getInputStream(), p[0]);
		byte[] got = in.readFully(bin.length, deadlineIn(2000), 1_000_000);
		check("3 MB binary body, exact length, every byte intact", got.length == bin.length && java.util.Arrays.equals(got, bin));
		check("socket timeout put back to 30 s", p[0].getSoTimeout() == 30000);
		p[1].close();
		boolean eof = false;
		try { in.readFully(10, deadlineIn(2000), 1000); } catch (BodyTimeoutException e) { } catch (IOException e) { eof = e.getMessage().contains("EO stream"); }
		check("end of stream before n bytes: IOException, not a timeout", eof);
		p[0].close();

		// a client that sends its headers and then nothing: cut at the deadline, not after 30 s
		Socket[] q = pair();
		q[1].getOutputStream().write("POST /x HTTP/1.1\r\nContent-Length: 100\r\n\r\nabc".getBytes(StandardCharsets.ISO_8859_1));
		in = new LineInputStream(q[0].getInputStream(), q[0]);
		check("request line read", "POST /x HTTP/1.1".equals(in.readLine()));
		Hashtable<String, String> h = new Hashtable<>();
		check("headers read", ServerEngine.readHeaders(in, h) == 100);
		long t0 = System.nanoTime();
		String why = null;
		try { in.readFully(100, deadlineIn(500), 1000); } catch (BodyTimeoutException e) { why = e.getMessage(); }
		long took = ms(t0);
		// the 3 bytes already buffered with the headers are credit: byte 4 is due 500 ms + 4 ms after the start
		check("stall after the headers: 408 after the grace (" + took + " ms): " + why, why != null && took >= 450 && took < 900 && why.contains("3 of 100"));
		check("socket timeout put back after a timeout", q[0].getSoTimeout() == 30000);
		q[0].close(); q[1].close();

		// a trickle: one byte every 200 ms (5 B/s), each well inside the 30 s socket timeout, against a 10 B/s floor after a
		// 500 ms grace: byte k is due at 0.5 + k/10 s and arrives at about 0.2 k s, so it falls behind at about 1 s, long
		// before the 10.5 s a single deadline for the whole body would allow
		Socket[] t = pair();
		AtomicBoolean stop = new AtomicBoolean();
		Thread.ofVirtual().start(() -> {
			try { OutputStream o = t[1].getOutputStream(); while (!stop.get()) { o.write('x'); o.flush(); Thread.sleep(200); } } catch (Exception e) {}
		});
		in = new LineInputStream(t[0].getInputStream(), t[0]);
		t0 = System.nanoTime();
		why = null;
		try { in.readFully(100, deadlineIn(500), 10); } catch (BodyTimeoutException e) { why = e.getMessage(); }
		took = ms(t0);
		stop.set(true);
		check("a trickle is cut once it falls behind the rate (" + took + " ms): " + why, why != null && took >= 800 && took < 1800);
		t[0].close(); t[1].close();

		// a body that keeps up, slowly, arrives whole: 10 KB every 100 ms (100 KB/s) against a 50 KB/s floor, 200 ms grace
		Socket[] f = pair();
		Thread.ofVirtual().start(() -> {
			try { OutputStream o = f[1].getOutputStream(); for (int i = 0; i < 20; i++) { o.write(new byte[10_000]); o.flush(); Thread.sleep(100); } } catch (Exception e) {}
		});
		in = new LineInputStream(f[0].getInputStream(), f[0]);
		t0 = System.nanoTime();
		got = in.readFully(200_000, deadlineIn(200), 50_000);
		check("a body at twice the floor arrives whole (" + ms(t0) + " ms, far past the grace)", got.length == 200_000 && ms(t0) >= 1800);
		f[0].close(); f[1].close();

		// rate 0: no deadline at all, only the socket's own timeout
		Socket[] z = pair();
		Thread.ofVirtual().start(() -> {
			try { Thread.sleep(700); z[1].getOutputStream().write(new byte[10]); z[1].getOutputStream().flush(); } catch (Exception e) {}
		});
		in = new LineInputStream(z[0].getInputStream(), z[0]);
		check("body_min_rate 0: a 700 ms pause past the grace is fine", in.readFully(10, deadlineIn(0), 0).length == 10);
		z[0].close(); z[1].close();

		// without a socket: the plain read, into an exact array
		LineInputStream plain = new LineInputStream(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.ISO_8859_1)));
		check("no socket: readFully with a rate is the plain read", "hello".equals(new String(plain.readFully(5, deadlineIn(-1000), 1), StandardCharsets.ISO_8859_1)));
		check("no socket: rest", " world".equals(new String(plain.readFully(6), StandardCharsets.ISO_8859_1)));
	}
}
