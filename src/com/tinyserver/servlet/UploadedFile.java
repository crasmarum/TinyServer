package com.tinyserver.servlet;

/**
 * One file from a multipart/form-data request.
 *
 * @param field       the form field name (name="...")
 * @param filename    the file name the browser sent, without any directory part ("C:\a\b.png" becomes "b.png")
 * @param contentType the part's Content-Type, "application/octet-stream" if none was given
 * @param data        the file's bytes, exactly as sent
 */
public record UploadedFile(String field, String filename, String contentType, byte[] data) {
	public int size() {
		return data.length;
	}

	@Override
	public String toString() {
		return field + ":" + filename + " (" + contentType + ", " + data.length + " bytes)";
	}
}
