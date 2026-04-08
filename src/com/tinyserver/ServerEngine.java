package com.tinyserver;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Hashtable;
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

	private final String METHOD = "___method_";

	private final ServerSocket mServerSocket;
	private final int mPort;
	private boolean isRunning = false;
	private JanitorThread janitor;

	protected Config mConfig = new Config();
	protected int MAX_REQUEST_LENGTH = 100000000; // 100M

	public int getRunningPort() {
		return mPort;
	}

	protected static void sendContentHeader(BufferedOutputStream bufferedoutputstream, int code, String contentType,
			long contentLength, long lastModified) throws IOException {

		bufferedoutputstream.write(("HTTP/1.0 " + code 
				+ " OK\r\n" + "Date: " + (new Date()).toString() + "\r\n"
				+ "Server: Tinyserver/1.0\r\n" 
				+ "Content-Type: " + contentType + "\r\n"
				+ "Connection: close\r\n"
				+ (contentLength == -1L ? "" : "Content-Length: " + contentLength + "\r\n") 
				+ "Last-modified: " + (new Date(lastModified)).toString() + "\r\n" + "\r\n").getBytes());
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
		janitor.stop();

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

				// TODO: implement blocking and rate limiting per address
				clientSocket.setSoTimeout(30000);
				Log.info("Request accepted for: " + clientSocket.getInetAddress().getHostAddress());

				final InputStream clientSocketIn = clientSocket.getInputStream();
				final OutputStream clientSocketOut = clientSocket.getOutputStream();

				if (mConfig.useVirtualThreads) {
					Thread.startVirtualThread(() -> {
						Servlet.noVirtulThreads.incrementAndGet();
						try {
							doIO(clientSocket, clientSocketIn, clientSocketOut);
						} finally {
							Servlet.noVirtulThreads.decrementAndGet();
						}
					});
				} else {
					new Thread() {
 						public void run() {
 							doIO(clientSocket, clientSocketIn, clientSocketOut);
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

	private void doIO(final Socket clientSocket, final InputStream clientSocketIn, final OutputStream clientSocketOut) {
		BufferedReader bufferedreader = null;
		BufferedOutputStream bufferedoutputstream = null;
		try {
			bufferedreader = new BufferedReader(
					new InputStreamReader(clientSocketIn, Charset.forName("utf-8")));
			bufferedoutputstream = new BufferedOutputStream(clientSocketOut);
			performHTMLprotocol(bufferedreader, bufferedoutputstream);
		} catch (SocketTimeoutException e) {
			sendError(bufferedoutputstream, 500, "Timeout: " + e.getMessage());
			Log.info("SocketTimeoutException");
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
			if (bufferedreader != null) {
				try {
					bufferedreader.close();
				} catch (IOException ignored) {
				}
			}
			try {
				clientSocket.close();
			} catch (IOException ignored) {
			}
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

	final void performHTMLprotocol(BufferedReader bufferedreader, BufferedOutputStream bufferedoutputstream)
			throws IOException {
		String line = bufferedreader.readLine();

		// TODO: implement HEAD
		if (line == null
				|| (!line.toLowerCase().trim().startsWith("get ") && !line.toLowerCase().trim().startsWith("post "))
				|| !(line.toLowerCase().trim().endsWith(" http/1.0")
						|| line.toLowerCase().trim().endsWith(" http/1.1"))) {

			sendError(bufferedoutputstream, 500, "Invalid Method.");
			return;
		}

		HTTPRequest request = new HTTPRequest(mConfig);
		if (line.toLowerCase().trim().startsWith("get ")) {
			getRequestFromGet(request, line, bufferedreader);
		} else if (line.toLowerCase().trim().startsWith("post ")) {
			getRequestFromPost(request, line, bufferedreader);
		} else {
			sendError(bufferedoutputstream, 500, "Unsupported Method: " + line);
			return;
		}

//		System.out.println(request.toString());
//		System.out.flush();
		execute(request, bufferedoutputstream);
	}

	protected abstract void execute(HTTPRequest request, BufferedOutputStream bufferedoutputstream) throws IOException;

	private void getRequestFromGet(HTTPRequest request, String path, BufferedReader bufferedreader) throws IOException {
		path = path.trim().substring(3, path.length() - 8).trim();
		int index = path.indexOf("?");
		String method = index < 0 ? path : path.substring(0, index);
		request.headers.put(METHOD, method);
		request.setMethod(method);

		path = index < 0 ? path : path.substring(index + 1);
		extractParameters(path, request.parameters);

		while (bufferedreader.ready()) {
			extractParameters(bufferedreader.readLine(), request.headers);
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

	private void getRequestFromPost(HTTPRequest request, String line, BufferedReader bufferedreader)
			throws IOException {
		String currentLine = line.trim().substring(4, line.length() - 8).trim();
		request.headers.put(METHOD, currentLine);
		request.setMethod(currentLine);

		String key = null;
		String value = null;
		while ((currentLine = bufferedreader.readLine()) != null) {
			// System.out.println(currentLine);
			int index = currentLine.indexOf(":");
			if (index < 0)
				break;
			key = currentLine.substring(0, index);
			value = currentLine.substring(index + 1);

			request.headers.put(key.toLowerCase(), value.trim());
		}

		int contentLength = 0;
		if (request.headers.get(CONTENT_LENGTH) != null) {
			contentLength = Integer.parseInt((String) request.headers.get(CONTENT_LENGTH));
		}
		
		Log.info("CONTENT_LENGTH: " + contentLength);
		if (contentLength < 0 || contentLength >= MAX_REQUEST_LENGTH) {
			throw new IOException("Request size too big.");
		}

		// content-type=multipart/form-data; boundary=----------Rjt4DIrIlBxobYSOFrQCdr
		String contentType = (String) request.headers.get(CONTENT_TYPE);
		if (contentType != null && contentType.trim().startsWith(CONTENT_TYPE_MULTIPART_FDATA)) {
			doUpload(request, bufferedreader);
			return;
		}

		int offset = 0;
		char[] buffer = new char[contentLength];
		while (offset < contentLength) {
			int read = bufferedreader.read(buffer, offset, contentLength - offset);
			if (read == -1) {
				throw new IOException("Unexpected EO stream.");
			}
			offset += read;
		}
		request.content = buffer;
		
		if (contentType != null && contentType.trim().startsWith(CONTENT_TYPE_FORM_URLENCODED)) {
			extractParameters(new String(request.content), request.parameters);
		}
	}

	// see https://www.rfc-editor.org/rfc/rfc7578
	private void doUpload(HTTPRequest request, BufferedReader bufferedreader) throws IOException {
		new IOException("Not supported yet.");
	}

	protected void deliverContent(BufferedOutputStream bufferedoutputstream, HTTPResponse response) throws IOException {
		bufferedoutputstream.write(("HTTP/1.0 " + response.returnCode().intValue() + " "
				+ response.returnCode().toString() + "\r\nDate: " + (new Date()).toString() + "\r\n").getBytes());
		bufferedoutputstream.write(("Server: Tinyserver/1.0\r\nContent-Type: " + response.getContentType() + ";"
				+ response.getCharacterEncoding() + "\r\n").getBytes());

		if (!response.getHeaders().containsKey(ACCESS_CONTROL_ALLOW_ORIGIN)) {
			response.getHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		}
		for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
			bufferedoutputstream.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
		}

		bufferedoutputstream.write(("Content-Length: " + response.getOutputStream().size() + "\r\n").getBytes());
		bufferedoutputstream.write(("Last-modified: " + (new Date()).toString() + "\r\n" + "\r\n").getBytes());

		bufferedoutputstream.write(response.getOutputStream().toByteArray());
		bufferedoutputstream.flush();
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
			sendContentHeader(bufferedoutputstream, 200, MimeUtils.getMimeType(file), transferSize, new Date().getTime());

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

	void deliverLocalContent(BufferedOutputStream bufferedoutputstream, String fileName, String htmlPath)
			throws IOException {

		File file = new File(htmlPath + fileName);
		if (!file.exists() || !file.getCanonicalPath().startsWith(htmlPath + File.separator)) {
			sendError(bufferedoutputstream, 404, "Not found: " + fileName);
			return;
		}
		if (!file.canRead()) {
			sendError(bufferedoutputstream, 404, "Not found: " + fileName);
			return;
		}
		long transferSize = file.length();

		sendContentHeader(bufferedoutputstream, 200, MimeUtils.getMimeType(fileName), transferSize, file.lastModified());
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

		setRunning(true);
	}

	public ServerEngine(String configPath) throws Exception {
		mConfig.init(configPath);
		mPort = mConfig.getPort();
		mServerSocket = new ServerSocket(mPort);
		MAX_REQUEST_LENGTH = mConfig.mMaxReqLen;

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