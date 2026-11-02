package com.tinyserver;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tinyserver.servlet.Inject;
import com.tinyserver.servlet.JspCompiler;
import com.tinyserver.servlet.Log;
import com.tinyserver.servlet.Servlet;

public class Config {
	public static final String MAP = "map";
	public static final String PORT = "port";
	public static final String INJECT = "inject";
	public static final String STATIC_INJECT = "static_inject";
	public static final String MAX_REQ_LEN = "max_req_len";
	public static final String USE_VIRT_THREADS = "use_virtual_threads";
	public static final String KEEP_CONNECTION_ALIVE = "keep_connection_alive";
	public static final String HEALTHZ_TOKEN = "healthz_token";
	public static final String DB_TOKEN = "db_token";
	public static final String JANITOR_SCHEDULE_MINUTES = "janitor_schedule_minutes";
	public static final String LOG_FILE = "log_file_dir";
	public static final String HTML_DIR = "html_dir";
	/** Boolean directive — if true, {@code deliverLocalContent} serves
	 *  static content from an in-memory snapshot of {@code html_dir}
	 *  taken at startup. See {@link HtmlCache}. */
	public static final String CACHE_HTML = "cache_html";
	/** Boolean directive — if true, static responses carry COOP / COEP so pages are cross-origin isolated
	 *  (SharedArrayBuffer, e.g. WebAssembly threads). Every subresource must then be same-origin or CORP-marked. */
	public static final String CROSS_ORIGIN_ISOLATION = "cross_origin_isolation";
	
	public static final String JSP_DIR = "jsp_dir";
	public static final String ALWAYS_COMPILE_JSPS = "always_compile_jsps";
	public static final String JSP_CLASS_PATH = "jsp_class_path";
	
	Map<String, String> mPathToClass = new HashMap<>();
	Map<String, String> mInject = new HashMap<>();
	Map<String, Object> mStaticInject = new HashMap<>();
	int mPort = 8080;
	int mMaxReqLen = 100000000;
	boolean useVirtualThreads = false;
	/**
	 * When true, the server speaks HTTP/1.1 + per-request keep-alive: a single
	 * accepted socket may serve many requests in a loop until the client
	 * sends Connection: close (or the SO_TIMEOUT idle window elapses).
	 * Saves TLS handshakes and works around end-of-stream pacing issues on
	 * some Android TCP stacks for large bodies.
	 *
	 * <p>Set to false to revert to the legacy HTTP/1.0 + close-after-each-
	 * response behaviour. Useful when the kept-alive working set pushes
	 * heap above the available headroom on a small VPS.
	 */
	boolean keepConnectionAlive = true;
	/**
	 * Shared secret required as {@code ?token=…} on {@code /healthz}.
	 * Null/empty means the endpoint is effectively disabled — every
	 * call returns 403. Set via the {@code healthz_token} config line.
	 */
	String healthzToken = null;
	/**
	 * Server-side secret ("pepper") an application can mix into hashes of
	 * tokens it stores, e.g. {@code Base64(SHA-256(db_token || token))},
	 * so a copy of its database alone cannot verify tokens. TinyServer
	 * only reads it from the config; null when not set.
	 */
	String dbToken = null;
	/**
	 * Process-wide handle to the active Config so non-Servlet code
	 * (e.g. a storage class) can read typed
	 * fields without depending on the servlet-context catch-all. Set
	 * by {@link #init(String)} at startup; tests that don't call
	 * {@code init} leave it null.
	 */
	private static volatile Config INSTANCE = null;
	int janitorScheduleMinutes = 12 * 60;
	String mLogFile = null;
	boolean alwaysCompileJsps = false;
	/** Value of the {@code cache_html} directive. Read at end of
	 *  {@link #init} to decide whether to slurp the html_dir into
	 *  memory. Default false so existing configs keep disk-serving. */
	boolean cacheHtml = false;
	/** Value of the {@code cross_origin_isolation} directive; default false. */
	boolean crossOriginIsolation = false;
	
	void addMapping(String path, String servletClass) throws ClassNotFoundException {
		Class.forName(servletClass);
		mPathToClass.put(path, servletClass);
		Log.info("Mappedd " + path + " to: " + servletClass);
	}

