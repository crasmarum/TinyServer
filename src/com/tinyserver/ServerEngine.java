package com.tinyserver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import com.tinyserver.repo.FlatRepository;
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
	public static int DEFAULT_WAIT_TIME_MS = 2500;
	
	private static final SimpleDateFormat FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
	
	static {
		FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	private final String METHOD = "___method_";
	private final String CMD_GET = "___cmd_get_";
	private final String GET = "GET";

	private final ServerSocket mServerSocket;
	private final int mPort;
	private boolean isRunning = false;
	private File mRepository;
	private JanitorThread janitor;

	protected Config mConfig = new Config();
	protected int MAX_REQUEST_LENGTH = 100000000; // 100M

	public int getRunningPort() {
		return mPort;
	}
	
	private static void rateLimit() {
		try {
			Thread.sleep(DEFAULT_WAIT_TIME_MS);
		} catch (InterruptedException ignored) {}
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
		bufferedoutputstream.write(("HTTP/1.0 " + code
				+ " OK\r\n" + "Date: " + FORMAT.format(new Date()).toString() + "\r\n"
				+ "Server: Tinyserver/1.0\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Connection: close\r\n"
				+ (cacheControl != null ? "Cache-Control: " + cacheControl + "\r\n" : "")
				+ staticHeaders
				+ (contentLength == -1L ? "" : "Content-Length: " + contentLength + "\r\n")
				+ "Last-modified: " + FORMAT.format(new Date(lastModified)).toString() + "\r\n" + "\r\n").getBytes());
	}

	protected static void sendNotModifiedContentHeader(BufferedOutputStream bufferedoutputstream, String contentType,
			long lastModified) throws IOException {
		sendNotModifiedContentHeader(bufferedoutputstream, contentType, lastModified, null);
	}

	protected static void sendNotModifiedContentHeader(BufferedOutputStream bufferedoutputstream, String contentType,
			long lastModified, String cacheControl) throws IOException {
		bufferedoutputstream.write(("HTTP/1.0 304  Not Modified\r\n"
				+ "Date: " + FORMAT.format(new Date()).toString() + "\r\n"
				+ "Server: Tinyserver/1.0\r\n"
				+ "Content-Type: " + contentType + "\r\n"
				+ "Connection: close\r\n"
				+ (cacheControl != null ? "Cache-Control: " + cacheControl + "\r\n" : "")
				+ staticHeaders
				+ "Last-modified: " + FORMAT.format(new Date(lastModified)).toString() + "\r\n" + "\r\n").getBytes());
	}

	public void stopServer() {
		setRunning(false);
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
	 * Client IP of the request being served on this thread. Set at the
	 * top of {@link #doIO} and cleared in its finally so that downstream
	 * helpers (notably {@link #sendError}) can attribute 4xx responses
	 * to {@link ProbeFilter} without threading the IP through every
	 * signature.
	 */
	private static final ThreadLocal<String> currentClientIp = new ThreadLocal<>();

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
	 * X-Forwarded-For is a comma-separated list — leftmost is the
	 * original client, each subsequent entry is a hop. Pull the first
	 * one, trimmed.
	 */
	private static String firstIpFromXFF(String xff) {
		if (xff == null) return null;
		int comma = xff.indexOf(',');
		return (comma < 0 ? xff : xff.substring(0, comma)).trim();
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
	 * Parses one request off {@code bufferedreader} and dispatches it via
	 * {@link #execute}. Returns whether the caller should keep the socket
	 * open for another request (HTTP/1.1 keep-alive), or close it.
	 */
	final boolean performHTMLprotocol(LineInputStream input, BufferedOutputStream bufferedoutputstream, String ip)
			throws IOException {
		String line;
		try {
			line = input.readLine();
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

		// HEAD is answered as GET through a stream that drops the body (see HeadOnlyOutputStream), so the status and
		// headers match GET exactly. Link-preview and monitoring bots send HEAD; it used to get a 500.
		boolean head = line.toLowerCase().trim().startsWith("head ");
		if (head) {
			line = "GET " + line.trim().substring(5);
			bufferedoutputstream = new HeadOnlyOutputStream(bufferedoutputstream);
		}

		if ((!line.toLowerCase().trim().startsWith("get ") && !line.toLowerCase().trim().startsWith("post "))
				|| !(line.toLowerCase().trim().endsWith(" http/1.0")
						|| line.toLowerCase().trim().endsWith(" http/1.1"))) {

			sendError(bufferedoutputstream, 500, "Invalid Method.");
			return false;
		}

		boolean http11 = line.toLowerCase().trim().endsWith(" http/1.1");

		HTTPRequest request = new HTTPRequest(mConfig);
		if (line.toLowerCase().trim().startsWith("get ")) {
			getRequestFromGet(request, line, input);
		} else if (line.toLowerCase().trim().startsWith("post ")) {
			getRequestFromPost(request, line, input, bufferedoutputstream);
		} else {
			sendError(bufferedoutputstream, 500, "Unsupported Method: " + line);
			return false;
		}

		// Determine the *effective* client IP for ProbeFilter purposes.
		// When the TCP source is a private-range proxy (AWS ALB, CF in-
		// VPC, etc.) and the proxy supplied X-Forwarded-For, the first
		// XFF entry is the real client. Otherwise the TCP source itself
		// is the client. Re-bind the ThreadLocal so sendError attributes
		// 4xx responses to the same effective IP.
		String effectiveIp = ip;
		if (isPrivateOrLoopbackIp(ip)) {
			String xff = firstIpFromXFF(request.headers.get(X_FORWARDED_FOR));
			if (xff != null && !xff.isEmpty()) {
				effectiveIp = xff;
			}
		}
		currentClientIp.set(effectiveIp);

		// Bot-probe drop: known scanner path → ban the effective IP and
		// close the socket without writing a response. Real users never
		// hit these paths so the false-positive rate is effectively zero.
		if (ProbeFilter.getInstance().isProbePath(request.getMethod())) {
			ProbeFilter.getInstance().ban(
					effectiveIp, "probe path: " + Log.sanitize(request.getMethod()));
			return false;
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

		if (request.headers.get(X_FORWARDED_FOR) != null) {
			Log.info("Request accepted for: " + request.headers.get(X_FORWARDED_FOR));
		} else {
			Log.info("Request accepted for: " + ip);
		}
		execute(request, bufferedoutputstream);
		if (head) {
			bufferedoutputstream.flush();   // push the headers held in the HEAD wrapper through to the socket
		}
		return request.keepAlive;
	}

	protected abstract void execute(HTTPRequest request, BufferedOutputStream bufferedoutputstream) throws IOException;

	private void getRequestFromGet(HTTPRequest request, String path, LineInputStream input) throws IOException {
		path = path.trim().substring(3, path.length() - 8).trim();
		int index = path.indexOf("?");
		String method = index < 0 ? path : path.substring(0, index);
		request.headers.put(METHOD, method);
		request.headers.put(CMD_GET, GET);
		request.setMethod(method);

		path = index < 0 ? path : path.substring(index + 1);
		extractParameters(path, request.parameters);
		
		String key = null;
		String value = null;
		String currentLine = null;
		while ((currentLine = input.readLine()) != null) {
			index = currentLine.indexOf(":");
			if (index < 0)
				break;
			key = currentLine.substring(0, index);
			value = currentLine.substring(index + 1);

			request.headers.put(key.toLowerCase(), value.trim());
//			Log.info(key.toLowerCase() + " -> " + value.trim());
		}
	}

	private void extractParameters(String line, Hashtable<String, String> request) throws UnsupportedEncodingException {
		StringTokenizer st = new StringTokenizer(line, "&");

		String key = null;
		String value = null;
		while (st.hasMoreElements()) {
			String parameter = st.nextToken();
			int index = parameter.indexOf("=");
			if (index < 0) {
				continue;
			}
			key = URLDecoder.decode(parameter.substring(0, index), "UTF-8");
			value = URLDecoder.decode(parameter.substring(index + 1), "UTF-8");

			request.put(key, value);
		}
	}

	private void getRequestFromPost(HTTPRequest request, String line, LineInputStream input, BufferedOutputStream out)
			throws IOException {
		String currentLine = line.trim().substring(4, line.length() - 8).trim();
		request.headers.put(METHOD, currentLine);
		request.setMethod(currentLine);

		String key = null;
		String value = null;
		while ((currentLine = input.readLine()) != null) {
			int index = currentLine.indexOf(":");
			if (index < 0)
				break;
			key = currentLine.substring(0, index);
			value = currentLine.substring(index + 1);

			request.headers.put(key.toLowerCase(), value.trim());
		}

		int contentLength = 0;
		if (request.headers.get(CONTENT_LENGTH) != null) {
			try {
				contentLength = Integer.parseInt(request.headers.get(CONTENT_LENGTH).trim());
			} catch (NumberFormatException e) {
				throw new MalformedRequestException("Bad Content-Length.");
			}
		}

		Log.info("CONTENT_LENGTH: " + contentLength);
		if (contentLength < 0 || contentLength >= MAX_REQUEST_LENGTH) {
			throw new RequestTooBigException("Request size bigger than: " + MAX_REQUEST_LENGTH);
		}

		// HTTP/1.1 clients such as curl announce larger bodies with "Expect: 100-continue" and wait for this go-ahead.
		String expect = request.headers.get("expect");
		if (expect != null && expect.toLowerCase().contains("100-continue")) {
			out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
		}

		// The body as exactly Content-Length raw bytes (Content-Length counts bytes).
		byte[] body = input.readFully(contentLength);

		// content-type=multipart/form-data; boundary=----------Rjt4DIrIlBxobYSOFrQCdr
		String contentType = (String) request.headers.get(CONTENT_TYPE);
		if (contentType != null && contentType.trim().toLowerCase().startsWith(CONTENT_TYPE_MULTIPART_FDATA)) {
			Multipart.parse(body, contentType, request);
			Log.info("multipart: " + request.parameters.size() + " fields, " + request.uploads.size() + " files");
			return;
		}

		request.content = new String(body, StandardCharsets.UTF_8).toCharArray();

		if (contentType != null && contentType.trim().startsWith(CONTENT_TYPE_FORM_URLENCODED)) {
			extractParameters(new String(request.content), request.parameters);
		}
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
				+ "\r\nDate: " + FORMAT.format(new Date()).toString() + "\r\n")
				.getBytes());
		bufferedoutputstream.write(("Server: Tinyserver/1.0\r\nContent-Type: " + response.getContentType() + ";"
				+ response.getCharacterEncoding() + "\r\n").getBytes());

		if (!response.getHeaders().containsKey(ACCESS_CONTROL_ALLOW_ORIGIN)) {
			response.getHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		}
		for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
			bufferedoutputstream.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
		}

		int bodyBytes = response.getOutputStream().size();
		bufferedoutputstream.write(("Content-Length: " + bodyBytes + "\r\n").getBytes());
		bufferedoutputstream.write(("Last-modified: "
				+ FORMAT.format(new Date()).toString()
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
					new Date().getTime(), cacheControlFor(internalMime));

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
		// probe and returns the same rate-limited 403 as the
		// traversal-guard branch below. This matters for two reasons:
		//   1. It matches the traversal guard's response, so a scanner
		//      probing /etc/passwd and a scanner probing /random.html
		//      both see the same 403 with the same delay — no oracle.
		//   2. It guarantees "cache_html=true" actually means "no
		//      filesystem lookups on the hot path"; a file added to
		//      html_dir after boot won't sneak into serving without
		//      an explicit /reloadcache call.
		HtmlCache cache = HtmlCache.getInstance();
		if (cache != null) {
			HtmlCache.Entry entry = cache.lookup(fileName);
			if (entry == null) {
				Log.info("Request from " + request.headers.get(X_FORWARDED_FOR)
						+ " rate limited (cache miss): " + Log.sanitize(fileName));
				rateLimit();
				sendError(bufferedoutputstream, 403, "Forbidden.");
				return;
			}
			String cc = cacheControlFor(entry.mimeType);
			if (request.headers.containsKey(CMD_GET)
					&& request.headers.containsKey(IF_MODIFIED_SINCE)
					&& FORMAT.format(new Date(entry.lastModified)).toString().equals(
							request.headers.get(IF_MODIFIED_SINCE))) {
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
			Log.info("Request from " + request.headers.get(X_FORWARDED_FOR)
				+ " rate limited: " + Log.sanitize(fileName));
			rateLimit();
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
				&& request.headers.containsKey(IF_MODIFIED_SINCE)
				&& FORMAT.format(new Date(file.lastModified())).toString().equals(
						request.headers.get(IF_MODIFIED_SINCE))) {
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
		try {
			sendContentHeader(bufferedoutputstream, errCode, "text/plain", errMsg.length(), System.currentTimeMillis());
			bufferedoutputstream.write(errMsg.getBytes());
			bufferedoutputstream.flush();
			bufferedoutputstream.close();
		} catch (IOException ignored) {
		}
	}

	public ServerEngine(int port) throws IOException {
		mServerSocket = new ServerSocket(port);
		mPort = port;

		mRepository = new File(FlatRepository.REPOSITORY_PATH);

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

	protected File getRepositoryLocation() {
		return mRepository;
	}

	protected String getRepositoryPath() {
		return FlatRepository.REPOSITORY_PATH;
	}

}