package com.tinyserver.servlet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import com.tinyserver.Config;
import com.tinyserver.servlet.HTTPResponse.HTTPRetCode;

public class HTTPRequest {
	private Config config;
	
	private String method;

	public final Hashtable<String, String> headers = new Hashtable<String, String>();
	
	public final Hashtable<String, String> parameters = new Hashtable<String, String>();
	
	/** multipart/form-data files: field name -> file name -> bytes (see also {@link #uploads}) */
	public final Hashtable<String, Hashtable<String, byte[]>> files = new Hashtable<String, Hashtable<String, byte[]>>();

	/** multipart/form-data files in the order they were sent, with their content types */
	public final List<UploadedFile> uploads = new ArrayList<>();
	
	public final Hashtable<String, String> attributes = new Hashtable<String, String>();
	
	public char[] content = new char[0];

	/**
	 * Whether the client opted into HTTP/1.1 keep-alive for this request.
	 * Computed by {@code ServerEngine.performHTMLprotocol} from the request
	 * line + {@code Connection} header. Handlers can mirror this onto the
	 * response by adding the corresponding {@code Connection} header — the
	 * default path in TinyServer.execute already does that.
	 */
	public boolean keepAlive = false;

	public HTTPRequest(Config config_) {
		config = config_;
	}

	/**
	 * Returns the {@link Config} this request was dispatched under, so
	 * servlets can read configured shared secrets / flags without going
	 * through a singleton.
	 */
	public Config getConfig() {
		return config;
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
				} else {
					throw new IOException("Not found: " + method);
				}
			}
			if (servlet != null) {
				Log.info("JSP servlet instance: " + servlet.getClass().getCanonicalName());
				if (!config.isAlwaysCompileJsps()) {
					JspCompiler.addServlet(method, servlet);
				}
			} else {
				throw new IOException("JSP error: " + method);
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
	
	/** the first uploaded file of a form field, or null */
	public UploadedFile getUpload(String field) {
		for (UploadedFile f : uploads) {
			if (f.field().equals(field)) {
				return f;
			}
		}
		return null;
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
