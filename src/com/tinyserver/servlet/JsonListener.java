package com.tinyserver.servlet;

import java.util.List;

import com.tinyserver.servlet.JsonObj.JSON_Type;

public class JsonListener {
	public static final String COLON = ":";

	public void on_data(JSON_Type type, List<String> key, String value) {
		
	}
	
	public static String toString(List<String> key) {
		StringBuilder sb = new StringBuilder();
		for (String str : key) {
			sb.append(COLON).append(str);
		}
		return sb.toString().substring(1);
	}
}