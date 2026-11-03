package com.tinyserver.servlet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Tests for Log: request threads never block on the log queue (writer
 * paused, queue full), a failed day rotation keeps the writer alive, stop()
 * drains the queue into the file, a dead writer falls back to stdout, and
 * sanitize() strips CR/LF and control characters. No JUnit; main exits
 * non-zero on failure. Run with -Djava.io.tmpdir=<dir> to choose where the
 * temporary log directory goes.
 */
public class LogTest {

	static int passed = 0, failed = 0;

	static void check(String label, boolean cond) {
		if (cond) {
			passed++;
			System.out.println("PASS " + label);
		} else {
			failed++;
			System.out.println("FAIL " + label);
		}
	}

	static boolean waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (cond.getAsBoolean()) {
				return true;
			}
			Thread.sleep(10);
		}
		return cond.getAsBoolean();
	}

	static String read(File f) {
		try {
			return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	static int countLines(File f, String marker) {
		int n = 0;
		try {
			List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
			for (String line : lines) {
				if (line.contains(marker)) {
					n++;
				}
			}
		} catch (IOException e) {
			return -1;
		}
		return n;
	}

	static long droppedReported(File f) {
		long total = 0;
		try {
			for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
				int i = line.indexOf(" log lines dropped");
				if (i > 0) {
					String head = line.substring(0, i);
					total += Long.parseLong(head.substring(head.lastIndexOf('\t') + 1));
				}
			}
		} catch (IOException e) {
			return -1;
		}
		return total;
	}

	/** Output stream that blocks every write until the gate opens, then forwards. */
	static class GatedStream extends OutputStream {
		final PrintStream target;
		final CountDownLatch gate = new CountDownLatch(1);
		volatile boolean blocked = false;

		GatedStream(PrintStream target) {
			this.target = target;
		}

		void pass() {
			blocked = true;
			try {
				gate.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void write(int b) {
			pass();
			target.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			pass();
			target.write(b, off, len);
		}

		@Override
		public void flush() {
			target.flush();
		}

		@Override
		public void close() {
			target.close();
		}
	}

	static void testSanitize() {
		check("sanitize(null) is null", Log.sanitize(null) == null);
		check("sanitize keeps plain text", "GET /a?b=c d".equals(Log.sanitize("GET /a?b=c d")));
		check("sanitize strips CR/LF", "ab\tERRORc".equals(Log.sanitize("a\r\nb\tERROR\nc")));
		check("sanitize replaces ESC", "x?[31mred?[0m".equals(Log.sanitize("x\u001b[31mred\u001b[0m")));
		check("sanitize replaces NUL, BEL, BS, DEL", "a?b?c?d?".equals(Log.sanitize("a\u0000b\u0007c\bd\u007f")));
		check("sanitize keeps TAB and non-ASCII", "a\tb é中".equals(Log.sanitize("a\tb é中")));
	}

	public static void main(String[] args) throws Exception {
		DateTimeFormatter origFormat = Log.mFileNameFormat;
		PrintStream origErr = System.err;
		File dir = Files.createTempDirectory("logtest").toFile();
		try {
			testSanitize();

			// --- file mode ---
			Log.setLogFileDir(dir.getAbsolutePath());
			File logFile = Log.mLogFile;
			check("file mode: writer thread running", Log.mThread != null && Log.mThread.isAlive());
			Log.info("hello\u001b[31m\r\nworld");
			check("file mode: sanitized line reaches the file",
					waitFor(() -> read(logFile).contains("INFO\thello?[31mworld"), 2000));
			Log.printStackTrace(new Exception("trace\u001b[2Jtest"));
			check("file mode: stack trace reaches the file, multi-line, ESC replaced",
					waitFor(() -> read(logFile).contains("java.lang.Exception: trace?[2Jtest\n\tat "), 2000));

			// --- writer paused: 20,000 calls from 8 threads must not block ---
			PrintStream fileOut = Log.mOut;
			GatedStream gated = new GatedStream(fileOut);
			Log.mOut = new PrintStream(gated);
			Log.info("pause-marker");
			check("paused: writer is blocked in its write", waitFor(() -> gated.blocked, 2000));
			int threads = 8, perThread = 2500;
			AtomicLong maxNanos = new AtomicLong();
			Thread[] workers = new Thread[threads];
			long t0 = System.nanoTime();
			for (int t = 0; t < threads; t++) {
				final int id = t;
				workers[t] = new Thread(() -> {
					for (int i = 0; i < perThread; i++) {
						long s = System.nanoTime();
						Log.info("burst " + id + " " + i);
						long d = System.nanoTime() - s;
						maxNanos.accumulateAndGet(d, Math::max);
					}
				});
				workers[t].start();
			}
			boolean allDone = true;
			for (Thread w : workers) {
				w.join(10000);
				allDone &= !w.isAlive();
			}
			long totalMs = (System.nanoTime() - t0) / 1_000_000;
			double maxMs = maxNanos.get() / 1e6;
			System.out.printf("  burst: %d calls in %d ms, slowest call %.3f ms, dropped %d%n",
					threads * perThread, totalMs, maxMs, Log.mDropped.get());
			check("paused: all 20,000 calls returned", allDone);
			check("paused: no call blocked more than 50 ms (slowest " + String.format("%.3f", maxMs) + " ms)",
					maxMs < 50);
			check("paused: overflow is counted as dropped", Log.mDropped.get() > 0);
			check("paused: queue is full, not over capacity", Log.mQueue.size() == Log.QUEUE_SIZE);
			gated.gate.countDown();
			check("resumed: queue drains",
					waitFor(() -> Log.mQueue.isEmpty() && Log.mDropped.get() == 0, 10000));
			check("resumed: 'N log lines dropped' line written",
					waitFor(() -> read(logFile).contains(" log lines dropped"), 2000));
			Thread.sleep(300);
			Log.mOut = fileOut;
			int burstLines = countLines(logFile, "INFO\tburst ");
			long reported = droppedReported(logFile);
			check("resumed: written + reported dropped = 20,000 (" + burstLines + " + " + reported + ")",
					burstLines + reported == threads * perThread);

			// --- forced rotation failure: log dir read-only, new day ---
			ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
			System.setErr(new PrintStream(errBuf, true));
			check("rotation: log dir made read-only", dir.setWritable(false, false));
			DateTimeFormatter rotFormat = DateTimeFormatter.ofPattern("'rot_'yyyy_MM_dd");
			Log.mFileNameFormat = rotFormat;
			Log.mLogName = "1999_01_01";
			Log.info("after-rotation-failure-1");
			check("rotation: failure keeps logging into the current file",
					waitFor(() -> read(logFile).contains("after-rotation-failure-1"), 2000));
			check("rotation: writer thread still alive", Log.mThread != null && Log.mThread.isAlive());
			check("rotation: mLogName set to today, no retry until tomorrow",
					rotFormat.format(LocalDate.now()).equals(Log.mLogName));
			check("rotation: new file was not created",
					!new File(dir, rotFormat.format(LocalDate.now()) + ".log").exists());
			long s = System.nanoTime();
			for (int i = 0; i < 10000; i++) {
				Log.info("after-rotation-failure-bulk " + i);
			}
			long bulkMs = (System.nanoTime() - s) / 1_000_000;
			check("rotation: 10,000 more calls do not hang (" + bulkMs + " ms)", bulkMs < 5000);
			Log.info("after-rotation-failure-2");
			check("rotation: later lines still reach the file",
					waitFor(() -> read(logFile).contains("after-rotation-failure-2"), 5000));
			String err = errBuf.toString(StandardCharsets.UTF_8);
			int reports = err.split("Log rotation to ", -1).length - 1;
			check("rotation: error printed once to System.err (" + reports + ")", reports == 1);
			System.setErr(origErr);
			dir.setWritable(true, false);
			Log.mFileNameFormat = origFormat;
			// back on the real name: the next rotation reopens the same daily file
			Log.info("after-rotation-restored");
			check("rotation: restored rotation reopens the daily file",
					waitFor(() -> read(logFile).contains("after-rotation-restored"), 2000));

			// --- stop() drains the queue into the file ---
			fileOut = Log.mOut;
			GatedStream gated2 = new GatedStream(fileOut);
			Log.mOut = new PrintStream(gated2);
			Log.info("drain-pause");
			check("stop: writer paused", waitFor(() -> gated2.blocked, 2000));
			for (int i = 0; i < 3000; i++) {
				Log.info("drain-line " + i);
			}
			Thread opener = new Thread(() -> {
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {}
				gated2.gate.countDown();
			});
			opener.start();
			Log.stop();
			opener.join();
			check("stop: writer thread ended", Log.mThread == null);
			check("stop: all 3000 queued lines are in the file", countLines(logFile, "INFO\tdrain-line ") == 3000);
			check("stop: mOut back to stdout", Log.mOut == System.out);
			int before = read(logFile).length();
			Log.info("after-stop goes to stdout");
			check("stop: later calls do not touch the file", read(logFile).length() == before);

			// --- writer dies unexpectedly: callers fall back to stdout ---
			Log.setLogFileDir(dir.getAbsolutePath());
			Thread writer = Log.mThread;
			// the uncaught-exception trace of the killed writer goes to a buffer
			System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
			Log.mOut = new PrintStream(new OutputStream() {
				@Override
				public void write(int b) {
					throw new IllegalStateException("simulated writer failure");
				}
			});
			Log.info("kill-writer");
			check("death: mThread cleared when the writer thread ends", waitFor(() -> Log.mThread == null, 2000));
			writer.join(2000);
			System.setErr(origErr);
			check("death: mOut back to stdout", Log.mOut == System.out);
			PrintStream origOut = System.out;
			ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
			System.setOut(new PrintStream(outBuf, true));
			s = System.nanoTime();
			try {
				for (int i = 0; i < 6000; i++) {
					Log.debug("after-death\u001b " + i);
				}
			} finally {
				System.setOut(origOut);
			}
			check("death: 6000 calls after death do not hang", (System.nanoTime() - s) / 1_000_000 < 10000);
			check("death: calls go to stdout, sanitized",
					outBuf.toString(StandardCharsets.UTF_8).contains("DEBUG\tafter-death? 5999"));
		} finally {
			Log.stop();
			System.setErr(origErr);
			Log.mFileNameFormat = origFormat;
			Log.mOut = System.out;
			Log.mThread = null;
			Log.mQueue = null;
			Log.mThreadRunning.set(false);
			Log.mDropped.set(0);
			Log.mLogName = null;
			Log.mLogFile = null;
			dir.setWritable(true, false);
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					f.delete();
				}
			}
			dir.delete();
		}

		System.out.println("\nLogTest: " + passed + " passed, " + failed + " failed.");
		if (failed > 0) System.exit(1);
	}
}
