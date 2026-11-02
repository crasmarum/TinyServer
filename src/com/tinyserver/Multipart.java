package com.tinyserver;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.UploadedFile;

/**
 * multipart/form-data parsing (RFC 7578, the format browsers use for forms with file inputs).
 *
 * The body is a sequence of parts separated by "--boundary" lines, each with its own headers (Content-Disposition
 * with name= and, for files, filename=; optionally Content-Type) and raw content, closed by "--boundary--". Plain fields
 * go to {@link HTTPRequest#parameters} (decoded as UTF-8, which browsers send); files go to {@link HTTPRequest#uploads}
 * and to {@link HTTPRequest#files} (field -> file name -> bytes). A file input left empty (empty file name, no data) is
 * skipped. Anything that does not follow the format throws {@link MalformedRequestException} (400).
 */
public final class Multipart {
	private Multipart() {}

	public static final int MAX_PARTS = 1000;
	public static final int MAX_PART_HEADERS = 16384;
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

	/** the boundary parameter of a multipart Content-Type, or null */
	public static String boundary(String contentType) {
		if (contentType == null) {
			return null;
		}
		String b = params(contentType).get("boundary");
		if (b == null || b.isEmpty() || b.length() > 200) {
			return null;
		}
		return b;
	}

	/** parse body into request.parameters, request.uploads and request.files */
	public static void parse(byte[] body, String contentType, HTTPRequest request) throws IOException {
		String boundary = boundary(contentType);
		if (boundary == null) {
			throw new MalformedRequestException("multipart/form-data without a boundary");
		}
		byte[] dashBoundary = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

		// The first boundary starts the body, or follows a preamble and its CRLF.
		int pos;
		if (startsWith(body, 0, dashBoundary)) {
			pos = dashBoundary.length;
		} else {
			int i = indexOf(body, delimiter, 0);
			if (i < 0) {
				throw new MalformedRequestException("multipart body without its boundary");
			}
			pos = i + delimiter.length;
		}

		int parts = 0;
		while (true) {
			// After a boundary: "--" closes the body; otherwise optional padding, then CRLF and the part.
			if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
				return;
			}
			while (pos < body.length && (body[pos] == ' ' || body[pos] == '\t')) {
				pos++;
			}
			if (!startsWith(body, pos, CRLF)) {
				throw new MalformedRequestException("multipart boundary not followed by a line break");
			}
			pos += 2;

			String headers;
			int dataStart;
			if (startsWith(body, pos, CRLF)) {                    // a part with no headers
				headers = "";
				dataStart = pos + 2;
			} else {
				int end = indexOf(body, CRLF_CRLF, pos);
				if (end < 0 || end - pos > MAX_PART_HEADERS) {
					throw new MalformedRequestException("multipart part headers missing or too long");
				}
				headers = new String(body, pos, end - pos, StandardCharsets.UTF_8);
				dataStart = end + 4;
			}
			int next = indexOf(body, delimiter, dataStart);
			if (next < 0) {
				throw new MalformedRequestException("multipart body is not closed by its boundary");
			}
			if (++parts > MAX_PARTS) {
				throw new MalformedRequestException("more than " + MAX_PARTS + " multipart parts");
			}
			addPart(headers, Arrays.copyOfRange(body, dataStart, next), request);
			pos = next + delimiter.length;
		}
	}

	private static void addPart(String headers, byte[] data, HTTPRequest request) {
		String disposition = null, type = null;
		for (String line : headers.split("\r\n")) {
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			if (name.equals("content-disposition")) {
				disposition = value;
			} else if (name.equals("content-type")) {
				type = value;
			}
		}
		if (disposition == null) {
			return;
		}
		Map<String, String> p = params(disposition);
		String field = p.get("name");
		if (field == null) {
			return;
		}
		String filename = p.containsKey("filename*") ? extValue(p.get("filename*")) : p.get("filename");
		if (filename == null) {
			request.parameters.put(field, new String(data, StandardCharsets.UTF_8));
			return;
		}
		filename = baseName(filename);
		if (filename.isEmpty() && data.length == 0) {
			return;                                           // a file input left empty
		}
		UploadedFile f = new UploadedFile(field, filename, type == null || type.isEmpty() ? "application/octet-stream" : type, data);
		request.uploads.add(f);
		request.files.computeIfAbsent(field, k -> new Hashtable<>()).put(filename, data);
	}

	/** a file name without any directory part (some browsers send full client paths) */
	static String baseName(String filename) {
		int cut = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
		return filename.substring(cut + 1);
	}

	/** RFC 5987 ext-value: charset'lang'percent-encoded, e.g. UTF-8''na%C3%AFve.txt */
	static String extValue(String v) {
		int a = v.indexOf('\''), b = a < 0 ? -1 : v.indexOf('\'', a + 1);
		if (b < 0) {
			return v;
		}
		try {
			return URLDecoder.decode(v.substring(b + 1).replace("+", "%2B"), v.substring(0, a).isEmpty() ? "UTF-8" : v.substring(0, a));
		} catch (Exception e) {
			return v.substring(b + 1);
		}
	}

	/**
	 * Parameters of a header value such as {@code form-data; name="a"; filename="b \"c\".txt"}: keys lower-cased,
	 * quoted values unquoted (backslash escapes a following quote or backslash only). Browsers percent-encode quotes and line breaks in names
	 * (%22, %0D, %0A); those are decoded too.
	 */
	static Map<String, String> params(String value) {
		Map<String, String> out = new HashMap<>();
		int i = value.indexOf(';');
		while (i >= 0 && i < value.length()) {
			i++;
			while (i < value.length() && value.charAt(i) == ' ') {
				i++;
			}
			int eq = value.indexOf('=', i);
			if (eq < 0) {
				break;
			}
			String key = value.substring(i, eq).trim().toLowerCase(Locale.ROOT);
			int j = eq + 1;
			String v;
			if (j < value.length() && value.charAt(j) == '"') {
				StringBuilder sb = new StringBuilder();
				j++;
				while (j < value.length() && value.charAt(j) != '"') {
					char c = value.charAt(j);
					// a backslash escapes only a quote or a backslash: browsers that send full Windows paths send
					// their backslashes unescaped ("C:\Users\me\photo.png")
					if (c == '\\' && j + 1 < value.length() && (value.charAt(j + 1) == '"' || value.charAt(j + 1) == '\\')) {
						c = value.charAt(++j);
					}
					sb.append(c);
					j++;
				}
				v = sb.toString().replace("%22", "\"").replace("%0D", "\r").replace("%0A", "\n");
				i = value.indexOf(';', j);
			} else {
				int semi = value.indexOf(';', j);
				v = (semi < 0 ? value.substring(j) : value.substring(j, semi)).trim();
				i = semi;
			}
			out.putIfAbsent(key, v);
		}
		return out;
	}

	static boolean startsWith(byte[] a, int at, byte[] prefix) {
		if (at < 0 || at + prefix.length > a.length) {
			return false;
		}
		for (int k = 0; k < prefix.length; k++) {
			if (a[at + k] != prefix[k]) {
				return false;
			}
		}
		return true;
	}

	static int indexOf(byte[] a, byte[] pattern, int from) {
		byte first = pattern[0];
		int last = a.length - pattern.length;
		for (int i = Math.max(0, from); i <= last; i++) {
			if (a[i] == first && startsWith(a, i, pattern)) {
				return i;
			}
		}
		return -1;
	}
}
