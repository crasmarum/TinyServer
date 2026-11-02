package com.tinyserver;

import java.io.IOException;

/** A request TinyServer cannot parse (for example a broken multipart body): answered with 400 Bad Request. */
public class MalformedRequestException extends IOException {

	private static final long serialVersionUID = 1L;

	public MalformedRequestException(String msg) {
		super(msg);
	}
}
