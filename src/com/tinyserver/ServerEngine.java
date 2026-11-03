package com.tinyserver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

import com.tinyserver.servlet.HTTPRequest;
import com.tinyserver.servlet.HTTPResponse;
import com.tinyserver.servlet.JanitorThread;
import com.tinyserver.servlet.Log;
import com.tinyserver.servlet.Servlet;

public abstract class ServerEngine implements Runnable {
	public static final String CRLF = "\r\n";
	protected static final String MIME_PART = "_mimePart__";
	protected static final String FILENAME = "filename";
	protected static final String NAME = "name";
	protected static final String BOUNDARY = "boundary";
	protected static final String CONTENT_LENGTH = "content-length";
	protected static final String CONTENT_TYPE = "content-type";
	protected static final String CONTENT_DISPOSITION = "content-disposition";
	protected static final String CONTENT_TRANSFER_ENCODING = "content-transfer-encoding";
	protected static final String CONTENT_TYPE_MULTIPART_FDATA = "multipart/form-data";
	protected static final String CONTENT_TYPE_FORM_URLENCODED= "application/x-www-form-urlencoded";
	private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "access-control-allow-origin";
	private static final String IF_MODIFIED_SINCE = "if-modified-since";

	public static final String  X_FORWARDED_FOR = "x-forwarded-for";
	private static final String TRANSFER_ENCODING = "transfer-encoding";

	/** most header lines accepted in one request; more is answered 413 */
	static final int MAX_HEADERS = 128;
	/** most header bytes (all header lines together) accepted in one request; more is answered 413 */
	static final int MAX_HEADER_BYTES = 128 * 1024;
	/** most query + urlencoded body parameters in one request (Hashtable keys can be made to collide); more is 413 */
	static final int MAX_PARAMS = 1000;

	/**
	 * IMF-fixdate (RFC 9110 5.6.7), e.g. "Sun, 06 Nov 1994 08:49:37 GMT": two-digit day, English names, always GMT.
	 * Immutable, so safe to share between request threads (unlike the SimpleDateFormat it replaces).
	 */
	static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
			.withZone(ZoneOffset.UTC);

	static String httpDate(long millis) {
		return HTTP_DATE.format(Instant.ofEpochMilli(millis));
	}

	private final String METHOD = "___method_";
	private final String CMD_GET = "___cmd_get_";
	private final String GET = "GET";

	private final ServerSocket mServerSocket;
	private final int mPort;
	private boolean isRunning = false;
	private JanitorThread janitor;
	private volatile Timer probeFilterTimer;   // set in run(), cancelled by stopServer() on the shutdown-hook thread
	/** why the accept loop died, or null; see {@link #getFatalError()} */
	private volatile Throwable fatalError;

	protected Config mConfig = new Config();
	protected int MAX_REQUEST_LENGTH = 100000000; // 100M

	public int getRunningPort() {
		return mPort;
	}

	/** the Throwable that ended the accept loop, or null while it runs or after a normal stopServer() */
	public Throwable getFatalError() {
		return fatalError;
	}

	/**
	 * @return the Cache-Control value we want to send for a given MIME
	 *   type. HTML must revalidate on every hit (a menu edit shouldn't
	 *   linger in browser caches for the RFC-heuristic freshness window,
	 *   which without an explicit Cache-Control ends up being (now -
	 *   Last-Modified) * 0.1 — that's what caused stale headers to
	 *   persist after a deploy until the user hit Cmd+R). Everything
	 *   else can sit in the browser cache for a day; assets change
	 *   rarely and a deploy bumps their mtime anyway, so the next
	 *   conditional GET after the max-age window closes still returns
	 *   304 when the bytes really haven't moved.
	 */
	protected static String cacheControlFor(String contentType) {
		if (contentType != null && contentType.startsWith("text/html")) {
			return "no-cache";
		}
		return "public, max-age=86400";
	}

	/** extra header lines for static responses: COOP / COEP when cross_origin_isolation is on, else empty */
	protected static volatile String staticHeaders = "";

	static final String ISOLATION_HEADERS = "Cross-Origin-Opener-Policy: same-origin\r\n"
			+ "Cross-Origin-Embedder-Policy: require-corp\r\n";

	protected static void sendContentHeader(BufferedOutputStream bufferedoutputstream, int code, String contentType,
			long contentLength, long lastModified) throws IOException {
		sendContentHeader(bufferedoutputstream, code, contentType, contentLength, lastModified, null);
	}

