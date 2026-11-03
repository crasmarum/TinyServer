package com.tinyserver;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ProbeFilter: map caps under many distinct keys, over-long keys ignored, the 404-rate ban, atomic counting under
 * concurrent recordError, cleanup() of expired entries, and no sweep work on the hot paths. Uses isolated instances with
 * a fake clock. No JUnit (see JsonParserTest); excluded from server.jar by name.
 *
 * Run: javac -d out $(find . -name "*.java") && java -cp out com.tinyserver.ProbeFilterTest
 */
public class ProbeFilterTest {
	static int passed = 0, failed = 0;
	static PrintStream out = System.out;

	static void check(String label, boolean cond) {
		if (cond) passed++; else { failed++; out.println("FAIL " + label); }
	}

	static String ip(int i) { return "10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255); }

	public static void main(String[] args) throws Exception {
		// Log prints to System.out when no log file is set; capture it so the many
		// "banned" lines stay quiet and the cap-hit lines can be counted
		ByteArrayOutputStream logBuf = new ByteArrayOutputStream();
		System.setOut(new PrintStream(logBuf, true, StandardCharsets.UTF_8));
		try {
			run(logBuf);
		} finally {
			System.setOut(out);
		}
		out.println("ProbeFilterTest: " + passed + " passed, " + failed + " failed.");
		if (failed > 0) System.exit(1);
	}

	static int capLines(ByteArrayOutputStream logBuf) {
		String s = logBuf.toString(StandardCharsets.UTF_8);
		int n = 0;
		for (String line : s.split("\n")) if (line.contains("map full")) n++;
		return n;
	}

	static void run(ByteArrayOutputStream logBuf) throws Exception {
		AtomicLong time = new AtomicLong(1_000_000_000L);

		// --- cap respected under 20k distinct keys: a full map evicts its soonest-expiring tenth, one log line per minute
		{
			ProbeFilter f = new ProbeFilter(time::get);
			for (int i = 0; i < 20_000; i++) { f.ban(ip(i), "test"); time.incrementAndGet(); }
			check("ban map capped: " + f.bannedSize(), f.bannedSize() <= ProbeFilter.MAX_TRACKED && f.bannedSize() > ProbeFilter.MAX_TRACKED * 8 / 10);
			check("newest key banned", f.isBanned(ip(19_999)));
			check("key past the old cap banned (flood does not switch off new bans)", f.isBanned(ip(15_000)));
			check("oldest key evicted first", !f.isBanned(ip(0)) && !f.isBanned(ip(5_000)));
			check("every ban started", f.bansStarted.get() == 20_000);
			int size = f.bannedSize();
			f.ban(ip(19_999), "again");
			check("re-ban of tracked key when full adds nothing", f.isBanned(ip(19_999)) && f.bannedSize() == size);

			for (int i = 0; i < 20_000; i++) { f.recordError(ip(100_000 + i)); time.incrementAndGet(); }
			check("window map capped: " + f.windowSize(), f.windowSize() <= ProbeFilter.MAX_TRACKED);
			check("newest window counted, oldest evicted", f.errorCount(ip(100_000 + 19_999)) == 1 && f.errorCount(ip(100_000)) == 0);
			f.recordError(ip(100_000 + 19_999));
			check("tracked key still counted when full", f.errorCount(ip(100_000 + 19_999)) == 2);
			check("one cap line in the first minute", capLines(logBuf) == 1);
			time.addAndGet(61_000);
			for (int i = 0; i < 2_000; i++) f.ban(ip(30_000 + i), "test");
			check("second cap line after a minute", capLines(logBuf) == 2);
			for (int i = 0; i < 2_000; i++) f.recordError(ip(130_000 + i));
			check("still two cap lines", capLines(logBuf) == 2);

			time.addAndGet(ProbeFilter.BAN_DURATION_MS + 1);
			f.cleanup();
			check("cleanup emptied both maps", f.bannedSize() == 0 && f.windowSize() == 0);
		}

		// --- equal expiry times: exactly a tenth is evicted, not every tied entry
		{
			ProbeFilter f = new ProbeFilter(time::get);
			for (int i = 0; i < ProbeFilter.MAX_TRACKED; i++) f.ban(ip(i), "test");
			f.ban(ip(50_000), "test");
			check("ties: one tenth evicted (" + f.bannedSize() + ")",
				f.bannedSize() == ProbeFilter.MAX_TRACKED - ProbeFilter.MAX_TRACKED / ProbeFilter.EVICT_DIVISOR + 1 && f.isBanned(ip(50_000)));
		}

		// --- while another thread evicts, a new key is not tracked (fail open), a tracked one still is
		{
			ProbeFilter f = new ProbeFilter(time::get);
			for (int i = 0; i < ProbeFilter.MAX_TRACKED; i++) f.ban(ip(i), "test");
			CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
			Thread holder = new Thread(() -> {
				f.evictLock.lock();
				try { held.countDown(); release.await(); } catch (InterruptedException ignored) {
				} finally { f.evictLock.unlock(); }
			});
			holder.start();
			held.await();
			f.ban(ip(60_000), "test");
			check("evicting elsewhere: new key not banned, nothing lost", !f.isBanned(ip(60_000)) && f.bannedSize() == ProbeFilter.MAX_TRACKED);
			f.ban(ip(1), "again");
			check("evicting elsewhere: tracked key re-banned", f.isBanned(ip(1)));
			release.countDown();
			holder.join();
			f.ban(ip(60_000), "test");
			check("after eviction: new key banned", f.isBanned(ip(60_000)));
		}

		// --- IPv6 clients are tracked by /64
		{
			String[][] keys = {
				{"2001:db8:1:2:0:0:0:5", "2001:db8:1:2::/64"},
				{"2001:db8:1:2::5", "2001:db8:1:2::/64"},
				{"2001:DB8:0001:0002:aaaa:bbbb:cccc:dddd", "2001:db8:1:2::/64"},
				{"2001:db8::1", "2001:db8:0:0::/64"},
				{"fe80:0:0:0:0:0:0:1%en0", "fe80:0:0:0::/64"},
				{"::ffff:1.2.3.4", "0:0:0:0::/64"},
				{"0:0:0:0:0:ffff:1.2.3.4", "0:0:0:0::/64"},
				{"::1:2:3:4:5:6:7", "0:1:2:3::/64"},
				{"1:2:3:4:5:6:7::", "1:2:3:4::/64"},
				{"::", "0:0:0:0::/64"},
				{"192.0.2.1", "192.0.2.1"},
				{"2001:db8:1:2::/64", "2001:db8:1:2::/64"},
				{"1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8:9"},
				{"1::2::3", "1::2::3"},
				{"1.2.3.4::", "1.2.3.4::"},
				{"12345::1", "12345::1"},
				{"g::1", "g::1"},
				{"1:2:3", "1:2:3"},
			};
			for (String[] kv : keys) {
				String got = ProbeFilter.clientKey(kv[0]);
				check("clientKey(" + kv[0] + ") = " + got, kv[1].equals(got));
			}
			ProbeFilter f = new ProbeFilter(time::get);
			f.ban("2001:db8:1:2::5", "test");
			check("ban covers the /64", f.isBanned("2001:db8:1:2:ffff:0:0:1") && f.isBanned("2001:db8:1:2:0:0:0:5"));
			check("ban stops at the /64", !f.isBanned("2001:db8:1:3::5"));
			for (int i = 0; i < ProbeFilter.MAX_404S_PER_WINDOW; i++) f.recordError("2001:db8:9:9::" + Integer.toHexString(i + 1));
			check("rotating inside a /64 still trips the 404 ban", f.isBanned("2001:db8:9:9::abcd") && f.rateBans.get() == 1);
			for (int i = 0; i < 30_000; i++) f.ban("2001:db8:7:7:" + Integer.toHexString(i) + "::1", "test");
			check("30k addresses in one /64 are one entry", f.bannedSize() == 3);
		}

		// --- long keys ignored outright
		{
			ProbeFilter f = new ProbeFilter(time::get);
			String k64 = "a".repeat(ProbeFilter.MAX_KEY_LENGTH), k65 = "a".repeat(ProbeFilter.MAX_KEY_LENGTH + 1);
			f.ban(k65, "test");
			for (int i = 0; i < 30; i++) f.recordError(k65);
			check("65-char key not banned", !f.isBanned(k65) && f.bannedSize() == 0);
			check("65-char key not counted", f.windowSize() == 0 && f.rateBans.get() == 0);
			f.ban("b".repeat(8000), "test");
			check("8000-char key not stored", f.bannedSize() == 0);
			f.ban(k64, "test");
			check("64-char key banned", f.isBanned(k64));
			f.ban(null, "test");
			f.ban("", "test");
			f.recordError(null);
			f.recordError("");
			check("null/empty ignored", f.bannedSize() == 1 && f.windowSize() == 0 && !f.isBanned(null) && !f.isBanned(""));
		}

		// --- 20 errors in a window ban; an elapsed window restarts the count
		{
			ProbeFilter f = new ProbeFilter(time::get);
			String a = "192.0.2.1";
			for (int i = 0; i < ProbeFilter.MAX_404S_PER_WINDOW - 1; i++) f.recordError(a);
			check("19 errors: not banned", !f.isBanned(a) && f.errorCount(a) == 19);
			f.recordError(a);
			check("20th error bans", f.isBanned(a) && f.rateBans.get() == 1);

			String b = "192.0.2.2";
			for (int i = 0; i < ProbeFilter.MAX_404S_PER_WINDOW - 1; i++) f.recordError(b);
			time.addAndGet(ProbeFilter.WINDOW_MS + 1);
			f.recordError(b);
			check("window elapsed: count restarts at 1", f.errorCount(b) == 1 && !f.isBanned(b));
			time.addAndGet(ProbeFilter.BAN_DURATION_MS);
			check("ban expires after its TTL", !f.isBanned(a));
		}

		// --- concurrent recordError: 8 threads x 5 on one IP bans exactly once, counts 40
		{
			boolean allOk = true;
			for (int round = 0; round < 300 && allOk; round++) {
				ProbeFilter f = new ProbeFilter(time::get);
				String a = "198.51.100.7";
				CountDownLatch go = new CountDownLatch(1);
				Thread[] ts = new Thread[8];
				for (int t = 0; t < ts.length; t++) {
					ts[t] = new Thread(() -> {
						try { go.await(); } catch (InterruptedException e) { return; }
						for (int i = 0; i < 5; i++) f.recordError(a);
					});
					ts[t].start();
				}
				go.countDown();
				for (Thread t : ts) t.join();
				if (f.errorCount(a) != 40 || f.rateBans.get() != 1 || f.bansStarted.get() != 1 || !f.isBanned(a)) {
					allOk = false;
					out.println("round " + round + ": count=" + f.errorCount(a) + " rateBans=" + f.rateBans.get()
						+ " bansStarted=" + f.bansStarted.get());
				}
			}
			check("concurrent recordError: count 40, exactly one ban (300 rounds)", allOk);
		}

		// --- cleanup() removes only expired entries
		{
			ProbeFilter f = new ProbeFilter(time::get);
			f.ban("203.0.113.1", "test");
			f.recordError("203.0.113.2");
			time.addAndGet(ProbeFilter.WINDOW_MS + 1);
			f.recordError("203.0.113.3");
			f.cleanup();
			check("expired window removed, fresh one kept", f.errorCount("203.0.113.2") == 0 && f.errorCount("203.0.113.3") == 1
				&& f.windowSize() == 1);
			check("unexpired ban kept", f.bannedSize() == 1 && f.isBanned("203.0.113.1"));
			time.addAndGet(ProbeFilter.BAN_DURATION_MS);
			f.cleanup();
			check("expired ban and window removed", f.bannedSize() == 0 && f.windowSize() == 0);
		}

		// --- hot paths do no sweep work: expired entries of other keys stay until cleanup()
		{
			ProbeFilter f = new ProbeFilter(time::get);
			for (int i = 0; i < 100; i++) { f.ban(ip(i), "test"); f.recordError(ip(1000 + i)); }
			time.addAndGet(ProbeFilter.BAN_DURATION_MS + 10 * 60_000);
			for (int i = 0; i < 1000; i++) { f.isBanned("198.18.0.1"); f.recordError("198.18.0.2"); time.addAndGet(1000); }
			check("isBanned/recordError left expired bans alone", f.bannedSize() == 100 + 1);  // + 198.18.0.2 rate-banned
			check("isBanned/recordError left expired windows alone", f.windowSize() == 100 + 1);
			f.cleanup();
			check("cleanup then sweeps them", f.bannedSize() == 1 && f.windowSize() == 1);
		}

		// --- singleton and probe paths unchanged
		check("singleton", ProbeFilter.getInstance() == ProbeFilter.getInstance());
		check("probe path", ProbeFilter.getInstance().isProbePath("/WP-Admin/x") && !ProbeFilter.getInstance().isProbePath("/index.html"));
	}
}
