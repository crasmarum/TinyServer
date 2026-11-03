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
				System.out.println("stopping...");
				Log.stop();
				server.stopServer();
			}
		});
		
		new Thread(server).start();
		
		System.out.println("TinyServer started.");
	}

}

