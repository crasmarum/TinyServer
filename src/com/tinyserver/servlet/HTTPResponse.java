package com.tinyserver.servlet;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;

public class HTTPResponse {
	public enum HTTPRetCode {		
		_200(200, "OK"), _400(400, "Bad Request"), _404(404, "Not Found"), 
		_411(411, "Length Required"), _413(413, "Request Entity Too Large"),
		_429(429, "Too Many Requests"), _500(500, "Internal Server Error"), 
		_501(501, "Not Implemented"), _505(505, "HTTP Version Not Supported");
		
		private int code;
		private String text;
		private HTTPRetCode(int code, String txt) {
			this.code = code;
			this.text = txt;
		}
		
		public int intValue() {
			return code;
		}

		public String toString() {
			return text;
		}
	}	
	
	HTTPRetCode mRetCode = HTTPRetCode._200;
	ByteArrayOutputStream mOut = new ByteArrayOutputStream();
	Map<String, String> mHeaders = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);

	String mContentType = "text/html";
	String mEncoding = "utf-8";
	

	public ByteArrayOutputStream getOutputStream() {
		return mOut;
	}
	
	public void setContentType(String contentType) {
		mContentType = contentType;
	}
	
	public String getContentType() {
		return mContentType;
	}
    
	public void setCharacterEncoding(String encoding) {
		mEncoding = encoding;
	}
	
	public String getCharacterEncoding() {
		return mEncoding;
	}
	
	public void addHeader(String name, String value) {
		value = (value == null ? "" : value);
		value = value.replace("\n","").replace("\r","");
		mHeaders.put(name, value);
	}
	
	public void addCookie() {
		// TODO
	}
	
	public Map<String, String> getHeaders() {
		return mHeaders;
	}
	
	public void setReturnCode(HTTPRetCode code) {
		mRetCode = code;
	}

	public HTTPRetCode returnCode() {
		return mRetCode;
	}
}
