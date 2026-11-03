package com.tinyserver.servlet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Log {
	public enum Level {
		DEBUG, INFO, ERROR
	}

	static class LogInfo {
		Level level;
		String msg;
		Exception ex;

		public LogInfo(Level level, String string, Exception ex) {
			this.level = level;
			this.msg = string;
			this.ex = ex;
		}
	}

	static DateTimeFormatter mFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

	static Level mLevel = Level.DEBUG;
	static volatile PrintStream mOut = System.out;

	static File mLogFile = null;
	static DateTimeFormatter mFileNameFormat = DateTimeFormatter.ofPattern("yyyy_MM_dd");
	// Non-null only while the writer thread is alive; the thread clears it in a
	// finally block so callers fall back to stdout if it ever ends.
	static volatile Thread mThread = null;
	static volatile ArrayBlockingQueue<LogInfo> mQueue = null;
	// true while the writer accepts new entries; stop() clears it.
	static AtomicBoolean mThreadRunning = new AtomicBoolean(false);
	// Entries dropped because the queue was full; the writer reports and resets it.
	static final AtomicLong mDropped = new AtomicLong();
	static volatile String mLogName = null;

	static final int QUEUE_SIZE = 5000;
	static final long STOP_WAIT_MS = 2000;

	public static void log(Level lvl, String msg) {
		if (lvl.compareTo(mLevel) < 0) {
			return;
		}

		msg = sanitize(msg);
		if (enqueue(new LogInfo(lvl, msg, null))) {
			return;
		}

		System.out.println(timestamp() + "\t" + lvl.name() + "\t" +  msg);
	}

	/**
	 * Hands an entry to the writer thread without ever blocking the caller.
	 * Returns false when there is no running writer (caller logs to stdout);
	 * a full queue drops the entry and counts it, and returns true.
	 */
	private static boolean enqueue(LogInfo logInfo) {
		ArrayBlockingQueue<LogInfo> queue = mQueue;
		if (mThread == null || queue == null || !mThreadRunning.get()) {
			return false;
		}
		if (!queue.offer(logInfo)) {
			mDropped.incrementAndGet();
		}
		return true;
	}

	public static void debug(String msg) {
		log(Level.DEBUG, msg);
	}

	public static void info(String msg) {
		log(Level.INFO, msg);
	}

	public static void error(String msg) {
		log(Level.ERROR, msg);
	}

	public static String timestamp() {
		return LocalDateTime.now().format(mFormatter);
	}

	public static void printStackTrace(Exception ex) {
		if (enqueue(new LogInfo(Level.ERROR, "", ex))) {
			return;
		}

		System.out.println(timestamp() + "\t" + Level.ERROR.name() + "\t" + stackTrace(ex));
	}

	private static String stackTrace(Exception ex) {
		// PrintWriter doesn't override toString(); the stack trace
		// is captured by the StringWriter underneath. flush() pushes
		// PrintWriter's internal buffer into the StringWriter
		// before we read its contents.
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		ex.printStackTrace(pw);
		pw.flush();
		// keep the trace multi-line, but no other control characters
		return clean(sw.toString(), true);
	}

	private static String getLogName() {
		return mFileNameFormat.format(LocalDate.now()) + ".log";
	}

	/**
	 * Stops accepting entries (later calls log to stdout), lets the writer
	 * drain what is already queued into the file, then closes it. Waits at
	 * most STOP_WAIT_MS for the drain so a stuck disk cannot hang shutdown.
	 */
	public static void stop() {
		Thread thread = mThread;
		if (thread == null) {
			return;
		}
		mThreadRunning.set(false);
		try {
			thread.join(STOP_WAIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (thread.isAlive()) {
			thread.interrupt();
		}
	}

	private static final void createLogFile(String logFileDir) throws IOException, FileNotFoundException {
		mLogName = mFileNameFormat.format(LocalDate.now());
		File logDir = new File(logFileDir);
		mLogFile = new File(logDir, getLogName());
		mLogFile.createNewFile();
		System.out.println("Log file: " + mLogFile.getAbsolutePath());
	}

	private static boolean isNewDay() {
		return !mFileNameFormat.format(LocalDate.now()).equals(mLogName);
	}

	/**
	 * Switches to a new daily file. On failure (directory gone or unwritable,
	 * disk full) keeps writing to the current stream and reports once; mLogName
	 * is set to today so rotation is not retried until the next day.
	 */
	private static void rotate(String logFileDir) {
		String today = mFileNameFormat.format(LocalDate.now());
		try {
			createLogFile(logFileDir);
			// open the new stream before closing the old one, so a failure
			// leaves the current stream usable
			PrintStream newOut = new PrintStream(new FileOutputStream(mLogFile, true));
			PrintStream oldOut = mOut;
			mOut = newOut;
			oldOut.close();
		} catch (Exception e) {
			mLogName = today;
			System.err.println(timestamp() + "\tERROR\tLog rotation to " + today
					+ " failed, still writing to the previous log file: " + e);
		}
	}

	private static void writeDropped() {
		long dropped = mDropped.getAndSet(0);
		if (dropped > 0) {
			mOut.println(timestamp() + "\t" + Level.ERROR.name() + "\t" + dropped + " log lines dropped");
			mOut.flush();
		}
	}

	public static void setLogFileDir(String logFileDir) throws Exception {
		stop();
		createLogFile(logFileDir);
		mOut = new PrintStream(new FileOutputStream(mLogFile, true));

		mQueue = new ArrayBlockingQueue<Log.LogInfo>(QUEUE_SIZE);
		mDropped.set(0);
		mThreadRunning.set(true);
		Thread thread = new Thread(new Runnable() {

			@Override
			public void run() {
				ArrayBlockingQueue<LogInfo> queue = mQueue;
				try {
					// after stop() keep going until the queue is drained
					while (mThreadRunning.get() || !queue.isEmpty()) {
						LogInfo logInfo = null;
						try {
							logInfo = queue.poll(100, TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							break;
						}

						if (isNewDay()) {
							rotate(logFileDir);
						}

						writeDropped();

						if (logInfo == null) {
							continue;
						}

						if (logInfo.ex != null) {
							logInfo.msg = stackTrace(logInfo.ex);
						}

						mOut.println(timestamp() + "\t" + logInfo.level.name() + "\t" +  logInfo.msg);
						mOut.flush();
					}
					writeDropped();
				} finally {
					// whatever ended the loop, send callers back to stdout
					// (unless a later setLogFileDir already replaced this thread)
					if (mThread == Thread.currentThread()) {
						mThreadRunning.set(false);
						mThread = null;
						PrintStream out = mOut;
						mOut = System.out;
						if (out != System.out) {
							try {
								out.close();
							} catch (Throwable ignored) {}
						}
					}
					System.out.println("Log thread stopped.");
				}
			}
		});
		// Daemon, so a failed startup (e.g. the port is taken) still ends the JVM with a
		// non-zero status and a supervisor can restart it; the shutdown hook below
		// drains the queue into the file on every normal or signalled exit.
		thread.setDaemon(true);
		mThread = thread;
		thread.start();
		if (mHookAdded.compareAndSet(false, true)) {
			try {
				Runtime.getRuntime().addShutdownHook(new Thread(Log::stop, "Log-drain"));
			} catch (IllegalStateException shuttingDown) {
			}
		}
		System.out.println("Log thread started.");
	}

	// The drain hook is registered once, by the first setLogFileDir.
	private static final AtomicBoolean mHookAdded = new AtomicBoolean(false);

	/**
	 * Makes a client-supplied string safe for a one-line log entry: CR and LF
	 * are removed, every other control character except TAB (C0 and DEL, so
	 * also ESC terminal sequences) becomes '?'. Null-safe.
	 */
	public static String sanitize(String msg) {
		return clean(msg, false);
	}

	private static String clean(String msg, boolean keepNewlines) {
		if (msg == null) {
			return msg;
		}
		int n = msg.length();
		int i = 0;
		while (i < n && !isControl(msg.charAt(i))) {
			i++;
		}
		if (i == n) {
			return msg;
		}
		StringBuilder sb = new StringBuilder(n);
		sb.append(msg, 0, i);
		for (; i < n; i++) {
			char c = msg.charAt(i);
			if (!isControl(c)) {
				sb.append(c);
			} else if (c == '\n' || c == '\r') {
				if (keepNewlines) {
					sb.append(c);
				}
			} else {
				sb.append('?');
			}
		}
		return sb.toString();
	}

	private static boolean isControl(char c) {
		return (c < 0x20 && c != '\t') || c == 0x7F;
	}
}
