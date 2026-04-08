package com.tinyserver.servlet;

public class TestServlet extends Servlet {

	@Override
	public void service(HTTPRequest request, HTTPResponse response) {
		response.getOutputStream().writeBytes("Test Servlet".getBytes());
	}
}
