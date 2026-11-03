package com.tinyserver.servlet;

import java.nio.charset.StandardCharsets;

public class MySStream {
	byte[] stream;
	int pos = 0;
	
	public MySStream(String str) {
		// Always UTF-8: the parser decodes multi-byte sequences as UTF-8, so the
		// JVM default charset (US-ASCII under -Dfile.encoding=COMPAT on a C
		// locale) would turn every non-ASCII character into '?'.
		stream = str.getBytes(StandardCharsets.UTF_8);
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