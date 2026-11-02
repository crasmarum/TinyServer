package com.tinyserver;

import java.io.IOException;

public class RequestTooBigException extends IOException {

	private static final long serialVersionUID = 1L;
	
	RequestTooBigException(String msg) {
		super(msg);
	}
}
