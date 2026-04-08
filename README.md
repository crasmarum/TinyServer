<table>
    <tr>
        <td><img src="https://github.com/crasmarum/TinyServer/blob/main/logo.png" width="96">&nbsp;</td>
        <td><h1>TinyServer — Developer Guide</h1></td>
    </tr>
</table>

---

TinyServer is a lightweight Java HTTP server that supports GET and POST requests, servlet-based request handling, minimal JSP templating, JSON parsing and serialization, dependency injection, and pluggable logging. It requires Java 21+ as it uses virtual threads.

---

## Table of Contents

1. [Starting the server](#1-starting-the-server)
2. [Configuration file](#2-configuration-file)
3. [Writing a servlet](#3-writing-a-servlet)
4. [Writing a JSP](#4-writing-a-jsp)
5. [Forwarding between servlets and JSPs](#5-forwarding-between-servlets-and-jsps)
6. [HTTPRequest reference](#6-httprequest-reference)
7. [HTTPResponse reference](#7-httprequest-reference)
8. [JSON serialization](#8-json-serialization)
9. [Dependency injection](#9-dependency-injection)
10. [Logging](#10-logging)
11. [Janitor thread](#11-janitor-thread)

---

## 1. Starting the server

### From the command line

```bash
# With a config file (recommended)
java -cp server.jar:web.jar com.tinyserver.Main /path/to/config.txt

# Without a config file — starts on port 8080 with defaults
java -cp server.jar:web.jar com.tinyserver.Main
```

See the `buid_jars.sh` shell script on how to build the above mentioned jars.

### Shutdown

The server installs a JVM shutdown hook. Press `Ctrl-C` or send `SIGTERM` — the log thread is flushed before the process exits.

---

## 2. Configuration file

The config file is a plain-text file. Each line is `key value` (whitespace-separated). Blank lines and lines beginning with `#` are ignored.

```
# ExampleWebApp/src/config.txt

port 8080
max_req_len 1000000
use_virtual_threads true
log_file_dir /Users/agent07/server

# Static HTML files
html_dir /Users/agent07/deploy/html

# JSP files
jsp_dir /Users/agent07/deploy/jsp
jsp_class_path server.jar:web.jar
always_compile_jsps true

# Servlet mappings  (map <url-path> <fully-qualified-class>)
map /testjson com.test.web.JsonServlet
map /TestServlet com.test.web.TestServlet
```

### All config keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `port` | int | 8080 | TCP port to listen on |
| `max_req_len` | int | 100 000 000 | Maximum POST body size in bytes |
| `use_virtual_threads` | bool | false | Use Java 21 virtual threads per request |
| `log_file_dir` | path | — | Directory for daily rotating log files. If absent, logs go to stdout |
| `html_dir` | path | — | Directory for static files. Requests that don't match a servlet are served from here |
| `jsp_dir` | path | — | Root directory for `.jsp` files |
| `jsp_class_path` | string | `server.jar` | Classpath passed to the JSP compiler (colon-separated jars) |
| `always_compile_jsps` | bool | false | Recompile the JSP on every request instead of caching the compiled class |
| `map` | — | — | Maps a URL path to a servlet class (see §3) |
| `inject` | — | — | Registers a dependency injection binding (see §9) |
| `static_inject` | — | — | Like `inject` but creates a single shared instance (see §9) |
| `janitor_schedule_minutes` | int | 720 | How often the janitor thread runs (see §11) |
| _anything else_ | string | — | Stored in the servlet context as `key → value`, readable via `Servlet.getFromContext(key)` |

---

## 3. Writing a servlet

All servlets extend `com.tinyserver.servlet.Servlet` and implement a single method:

```java
public void service(HTTPRequest request, HTTPResponse response)
```

### Minimal example — JSON response

```java
// ExampleWebApp/src/com/test/web/JsonServlet.java

package com.test.web;

import com.tinyserver.servlet.*;
import com.tinyserver.servlet.HTTPResponse.HTTPRetCode;
import java.io.File;

class RuntimeValues {
    public long maxMemoryM;
    public long usedMemoryM;
    public long noThreads;
    public long noVirtualThreads;
    public long queriesPerMinute;
    public long freeDiskM;
}

public class JsonServlet extends Servlet {

    @Override
    public void service(HTTPRequest request, HTTPResponse response) {
        response.setContentType("application/json");
        try {
            RuntimeValues varz = new RuntimeValues();
            varz.maxMemoryM  = Runtime.getRuntime().maxMemory()  / 1_000_000;
            varz.usedMemoryM = Runtime.getRuntime().totalMemory()/ 1_000_000;
            varz.noThreads   = Thread.activeCount();
            varz.freeDiskM   = new File("/").getUsableSpace() / 1_000_000;

            response.getOutputStream().writeBytes(JsonSerializer.toJson(varz).getBytes());
        } catch (Exception e) {
            Log.error(e.toString());
            response.getOutputStream().writeBytes(e.toString().getBytes());
            response.setReturnCode(HTTPRetCode._500);
        }
    }
}
```

Register it in `config.txt`:

```
map /testjson com.test.web.JsonServlet
```

Now `GET /testjson` returns a JSON object.

### Minimal example — form handler that forwards to a JSP

```java
// ExampleWebApp/src/com/test/web/TestServlet.java

public class TestServlet extends Servlet {

    @Override
    public void service(HTTPRequest request, HTTPResponse response) {
        String name = request.parameters.get("name");           // form field
        request.attributes.put("msg", "Hello, " + name + "!"); // pass data to JSP
        request.forwardTo("/jsp/Test.jsp", response);           // render JSP
    }
}
```

Register it:

```
map /TestServlet com.test.web.TestServlet
```

---

## 4. Writing a JSP

JSP files live in `jsp_dir` and are served under `/jsp/<filename>`. The compiler supports three tag types:

| Tag | Purpose |
|-----|---------|
| `<%! ... %>` | **Declaration** — Java method or field placed outside `service()` |
| `<%= ... %>` | **Expression** — value written to the response with `out.print(...)` |
| `<% ... %>` | **Scriptlet** — raw Java statements inside `service()` |

The compiled class has access to `request` (`HTTPRequest`) and `response` (`HTTPResponse`) automatically.

### Example — `Test.jsp`

```jsp
<%!
  // Declaration: helper method available to all scriptlets/expressions on this page
  java.lang.String getItem(int indx) {
      return new com.test.web.TestClass().getMessage() + indx;
  }
%>

<!DOCTYPE html>
<html>
<head><title>Hello Tiny JSP</title></head>
<body>

  <%-- Expression: evaluated and written inline --%>
  <p>Server time: <%= (new java.util.Date()).toLocaleString() %></p>

  <%-- Reading a request attribute set by a servlet --%>
  <b><%= request.attributes.get("msg") == null ? "" : request.attributes.get("msg") %></b>

  <%-- Scriptlet: loop --%>
  <% for (int i = 0; i < 3; i++) { %>
      <p><%= getItem(i) %></p>
  <% } %>

</body>
</html>
```

JSPs are compiled on first request and cached unless `always_compile_jsps true` is set in config.

---

## 5. Forwarding between servlets and JSPs

A servlet can hand off to another servlet or JSP using `request.forwardTo(path, response)`. The same `HTTPRequest` object is reused, so attributes set before the forward are available in the target.

```java
// Set data in the originating servlet
request.attributes.put("msg", "Hello from TestServlet " + name + "!");

// Forward to a JSP — note the leading /jsp/ prefix
request.forwardTo("/jsp/Test.jsp", response);
```

Inside the JSP, read the attribute:

```jsp
<%= request.attributes.get("msg") %>
```

---

## 6. HTTPRequest reference

`com.tinyserver.servlet.HTTPRequest`

| Member | Type | Description |
|--------|------|-------------|
| `headers` | `Hashtable<String,String>` | HTTP request headers (lower-cased keys) |
| `parameters` | `Hashtable<String,String>` | Query-string (GET) or form (POST `application/x-www-form-urlencoded`) parameters |
| `attributes` | `Hashtable<String,String>` | Arbitrary key/value pairs for inter-servlet communication |
| `content` | `char[]` | Raw POST body |
| `files` | `Hashtable<String, Hashtable<String,byte[]>>` | Uploaded files (multipart — reserved, not yet implemented) |
| `getContent()` | `String` | `content` as a `String` |
| `getMethod()` | `String` | The URL path, e.g. `/TestServlet` |
| `forwardTo(path, response)` | `void` | Dispatch to another servlet or JSP |
| `getServlet(path)` | `Servlet` | Resolve a path to its `Servlet` instance (rarely called directly) |

---

## 7. HTTPResponse reference

`com.tinyserver.servlet.HTTPResponse`

| Method | Description |
|--------|-------------|
| `getOutputStream()` | `ByteArrayOutputStream` — write the response body here |
| `setContentType(String)` | Default is `"text/html"`. Use `"application/json"` for JSON responses |
| `setCharacterEncoding(String)` | Default is `"utf-8"` |
| `setReturnCode(HTTPRetCode)` | Set HTTP status. Default is `_200` |
| `addHeader(String name, String value)` | Add an arbitrary response header |

### Available status codes (`HTTPRetCode` enum)

`_200`, `_400`, `_404`, `_411`, `_413`, `_429`, `_500`, `_501`, `_505`

---

## 8. JSON serialization

`com.tinyserver.servlet.JsonSerializer` converts plain Java objects to/from JSON using reflection. All fields must be one of: `String`, `int`, `long`, `BigInteger`, or `List<String | Integer | Long | T>` where `T` is another serializable type.

### Serializing

```java
RuntimeValues varz = new RuntimeValues();
varz.maxMemoryM = 512L;

String json = JsonSerializer.toJson(varz);
// → {"object":"RuntimeValues","maxMemoryM":512,...}
```

The `"object"` field is always emitted first and contains the simple class name. It is used during deserialization for type checking.

### Deserializing

```java
MyClass obj = new MyClass();
boolean ok = JsonSerializer.getObjFrom(jsonString, obj);
```

Returns `false` if any field could not be set.

### Limitations

- Only public/package-private fields are inspected (no getters/setters).
- Nested objects in lists must have a no-arg constructor.
- `boolean`, `double`, `float`, and `Map` are not supported — they throw `IllegalArgumentException`.

---

## 9. Dependency injection

TinyServer has a simple field-level DI system. Annotate a field in your servlet with `@Inject`:

```java
import com.tinyserver.servlet.Inject;

public class MyServlet extends Servlet {

    @Inject
    MyRepository repo;   // injected before service() is called

    @Override
    public void service(HTTPRequest request, HTTPResponse response) {
        repo.doSomething();
    }
}
```

Register the concrete implementation in `config.txt`:

```
# inject <concrete-class> <interface-or-base-class>
inject com.myapp.SqlRepository com.myapp.MyRepository

# static_inject — same but creates one shared instance (singleton)
static_inject com.myapp.SqlRepository com.myapp.MyRepository
```

- `inject` creates a new instance per request.
- `static_inject` creates one instance at startup and reuses it.
- The concrete class must implement or extend the declared field type, or config loading will throw `ClassNotFoundException`.

---

## 10. Logging

`com.tinyserver.servlet.Log` is a static, async logger.

```java
Log.debug("Detailed diagnostic info");
Log.info("Mapped /foo to com.example.FooServlet");
Log.error("Something went wrong: " + e.getMessage());
Log.printStackTrace(exception);
```

### Log output

- Without `log_file_dir` in config: logs go to stdout synchronously.
- With `log_file_dir /path/to/dir`: a background thread writes to daily-rotating files named `yyyy_MM_dd.log`. The log thread is drained and closed on `Log.stop()` (called automatically by the shutdown hook).

### Log format

```
2026-04-06 10:23:45,123    INFO    HTTPRequest method: /TestServlet
```

### Input sanitization

`Log.sanitize(msg)` strips `\n` and `\r` from log messages to prevent log injection. It is called automatically for all `Log.info()` / `Log.debug()` / `Log.error()` calls.

---

## 11. Janitor thread

The janitor thread lets you run periodic background work (database cleanup, cache expiry, etc.). Implement `com.tinyserver.servlet.JanitorThread` and register it as a `static_inject`:

```java
public class MyJanitor extends JanitorThread {
    @Override
    public void doRecurringWork() {
        Log.info("Janitor: cleaning up...");
        // delete old records, compact caches, etc.
    }
}
```

```
static_inject com.myapp.MyJanitor com.tinyserver.servlet.JanitorThread
janitor_schedule_minutes 60
```

`doRecurringWork()` is called immediately at startup and then every `janitor_schedule_minutes` minutes.

---

## 12. Async requests from the browser (JSP example)

Fetch a servlet response asynchronously and show it in the page, or in an alert:

```html
<!-- Show raw response text in an alert (fetches /varz) -->
<button onclick="fetchVarzAlert()">Async</button>

<!-- Render JSON response as a table -->
<button onclick="fetchVarz()">Get JSON</button>

<script>
  async function fetchVarzAlert() {
    try {
      const response = await fetch('/varz', {
        method: 'GET',
        headers: { 'Accept': 'application/json' }
      });
      const text = await response.text();
      alert(text);
    } catch (error) {
      alert(`Error fetching /varz:\n${error.message}`);
    }
  }

  async function fetchVarz() {
    try {
      const response = await fetch('/testjson', {
        method: 'GET',
        headers: { 'Accept': 'application/json' }
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      renderTable(data);
    } catch (error) {
      alert(`Error: ${error.message}`);
    }
  }
</script>
```

Note: unless the header is already set via response.getHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "whatever"), TinyServer sends `Access-Control-Allow-Origin: *` on all servlet responses automatically, so cross-origin fetch calls from the same host always succeed.

---

## 13. Project layout

```
TinyServer/src/
  com/tinyserver/
    Main.java               Entry point — reads config, starts server, installs shutdown hook
    TinyServer.java         Concrete server — dispatches requests to servlets / static files
    ServerEngine.java       Abstract base — HTTP parsing (GET/POST), socket accept loop
    Config.java             Config file parser, servlet registry, DI container
    MimeUtils.java          Maps file extensions to MIME types

  com/tinyserver/servlet/
    Servlet.java            Base class for all servlets (also holds servlet context)
    HTTPRequest.java        Parsed request: headers, parameters, attributes, body
    HTTPResponse.java       Response builder: status, content-type, headers, body stream
    JspCompiler.java        Compiles .jsp files to Servlet subclasses at runtime
    JsonSerializer.java     Reflection-based JSON serializer / deserializer
    JsonParser.java         Low-level JSON stream parser
    JsonObj.java            JSON value types (string, number, array, object)
    JsonListener.java       Callback interface for JsonParser
    Inject.java             @Inject field annotation for DI
    JanitorThread.java      Abstract base for periodic background tasks
    Log.java                Async logger with daily-rotating file output
    MimeUtils.java          (servlet package copy)
    MySStream.java          Internal byte-stream helper
    ReadWriteLock.java      Reentrant read/write lock used internally

ExampleWebApp/src/
  com/example/web/
    JsonServlet.java        Example: returns runtime metrics as JSON (maps to /testjson)
    TestServlet.java        Example: reads a form field, sets an attribute, forwards to JSP
    TestClass.java          Helper used by Test.jsp

ExampleWebApp/jsp/
  Test.jsp                  Example JSP: date expression, form submit, async fetch, scriptlet loop

ExampleWebApp/src/config.txt  Canonical example config file
```