	void addInject(String derivedClass, String baseClass) throws ClassNotFoundException {
		Class<?> derClazz = Class.forName(derivedClass);
		Class<?> baseClazz  = Class.forName(baseClass);
		if (!baseClazz.isAssignableFrom(derClazz)) {
			throw new ClassNotFoundException(derivedClass + " not implementing " + baseClass);
		}

		mInject.put(baseClass, derivedClass);
		Log.info("Injecting dependency " + derivedClass + " into: " + baseClass);
	}
	
	private void addStaticInject(String derivedClass, String baseClass) throws Exception {
		Class<?> derClazz = Class.forName(derivedClass);
		Class<?> baseClazz  = Class.forName(baseClass);
		if (!baseClazz.isAssignableFrom(derClazz)) {
			throw new ClassNotFoundException(derivedClass + " not implementing " + baseClass);
		}
		
		Object newInstance = derClazz.getDeclaredConstructor().newInstance();
		setInjectedFields(newInstance);
		mStaticInject.put(baseClass, newInstance);
		Log.info("Static injecting dependency " + derivedClass + " into: " + baseClass);
	}
	
	public Config() {
	}
	
	public void init(String filePath) throws Exception {
		INSTANCE = this;
		List<String> content = Files.readAllLines(Paths.get(filePath));

		// Two-pass parse: first populate all state (scalar config + servlet
		// context + log/html/jsp dirs), then run the directives that
		// instantiate or wire other classes (map / static_inject / inject).
		// This guarantees any class constructed by static_inject sees
		// a fully-populated Config — e.g. SQLiteDataStore.init's
		// hash_auth_token backfill in addColumnIfMissing reads
		// Config.getInstance().getDbToken(), and would see a null pepper
		// if static_inject happened to appear above db_token in the file.
		// Single-pass parsing made the config file order load-bearing in
		// non-obvious ways; the split removes that fragility.
		for (String line : content) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] tokens = line.split("\\s+");
			if (MAP.equals(tokens[0]) || STATIC_INJECT.equals(tokens[0])
					|| INJECT.equals(tokens[0])) {
				continue;
			}
			applyStateLine(tokens);
		}

		for (String line : content) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] tokens = line.split("\\s+");
			if (MAP.equals(tokens[0]) && tokens.length == 3) {
				addMapping(tokens[1], tokens[2]);
			} else if (STATIC_INJECT.equals(tokens[0]) && tokens.length == 3) {
				addStaticInject(tokens[1], tokens[2]);
			} else if (INJECT.equals(tokens[0]) && tokens.length == 3) {
				addInject(tokens[1], tokens[2]);
			}
		}

		// After both passes: if cache_html is on, snapshot html_dir into
		// memory. Done here (not in applyStateLine) so we don't care
		// which line of the config file appeared first — cache_html and
		// html_dir can be listed in any order.
		if (cacheHtml) {
			String htmlDir = TinyServer.getHtmlDirectory();
			if (htmlDir == null) {
				throw new IllegalStateException(
						"cache_html=true requires html_dir to be set");
			}
			HtmlCache.load(htmlDir);
		}
	}

	/**
	 * Handles one non-directive line — sets a typed Config field, mutates
	 * a static helper (Log / JspCompiler / TinyServer.html_dir), or falls
	 * back to {@link Servlet#addToServletContext} for the catch-all
	 * key/value form. Pulled out of {@link #init} so the two-pass loop
	 * doesn't duplicate the whole switch chain.
	 */
	private void applyStateLine(String[] tokens) throws Exception {
		if (LOG_FILE.equals(tokens[0]) && tokens.length == 2) {
			mLogFile = tokens[1];
			Log.setLogFileDir(mLogFile);
		} else if (PORT.equals(tokens[0]) && tokens.length == 2) {
			mPort = Integer.parseInt(tokens[1]);
		} else if (MAX_REQ_LEN.equals(tokens[0]) && tokens.length == 2) {
			mMaxReqLen = Integer.parseInt(tokens[1]);
		} else if (USE_VIRT_THREADS.equals(tokens[0]) && tokens.length == 2) {
			useVirtualThreads = Boolean.parseBoolean(tokens[1]);
		} else if (KEEP_CONNECTION_ALIVE.equals(tokens[0]) && tokens.length == 2) {
			keepConnectionAlive = Boolean.parseBoolean(tokens[1]);
			Log.info("keep_connection_alive: " + keepConnectionAlive);
		} else if (HEALTHZ_TOKEN.equals(tokens[0]) && tokens.length == 2) {
			healthzToken = tokens[1];
			// Deliberately don't log the token itself.
			Log.info("healthz_token: loaded (len=" + healthzToken.length() + ")");
		} else if (DB_TOKEN.equals(tokens[0]) && tokens.length == 2) {
			dbToken = tokens[1];
			// Don't log the pepper itself — emit a presence signal
			// only, mirroring healthz_token's logging policy.
			Log.info("db_token: loaded (len=" + dbToken.length() + ")");
		} else if (JANITOR_SCHEDULE_MINUTES.equals(tokens[0]) && tokens.length == 2) {
			janitorScheduleMinutes = Integer.parseInt(tokens[1]);
		} else if (JSP_DIR.equals(tokens[0]) && tokens.length == 2) {
			JspCompiler.setDirectory(tokens[1]);
			Log.info("JSP_DIR: " + JspCompiler.getDirectory());
		} else if (ALWAYS_COMPILE_JSPS.equals(tokens[0]) && tokens.length == 2) {
			alwaysCompileJsps = Boolean.parseBoolean(tokens[1]);
			Log.info("Always compile jsps: " + alwaysCompileJsps);
		} else if (JSP_CLASS_PATH.equals(tokens[0]) && tokens.length == 2) {
			JspCompiler.setJSP_CP(tokens[1]);
			Log.info("JSP_CLASS_PATH: " + JspCompiler.getJSP_CP());
		} else if (HTML_DIR.equals(tokens[0]) && tokens.length == 2) {
			TinyServer.setHtmlDirectory(tokens[1]);
			Log.info("HTML_DIR: " + TinyServer.getHtmlDirectory());
		} else if (CACHE_HTML.equals(tokens[0]) && tokens.length == 2) {
			cacheHtml = Boolean.parseBoolean(tokens[1]);
			Log.info("cache_html: " + cacheHtml);
		} else if (CROSS_ORIGIN_ISOLATION.equals(tokens[0]) && tokens.length == 2) {
			crossOriginIsolation = Boolean.parseBoolean(tokens[1]);
			Log.info("cross_origin_isolation: " + crossOriginIsolation);
		} else if (tokens.length > 1) {
			Servlet.addToServletContext(tokens[0], tokens[1]);
		}
	}

	public int getPort() {
		return mPort;
	}
	
	/**
	 * Returns the shared secret expected as {@code ?token=…} on the
	 * {@code /healthz} endpoint, or {@code null} if none is configured
	 * (in which case the endpoint refuses every request).
	 */
	public String getHealthzToken() {
		return healthzToken;
	}

	/**
	 * Returns the server-side pepper for {@code hash_auth_token}, or
	 * {@code null} if no {@code db_token} line is configured.
	 */
	public String getDbToken() {
		return dbToken;
	}

	/**
	 * Returns the Config instance populated by {@link #init(String)}
	 * during server startup, or {@code null} if init hasn't run (tests
	 * that construct Config directly, or pre-init reflective probes).
	 */
	public static Config getInstance() {
		return INSTANCE;
	}

	public boolean isAlwaysCompileJsps() {
		return alwaysCompileJsps;
	}
	
	public Servlet getServlet(String path) {
		String clazz = mPathToClass.get(path);
		if (clazz == null) {
			return null;
		}
		
		try {
			Object servlet = (Servlet) Class.forName(clazz).getDeclaredConstructor().newInstance();
			setInjectedFields(servlet);
			return (Servlet) servlet;
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
		} 
	}

	private void setInjectedFields(Object obj) throws IllegalAccessException, Exception {
		Field[] fields = obj.getClass().getDeclaredFields();
		for (Field fld : fields) {
			if (fld.isAnnotationPresent(Inject.class)) {
				fld.setAccessible(true);
				fld.set(obj, getInstance(fld.getType().getCanonicalName()));
			}
		}
	}

	private Object getInstance(String baseClass) throws Exception {
		Object obj = mStaticInject.get(baseClass);
		return obj != null ? obj : Class.forName(mInject.get(baseClass)).getDeclaredConstructor().newInstance();
	}
	
	public Object getInstanceOrNull(String baseClass) {
		if (mStaticInject.get(baseClass) != null) {
			return mStaticInject.get(baseClass);
		} else if (mInject.get(baseClass) != null) {
			try {
				Object newInstance = Class.forName(mInject.get(baseClass)).getDeclaredConstructor().newInstance();
				setInjectedFields(newInstance);
				return newInstance;
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		} else {
			return null;
		}
	}
}
