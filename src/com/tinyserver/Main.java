package com.tinyserver;

import com.tinyserver.servlet.Log;

public class Main {

	public static void main(String[] args) throws Exception {
		
		final TinyServer server;
		if (args.length > 0) {
			server = new TinyServer(args[0]);
		} else {
			server = new TinyServer(8080);
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

