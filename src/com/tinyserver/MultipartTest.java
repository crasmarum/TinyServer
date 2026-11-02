package com.tinyserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.TreeMap;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.Servlet;
import com.tinyserver.servlet.UploadedFile;

/**
 * File uploads (multipart/form-data): parser unit cases, then end to end against a running TinyServer with an echo
 * servlet: browser-style bodies with binary and UTF-8 content, curl -F (Expect: 100-continue), and a second request on
 * the same kept-alive connection after an upload. No JUnit (see JsonParserTest); excluded from server.jar by name.
 *
 * Run: javac -d out $(find . -name "*.java") && java -cp out com.tinyserver.MultipartTest
 */
public class MultipartTest {
	static int passed = 0, failed = 0;

	static void check(String label, boolean cond) {
		if (cond) passed++; else { failed++; System.out.println("FAIL " + label); }
	}

	/** echoes what TinyServer parsed: fields, then files with size and SHA-256 */
	public static class EchoServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, com.tinyserver.servlet.HTTPResponse response) {
			StringBuilder sb = new StringBuilder();
			for (var e : new TreeMap<>(request.parameters).entrySet()) sb.append("field ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
			for (UploadedFile f : request.uploads) sb.append("file ").append(f.field()).append(' ').append(f.filename()).append(' ').append(f.contentType())
					.append(' ').append(f.size()).append(' ').append(sha(f.data())).append('\n');
			response.setContentType("text/plain");
			response.getOutputStream().writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	static String sha(byte[] b) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)).substring(0, 16); }
		catch (Exception e) { throw new RuntimeException(e); }
	}

	/** a browser-style multipart body */
	static final class Body {
		final String boundary;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		Body(String boundary) { this.boundary = boundary; }
		Body field(String name, String value) {
			w("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
			return this;
		}
		Body file(String name, String filename, String type, byte[] data) {
			w("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
					+ (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\n");
			out.writeBytes(data);
			w("\r\n");
			return this;
		}
		byte[] end() { w("--" + boundary + "--\r\n"); return out.toByteArray(); }
		void w(String s) { out.writeBytes(s.getBytes(StandardCharsets.UTF_8)); }
		String type() { return "multipart/form-data; boundary=" + boundary; }
	}

	static HTTPRequest parse(byte[] body, String type) throws Exception {
		HTTPRequest r = new HTTPRequest(new Config());
		Multipart.parse(body, type, r);
		return r;
	}

	static boolean throwsMalformed(byte[] body, String type) {
		try { parse(body, type); return false; } catch (MalformedRequestException e) { return true; } catch (Exception e) { return false; }
	}

	public static void main(String[] args) throws Exception {
		unit();
		endToEnd();
		System.out.println("MultipartTest: " + passed + " passed, " + failed + " failed.");
		System.exit(failed == 0 ? 0 : 1);
	}

	static void unit() throws Exception {
		byte[] all = new byte[256];
		for (int i = 0; i < 256; i++) all[i] = (byte) i;
		// data that looks like the boundary and line breaks, but is not a delimiter
		byte[] tricky = "line1\r\nline2\r\n--notTheBoundary\r\n-----WebKitFormBoundaryX\r\nend".getBytes(StandardCharsets.UTF_8);
		Body b = new Body("----WebKitFormBoundaryX7MA4YWxkTrZu0gW");
		byte[] body = b.field("title", "Héllo 世界 😀").field("empty", "").file("img", "C:\\Users\\me\\photo.png", "image/png", all)
				.file("txt", "notes/tricky.txt", "text/plain", tricky).file("none", "", "application/octet-stream", new byte[0]).end();
		HTTPRequest r = parse(body, b.type());
		check("UTF-8 field", "Héllo 世界 😀".equals(r.parameters.get("title")));
		check("empty field", "".equals(r.parameters.get("empty")));
		check("two files, empty file input skipped", r.uploads.size() == 2);
		UploadedFile img = r.getUpload("img");
		check("binary file: all 256 byte values intact", img != null && java.util.Arrays.equals(all, img.data()));
		check("directory stripped from file name (Windows path)", img != null && "photo.png".equals(img.filename()));
		check("content type kept", img != null && "image/png".equals(img.contentType()));
		check("data with CRLF and boundary look-alikes intact", java.util.Arrays.equals(tricky, r.getUpload("txt").data()) && "tricky.txt".equals(r.getUpload("txt").filename()));
		check("files map: field -> name -> bytes", r.files.get("img") != null && java.util.Arrays.equals(all, r.files.get("img").get("photo.png")));

		// quoted boundary, preamble and epilogue, missing content type, escaped quote, RFC 5987 file name
		String bd = "abc:def";
		String raw = "This is a preamble.\r\n--abc:def\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a \\\"q\\\".bin\"\r\n\r\nXYZ\r\n"
				+ "--abc:def\r\nContent-Disposition: form-data; name=\"g\"; filename=\"x.txt\"; filename*=UTF-8''na%C3%AFve%20%E6%96%87.txt\r\n\r\nG\r\n"
				+ "--abc:def--\r\nepilogue ignored";
		r = parse(raw.getBytes(StandardCharsets.UTF_8), "multipart/form-data; charset=utf-8; boundary=\"" + bd + "\"");
		check("quoted boundary + preamble + epilogue", r.uploads.size() == 2);
		check("escaped quote in file name", "a \"q\".bin".equals(r.uploads.get(0).filename()));
		check("default content type", "application/octet-stream".equals(r.uploads.get(0).contentType()));
		check("filename* (RFC 5987) wins", "naïve 文.txt".equals(r.uploads.get(1).filename()));
		check("no parts at all", parse("--b--\r\n".getBytes(), "multipart/form-data; boundary=b").uploads.isEmpty());
		check("LF-only part header line endings are not accepted silently",
				throwsMalformed("--b\nContent-Disposition: form-data; name=\"x\"\n\nv\n--b--".getBytes(), "multipart/form-data; boundary=b"));

		// malformed
		check("no boundary parameter", throwsMalformed(body, "multipart/form-data"));
		check("wrong boundary", throwsMalformed(body, "multipart/form-data; boundary=other"));
		check("truncated body", throwsMalformed(java.util.Arrays.copyOf(body, body.length - 60), b.type()));
	}

	static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

	static void endToEnd() throws Exception {
		File dir = Files.createTempDirectory("ts-upload").toFile();
		int port = freePort();
		File cfg = new File(dir, "config.txt");
		Files.writeString(cfg.toPath(), "port " + port + "\nmax_req_len 20000000\nuse_virtual_threads true\nlog_file_dir " + dir
				+ "\nmap /upload com.tinyserver.MultipartTest$EchoServlet\n");
		TinyServer server = new TinyServer(cfg.getPath());
		new Thread(server).start();
		Thread.sleep(300);
		String url = "http://127.0.0.1:" + port + "/upload";

		// 1. a browser-style form with a 3 MB random binary file and UTF-8 everywhere
		byte[] big = new byte[3_000_000];
		new Random(42).nextBytes(big);
		Body b = new Body("----geckoformboundary9f2c");
		byte[] body = b.field("note", "café 😀").file("upload", "données 写真.bin", "application/x-test", big).end();
		long t0 = System.nanoTime();
		String res = post(port, b.type(), body);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		check("HTTP 200", res.startsWith("HTTP/1.1 200"));
		check("field echoed", res.contains("field note=café 😀"));
		check("3 MB binary file arrives byte for byte (" + ms + " ms)", res.contains("file upload données 写真.bin application/x-test 3000000 " + sha(big)));

		// 2. curl -F, which sends Expect: 100-continue and waits for it
		File f = new File(dir, "sample.bin");
		byte[] sample = new byte[200_000];
		new Random(7).nextBytes(sample);
		Files.write(f.toPath(), sample);
		try {
			Process p = new ProcessBuilder("curl", "-s", "-m", "10", "-F", "who=curl", "-F", "doc=@" + f.getPath() + ";type=application/pdf", url).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor();
			check("curl -F upload (Expect: 100-continue): " + out.replace('\n', '|'),
					out.contains("field who=curl") && out.contains("file doc sample.bin application/pdf 200000 " + sha(sample)));
		} catch (java.io.IOException noCurl) {
			System.out.println("(curl not available, skipped)");
		}

		// 3. keep-alive: an upload, then a second request on the same socket
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(5000);
			Body k = new Body("BOUNDARY");
			byte[] kb = k.file("f", "k.bin", "application/octet-stream", new byte[] {0, 13, 10, 45, 45, (byte) 255}).end();
			OutputStream o = s.getOutputStream();
			o.write(("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: " + k.type() + "\r\nContent-Length: " + kb.length + "\r\n\r\n").getBytes());
			o.write(kb);
			String body2 = "a=1&b=%C3%A9";
			o.write(("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "
					+ body2.getBytes().length + "\r\nConnection: close\r\n\r\n" + body2).getBytes());
			o.flush();
			String both = new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
			check("upload then a second request on the same connection", both.contains("file f k.bin application/octet-stream 6 ")
					&& both.contains("field a=1") && both.contains("field b=é") && both.indexOf("HTTP/1.1 200") != both.lastIndexOf("HTTP/1.1 200"));
		}

		// 4. a malformed multipart body gets 400, not a stalled connection
		res = post(port, "multipart/form-data; boundary=nope", "garbage".getBytes());
		check("malformed multipart -> 400: " + res.lines().findFirst().orElse(""), res.startsWith("HTTP/1.0 400") || res.startsWith("HTTP/1.1 400"));

		server.stopServer();
	}

	/** one POST over a fresh socket (Connection: close); returns the whole response, headers included */
	static String post(int port, String contentType, byte[] body) throws Exception {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(15000);
			OutputStream o = s.getOutputStream();
			o.write(("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			o.write(body);
			o.flush();
			return new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
		}
	}

	static byte[] readAll(InputStream in) throws Exception {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		try { while ((n = in.read(buf)) > 0) b.write(buf, 0, n); } catch (java.net.SocketTimeoutException ignored) {}
		return b.toByteArray();
	}
}
