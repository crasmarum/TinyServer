package com.tinyserver.servlet;

import javax.tools.*;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class JspCompiler {
	private static String JSP_CP = "server.jar";
	private static String jspDirectory;
	
	private static Map<String, Servlet> jspServlets = new HashMap<String, Servlet>();
	
	public static String getJSP_CP() {
		return JSP_CP;
	}

	public static void setJSP_CP(String jSP_CP) {
		JSP_CP = jSP_CP;
	}
	
	public static void addServlet(String key, Servlet servlet) {
		jspServlets.put(key, servlet);
	}
	
	public static Servlet getServlet(String key) {
		Servlet servlet = jspServlets.get(key);
		if (servlet != null) {
			try {
				servlet = servlet.getClass().getDeclaredConstructor().newInstance();
			} catch (Exception e) {
				System.err.println("Cannot create JSP servlet: " + e.getMessage());
				return null;
			}
		}
		return servlet;
	}
	
	public static Object getLock() {
		return jspServlets;
	}

	private String generateSourceCode(String className, File jspFile) {
        String content;
		try {
			content = Files.readString(jspFile.toPath());
		} catch (IOException e) {
			return null;
		}
        
        StringBuilder javaSource = new StringBuilder();
        StringBuilder declarations = new StringBuilder();
        StringBuilder serviceMethod = new StringBuilder();

        // 1. Setup Boilerplate
        javaSource.append("import java.io.*;\n\n");

        javaSource.append("public class "
        		+ className
        		+ " extends com.tinyserver.servlet.Servlet {\n");

        serviceMethod.append("  @Override\n");
        serviceMethod.append("  public void service(com.tinyserver.servlet.HTTPRequest request, "
        		                                 + "com.tinyserver.servlet.HTTPResponse response) {\n");
        serviceMethod.append("      PrintWriter out = new PrintWriter(response.getOutputStream());\n");
        serviceMethod.append("      response.setContentType(\"text/html\");\n");

        // 2. Simple Regex Parsing Logic
        // This regex finds <%! ... %>, <%= ... %>, and <% ... %>
        Pattern pattern = Pattern.compile("<%(!|=)?(.*?)%>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        
        int lastEnd = 0;
        while (matcher.find()) {
            // Write everything BEFORE the JSP tag as a String literal
            String textMatch = content.substring(lastEnd, matcher.start());
            if (!textMatch.isEmpty()) {
                serviceMethod.append("      out.write(\"")
                             .append(textMatch.replace("\\", "\\\\")
                                              .replace("\"", "\\\"")
                                              .replace("\n", "\\n")
                                              .replace("\r", "\\r")
                                              .replace("\t", "\\t"))
                             .append("\");\n");
            }

           String type = matcher.group(1); // !, =, or null
           String code = matcher.group(2).trim();

            if ("!".equals(type)) {
                // Declaration: Goes outside the method
                declarations.append("  ").append(code).append("\n");
            } else if ("=".equals(type)) {
                // Expression: Becomes out.print()
                serviceMethod.append("      out.print(").append(code).append(");\n");
            } else {
                // Scriptlet: Raw Java code inside the method
                serviceMethod.append("    ").append(code).append("\n");
            }
            lastEnd = matcher.end();
        }

        // Write the remaining static HTML after the last tag
        if (lastEnd < content.length()) {
            serviceMethod.append("      out.write(\"")
                         .append(content.substring(lastEnd).replace("\\", "\\\\")
                                                           .replace("\"", "\\\"")
                                                           .replace("\n", "\\n")
                                                           .replace("\r", "\\r")
                                                           .replace("\t", "\\t"))
                         .append("\");\n");
        }
        serviceMethod.append("      out.flush();");

        // 3. Assemble the pieces
        javaSource.append(declarations);
        javaSource.append(serviceMethod);
        javaSource.append("  }\n}");

        return javaSource.toString();
	}
	
	private Servlet compileServlet(final String className, final String sourceCode) throws Exception {
		
        // 1. Setup the compiler
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
        	Log.error("No Java compiler found.");
        	return null;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        
        // 2. Map to store the resulting byte code
        Map<String, byte[]> classBytes = new HashMap<>();
        
        // 3. Custom FileManager to intercept the compiler output
        JavaFileManager fileManager = new ForwardingJavaFileManager<StandardJavaFileManager>(
                compiler.getStandardFileManager(diagnostics, null, null)) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, 
                                                       JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() throws IOException {
                                super.close();
                                classBytes.put(className, toByteArray());
                            }
                        };
                    }
                };
            }
        };
        
        // 4. Wrap source code in a JavaFileObject
        JavaFileObject sourceFile = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return sourceCode;
            }
        };
        
        List<String> options = new ArrayList<>();
        options.add("-classpath");
        options.add(getJSP_CP());
        
        // 5. Run the compiler task
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, Collections.singletonList(sourceFile));
        
        if (task.call()) {
            // 6. Define a ClassLoader to load the bytes
            ClassLoader loader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] b = classBytes.get(name);
                    if (b == null) return super.findClass(name);
                    return defineClass(name, b, 0, b.length);
                }
            };

            // 7. Create instance
            Class<?> clazz = loader.loadClass(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            return (Servlet)instance;
        } else {
            diagnostics.getDiagnostics().forEach(System.err::println);
            
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                System.out.format("Error on line %d in %s%n",
                    diagnostic.getLineNumber(),
                    diagnostic.getSource().toUri());
                System.out.println(diagnostic.getMessage(null));
            }
            
    		return null;
        }
	}
	
	public Servlet getServlet(File jspFile) {
		Log.info("Compiling JSP file: " + jspFile.getAbsolutePath());
		String className = jspFile.getName().substring(0, jspFile.getName().lastIndexOf('.'));
		className += Math.abs(jspFile.getName().hashCode());
		
		Log.info("ClassName: " + className);
		String sourceCode = generateSourceCode(className, jspFile);
//		System.out.println("SourceCode: " + sourceCode);
		if (sourceCode == null) {
			return null;
		}
		
		try {
			return compileServlet(className, sourceCode);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public static String getDirectory() {
		return jspDirectory;
	}

	public static void setDirectory(String jspPath) {
		jspDirectory = jspPath;
	}
}
