package com.tinyserver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.HTTPResponse;
import com.tinyserver.servlet.Log;
import com.tinyserver.servlet.Servlet;


/**
 * 
 * Tiny HTTP server supporting servlets and minimal JSPs.
 * 
 */
public class TinyServer extends ServerEngine {

	private static String htmlDirectory;
	
	public static String getHtmlDirectory() {
		return htmlDirectory;
	}

	/**
	 * Stored canonical (absolute, symlinks resolved, no trailing separator) so the traversal guard in
	 * deliverLocalContent, which compares canonical file paths against {@code htmlDirectory + File.separator},
	 * also holds for a relative html_dir, one with a trailing slash or one reached through a symlink.
	 */
	public static void setHtmlDirectory(String directory) {
		htmlDirectory = canonicalDirectory(directory);
	}

	/** the canonical form of a configured directory, or its absolute form if it cannot be resolved; null stays null */
	static String canonicalDirectory(String directory) {
		if (directory == null) {
			return null;
		}
		try {
			return new File(directory).getCanonicalPath();
		} catch (IOException e) {
			return new File(directory).getAbsolutePath();
		}
	}
	
	public TinyServer(int port) throws IOException {
		super(port);
	}
	
	public TinyServer(String configFile) throws Exception {
		super(configFile);
	}

	protected void execute(HTTPRequest request, BufferedOutputStream bufferedoutputstream) {
		String method = request.getMethod();
		try {
			Log.info("HTTPRequest method: " + Log.sanitize(method));
			Servlet servlet = null;
			
			try {
				servlet = request.getServlet(method);
			} catch (IOException ex) {
				Log.info(ex.getMessage());
				sendError(bufferedoutputstream, 404, "Not found: " + method);
				return;
			}

			if (servlet != null) {
				Log.info("servlet: " + servlet.getClass().descriptorString());
				HTTPResponse response = new HTTPResponse();
				// Mirror the client's keep-alive choice onto the response
				// before invoking the servlet so servlets that explicitly
				// set Connection: close (e.g. the JSP path below) win.
				response.addHeader("Connection", request.keepAlive ? "keep-alive" : "close");
				if (HTTPRequest.isJsp(method)) {
					response.addHeader("Connection", "close");
				}
				long svcStartNs = System.nanoTime();
				servlet.service(request, response);
				long svcMs = (System.nanoTime() - svcStartNs) / 1_000_000L;
				Log.info("[SERVICE " + method + "] service=" + svcMs + "ms");
				deliverContent(bufferedoutputstream, response);
			} else if (htmlDirectory != null && "/".equalsIgnoreCase(method)) {
				deliverLocalContent(bufferedoutputstream, "/index.html", htmlDirectory, request);
			} else if (htmlDirectory != null) {
				deliverLocalContent(bufferedoutputstream, method, htmlDirectory, request);
			} else if ("/".equalsIgnoreCase(method)) {
				deliverInternalContent("index.html", bufferedoutputstream);
			} else {
				sendError(bufferedoutputstream, 404, "Not found: " + method);
			}
		} catch (Exception ex) {
			Log.error(ex.getMessage());
			sendError(bufferedoutputstream, 500, "Unexpected error.");
		}
	}
}

