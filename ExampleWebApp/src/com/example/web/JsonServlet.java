package com.example.web;

import java.io.File;


import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.HTTPResponse;
import com.tinyserver.servlet.JsonSerializer;
import com.tinyserver.servlet.Log;
import com.tinyserver.servlet.Servlet;
import com.tinyserver.servlet.HTTPResponse.HTTPRetCode;

class RuntimeValues {
	public long maxMemoryM;
	public long usedMemoryM;
	public long noThreads;
	public long noVirtualThreads;
	public long queriesPerMinute;
	public long freeDiskM;
}

public class JsonServlet extends Servlet {

	public JsonServlet() {
	}
	

	@Override
	public void service(HTTPRequest request, HTTPResponse response) {
		response.setContentType("application/json");


		try {
			RuntimeValues varz = new RuntimeValues();
			varz.maxMemoryM = Runtime.getRuntime().maxMemory() / 1000000;
			varz.usedMemoryM = Runtime.getRuntime().totalMemory() / 1000000;
			varz.noThreads = Thread.activeCount();
			varz.freeDiskM = new File("/").getUsableSpace() / 1000000;
			
			response.getOutputStream().writeBytes(JsonSerializer.toJson(varz).getBytes());
		} catch (Exception e) {
			Log.error(e.toString());
			response.getOutputStream().writeBytes(e.toString().getBytes());
			response.setReturnCode(HTTPRetCode._500);
		}
	}

}