	protected static void sendContentHeader(BufferedOutputStream bufferedoutputstream, int code, String contentType,
			long contentLength, long lastModified, String cacheControl) throws IOException {
		closeAfterResponse.set(Boolean.TRUE);   // the header below tells the client this connection ends here
		bufferedoutputstream.write(("HTTP/1.0 " + code
				+ " OK\r\n" + "Date: " + httpDate(System.currentTimeMillis()) + "\r\n"
				+ "Server: Tinyserver/1.0\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Connection: close\r\n"
				+ (cacheControl != null ? "Cache-Control: " + cacheControl + "\r\n" : "")
				+ staticHeaders
				+ (contentLength == -1L ? "" : "Content-Length: " + contentLength + "\r\n")
				+ "Last-modified: " + httpDate(lastModified) + "\r\n" + "\r\n").getBytes());
	}

	protected static void sendNotModifiedContentHeader(BufferedOutputStream bufferedoutputstream, String contentType,
			long lastModified) throws IOException {
		sendNotModifiedContentHeader(bufferedoutputstream, contentType, lastModified, null);
	}

	protected static void sendNotModifiedContentHeader(BufferedOutputStream bufferedoutputstream, String contentType,
			long lastModified, String cacheControl) throws IOException {
		closeAfterResponse.set(Boolean.TRUE);
		bufferedoutputstream.write(("HTTP/1.0 304  Not Modified\r\n"
				+ "Date: " + httpDate(System.currentTimeMillis()) + "\r\n"
				+ "Server: Tinyserver/1.0\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Connection: close\r\n"
				+ (cacheControl != null ? "Cache-Control: " + cacheControl + "\r\n" : "")
				+ staticHeaders
				+ "Last-modified: " + httpDate(lastModified) + "\r\n" + "\r\n").getBytes());
	}

	/**
	 * Whether a conditional GET can be answered 304: If-Modified-Since equals our Last-modified string, or it is a
	 * date at or after the file's modification time (HTTP dates have whole seconds).
	 */
	static boolean notModifiedSince(String ifModifiedSince, long lastModified) {
		if (ifModifiedSince == null) {
			return false;
		}
		if (ifModifiedSince.equals(httpDate(lastModified))) {
			return true;
		}
		try {
			long since = ZonedDateTime.parse(ifModifiedSince.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
			return lastModified / 1000 <= since;
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	public void stopServer() {
		setRunning(false);
		if (probeFilterTimer != null) {
			probeFilterTimer.cancel();
		}
		if (mServerSocket == null) {
			return;
		}
		try {
			mServerSocket.close();
		} catch (Exception e) {
		}
		if (janitor != null) {   // only created when the config injects a JanitorThread
			janitor.stop();
		}

		Log.info("Server stopped.");
	}

	@Override
	public void run() {
		if (mConfig.useVirtualThreads) {
			Log.info("Server is using virtual threads.");
		}
		
		janitor = (JanitorThread)mConfig.getInstanceOrNull(JanitorThread.class.getCanonicalName());
		if (janitor != null) {
			janitor.start();
			setupJanitorThread(janitor);
		}

		// Expired bans and 4xx windows are dropped here once a minute, off the accept thread.
		probeFilterTimer = new Timer("ProbeFilter-cleanup", true);
		probeFilterTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					ProbeFilter.getInstance().cleanup();
				} catch (RuntimeException e) {   // an escaped exception would cancel the Timer for good
					Log.printStackTrace(e);
				}
			}
		}, 60000, 60000);

