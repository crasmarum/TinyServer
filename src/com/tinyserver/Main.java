package com.tinyserver;

import com.tinyserver.servlet.Log;

public class Main {

	public static void main(String[] args) throws Exception {
		
		final TinyServer server;
		try {
			if (args.length > 0) {
				server = new TinyServer(args[0]);
			} else {
				server = new TinyServer(8080);
			}
		} catch (Throwable t) {
			// e.g. the port is still taken: exit non-zero so a supervisor restarts us,
			// even if the config started threads that would keep the JVM alive
			t.printStackTrace();
			Log.stop();
			System.exit(1);
			return;
		}
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				stopping = true;
				System.out.println("stopping...");
				Log.stop();
				server.stopServer();
			}
		});
		
		Thread serverThread = new Thread(server, "TinyServer");
		serverThread.start();
		
		System.out.println("TinyServer started.");

		// The server thread ends only when the shutdown hook stops it. Any other end (the accept loop died, or an
		// exception before it started) exits with status 1, so systemd (Restart=on-failure) restarts the service
		// instead of leaving a JVM that other threads keep alive with no listener.
		serverThread.join();
		if (!stopping) {
			Throwable why = server.getFatalError();
			System.err.println("TinyServer stopped unexpectedly" + (why != null ? ": " + why : "") + "; exiting with status 1");
			Log.stop();
			System.exit(1);
		}
	}

	/** set by the shutdown hook, so the end of the server thread it causes is not taken for a failure */
	private static volatile boolean stopping = false;

}

