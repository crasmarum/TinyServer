package com.tinyserver.servlet;

import java.util.ArrayList;
import java.util.List;


public class ReadWriteLock {
	private static boolean DEBUG = false;
	
	public static String READ_RIGHT = "read_r";
	public static String WRITE_RIGHT = "write_r";
	
	private volatile int mActiveReaders = 0;
	private volatile int mWaitingWriters = 0;
	private volatile Thread currentWritingThread = null;	

	//private List mEventListenerList = Collections.synchronizedList(new ArrayList());
	private List<Thread> mCurrentReadingThreads = new ArrayList<Thread>();


	/**
	 * Try to obtain a reading right. It blocks iff
	 * other thread already has a writing right on this lock, 
	 * multiple reads being permitted. Threads trying to aquire
	 * writing rights have priority over threads trying to aquire
	 * reading rights.
	 * @throws KillSignalException
	 */
	 public synchronized void acquireRead()  throws InterruptedException {
		acquireRead(null);
	}
	
	/**
	 * Try to obtain a reading right. It blocks iff
	 * other thread already has a writing right on this lock, 
	 * multiple reads being permitted.
	 * If the <code>source</code> is not null, 
	 * the lock will fire an <code>UnexpectedEvent</code>
	 * of source <code>source</code> to all <code>ImailboxListener</code> 
	 * objects that listen on this lock iff this method blocks.
	 * @param source
	 * @throws KillSignalException
	 */
  	public synchronized void acquireRead(Object source) throws InterruptedException {
  		//System.out.println("aquiring read lock for " + Thread.currentThread());
  		if (mCurrentReadingThreads.indexOf(Thread.currentThread()) != -1) {
  			return;	// reentrant read
  		}

  		if (currentWritingThread == Thread.currentThread()) {
  			releaseWrite();		// do we need this?
  			throw new IllegalMonitorStateException("thread aquired write status already");
  		}
			
		boolean firstTime = true;  		
		while(currentWritingThread != null || mWaitingWriters > 0) {
			if (source != null && firstTime) {
				firstTime = false;
			}
			
			try {
				notifyAll();
				wait();
			} catch (InterruptedException ex) {
				//other thread wakes up a waiting thread using thread.interrupt() just for killing
				throw ex;
			}
		}
		//System.out.println("read lock for " + Thread.currentThread() + "aquired");
		mCurrentReadingThreads.add(Thread.currentThread());    	
		mActiveReaders++;		
	}
	
	public synchronized String getCurrentRight() {
		if (mCurrentReadingThreads.indexOf(Thread.currentThread()) != -1) {
			return READ_RIGHT;
		}
		if (Thread.currentThread() == currentWritingThread) {
			return WRITE_RIGHT;
		}
		return null;
	}
	
	private synchronized void decrementWaitingWriters() {
		mWaitingWriters--;		
	}

  	public synchronized void releaseRead() {
		//System.out.println("read lock for " + Thread.currentThread() + "released");
  		if (mCurrentReadingThreads.indexOf(Thread.currentThread()) == -1) {
			notifyAll(); 
  			return;	// has no right to release
  		}
		mActiveReaders--;
		mCurrentReadingThreads.remove(Thread.currentThread());
		notifyAll();    	
	}
	
	/**
	 * Try to obtain a writing right. It blocks iff
	 * other threads already have a writing right on this lock 
	 * or other thread already has a writing right on this lock.
	 * Threads trying to aquire
	 * writing rights have priority over threads trying to aquire
	 * reading rights. 
	 * @throws KillSignalException
	 */
	 public synchronized void acquireWrite() throws InterruptedException {
		acquireWrite(null);
	}

  	/**
  	 * Try to obtain a writing right. It blocks iff
	 * other threads already have a writing right on this lock, 
	 * or other thread already has a writing right on this lock.
	 * Threads trying to aquire writing rights have priority over 
	 * threads trying to aquire reading rights. 
	 * If the <code>source</code> is not null, 
	 * the lock will fire an <code>UnexpectedEvent</code>
	 * of source <code>source</code> to all <code>ImailboxListener</code> 
	 * objects that listen on this lock iff this method blocks.
	 * @param source
	 * @throws KillSignalException
	 */
	public synchronized void acquireWrite(Object source) throws InterruptedException {
		//System.out.println("aquiring write lock for " + Thread.currentThread());
  		if (Thread.currentThread() == currentWritingThread) {
  			return;				// re-entrant for writing
  		}
  		if (mCurrentReadingThreads.indexOf(Thread.currentThread()) != -1) {
  			releaseRead();			// do we need this?
  			throw new IllegalMonitorStateException("thread aquired read status already");
  		}
    	mWaitingWriters++;
		
		boolean firstTime = true;
		while (currentWritingThread != null || mActiveReaders > 0) {
			if (source != null && firstTime) {
				firstTime = false;	
			}
			
			try {
				notifyAll();
				wait();
			} catch (InterruptedException ex) {
				//server wake up a waiting thread using thread.interrupt() just for killing
				decrementWaitingWriters();
				throw ex;
			}
		}    	
		//System.out.println("write lock for " + Thread.currentThread() + "aquired");
  		decrementWaitingWriters();
		currentWritingThread = Thread.currentThread();
	}

	public synchronized void releaseWrite() {
		//only the thread that aquired write can release the lock
		//System.out.println("write lock for " + Thread.currentThread() + "released");
		if (currentWritingThread != Thread.currentThread()) {
			// DO NOT throw exceptions here! reason: aquireWrite(long) may fail
			notifyAll(); 
			return;
		}
		currentWritingThread = null;    	
		notifyAll();
	}		
	
//	public boolean isInUse() {
//		return mEventListenerList.size() > 0;
//	}	
	
	/**
	 * @return
	 */
	public static boolean isDEBUG() {
		return DEBUG;
	}

	/**
	 * @param b
	 */
	public static void setDEBUG(boolean b) {
		DEBUG = b;
	}

}
