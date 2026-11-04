package com.tinyserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.HTTPResponse;
import com.tinyserver.servlet.JspCompiler;
import com.tinyserver.servlet.Log;
import com.tinyserver.servlet.Servlet;

/**
 * Request framing, parameters, keep-alive, client identity and response headers, end to end: a real TinyServer on
 * 127.0.0.1 (a free port in 9400-9449, or the range given as the argument) with a temp html_dir and a few servlets,
 * spoken to in raw HTTP over sockets. No JUnit (see JsonParserTest); excluded from server.jar by name. Any failure,
 * including a setup failure, exits non-zero.
 *
 * Run: javac -d out $(find . -name "*.java") && java -cp out com.tinyserver.HttpProtocolTest [lo-hi]
 */
public class HttpProtocolTest {
	static int passed = 0, failed = 0;

	static void check(String label, boolean cond) {
		if (cond) passed++; else { failed++; System.out.println("FAIL " + label); }
	}

	static final Pattern IMF_FIXDATE = Pattern.compile("(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} "
			+ "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");

	/** echoes the routed path and the parameters it got */
	public static class EchoServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			StringBuilder sb = new StringBuilder("path=" + request.getMethod() + "\n");
			sb.append("params=").append(request.parameters.size()).append('\n');
			if (request.parameters.size() < 20) {
				for (var e : new TreeMap<>(request.parameters).entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
			}
			response.setContentType("text/plain");
			response.getOutputStream().writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	public static class JsonServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			response.setContentType("application/json");
			response.getOutputStream().writeBytes("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
		}
	}

