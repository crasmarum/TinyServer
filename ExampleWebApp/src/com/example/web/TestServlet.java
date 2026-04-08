package com.example.web;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.HTTPResponse;
import com.tinyserver.servlet.Servlet;


public class TestServlet extends Servlet {

	public TestServlet() {
	}
	

	@Override
	public void service(HTTPRequest request, HTTPResponse response) {
//		System.out.println(request.toString());
		String name = request.parameters.get("name");
		request.attributes.put("msg", "Hello from TestServlet " + name + "!");
		request.forwardTo("/jsp/Test.jsp", response);
	}
}

