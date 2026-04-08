package com.tinyserver;

import java.io.BufferedOutputStream;
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

	public static void setHtmlDirectory(String directory) {
		htmlDirectory = directory;
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
				if (HTTPRequest.isJsp(method)) {
					response.addHeader("Connection", "close");
				}
				servlet.service(request, response);
				deliverContent(bufferedoutputstream, response);
			} else if (htmlDirectory != null && "/".equalsIgnoreCase(method)) {
				deliverLocalContent(bufferedoutputstream, "/index.html", htmlDirectory);
			} else if (htmlDirectory != null) {
				deliverLocalContent(bufferedoutputstream, method, htmlDirectory);
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

