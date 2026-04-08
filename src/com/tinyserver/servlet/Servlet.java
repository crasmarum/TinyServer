package com.tinyserver.servlet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public abstract class Servlet {
	private static Map<String, Object> context = new ConcurrentHashMap<String, Object>();
	public static AtomicLong noVirtulThreads = new AtomicLong();
	
	public abstract void service(HTTPRequest request, HTTPResponse response);
	
	protected void init() {
	}

	protected static Map<String, Object> getServletContext() {
		return context;
	}
	
	public static void addToServletContext(String key, Object value) {
		context.put(key, value);
	}
	
	public static Object getFromContext(String key) {
		return context.get(key);
	}
	
	public static void checkNotNull(String... keys) {
		for (String key : keys) {
			if (key == null || context.get(key) == null) {
				throw new IllegalArgumentException("Null value for Servlet context var: " + key);
			}
		}
	}
}
