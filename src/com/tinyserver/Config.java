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
	public static final String JANITOR_SCHEDULE_MINUTES = "janitor_schedule_minutes";
	public static final String LOG_FILE = "log_file_dir";
	public static final String HTML_DIR = "html_dir";
	
	public static final String JSP_DIR = "jsp_dir";
	public static final String ALWAYS_COMPILE_JSPS = "always_compile_jsps";
	public static final String JSP_CLASS_PATH = "jsp_class_path";
	
	Map<String, String> mPathToClass = new HashMap<>();
	Map<String, String> mInject = new HashMap<>();
	Map<String, Object> mStaticInject = new HashMap<>();
	int mPort = 8080;
	int mMaxReqLen = 100000000;
	boolean useVirtualThreads = false;
	int janitorScheduleMinutes = 12 * 60;
	String mLogFile = null;
	boolean alwaysCompileJsps = false;
	
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
		List<String> content = Files.readAllLines(Paths.get(filePath));
		for (String line : content) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] tokens = line.split("\\s+");
			if (LOG_FILE.equals(tokens[0]) && tokens.length == 2) {
				mLogFile = tokens[1];
				Log.setLogFileDir(mLogFile);
			} else if (MAP.equals(tokens[0]) && tokens.length == 3) {
				addMapping(tokens[1], tokens[2]);
			} else if (STATIC_INJECT.equals(tokens[0]) && tokens.length == 3) {
				addStaticInject(tokens[1], tokens[2]);
			} else if (INJECT.equals(tokens[0]) && tokens.length == 3) {
				addInject(tokens[1], tokens[2]);
			} else if (PORT.equals(tokens[0]) && tokens.length == 2) {
				mPort = Integer.parseInt(tokens[1]);
			} else if (MAX_REQ_LEN.equals(tokens[0]) && tokens.length == 2) {
				mMaxReqLen = Integer.parseInt(tokens[1]);
			} else if (USE_VIRT_THREADS.equals(tokens[0]) && tokens.length == 2) {
				useVirtualThreads = Boolean.parseBoolean(tokens[1]);
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
			} else if (LOG_FILE.equals(tokens[0]) && tokens.length == 2) {
				mLogFile = tokens[1];
				Log.setLogFileDir(mLogFile);
			} else if (HTML_DIR.equals(tokens[0]) && tokens.length == 2) {
				TinyServer.setHtmlDirectory(tokens[1]);
				Log.info("HTML_DIR: " + TinyServer.getHtmlDirectory());
			} else if (tokens.length > 1) {
				Servlet.addToServletContext(tokens[0], tokens[1]);
			}
		}
	}

	public int getPort() {
		return mPort;
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