		while (isRunning()) {
			try {
				final Socket clientSocket = accept();
				if (clientSocket == null) {
					continue;
				}

				// Cheapest possible bot-probe defence: drop the connection
				// before any I/O if the source IP has already earned a ban.
				// Skip the check for private-range source IPs because those
				// are our proxy (AWS ALB, Cloudflare in-VPC, etc.) — we
				// can't know the real client identity until the X-Forwarded-
				// For header is parsed, which happens inside performHTMLprotocol.
				// Banning the proxy IP here would kill health checks and
				// every other genuine client behind the same proxy.
				String acceptIp = clientSocket.getInetAddress().getHostAddress();
				if (!isPrivateOrLoopbackIp(acceptIp)
						&& ProbeFilter.getInstance().isBanned(acceptIp)) {
					try { clientSocket.close(); } catch (IOException ignored) {}
					continue;
				}

				clientSocket.setSoTimeout(30000);
//				Log.info("Request accepted for: " + clientSocket.getInetAddress().getHostAddress());

				final InputStream clientSocketIn = clientSocket.getInputStream();
				final OutputStream clientSocketOut = clientSocket.getOutputStream();

				if (mConfig.useVirtualThreads) {
					Thread.startVirtualThread(() -> {
						Servlet.noVirtulThreads.incrementAndGet();
						try {
							doIO(clientSocket, clientSocketIn, clientSocketOut, clientSocket.getInetAddress().getHostAddress());
						} finally {
							Servlet.noVirtulThreads.decrementAndGet();
						}
					});
				} else {
					new Thread() {
 						public void run() {
 							doIO(clientSocket, clientSocketIn, clientSocketOut, clientSocket.getInetAddress().getHostAddress());
 						}
 					}.start();
				}
			} catch (IOException ioex) {
				ioex.printStackTrace();
			} catch (Throwable ex) {
				// The listener cannot carry on (e.g. an OutOfMemoryError while starting a request thread). Record why and
				// stop, so the launcher can exit non-zero and a supervisor restart us, rather than leave a process running
				// with a dead port (other threads, such as a janitor's Timer, would keep the JVM alive).
				fatalError = ex;
				System.err.println(Log.timestamp() + "\tERROR\tAccept loop failed, stopping the server: " + ex);
				ex.printStackTrace();
				Log.error("Accept loop failed, stopping the server: " + ex);
				stopServer();
				break;
			}
		}
	}

	private void setupJanitorThread(final JanitorThread janitor) {
		Log.info("Setting up janitor thread: " + janitor.getClass().getCanonicalName());
		Timer timer = new Timer();
		long timeMs = mConfig.janitorScheduleMinutes * 60000;
		timer.schedule(new TimerTask() {
			
			@Override
			public void run() {
				Log.info("Janitor task, next run in " + mConfig.janitorScheduleMinutes + " min.");
				janitor.doRecurringWork();
			}
		}, 0, timeMs);
	}

	/**
	 * Client IP of the request being served on this thread. Set to the
	 * TCP peer at the top of {@link #doIO} and of every request in
	 * {@link #performHTMLprotocol}, re-bound to the X-Forwarded-For client
	 * once the headers are parsed, and cleared in doIO's finally so that downstream
	 * helpers (notably {@link #sendError}) can attribute 4xx responses
	 * to {@link ProbeFilter} without threading the IP through every
	 * signature.
	 */
	private static final ThreadLocal<String> currentClientIp = new ThreadLocal<>();

	/**
	 * Set by every writer whose response tells the client the connection ends (the HTTP/1.0 static and error
	 * headers, a servlet/JSP response with Connection: close), so {@link #performHTMLprotocol} stops reading
	 * further requests from that socket instead of waiting for the idle timeout.
	 */
	private static final ThreadLocal<Boolean> closeAfterResponse = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * Private ranges (RFC1918 IPv4, ULA IPv6) + loopback (both IPv4 and
	 * IPv6 in any representation) + link-local + any-local. These are
	 * the addresses we expect a fronting proxy (AWS ALB, Cloudflare-in-
	 * VPC, nginx, etc.) to present as the TCP source, plus loopback so
	 * local healthchecks don't ban themselves. Banning these would kill
	 * every legitimate client behind the proxy.
	 *
	 * <p>{@code InetAddress.getByName(ip)} for a numeric IP string does
	 * not trigger DNS — it just parses the address — so this is cheap.
	 */
	private static boolean isPrivateOrLoopbackIp(String ip) {
		if (ip == null || ip.isEmpty()) return false;
		try {
			java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
			return addr.isLoopbackAddress()
					|| addr.isSiteLocalAddress()
					|| addr.isLinkLocalAddress()
					|| addr.isAnyLocalAddress();
		} catch (java.net.UnknownHostException ignored) {
			return false;
		}
	}

	/**
	 * The client address a trusted reverse proxy put in X-Forwarded-For, or null. The header is a comma-separated
	 * list; Apache mod_proxy APPENDS the address it saw, so only the LAST entry is trustworthy (everything before it
	 * is whatever the client sent). The entry must be a numeric IP literal: dotted-quad IPv4, or IPv6 (contains ':'),
	 * at most 45 characters of [0-9A-Fa-f.:]. InetAddress.getByName falls back to a host-name lookup for anything it
	 * cannot parse as a literal ("cafe", "dead.beef", "999.1.1.1", ".::1"), so it never sees such strings: IPv4 is
	 * parsed here (four decimal octets 0-255, no leading zeros) and built with getByAddress; IPv6 must start with a
	 * hex digit or ':', the only case where the JDK refuses a bad literal without a lookup. Returns the address in
	 * canonical form.
	 */
	static String clientIpFromXFF(String xff) {
		if (xff == null) return null;
		int comma = xff.lastIndexOf(',');
		String s = (comma < 0 ? xff : xff.substring(comma + 1)).trim();
		if (s.isEmpty() || s.length() > 45) return null;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':')) {
				return null;
			}
		}
		try {
			if (s.indexOf(':') < 0) {
				byte[] v4 = parseIPv4(s);
				return v4 == null ? null : InetAddress.getByAddress(v4).getHostAddress();
			}
			if (s.charAt(0) == '.') {
				return null;   // not a v6 literal to the JDK, which would then resolve it as a name
			}
			return InetAddress.getByName(s).getHostAddress();
		} catch (UnknownHostException | IllegalArgumentException e) {
			return null;
		}
	}

	/** four decimal octets 0-255 without leading zeros ("010" is octal to some parsers), or null */
	private static byte[] parseIPv4(String s) {
		String[] parts = s.split("\\.", -1);
		if (parts.length != 4) return null;
		byte[] addr = new byte[4];
		for (int i = 0; i < 4; i++) {
			String p = parts[i];
			if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) return null;
			int v = 0;
			for (int j = 0; j < p.length(); j++) {
				char c = p.charAt(j);
				if (c < '0' || c > '9') return null;
				v = v * 10 + (c - '0');
			}
			if (v > 255) return null;
			addr[i] = (byte) v;
		}
		return addr;
	}

	/** the client identity for logs and ProbeFilter: the proxy-supplied X-Forwarded-For when the peer is our proxy */
	static String effectiveClientIp(String peerIp, String xff) {
		if (isPrivateOrLoopbackIp(peerIp)) {
			String fromProxy = clientIpFromXFF(xff);
			if (fromProxy != null) {
				return fromProxy;
			}
		}
		return peerIp;
	}

	private void doIO(final Socket clientSocket, final InputStream clientSocketIn, final OutputStream clientSocketOut, String ip) {
		LineInputStream input = null;
		BufferedOutputStream bufferedoutputstream = null;
		currentClientIp.set(ip);
		try {
			// bytes, not a Reader: request bodies (file uploads) must arrive undecoded, see LineInputStream
			input = new LineInputStream(clientSocketIn);
			bufferedoutputstream = new BufferedOutputStream(clientSocketOut);
			// HTTP/1.1 keep-alive: loop handling requests on the same socket
			// until the client (or this side, via Connection: close) decides
			// to terminate. The 30 s setSoTimeout in the caller bounds the
			// idle wait for the next request line on a kept-alive socket.
			boolean keepAlive = true;
			while (keepAlive) {
				keepAlive = performHTMLprotocol(input, bufferedoutputstream, ip);
			}
		} catch (SocketTimeoutException e) {
			sendError(bufferedoutputstream, 500, "Timeout: " + e.getMessage());
			Log.info("SocketTimeoutException");
		} catch (RequestTooBigException ex) {
			sendError(bufferedoutputstream, 413, ex.getMessage());
		} catch (MalformedRequestException ex) {
			sendError(bufferedoutputstream, 400, ex.getMessage());
		} catch (IOException e) {
			Log.printStackTrace(e);
			sendError(bufferedoutputstream, 500, "Server side error.");
		} finally {
			if (bufferedoutputstream != null) {
				try {
					bufferedoutputstream.close();
				} catch (IOException ignored) {
				}
			}
			if (input != null) {
				try {
					input.close();
				} catch (IOException ignored) {
				}
			}
			try {
				clientSocket.close();
			} catch (IOException ignored) {
			}
			currentClientIp.remove();
			closeAfterResponse.remove();
		}
	}

	private Socket accept() throws IOException {
		try {
			if (mServerSocket != null) {
				return mServerSocket.accept();
			}
			return null;
		} catch (SocketException ignored) {
			return null;
		} catch (Exception ex) {
			Log.printStackTrace(ex);
			throw new IOException();
		}
	}

	/**
	 * Parses one request off {@code input} and dispatches it via
	 * {@link #execute}. Returns whether the caller should keep the socket
	 * open for another request (HTTP/1.1 keep-alive), or close it.
	 *
	 * <p>Framing is strict because behind a proxy the socket is shared by
	 * many users: the request line must be exactly METHOD SP target SP
	 * version, headers end only at an empty line, Transfer-Encoding is
	 * refused and a GET/HEAD may not carry a body. Anything else throws
	 * {@link MalformedRequestException} (400) or {@link RequestTooBigException}
	 * (413) and doIO closes the connection, so no unread bytes can ever be
	 * taken for the next request.
	 */
	final boolean performHTMLprotocol(LineInputStream input, BufferedOutputStream bufferedoutputstream, String ip)
			throws IOException {
		// Until this request's headers are parsed its client is unknown: errors in the request line or headers are
		// charged to the TCP peer, never to the previous request's X-Forwarded-For client on a pooled proxy socket.
		currentClientIp.set(ip);
		String line;
		try {
			line = input.readLine();
			if (line != null && line.isEmpty()) {
				line = input.readLine();   // RFC 9112 2.2: tolerate one stray CRLF before a request line
			}
		} catch (SocketTimeoutException | java.net.SocketException endOfKeepAlive) {
			// Idle timeout on a kept-alive socket, or the peer dropped its
			// half before sending the next request. Both are normal
			// end-of-conversation signals — silently end the loop rather
			// than logging a stack trace and trying to write 500 to a
			// closed socket.
			return false;
		}

		// Client closed the socket between requests. Normal end-of-stream
		// on a kept-alive connection — caller should just stop looping.
		if (line == null) {
			return false;
		}

		// request-line = method SP request-target SP HTTP-version (LineInputStream already dropped the CRLF)
		String[] parts = line.split(" ", -1);
		if (parts.length != 3 || parts[0].isEmpty() || !parts[1].startsWith("/")) {
			throw new MalformedRequestException("Bad request line.");
		}
		String version = parts[2];
		boolean http11 = version.equalsIgnoreCase("HTTP/1.1");
		if (!http11 && !version.equalsIgnoreCase("HTTP/1.0")) {
			throw new MalformedRequestException("Unsupported HTTP version.");
		}
		String httpMethod = parts[0].toUpperCase(Locale.ROOT);
		boolean post = httpMethod.equals("POST");
		// HEAD is answered as GET through a stream that drops the body (see HeadOnlyOutputStream), so the status and
		// headers match GET exactly. Link-preview and monitoring bots send HEAD; it used to get a 500.
		boolean head = httpMethod.equals("HEAD");
		if (!post && !head && !httpMethod.equals(GET)) {
			sendError(bufferedoutputstream, 501, "Unsupported Method.");
			return false;
		}
		if (head) {
			bufferedoutputstream = new HeadOnlyOutputStream(bufferedoutputstream);
		}
		closeAfterResponse.set(Boolean.FALSE);

		HTTPRequest request = new HTTPRequest(mConfig);
		long contentLength = readHeaders(input, request.headers);

		String target = parts[1];
		int q = target.indexOf('?');
		String path = q < 0 ? target : target.substring(0, q);
		request.headers.put(METHOD, path);
		if (post) {
			request.headers.remove(CMD_GET);
		} else {
			request.headers.put(CMD_GET, GET);
		}
		request.setMethod(path);

		// Determine the *effective* client IP for ProbeFilter purposes.
		// When the TCP source is a private-range proxy (Apache on
		// loopback, AWS ALB, etc.) and it supplied X-Forwarded-For, the
		// LAST entry, if it is an IP literal, is the client the proxy saw
		// (see clientIpFromXFF). Otherwise the TCP source itself is the
		// client. Re-bind the ThreadLocal so sendError attributes 4xx
		// responses (including parse errors below) to the same effective IP.
		String effectiveIp = effectiveClientIp(ip, request.headers.get(X_FORWARDED_FOR));
		currentClientIp.set(effectiveIp);

		// Bot-probe drop: known scanner path → ban the effective IP and
		// close the socket without writing a response. Real users never
		// hit these paths so the false-positive rate is effectively zero.
		if (ProbeFilter.getInstance().isProbePath(request.getMethod())) {
			ProbeFilter.getInstance().ban(
					effectiveIp, "probe path: " + Log.sanitize(request.getMethod()));
			return false;
		}

		int params = q < 0 ? 0 : extractParameters(target.substring(q + 1), request.parameters, 0);
		if (post) {
			readPostBody(request, contentLength, params, input, bufferedoutputstream);
		} else if (contentLength > 0) {
			// a body has no meaning on GET/HEAD and must never stay unread on a kept-alive (proxy-shared) socket
			throw new MalformedRequestException("A GET or HEAD request cannot carry a body.");
		}

		// Standard HTTP/1.1 keep-alive determination from the request:
		//   HTTP/1.1 + no "Connection: close" → keep-alive.
		//   HTTP/1.0 + explicit "Connection: keep-alive" → keep-alive.
		//   Otherwise → close.
		// The Config.keepConnectionAlive flag is a hard kill-switch — when
		// false we always close, which reverts to the legacy HTTP/1.0
		// behaviour regardless of what the client requested.
		String conn = request.headers.get("connection");
		boolean wantsClose     = conn != null && conn.toLowerCase().contains("close");
		boolean wantsKeepAlive = conn != null && conn.toLowerCase().contains("keep-alive");
		request.keepAlive = mConfig.keepConnectionAlive
				&& !wantsClose
				&& (http11 || wantsKeepAlive);

		Log.info("Request accepted for: " + effectiveIp);
		execute(request, bufferedoutputstream);
		if (head) {
			bufferedoutputstream.flush();   // push the headers held in the HEAD wrapper through to the socket
		}
		// Keep reading from this socket only if the response we actually sent left it open.
		return request.keepAlive && !closeAfterResponse.get();
	}

	protected abstract void execute(HTTPRequest request, BufferedOutputStream bufferedoutputstream) throws IOException;

	/**
	 * Reads header lines up to the empty line that ends them into {@code headers} (keys lower-cased, values trimmed)
	 * and returns the Content-Length, or -1 if there is none. Refuses (400) a line without ':' or with whitespace
	 * before it, obsolete line folding, a stray CR, any Transfer-Encoding, a Content-Length that is not all digits
	 * and two different Content-Lengths; refuses (413) more than {@link #MAX_HEADERS} lines or
	 * {@link #MAX_HEADER_BYTES} bytes.
	 */
	static long readHeaders(LineInputStream input, Hashtable<String, String> headers) throws IOException {
		int count = 0;
		long bytes = 0;
		long contentLength = -1;
		while (true) {
			byte[] raw = input.readLineAsBytes();
			if (raw == null) {
				throw new MalformedRequestException("Connection closed inside the request headers.");
			}
			if (raw.length == 0) {
				return contentLength;
			}
			bytes += raw.length + 2;
			if (++count > MAX_HEADERS || bytes > MAX_HEADER_BYTES) {
				throw new RequestTooBigException("More than " + MAX_HEADERS + " header lines or " + MAX_HEADER_BYTES + " header bytes.");
			}
			String line = new String(raw, StandardCharsets.UTF_8);
			if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
				throw new MalformedRequestException("Obsolete header line folding.");
			}
			int index = line.indexOf(':');
			if (index <= 0 || line.indexOf('\r') >= 0) {
				throw new MalformedRequestException("Bad header line.");
			}
			String key = line.substring(0, index);
			if (key.indexOf(' ') >= 0 || key.indexOf('\t') >= 0) {
				throw new MalformedRequestException("Bad header name.");
			}
			key = key.toLowerCase(Locale.ROOT);
			String value = line.substring(index + 1).trim();
			if (key.equals(TRANSFER_ENCODING)) {
				throw new MalformedRequestException("Transfer-Encoding is not supported.");
			}
			if (key.equals(CONTENT_LENGTH)) {
				if (value.isEmpty() || value.length() > 18 || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
					throw new MalformedRequestException("Bad Content-Length.");
				}
				long cl = Long.parseLong(value);
				if (contentLength >= 0 && cl != contentLength) {
					throw new MalformedRequestException("Conflicting Content-Length headers.");
				}
				contentLength = cl;
			}
			headers.put(key, value);
		}
	}

	/**
	 * Adds the name=value pairs of a query string or urlencoded body to {@code request} and returns {@code count}
	 * plus the number of '&'-separated tokens seen (pairs without '=' count too). More than {@link #MAX_PARAMS} in
	 * total throws {@link RequestTooBigException}: colliding keys make each Hashtable insert linear. Malformed
	 * percent-encoding throws {@link MalformedRequestException}.
	 */
	static int extractParameters(String line, Hashtable<String, String> request, int count) throws IOException {
		StringTokenizer st = new StringTokenizer(line, "&");

		String key = null;
		String value = null;
		while (st.hasMoreElements()) {
			String parameter = st.nextToken();
			if (++count > MAX_PARAMS) {
				throw new RequestTooBigException("More than " + MAX_PARAMS + " parameters.");
			}
			int index = parameter.indexOf("=");
			if (index < 0) {
				continue;
			}
			try {
				key = URLDecoder.decode(parameter.substring(0, index), StandardCharsets.UTF_8);
				value = URLDecoder.decode(parameter.substring(index + 1), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				throw new MalformedRequestException("Bad percent-encoding in a parameter.");
			}

			request.put(key, value);
		}
		return count;
	}

	private void readPostBody(HTTPRequest request, long contentLength, int params, LineInputStream input, BufferedOutputStream out)
			throws IOException {
		if (contentLength < 0) {
			contentLength = 0;   // no Content-Length and (refused) no Transfer-Encoding: no body
		}

		Log.info("CONTENT_LENGTH: " + contentLength);
		if (contentLength >= MAX_REQUEST_LENGTH) {
			throw new RequestTooBigException("Request size bigger than: " + MAX_REQUEST_LENGTH);
		}

		// HTTP/1.1 clients such as curl announce larger bodies with "Expect: 100-continue" and wait for this go-ahead.
		String expect = request.headers.get("expect");
		if (expect != null && expect.toLowerCase().contains("100-continue")) {
			out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
		}

		// The body as exactly Content-Length raw bytes (Content-Length counts bytes).
		byte[] body = input.readFully((int) contentLength);

		// content-type=multipart/form-data; boundary=----------Rjt4DIrIlBxobYSOFrQCdr
		String contentType = (String) request.headers.get(CONTENT_TYPE);
		if (contentType != null && contentType.trim().toLowerCase().startsWith(CONTENT_TYPE_MULTIPART_FDATA)) {
			Multipart.parse(body, contentType, request);
			Log.info("multipart: " + request.parameters.size() + " fields, " + request.uploads.size() + " files");
			return;
		}

		request.content = new String(body, StandardCharsets.UTF_8).toCharArray();

		if (contentType != null && contentType.trim().startsWith(CONTENT_TYPE_FORM_URLENCODED)) {
			extractParameters(new String(request.content), request.parameters, params);
		}
	}

	/** a response header name or value with CR and LF removed, so nothing a servlet sets can split the header block */
	static String headerSafe(String s) {
		return s == null ? "" : s.replace("\r", "").replace("\n", "");
	}

	/**
	 * The Content-Type value for a servlet/JSP response: "type; charset=enc" for textual types (text/*, or anything
	 * json, javascript or xml) that do not name a charset already; the type alone otherwise (image/png).
	 */
	static String contentTypeWithCharset(String contentType, String encoding) {
		String type = (contentType == null || contentType.isBlank()) ? "text/html" : contentType.trim();
		String lower = type.toLowerCase(Locale.ROOT);
		boolean textual = lower.startsWith("text/") || lower.contains("json") || lower.contains("javascript") || lower.contains("xml");
		if (!textual || lower.contains("charset=") || encoding == null || encoding.isBlank()) {
			return type;
		}
		return type + "; charset=" + encoding.trim();
	}

	protected void deliverContent(BufferedOutputStream bufferedoutputstream, HTTPResponse response) throws IOException {
		// HTTP/1.1 — clients reuse the underlying TCP socket between
		// requests, eliminating per-call TLS handshakes and the trailing
		// Connection: close that was stalling large image bodies on
		// some Android TCP stacks. The actual keep-alive vs close
		// decision is encoded in the Connection response header (set by
		// the dispatch layer based on the request's keep-alive bit).
		// Config.keepConnectionAlive=false flips us back to HTTP/1.0.
		String httpVersion = mConfig.keepConnectionAlive ? "HTTP/1.1" : "HTTP/1.0";
		bufferedoutputstream.write(
				(httpVersion + " " + response.returnCode().intValue() + " " + response.returnCode().toString()
				+ "\r\nDate: " + httpDate(System.currentTimeMillis()) + "\r\n")
				.getBytes());
		bufferedoutputstream.write(("Server: Tinyserver/1.0\r\nContent-Type: "
				+ headerSafe(contentTypeWithCharset(response.getContentType(), response.getCharacterEncoding())) + "\r\n").getBytes());

		if (!response.getHeaders().containsKey(ACCESS_CONTROL_ALLOW_ORIGIN)) {
			response.getHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		}
		String connection = response.getHeaders().get("Connection");
		if (!mConfig.keepConnectionAlive || (connection != null && connection.toLowerCase(Locale.ROOT).contains("close"))) {
			closeAfterResponse.set(Boolean.TRUE);   // we told the client this connection ends here
		}
		for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
			bufferedoutputstream.write((headerSafe(entry.getKey()) + ": " + headerSafe(entry.getValue()) + "\r\n").getBytes());
		}

		int bodyBytes = response.getOutputStream().size();
		bufferedoutputstream.write(("Content-Length: " + bodyBytes + "\r\n").getBytes());
		bufferedoutputstream.write(("Last-modified: "
				+ httpDate(System.currentTimeMillis())
				+ "\r\n" + "\r\n").getBytes());

		long writeStartNs = System.nanoTime();
		bufferedoutputstream.write(response.getOutputStream().toByteArray());
		bufferedoutputstream.flush();
		long writeMs = (System.nanoTime() - writeStartNs) / 1_000_000L;
		// Single line per response, easy to grep. If `write` is multi-second
		// for a body in the hundreds of KB, the bottleneck is the wire (TCP
		// receive window on the client); if `bodyBytes` is huge for a poll
		// response, the bottleneck is upstream (servlet+DB).
		Log.info("[DELIVER] bodyBytes=" + bodyBytes + " write=" + writeMs + "ms");
	}

	protected void deliverInternalContent(String file, BufferedOutputStream bufferedoutputstream) throws IOException {
		InputStream fis = null;
		try {
			fis = Config.class.getResourceAsStream(file);
			if (fis == null) {
				sendError(bufferedoutputstream, 404, "File not found.");
				return;
			}
			long transferSize = fis.available();
			String internalMime = MimeUtils.getMimeType(file);
			sendContentHeader(bufferedoutputstream, 200, internalMime, transferSize,
					System.currentTimeMillis(), cacheControlFor(internalMime));

			byte[] buffer = new byte[2048];
			int bytesRead = 0;
			while ((bytesRead = fis.read(buffer)) > 0) {
				bufferedoutputstream.write(buffer, 0, bytesRead);
			}
			bufferedoutputstream.flush();
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
	}

	void deliverLocalContent(BufferedOutputStream bufferedoutputstream, String fileName, String htmlPath, HTTPRequest request)
			throws IOException {

		// When cache_html is on at boot, HtmlCache holds an in-memory
		// snapshot of the whole html_dir and the disk path is
		// intentionally unreachable: a cache miss is treated as a
		// probe and returns the same 403 as the traversal-guard branch
		// below. This matters for two reasons:
		//   1. It matches the traversal guard's response, so a scanner
		//      probing /etc/passwd and a scanner probing /random.html
		//      both see the same 403 — no oracle.
		//   2. It guarantees "cache_html=true" actually means "no
		//      filesystem lookups on the hot path"; a file added to
		//      html_dir after boot won't sneak into serving without
		//      an explicit /reloadcache call.
		// The 403 is immediate: a sleep here would only hold the
		// proxy's worker (a tarpit amplifies, it does not protect);
		// ProbeFilter's 4xx count is the rate control.
		HtmlCache cache = HtmlCache.getInstance();
		if (cache != null) {
			HtmlCache.Entry entry = cache.lookup(fileName);
			if (entry == null) {
				Log.info("Request from " + currentClientIp.get()
						+ " 403 (cache miss): " + Log.sanitize(fileName));
				sendError(bufferedoutputstream, 403, "Forbidden.");
				return;
			}
			String cc = cacheControlFor(entry.mimeType);
			if (request.headers.containsKey(CMD_GET)
					&& notModifiedSince(request.headers.get(IF_MODIFIED_SINCE), entry.lastModified)) {
				sendNotModifiedContentHeader(bufferedoutputstream, entry.mimeType, entry.lastModified, cc);
				bufferedoutputstream.flush();
				return;
			}
			sendContentHeader(bufferedoutputstream, 200, entry.mimeType,
					entry.data.length, entry.lastModified, cc);
			bufferedoutputstream.write(entry.data);
			bufferedoutputstream.flush();
			return;
		}

		File file = new File(htmlPath + fileName);
		if (!file.exists() || !file.getCanonicalPath().startsWith(htmlPath + File.separator) || file.isDirectory()) {
			Log.info("Request from " + currentClientIp.get()
				+ " 403: " + Log.sanitize(fileName));
			sendError(bufferedoutputstream, 403, "Forbidden.");
			return;
		}
		if (!file.canRead()) {
			sendError(bufferedoutputstream, 404, "Not found: " + fileName);
			return;
		}

//		if (request.headers.containsKey(IF_MODIFIED_SINCE)) {
//			Log.debug(IF_MODIFIED_SINCE + ": " + fileName);
//		}
		String diskMime = MimeUtils.getMimeType(fileName);
		String diskCc = cacheControlFor(diskMime);
		if (request.headers.containsKey(CMD_GET)
				&& notModifiedSince(request.headers.get(IF_MODIFIED_SINCE), file.lastModified())) {
			Log.debug("Unmodified: " + fileName);
			sendNotModifiedContentHeader(bufferedoutputstream, diskMime, file.lastModified(), diskCc);
			bufferedoutputstream.flush();
			return;
		}

		long transferSize = file.length();

		sendContentHeader(bufferedoutputstream, 200, diskMime, transferSize, file.lastModified(), diskCc);
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			byte[] buffer = new byte[2048];
			int bytesRead = 0;
			while ((bytesRead = fis.read(buffer)) > 0) {
				bufferedoutputstream.write(buffer, 0, bytesRead);
			}
			bufferedoutputstream.flush();
		} finally {
			if (fis != null) {
				fis.close();
			}
		}
	}

	protected static final void sendError(BufferedOutputStream bufferedoutputstream, int errCode, String errMsg) {
		// Attribute the miss to the source IP via ProbeFilter — enough
		// 4xx responses inside the rolling window auto-bans the caller.
		// Only count 4xx; 5xx is our fault and shouldn't penalise the
		// client.
		if (errCode >= 400 && errCode < 500) {
			ProbeFilter.getInstance().recordError(currentClientIp.get());
		}
		closeAfterResponse.set(Boolean.TRUE);   // the stream is closed below
		try {
			byte[] msg = errMsg.getBytes(StandardCharsets.UTF_8);
			sendContentHeader(bufferedoutputstream, errCode, "text/plain", msg.length, System.currentTimeMillis());
			bufferedoutputstream.write(msg);
			bufferedoutputstream.flush();
			bufferedoutputstream.close();
		} catch (IOException ignored) {
		}
	}

	public ServerEngine(int port) throws IOException {
		mServerSocket = new ServerSocket(port);
		mPort = port;

		setRunning(true);
	}

	public ServerEngine(String configPath) throws Exception {
		mConfig.init(configPath);
		mPort = mConfig.getPort();
		mServerSocket = new ServerSocket(mPort);
		MAX_REQUEST_LENGTH = mConfig.mMaxReqLen;
		staticHeaders = mConfig.crossOriginIsolation ? ISOLATION_HEADERS : "";

		Log.info("MAX_REQUEST_LENGTH :" + MAX_REQUEST_LENGTH);
		Log.info("PORT :" + mPort);

		setRunning(true);
	}

	private synchronized boolean isRunning() {
		return isRunning;
	}

	protected synchronized void setRunning(boolean isRunning) {
		this.isRunning = isRunning;
	}

}
