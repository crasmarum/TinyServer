package com.tinyserver.servlet;

public class MySStream {
	byte[] stream;
	int pos = 0;
	
	public MySStream(String str) {
		stream = str.getBytes();
	}
	
	byte get () {
		if (stream.length <= pos) {
			return -1;
		}
		return stream[pos++];
	}
	
	byte peek() {
		if (stream.length <= pos) {
			return -1;
		}
		return stream[pos];
	}
}