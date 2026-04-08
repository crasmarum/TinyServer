package com.tinyserver.servlet;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;

import com.tinyserver.Config;
import com.tinyserver.servlet.HTTPResponse.HTTPRetCode;

public class HTTPRequest {
	private Config config;
	
	private String method;

	public final Hashtable<String, String> headers = new Hashtable<String, String>();
	
	public final Hashtable<String, String> parameters = new Hashtable<String, String>();
	
	public final Hashtable<String, Hashtable<String, byte[]>> files = new Hashtable<String, Hashtable<String, byte[]>>();
	
	public final Hashtable<String, String> attributes = new Hashtable<String, String>();
	
	public char[] content = new char[0];
	
	public HTTPRequest(Config config_) {
		config = config_;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("Headers: ").append(headers).append("\n\t")
		   .append("Params: ").append(parameters).append("\n\t")
		   .append("Attributes: ").append(attributes).append("\n\t")
		   .append("Files: ").append(files.keySet()).append("\n\t")	
		   .append(new String(content)).append("\n");
		return sb.toString();
	}
	
	public static boolean isJsp(String method) {
		return method.startsWith("/jsp/");
	}
	
	public Servlet getServlet(String method) throws IOException {
		Servlet servlet = config.getServlet(method);
		
		if (servlet == null 
				&& isJsp(method) 
				&& JspCompiler.getDirectory() != null
				&& (servlet = JspCompiler.getServlet(method)) == null) {
			synchronized(JspCompiler.getLock()) {
				File file = new File(JspCompiler.getDirectory() + method.substring(4));
				Log.info("JSP: " + file.getAbsolutePath());
				if (file.exists() && file.isFile() 
						&& file.getCanonicalPath().startsWith(JspCompiler.getDirectory() + File.separator)) {
					servlet = new JspCompiler().getServlet(file);
					if (servlet != null) {
						Log.info("JSP servlet instance: " + servlet.getClass().getCanonicalName());
						if (!config.isAlwaysCompileJsps()) {
							JspCompiler.addServlet(method, servlet);
						}
					} else {
						throw new IOException("JSP error: " + method);
					}
				} else {
					throw new IOException("Not found: " + method);
				}
			}
		}

		return servlet;
	}
	
	public void forwardTo(String jspOrServlet, HTTPResponse response) {
		Servlet servlet = null;
		try {
			servlet = getServlet(jspOrServlet);
		} catch (IOException e) {
			response.getOutputStream().writeBytes(e.toString().getBytes());
			response.setReturnCode(HTTPRetCode._500);
			return;
		}
		if (servlet == null) {
			response.getOutputStream().writeBytes(("Not found: " + jspOrServlet).getBytes());
			response.setReturnCode(HTTPRetCode._404);
			return;
		}
		
		servlet.service(this, response);
	}
	
	public String getContent() {
		return new String(content);
	}

	public String getMethod() {
		return method;
	}
	
	public void setMethod(String method) {
		this.method = method;
	}
}
