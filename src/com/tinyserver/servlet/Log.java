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
	
	static DateTimeFormatter mFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss,SSS");
	
	static Level mLevel = Level.DEBUG;
	static PrintStream mOut = System.out;
	
	static File mLogFile = null;
	static DateTimeFormatter mFileNameFormat = DateTimeFormatter.ofPattern("yyyy_MM_dd");
	static Thread mThread = null;
	static ArrayBlockingQueue<LogInfo> mQueue = null;
	static AtomicBoolean mThreadRunning = new AtomicBoolean(false);
	static String mLogName = null; 
	
	public static void log(Level lvl, String msg) {
		if (lvl.compareTo(mLevel) < 0) {
			return;
		}
		
		if (mThread != null) {
			try {
				mQueue.put(new LogInfo(lvl, sanitize(msg), null));
			} catch (InterruptedException ignored) {}
			return;
		}
		
		System.out.println(timestamp() + "\t" + lvl.name() + "\t" +  msg);
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
		if (mThread != null) {
			try {
				mQueue.put(new LogInfo(Level.ERROR, "", ex));
			} catch (InterruptedException ignored) {}
			return;
		}
		
		ex.printStackTrace(mOut);
	}

	private static String getLogName() {
		return mFileNameFormat.format(LocalDate.now()) + ".log";
	}
	
	public static void stop() {
		if (mThread == null) {
			return;
		}
		mThreadRunning.set(false);
		mThread.interrupt();
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

	public static void setLogFileDir(String logFileDir) throws Exception {
		createLogFile(logFileDir);
		mOut = new PrintStream(new FileOutputStream(mLogFile, true));
		
		mQueue = new ArrayBlockingQueue<Log.LogInfo>(5000);
		mThreadRunning.set(true);
		mThread = new Thread(new Runnable() {
			
			@Override
			public void run() {
				while (mThreadRunning.get()) {
					LogInfo logInfo = null;
					try {
						logInfo = mQueue.poll(100, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						break;
					}
					
					if (logInfo == null) {
						continue;
					}
					
					if (isNewDay()) {
						try {
							createLogFile(logFileDir);
							mOut.close();
							mOut = new PrintStream(new FileOutputStream(mLogFile, true));
						} catch (Exception e) {
							printStackTrace(e);
							break;
						} 
					}

					if (logInfo.ex != null) {
						PrintWriter pw = new PrintWriter(new StringWriter());
						logInfo.ex.printStackTrace(pw);
						logInfo.msg = pw.toString();
					} 
					
					mOut.println(timestamp() + "\t" + logInfo.level.name() + "\t" +  logInfo.msg);
					mOut.flush();
				}
				
				mOut.close();
				System.out.println("Log thread stopped.");
			}
		}); 
		mThread.start();
		System.out.println("Log thread started.");
	}
	
	public static String sanitize(String msg) {
		if (msg == null) {
			return msg;
		}
		return msg.replace("\n","").replace("\r","");
	}
}