	public static class PngServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			response.setContentType("image/png");
			response.getOutputStream().writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
		}
	}

	/** a servlet that (wrongly) puts line breaks into its content type and a header */
	public static class CrlfServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			response.setContentType("text/plain\r\nX-Injected: 1");
			response.getHeaders().put("X-Note", "a\r\nX-Injected2: 2");
			response.getOutputStream().writeBytes("crlf".getBytes(StandardCharsets.UTF_8));
		}
	}

	/** the body it got: length in chars and a SHA-256 of its UTF-8 bytes; ?ms=n holds the request that long first */
	public static class ContentServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			try {
				String ms = request.parameters.get("ms");
				if (ms != null) Thread.sleep(Long.parseLong(ms));
				String c = request.getContent();
				byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(c.getBytes(StandardCharsets.UTF_8));
				response.setContentType("text/plain");
				response.getOutputStream().writeBytes(("len=" + c.length() + " sha=" + java.util.HexFormat.of().formatHex(h)).getBytes(StandardCharsets.UTF_8));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/** a 4 MB response: byte i is (i * 7) & 0xff */
	public static class BigServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			byte[] b = new byte[4 << 20];
			for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 7);
			response.setContentType("application/octet-stream");
			response.getOutputStream().writeBytes(b);
		}
	}

	public static class BoomServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			throw new IllegalStateException("servlet failed");
		}
	}

	public static class ErrorServlet extends Servlet {
		@Override
		public void service(HTTPRequest request, HTTPResponse response) {
			throw new AssertionError("an Error in a servlet");
		}
	}

	/** a parsed response: status line, header block (lower-cased names), body */
	static final class Resp {
		String status = "";
		final TreeMap<String, String> headers = new TreeMap<>();
		String rawHeaders = "";
		byte[] body = new byte[0];
		int code() { String[] p = status.split(" "); return p.length > 1 ? Integer.parseInt(p[1]) : -1; }
		String h(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
		String text() { return new String(body, StandardCharsets.UTF_8); }
	}

	/** reads one response off {@code in}: headers, then Content-Length bytes (none for HEAD) */
	static Resp readResponse(InputStream in, boolean head) throws IOException {
		ByteArrayOutputStream hb = new ByteArrayOutputStream();
		int c, match = 0;
		while (match < 4 && (c = in.read()) != -1) {
			hb.write(c);
			match = (c == '\r' && (match == 0 || match == 2)) || (c == '\n' && (match == 1 || match == 3)) ? match + 1 : (c == '\r' ? 1 : 0);
		}
		Resp r = new Resp();
		r.rawHeaders = hb.toString(StandardCharsets.ISO_8859_1);
		String[] lines = r.rawHeaders.split("\r\n");
		r.status = lines.length > 0 ? lines[0] : "";
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
		}
		String cl = r.h("content-length");
		if (!head && cl != null) r.body = in.readNBytes(Integer.parseInt(cl));
		return r;
	}

	static int port;

	/** one exchange on a fresh socket; the socket is handed back so callers can check how it ends */
	static Socket open() throws IOException {
		Socket s = new Socket("127.0.0.1", port);
		s.setSoTimeout(5000);
		return s;
	}

	static Resp exchange(String raw) throws IOException {
		try (Socket s = open()) {
			s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
			s.getOutputStream().flush();
			return readResponse(new BufferedInputStream(s.getInputStream()), raw.startsWith("HEAD "));
		}
	}

	/** sends raw, reads one response, then returns ms until the server closes (-1), or -1 if it did not within 5 s */
	static long[] exchangeAndTimeClose(String raw, Resp[] out) throws IOException {
		try (Socket s = open()) {
			s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
			s.getOutputStream().flush();
			InputStream in = new BufferedInputStream(s.getInputStream());
			long t0 = System.nanoTime();
			out[0] = readResponse(in, false);
			long tResp = (System.nanoTime() - t0) / 1_000_000;
			try {
				int extra = 0;
				while (in.read() != -1) extra++;
				return new long[] {tResp, (System.nanoTime() - t0) / 1_000_000, extra};
			} catch (java.net.SocketTimeoutException e) {
				return new long[] {tResp, -1, 0};
			}
		}
	}

	static int portLo = 9400, portHi = 9449;

	/** true if nothing listens on {@code p}; probes the wildcard address, the same bind TinyServer does */
	static boolean portFree(int p) {
		try (ServerSocket s = new ServerSocket(p)) {
			return true;
		} catch (IOException busy) {
			return false;
		}
	}

	/** starts TinyServer on the first free port of the range; a port taken between the probe and the bind is skipped */
	static TinyServer startServer(File dir, File html) throws Exception {
		for (int p = portLo; p <= portHi; p++) {
			if (!portFree(p)) continue;
			port = p;
			File cfg = new File(dir, "config.txt");
			// html_dir with a trailing slash, and (on macOS) under the /var -> /private/var symlink: canonicalized at load
			Files.writeString(cfg.toPath(), "port " + port + "\nmax_req_len 6000000\nuse_virtual_threads true\nlog_file_dir " + dir
					+ "\nhtml_dir " + html.getPath() + "/\n"
					+ "map /echo com.tinyserver.HttpProtocolTest$EchoServlet\n"
					+ "map /json com.tinyserver.HttpProtocolTest$JsonServlet\n"
					+ "map /png com.tinyserver.HttpProtocolTest$PngServlet\n"
					+ "map /crlf com.tinyserver.HttpProtocolTest$CrlfServlet\n"
					+ "map /content com.tinyserver.HttpProtocolTest$ContentServlet\n"
					+ "map /boom com.tinyserver.HttpProtocolTest$BoomServlet\n"
					+ "map /big com.tinyserver.HttpProtocolTest$BigServlet\n"
					+ "map /error com.tinyserver.HttpProtocolTest$ErrorServlet\n"
					// a 4 MB body budget (3 MB for bodies over 64 KB) and a 50 KB/s floor, small enough to fill in a test
					+ "body_budget 4000000\nbody_min_rate 50000\n");
			try {
				return new TinyServer(cfg.getPath());
			} catch (BindException taken) {
			}
		}
		throw new IOException("no free port in " + portLo + "-" + portHi);
	}

	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				String[] range = args[0].split("-");
				portLo = Integer.parseInt(range[0]);
				portHi = Integer.parseInt(range[range.length - 1]);
			}
			run();
		} catch (Throwable t) {
			// a setup failure must not leave the JVM hanging or exit 0
			t.printStackTrace(System.out);
			failed++;
		}
		System.out.println("HttpProtocolTest: " + passed + " passed, " + failed + " failed.");
		Log.stop();
		System.exit(failed == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		unit();
		File dir = Files.createTempDirectory("ts-protocol").toFile();
		File html = new File(dir, "html");
		html.mkdirs();
		Files.writeString(new File(html, "index.html").toPath(), "<html>home</html>");
		Files.writeString(new File(html, "a.txt").toPath(), "static text");
		TinyServer server = startServer(dir, html);
		new Thread(server).start();
		Thread.sleep(300);
		try {
			endToEnd(html);
			logLines(dir);
			bodyBudget(server, dir);
		} finally {
			server.stopServer();
		}
	}

	static void unit() throws Exception {
		// X-Forwarded-For: only the last entry (what the proxy appended), only an IP literal
		check("XFF last entry wins", "5.6.7.8".equals(ServerEngine.clientIpFromXFF("1.2.3.4, 5.6.7.8")));
		check("XFF single entry", "9.9.9.9".equals(ServerEngine.clientIpFromXFF(" 9.9.9.9 ")));
		check("XFF IPv6", ServerEngine.clientIpFromXFF("1.2.3.4, 2001:db8::1") != null);
		check("XFF garbage refused", ServerEngine.clientIpFromXFF("1.2.3.4, attacker-text") == null);
		check("XFF hex-letter host names refused (no DNS)", ServerEngine.clientIpFromXFF("cafe") == null
				&& ServerEngine.clientIpFromXFF("dead.beef") == null && ServerEngine.clientIpFromXFF("1.2.3") == null);
		check("XFF too long refused", ServerEngine.clientIpFromXFF("1:" + "0".repeat(60)) == null);
		check("XFF bad IPv6 refused", ServerEngine.clientIpFromXFF("1:2:3:4:5:6:7:8:9") == null);
		// octets over 255 and a leading '.' are not literals to the JDK, which would resolve them as host names
		for (String bad : new String[] {"1.2.3.999", "300.1.1.1", "999.1.1.1", "256.0.0.1", "1.2.3.256", "999.999.999.999",
				".::1", ".:", "..:..", "1..2.3", "1.2.3.4.", "010.1.1.1", "1.2.3.4.5"}) {
			check("XFF '" + bad + "' refused", ServerEngine.clientIpFromXFF(bad) == null);
		}
		check("XFF octet edges", "0.0.0.0".equals(ServerEngine.clientIpFromXFF("0.0.0.0"))
				&& "255.255.255.255".equals(ServerEngine.clientIpFromXFF("255.255.255.255"))
				&& "10.0.200.9".equals(ServerEngine.clientIpFromXFF("10.0.200.9")));
		check("XFF IPv6 canonical / mapped", "0:0:0:0:0:0:0:1".equals(ServerEngine.clientIpFromXFF("::1"))
				&& "1.2.3.4".equals(ServerEngine.clientIpFromXFF("::ffff:1.2.3.4")));
		check("XFF empty / null", ServerEngine.clientIpFromXFF("") == null && ServerEngine.clientIpFromXFF(null) == null
				&& ServerEngine.clientIpFromXFF("1.2.3.4,") == null);
		check("effective IP behind loopback proxy", "5.6.7.8".equals(ServerEngine.effectiveClientIp("127.0.0.1", "1.2.3.4, 5.6.7.8")));
		check("garbage XFF falls back to the peer", "127.0.0.1".equals(ServerEngine.effectiveClientIp("127.0.0.1", "1.2.3.4, evil")));
		check("public peer ignores XFF", "203.0.113.9".equals(ServerEngine.effectiveClientIp("203.0.113.9", "5.6.7.8")));

		// dates: IMF-fixdate with a two-digit day; If-Modified-Since by string or by instant
		long jan5 = 1736074800000L;   // Sun, 05 Jan 2025 11:00:00 GMT
		check("IMF-fixdate two-digit day: " + ServerEngine.httpDate(jan5), "Sun, 05 Jan 2025 11:00:00 GMT".equals(ServerEngine.httpDate(jan5)));
		check("IMS equal string", ServerEngine.notModifiedSince("Sun, 05 Jan 2025 11:00:00 GMT", jan5 + 999));
		check("IMS later date", ServerEngine.notModifiedSince("Mon, 06 Jan 2025 00:00:00 GMT", jan5));
		check("IMS earlier date", !ServerEngine.notModifiedSince("Sat, 04 Jan 2025 00:00:00 GMT", jan5));
		check("IMS garbage", !ServerEngine.notModifiedSince("yesterday", jan5) && !ServerEngine.notModifiedSince(null, jan5));

		// Content-Type charset
		check("json gets charset", "application/json; charset=utf-8".equals(ServerEngine.contentTypeWithCharset("application/json", "utf-8")));
		check("png has no charset", "image/png".equals(ServerEngine.contentTypeWithCharset("image/png", "utf-8")));
		check("existing charset kept", "text/html; charset=iso-8859-1".equals(ServerEngine.contentTypeWithCharset("text/html; charset=iso-8859-1", "utf-8")));

		// parameters: cap and bad escapes
		java.util.Hashtable<String, String> p = new java.util.Hashtable<>();
		check("1000 params ok", ServerEngine.extractParameters("a=1&".repeat(1000), p, 0) == 1000);
		boolean tooMany = false;
		try { ServerEngine.extractParameters("x&".repeat(1001), new java.util.Hashtable<>(), 0); } catch (RequestTooBigException e) { tooMany = true; }
		check("1001 tokens without '=' -> RequestTooBig", tooMany);
		boolean bad = false;
		try { ServerEngine.extractParameters("a=%zz", new java.util.Hashtable<>(), 0); } catch (MalformedRequestException e) { bad = true; }
		check("%zz -> Malformed", bad);

		// configured directories are canonical, without a trailing separator
		File tmp = Files.createTempDirectory("ts-dir").toFile();
		JspCompiler.setDirectory(tmp.getPath() + "/");
		check("jsp_dir canonical: " + JspCompiler.getDirectory(), tmp.getCanonicalPath().equals(JspCompiler.getDirectory()));
		JspCompiler.setDirectory(null);
		check("canonical html_dir from a relative path", new File(".").getCanonicalPath().equals(TinyServer.canonicalDirectory("./")));
		tmp.delete();
	}

	/** the 'Request accepted for' log line names the validated last XFF entry, never the raw header */
	static void logLines(File dir) throws Exception {
		exchange("GET /echo HTTP/1.1\r\nX-Forwarded-For: 1.2.3.4, 5.6.7.8\r\nConnection: close\r\n\r\n");
		exchange("GET /echo HTTP/1.1\r\nX-Forwarded-For: 1.2.3.4, forged-name-xyz\r\nConnection: close\r\n\r\n");
		String log = "";
		for (int i = 0; i < 40 && !(log.contains("Request accepted for: 5.6.7.8") && log.contains("Request accepted for: 127.0.0.1")); i++) {
			Thread.sleep(50);
			StringBuilder sb = new StringBuilder();
			for (File f : dir.listFiles((d, n) -> n.endsWith(".log"))) sb.append(Files.readString(f.toPath()));
			log = sb.toString();
		}
		check("log names the last XFF entry", log.contains("Request accepted for: 5.6.7.8"));
		check("log never echoes the raw XFF header", !log.contains("1.2.3.4") && !log.contains("forged-name-xyz"));
	}

	/** waits up to 2 s for the budget to be empty again (a reservation is released when its servlet returns) */
	static boolean budgetEmpty(BodyBudget b) throws InterruptedException {
		for (int i = 0; i < 200 && b.used() != 0; i++) Thread.sleep(10);
		return b.used() == 0 && b.usedLarge() == 0;
	}

	static String post(String path, String type, byte[] body, String extra) {
		return "POST " + path + " HTTP/1.1\r\nHost: x\r\n" + (type != null ? "Content-Type: " + type + "\r\n" : "")
				+ "Content-Length: " + body.length + "\r\n" + extra + "\r\n";
	}

	/** sends head then body on a fresh socket and reads one response */
	static Resp send(String head, byte[] body) throws IOException {
		try (Socket s = open()) {
			OutputStream o = s.getOutputStream();
			o.write(head.getBytes(StandardCharsets.ISO_8859_1));
			o.write(body);
			o.flush();
			return readResponse(new BufferedInputStream(s.getInputStream()), false);
		}
	}

	static String sha(byte[] utf8, int chars) throws Exception {
		return "len=" + chars + " sha=" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(utf8));
	}

	/**
	 * The request-body memory budget end to end (budget 4 MB, 3 MB of it for bodies over 64 KB; body_min_rate 50 KB/s):
	 * bodies arrive intact, a full budget answers 503 while small requests still get through, slow bodies get 408, a
	 * body nobody reads gets 405 without being read, and every way a request can end gives its reservation back.
	 */
	static void bodyBudget(TinyServer server, File dir) throws Exception {
		BodyBudget b = server.bodyBudget();
		long waitMs = ServerEngine.BODY_WAIT_MS, graceMs = ServerEngine.BODY_GRACE_MS;
		ServerEngine.BODY_WAIT_MS = 300;
		ServerEngine.BODY_GRACE_MS = 500;
		ProbeFilter pf = ProbeFilter.getInstance();
		try {
			check("budget from the config: " + b.total(), b.total() == 4_000_000 && b.largeCap() == 3_000_000);

			// --- bodies arrive intact through the exact-length read and the single decode
			StringBuilder json = new StringBuilder("{\"t\":\"");
			while (json.length() < 100_000) json.append("é中x\\\" ");
			json.append("\"}");
			byte[] jb = json.toString().getBytes(StandardCharsets.UTF_8);
			Resp jr = send(post("/content", "application/json", jb, "Connection: close\r\n"), jb);
			check("100 KB JSON with non-ASCII arrives intact: " + jr.text(), jr.code() == 200 && jr.text().equals(sha(jb, json.length())));
			String form = "a=" + "x".repeat(60_000) + "&b=%C3%A9&c=" + "y".repeat(40_000);
			Resp fr = send(post("/echo", "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8), "Connection: close\r\n"),
					form.getBytes(StandardCharsets.UTF_8));
			check("100 KB form: parameters from the one decode", fr.code() == 200 && fr.text().contains("params=3") && fr.text().contains("b=é\n"));
			check("budget back to 0 after normal requests", budgetEmpty(b));

			// --- a full large pool: 4 bodies of 150 KB (750 KB each) that send their headers and then nothing; a 3 s grace
			// keeps them for the checks below
			ServerEngine.BODY_GRACE_MS = 3000;
			List<Socket> holders = new ArrayList<>();
			long tHold = System.nanoTime();
			for (int i = 0; i < 4; i++) {
				Socket h = open();
				h.getOutputStream().write(("POST /content HTTP/1.1\r\nX-Forwarded-For: 198.51.100.2" + i
						+ "\r\nContent-Length: 150000\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				h.getOutputStream().flush();
				holders.add(h);
			}
			for (int i = 0; i < 100 && b.usedLarge() < 3_000_000; i++) Thread.sleep(10);
			check("4 stalled 150 KB bodies hold the large pool: " + b.usedLarge(), b.usedLarge() == 3_000_000);

			int busyBefore = pf.errorCount("198.51.100.30");
			long t0 = System.nanoTime();
			Resp busy = exchange("POST /content HTTP/1.1\r\nX-Forwarded-For: 198.51.100.30\r\nContent-Length: 150000\r\n\r\n");
			long busyMs = (System.nanoTime() - t0) / 1_000_000;
			check("a fifth large body: 503 after the wait (" + busyMs + " ms): " + busy.status, busy.code() == 503 && busyMs >= 250 && busyMs < 1500
					&& busy.status.endsWith("Service Unavailable"));
			check("503 says when to retry", "2".equals(busy.h("retry-after")));
			check("a 503 is not counted against the client", pf.errorCount("198.51.100.30") == busyBefore);

			// after a 503 the server keeps reading (and dropping) the body for a while instead of closing at once,
			// so a client still sending is not reset before it has read the answer
			try (Socket d = open()) {
				OutputStream o = d.getOutputStream();
				o.write("POST /content HTTP/1.1\r\nContent-Length: 300000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				o.flush();
				Resp dr = readResponse(new BufferedInputStream(d.getInputStream()), false);
				int wrote = 0;
				try {
					for (int i = 0; i < 8; i++) { o.write(new byte[16_384]); o.flush(); wrote++; Thread.sleep(50); }
				} catch (IOException reset) {
				}
				check("503, then the body is still taken for a while (" + wrote + "/8 writes of 16 KB)", dr.code() == 503 && wrote == 8);
			}

			byte[] hundred = new byte[100_000];
			java.util.Arrays.fill(hundred, (byte) 'z');
			Resp sent = send(post("/content", "text/plain", hundred, "X-Forwarded-For: 198.51.100.31\r\n"), hundred);
			check("a 100 KB body already sent in full still gets its 503 (no reset): " + sent.status, sent.code() == 503);

			byte[] small = "{\"move\":\"D4\"}".repeat(140).getBytes(StandardCharsets.UTF_8);
			Resp sm = send(post("/content", "application/json", small, "Connection: close\r\n"), small);
			check("a 2 KB request still gets through (the small reserve): " + sm.status, sm.code() == 200);
			check("GETs are not budgeted", exchange("GET /echo HTTP/1.1\r\nConnection: close\r\n\r\n").code() == 200);

			try (Socket e = open()) {
				e.getOutputStream().write("POST /content HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				e.getOutputStream().flush();
				Resp er = readResponse(new BufferedInputStream(e.getInputStream()), false);
				check("Expect: 100-continue while busy: a final 503, never 100 Continue: " + er.status, er.code() == 503);
			}
			String rep = b.report();
			check("the minute report counts the refusals: " + rep, rep != null && rep.contains("busy (503): 4"));

			// the holders: 408 once the grace is over (3 s, their first byte due then); they sent nothing, so it counts
			int[] before = new int[4];
			for (int i = 0; i < 4; i++) before[i] = pf.errorCount("198.51.100.2" + i);
			int timedOut = 0;
			long lastMs = 0;
			for (Socket h : holders) {
				h.setSoTimeout(8000);
				Resp hr = readResponse(new BufferedInputStream(h.getInputStream()), false);
				lastMs = (System.nanoTime() - tHold) / 1_000_000;
				if (hr.code() == 408 && hr.text().contains("0 of 150000")) timedOut++;
				h.close();
			}
			check("stalled bodies get 408 after the grace (" + timedOut + "/4, last after " + lastMs + " ms)", timedOut == 4 && lastMs >= 2900 && lastMs < 4500);
			ServerEngine.BODY_GRACE_MS = 500;
			boolean counted = true;
			for (int i = 0; i < 4; i++) counted &= pf.errorCount("198.51.100.2" + i) == before[i] + 1;
			check("a 408 for a body that never came is counted against the client", counted);
			check("budget back to 0 after the 408s", budgetEmpty(b));
			Resp retry = send(post("/content", "text/plain", hundred, "Connection: close\r\n"), hundred);
			check("a retry now succeeds", retry.code() == 200 && retry.text().startsWith("len=100000 "));

			// a trickle that would pass the old 30 s idle timeout: 1 byte every 300 ms, far under the 50 KB/s floor
			int trickleBefore = pf.errorCount("198.51.100.35");
			try (Socket tr = open()) {
				OutputStream o = tr.getOutputStream();
				o.write("POST /content HTTP/1.1\r\nX-Forwarded-For: 198.51.100.35\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				o.write(new byte[2000]);   // 2 KB at once, then the trickle: a slow sender, not a stall
				o.flush();
				t0 = System.nanoTime();
				tr.setSoTimeout(300);
				InputStream in = new BufferedInputStream(tr.getInputStream());
				Resp got = null;
				for (int i = 0; i < 40 && got == null; i++) {
					try { o.write('x'); o.flush(); } catch (IOException closed) { }
					try { in.mark(1); if (in.read() != -1) { in.reset(); tr.setSoTimeout(2000); got = readResponse(in, false); } } catch (java.net.SocketTimeoutException wait) { }
				}
				long trMs = (System.nanoTime() - t0) / 1_000_000;
				// cut when it falls behind the rate, soon after the 0.5 s grace (a deadline for the whole body would be 2.5 s)
				check("a trickling body is cut once it falls behind (" + trMs + " ms): " + (got == null ? "no answer" : got.status),
						got != null && got.code() == 408 && trMs >= 400 && trMs < 1500);
			}
			check("a 408 for a body that was arriving, only too slowly, is not counted", pf.errorCount("198.51.100.35") == trickleBefore);
			check("budget back to 0 after the trickle", budgetEmpty(b));

			// --- 405: a body to an address nobody reads is refused at once, unread
			t0 = System.nanoTime();
			byte[] kb = new byte[1024];
			Resp nr = send("POST /a.txt HTTP/1.1\r\nX-Forwarded-For: 198.51.100.40\r\nContent-Length: 1000000\r\n\r\n", kb);
			long nrMs = (System.nanoTime() - t0) / 1_000_000;
			check("POST with a body to a static file: 405 at once (" + nrMs + " ms), Allow: " + nr.h("allow"),
					nr.code() == 405 && nrMs < 1500 && "GET, HEAD".equals(nr.h("allow")) && nr.status.endsWith("Method Not Allowed"));
			check("POST / with a body: 405", send("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\n", "a=1".getBytes()).code() == 405);
			check("POST to a static file without a body is served as before", exchange("POST /a.txt HTTP/1.1\r\nContent-Length: 0\r\n\r\n").code() == 200);
			check("GET of the same file still works", exchange("GET /a.txt HTTP/1.1\r\n\r\n").code() == 200);
			check("a mapped servlet takes a body", server.acceptsBody("/content") && !server.acceptsBody("/a.txt") && !server.acceptsBody("/jsp/x.jsp"));
			File jsp = Files.createTempDirectory("ts-jsp").toFile();
			JspCompiler.setDirectory(jsp.getPath());
			check("with jsp_dir set, a JSP takes a body", server.acceptsBody("/jsp/x.jsp"));
			JspCompiler.setDirectory(null);
			jsp.delete();

			// --- every way a request ends gives its reservation back
			check("413 for a body over max_req_len, without waiting", exchange("POST /content HTTP/1.1\r\nContent-Length: 6000000\r\n\r\n").code() == 413);
			check("  budget 0 after 413", budgetEmpty(b));
			String bad = "--zz\r\nno headers end";
			check("400 for a bad multipart body", send(post("/echo", "multipart/form-data; boundary=zz", bad.getBytes(), ""), bad.getBytes()).code() == 400);
			check("  budget 0 after 400", budgetEmpty(b));
			try (Socket eof = open()) {
				eof.getOutputStream().write("POST /content HTTP/1.1\r\nContent-Length: 10000\r\n\r\nonly a little".getBytes(StandardCharsets.ISO_8859_1));
				eof.shutdownOutput();
				readResponse(new BufferedInputStream(eof.getInputStream()), false);
			}
			check("  budget 0 after the client closed mid-body", budgetEmpty(b));
			check("500 when a servlet throws", send(post("/boom", "text/plain", hundred, ""), hundred).code() == 500);
			check("  budget 0 after a servlet exception", budgetEmpty(b));
			try (Socket er = open()) {
				er.getOutputStream().write(post("/error", "text/plain", hundred, "").getBytes(StandardCharsets.ISO_8859_1));
				er.getOutputStream().write(hundred);
				er.getOutputStream().flush();
				check("an Error in a servlet ends the connection", new BufferedInputStream(er.getInputStream()).read() == -1);
			} catch (IOException reset) {
				check("an Error in a servlet ends the connection (reset)", true);
			}
			check("  budget 0 after a servlet Error", budgetEmpty(b));
			exchange("POST /.env HTTP/1.1\r\nX-Forwarded-For: 192.0.2.99\r\nContent-Length: 5\r\n\r\nhello");
			check("  budget 0 after a probe path (closed, no answer)", budgetEmpty(b));
			check("GET with a body is 400, nothing reserved", exchange("GET /echo HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello").code() == 400 && budgetEmpty(b));
			check("HEAD reserves nothing", exchange("HEAD /a.txt HTTP/1.1\r\n\r\n").code() == 200 && budgetEmpty(b));
			try (Socket k = open()) {
				OutputStream o = k.getOutputStream();
				InputStream in = new BufferedInputStream(k.getInputStream());
				int ok = 0;
				for (int i = 0; i < 50; i++) {
					byte[] body = ("n=" + i + "&pad=" + "p".repeat(i * 2000)).getBytes(StandardCharsets.ISO_8859_1);
					o.write(post("/echo", "application/x-www-form-urlencoded", body, "").getBytes(StandardCharsets.ISO_8859_1));
					o.write(body);
					o.flush();
					Resp kr = readResponse(in, false);
					if (kr.code() == 200 && kr.text().contains("n=" + i + "\n")) ok++;
				}
				check("50 POSTs (up to 100 KB) on one kept-alive socket: " + ok, ok == 50);
			}
			check("  budget 0 after keep-alive POSTs", budgetEmpty(b));

			// --- the reservation ends when the servlet returns, not when a slow reader has taken the response
			try (Socket slow = new Socket()) {
				slow.setReceiveBufferSize(1024);
				slow.connect(new java.net.InetSocketAddress("127.0.0.1", port));
				slow.getOutputStream().write(post("/big", "text/plain", hundred, "").getBytes(StandardCharsets.ISO_8859_1));
				slow.getOutputStream().write(hundred);
				slow.getOutputStream().flush();
				Thread.sleep(500);   // the servlet has returned; its 4 MB response is stuck in the socket buffers
				check("a client that does not read its response holds no budget (" + b.used() + ")", budgetEmpty(b));
				slow.setSoTimeout(10000);
				InputStream in = new BufferedInputStream(slow.getInputStream());
				Resp big = readResponse(in, false);
				boolean same = big.body.length == 4 << 20;
				for (int i = 0; same && i < big.body.length; i++) same = big.body[i] == (byte) (i * 7);
				check("the 4 MB response, written from its buffer without a copy, arrives intact", big.code() == 200 && same);
			}
			byte[] cjkForm = ("a=" + "%E4%B8%AD".repeat(20_000)).getBytes(StandardCharsets.ISO_8859_1);
			Resp cjk = send(post("/echo", "application/x-www-form-urlencoded", cjkForm, "Connection: close\r\n"), cjkForm);
			check("a 180 KB urlencoded form (11 x = 2 MB, within the large pool) is parsed", cjk.code() == 200 && cjk.text().contains("params=1"));
			check("  budget 0 after the form", budgetEmpty(b));

			// --- many at once: each answered 200 or 503, never more than the budget, all given back
			List<Thread> ts = new ArrayList<>();
			java.util.concurrent.ConcurrentLinkedQueue<Integer> codes = new java.util.concurrent.ConcurrentLinkedQueue<>();
			java.util.concurrent.atomic.AtomicLong maxUsed = new java.util.concurrent.atomic.AtomicLong(), maxLarge = new java.util.concurrent.atomic.AtomicLong();
			java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
			Thread sampler = Thread.ofVirtual().start(() -> {
				while (sampling.get()) {
					maxUsed.accumulateAndGet(b.used(), Math::max);
					maxLarge.accumulateAndGet(b.usedLarge(), Math::max);
					try { Thread.sleep(1); } catch (InterruptedException e) { return; }   // parks: a spin would hold a lone carrier
				}
			});
			for (int i = 0; i < 40; i++) {
				ts.add(Thread.ofVirtual().start(() -> {
					try { codes.add(send(post("/content?ms=50", "text/plain", hundred, "Connection: close\r\n"), hundred).code()); }
					catch (IOException e) { codes.add(-1); }
				}));
			}
			for (Thread th : ts) th.join();
			sampling.set(false);
			sampler.join();
			long ok200 = codes.stream().filter(c -> c == 200).count(), busy503 = codes.stream().filter(c -> c == 503).count();
			check("40 concurrent 100 KB posts: " + ok200 + " x 200, " + busy503 + " x 503", ok200 + busy503 == 40 && ok200 >= 6);
			check("never more than the budget held (" + maxUsed + " bytes, large " + maxLarge + ")",
					maxUsed.get() > 0 && maxUsed.get() <= b.total() && maxLarge.get() <= b.largeCap());
			check("  budget 0 at the end", budgetEmpty(b));

			// the startup line describes the budget
			String log = "";
			for (File f : dir.listFiles((d, n) -> n.endsWith(".log"))) log += Files.readString(f.toPath());
			check("startup log line: body budget", log.contains("Body budget: 3.8 MB (bodies over 64 KB: at most 2.9 MB together)"));
		} finally {
			ServerEngine.BODY_WAIT_MS = waitMs;
			ServerEngine.BODY_GRACE_MS = graceMs;
		}
	}

	/** n keys from the "Aa"/"BB" family, which all share one String.hashCode */
	static String collidingParams(int n) {
		StringBuilder sb = new StringBuilder();
		int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append('&');
			for (int b = 0; b < bits; b++) sb.append(((i >> b) & 1) == 0 ? "Aa" : "BB");
			sb.append("=1");
		}
		return sb.toString();
	}

	static void endToEnd(File html) throws Exception {
		Resp[] r = new Resp[1];

		// --- framing: anything ambiguous is a 400 and the connection is closed, so nothing is left to smuggle
		long[] t = exchangeAndTimeClose("GET /index.html HTTP/1.1\r\nHost: x\r\nContent-Length: 46\r\n\r\n"
				+ "GET /a.txt HTTP/1.1\r\nHost: victim\r\n\r\n", r);
		check("GET with a body -> 400: " + r[0].status, r[0].code() == 400);
		check("GET with a body: closed, no second response (" + t[1] + " ms, " + t[2] + " extra bytes)", t[1] >= 0 && t[1] < 2000 && t[2] == 0);

		t = exchangeAndTimeClose("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
				+ "0\r\n\r\nGET /a.txt HTTP/1.1\r\nHost: victim\r\n\r\n", r);
		check("Transfer-Encoding -> 400: " + r[0].status, r[0].code() == 400);
		check("Transfer-Encoding: closed, no second response", t[1] >= 0 && t[1] < 2000 && t[2] == 0);
		check("Transfer-Encoding on GET -> 400", exchange("GET /echo HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").code() == 400);

		check("header line without ':' -> 400", exchange("GET /echo HTTP/1.1\r\nHost: x\r\nnot a header\r\nX-After: 1\r\n\r\n").code() == 400);
		check("obsolete folding -> 400", exchange("GET /echo HTTP/1.1\r\nX-A: 1\r\n folded\r\n\r\n").code() == 400);
		check("space before ':' -> 400", exchange("GET /echo HTTP/1.1\r\nContent-Length : 5\r\n\r\n").code() == 400);
		check("Content-Length not digits -> 400", exchange("POST /echo HTTP/1.1\r\nContent-Length: +5\r\n\r\nhello").code() == 400);
		check("two different Content-Lengths -> 400", exchange("POST /echo HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!").code() == 400);
		check("two equal Content-Lengths -> 200", exchange("POST /echo HTTP/1.1\r\nContent-Length: 3\r\nContent-Length: 3\r\nConnection: close\r\n\r\na=1").code() == 200);
		check("GET with Content-Length: 0 -> 200", exchange("GET /echo HTTP/1.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").code() == 200);

		StringBuilder hdrs = new StringBuilder();
		for (int i = 0; i < 128; i++) hdrs.append("X-H").append(i).append(": v\r\n");
		check("128 headers -> 200", exchange("GET /echo HTTP/1.1\r\n" + hdrs + "\r\n").code() == 200);
		check("129 headers -> 413", exchange("GET /echo HTTP/1.1\r\n" + hdrs + "X-H128: v\r\n\r\n").code() == 413);
		StringBuilder fat = new StringBuilder();
		for (int i = 0; i < 3; i++) fat.append("X-F").append(i).append(": ").append("v".repeat(60000)).append("\r\n");
		check("over 128 KB of headers -> 413", exchange("GET /echo HTTP/1.1\r\n" + fat + "\r\n").code() == 413);

		// --- request line
		check("extra spaces in the request line -> 400", exchange("GET  /echo HTTP/1.1\r\n\r\n").code() == 400);
		check("9 trailing spaces -> 400 (not a dropped connection)", exchange("GET /echo HTTP/1.1         \r\n\r\n").code() == 400);
		check("missing version -> 400", exchange("GET /echo\r\n\r\n").code() == 400);
		check("unsupported method -> 501", exchange("PUT /echo HTTP/1.1\r\n\r\n").code() == 501);
		check("lower-case method still works", exchange("get /echo HTTP/1.1\r\nConnection: close\r\n\r\n").code() == 200);

		// --- a pooled proxy socket: a malformed request is never charged to the previous request's XFF client
		ProbeFilter pf = ProbeFilter.getInstance();
		String[] badNext = {"GET /echo HTTP/1.1\r\nX-Forwarded-For: 198.51.100.9\r\nnot a header\r\n\r\n",
				"GET /echo HTTP/1.1\r\nX-Forwarded-For: 198.51.100.9\r\nTransfer-Encoding: chunked\r\n\r\n",
				"GET  /echo HTTP/1.1\r\n\r\n"};
		for (String bad : badNext) {
			int innocent = pf.errorCount("203.0.113.7"), sender = pf.errorCount("198.51.100.9"), peer = pf.errorCount("127.0.0.1");
			try (Socket s = open()) {
				InputStream in = new BufferedInputStream(s.getInputStream());
				s.getOutputStream().write("GET /echo HTTP/1.1\r\nX-Forwarded-For: 203.0.113.7\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				s.getOutputStream().flush();
				Resp first = readResponse(in, false);
				s.getOutputStream().write(bad.getBytes(StandardCharsets.UTF_8));
				s.getOutputStream().flush();
				Resp second = readResponse(in, false);
				String label = bad.substring(bad.lastIndexOf("\r\n", bad.length() - 5) + 2).trim();
				check("pooled socket: 200 then 400 (" + label + "): " + first.status + " / " + second.status,
						first.code() == 200 && second.code() == 400);
			}
			check("pooled socket: previous client not charged (" + pf.errorCount("203.0.113.7") + ")", pf.errorCount("203.0.113.7") == innocent);
			check("pooled socket: unparsed XFF not trusted", pf.errorCount("198.51.100.9") == sender);
			check("pooled socket: error charged to the peer", pf.errorCount("127.0.0.1") == peer + 1);
		}

		// --- parameters
		StringBuilder q = new StringBuilder();
		for (int i = 0; i < 1001; i++) q.append(i == 0 ? "" : "&").append("p").append(i).append("=1");
		check("1001 query params -> 413", exchange("GET /echo?" + q + " HTTP/1.1\r\n\r\n").code() == 413);
		String flood = collidingParams(1000);
		long t0 = System.nanoTime();
		Resp fr = exchange("POST /echo HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + flood.length()
				+ "\r\nConnection: close\r\n\r\n" + flood);
		long floodMs = (System.nanoTime() - t0) / 1_000_000;
		check("1000 colliding keys parsed (" + floodMs + " ms)", fr.code() == 200 && fr.text().contains("params=1000") && floodMs < 1000);
		String flood2 = collidingParams(1024);
		check("1024 colliding keys in a body -> 413", exchange("POST /echo HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "
				+ flood2.length() + "\r\n\r\n" + flood2).code() == 413);
		check("query + body share the cap -> 413", exchange("POST /echo?" + "a=1&".repeat(600) + " HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "
				+ "b=1&".repeat(500).length() + "\r\n\r\n" + "b=1&".repeat(500)).code() == 413);
		check("malformed %zz -> 400", exchange("GET /echo?a=%zz HTTP/1.1\r\n\r\n").code() == 400);
		check("malformed %zz in a form body -> 400", exchange("POST /echo HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 5\r\n\r\na=%zz").code() == 400);

		Resp pq = exchange("POST /echo?a=1&c=%C3%A9 HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 3\r\nConnection: close\r\n\r\nb=2");
		check("POST /echo?a=1 routes to /echo with a, c and body b: " + pq.text().replace('\n', '|'),
				pq.code() == 200 && pq.text().contains("path=/echo\n") && pq.text().contains("a=1\n") && pq.text().contains("b=2\n") && pq.text().contains("c=é\n"));
		Resp gq = exchange("GET /echo HTTP/1.1\r\nConnection: close\r\n\r\n");
		check("GET without a query has no parameters", gq.text().contains("params=0"));

		// --- keep-alive
		t = exchangeAndTimeClose("GET /a.txt HTTP/1.1\r\nHost: x\r\n\r\n", r);
		check("static file served (html_dir with trailing slash, canonicalized): " + r[0].status, r[0].code() == 200 && "static text".equals(r[0].text()));
		check("static response closes the connection promptly (" + t[1] + " ms)", t[1] >= 0 && t[1] < 2000);
		check("Date is IMF-fixdate: " + r[0].h("date"), r[0].h("date") != null && IMF_FIXDATE.matcher(r[0].h("date")).matches());
		check("Last-modified is IMF-fixdate: " + r[0].h("last-modified"), r[0].h("last-modified") != null && IMF_FIXDATE.matcher(r[0].h("last-modified")).matches());

		try (Socket s = open()) {
			OutputStream o = s.getOutputStream();
			InputStream in = new BufferedInputStream(s.getInputStream());
			o.write("GET /json HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
			o.flush();
			Resp a = readResponse(in, false);
			o.write("GET /echo?n=2 HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
			o.flush();
			Resp b = readResponse(in, false);
			check("servlet keeps HTTP/1.1 alive: " + a.status + " / " + a.h("connection"), a.code() == 200 && "keep-alive".equals(a.h("connection")));
			check("second request on the same socket", b.code() == 200 && b.text().contains("n=2"));
			check("JSON Content-Type: " + a.h("content-type"), "application/json; charset=utf-8".equals(a.h("content-type")));
			check("servlet Date is IMF-fixdate", a.h("date") != null && IMF_FIXDATE.matcher(a.h("date")).matches());
			o.write("GET /echo HTTP/1.1\r\nConnection: close\r\n\r\n".getBytes());
			o.flush();
			Resp c = readResponse(in, false);
			long tc = System.nanoTime();
			int end = in.read();
			check("Connection: close request closes after the response", c.code() == 200 && end == -1 && (System.nanoTime() - tc) / 1_000_000 < 2000);
		}
		Resp png = exchange("GET /png HTTP/1.1\r\nConnection: close\r\n\r\n");
		check("image/png has no charset: " + png.h("content-type"), "image/png".equals(png.h("content-type")));
		Resp crlf = exchange("GET /crlf HTTP/1.1\r\nConnection: close\r\n\r\n");
		check("CR/LF stripped from Content-Type and headers", crlf.code() == 200 && !crlf.rawHeaders.contains("\r\nX-Injected")
				&& "crlf".equals(crlf.text()));

		// --- static: conditional GET, HEAD, misses
		String lm = r[0].h("last-modified");
		Resp nm = exchange("GET /a.txt HTTP/1.1\r\nIf-Modified-Since: " + lm + "\r\n\r\n");
		check("If-Modified-Since round trip -> 304: " + nm.status, nm.code() == 304);
		Resp nm2 = exchange("GET /a.txt HTTP/1.1\r\nIf-Modified-Since: " + ServerEngine.httpDate(System.currentTimeMillis() + 3_600_000) + "\r\n\r\n");
		check("If-Modified-Since in the future -> 304", nm2.code() == 304);
		Resp m = exchange("GET /a.txt HTTP/1.1\r\nIf-Modified-Since: Sat, 01 Jan 2000 00:00:00 GMT\r\n\r\n");
		check("If-Modified-Since in the past -> 200", m.code() == 200);
		Resp hd = exchange("HEAD /a.txt HTTP/1.1\r\n\r\n");
		check("HEAD: GET headers, no body", hd.code() == 200 && "11".equals(hd.h("content-length")));

		t = exchangeAndTimeClose("GET /missing.html HTTP/1.1\r\nHost: x\r\n\r\n", r);
		check("missing static file -> 403 in " + t[0] + " ms", r[0].code() == 403 && t[0] < 100);
		check("traversal -> 403", exchange("GET /../" + html.getParentFile().getName() + "/config.txt HTTP/1.1\r\n\r\n").code() == 403);

		// --- client identity behind the proxy: the last X-Forwarded-For entry is the one banned for a probe path
		exchangeAndTimeClose("GET /x.php HTTP/1.1\r\nX-Forwarded-For: 198.51.100.7, 192.0.2.44\r\n\r\n", r);
		check("probe path bans the LAST XFF entry", ProbeFilter.getInstance().isBanned("192.0.2.44")
				&& !ProbeFilter.getInstance().isBanned("198.51.100.7"));
		exchangeAndTimeClose("GET /y.php HTTP/1.1\r\nX-Forwarded-For: 192.0.2.55, not-an-ip-192.0.2.66\r\n\r\n", r);
		check("garbage XFF is not used as the identity", !ProbeFilter.getInstance().isBanned("192.0.2.55")
				&& !ProbeFilter.getInstance().isBanned("not-an-ip-192.0.2.66"));

		// a stray CRLF before a request line is tolerated
		check("leading CRLF tolerated", exchange("\r\nGET /echo HTTP/1.1\r\nConnection: close\r\n\r\n").code() == 200);
	}
}
