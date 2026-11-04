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
If the server stops for any other reason (it fails to start, e.g. the port is taken, or its accept loop fails), the
process exits with status 1, so a supervisor such as systemd (`Restart=on-failure`) restarts it.

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
html_dir /Users/agent07/server/html

# JSP files
jsp_dir /Users/agent07/server/jsp
jsp_class_path server.jar:web.jar
always_compile_jsps true

# Servlet mappings  (map <url-path> <fully-qualified-class>)
map /testjson com.example.web.JsonServlet
map /TestServlet com.example.web.TestServlet
```

### All config keys

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `port` | int | 8080 | TCP port to listen on |
| `max_req_len` | int | 100 000 000 | Maximum POST body size in bytes |
| `body_budget` | bytes | heap / 4 | Memory the request bodies being read or served may hold together (see "Request body memory" below) |
| `body_min_rate` | bytes/s | 4096 | After a 5 s grace, a request body must keep arriving at least this fast, or it gets 408. `0` turns this off |
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
| `keep_connection_alive` | bool | true | HTTP/1.1 keep-alive. `false` falls back to HTTP/1.0, closing the connection after each response (less memory on a small server) |
| `cache_html` | bool | false | Serve the static files from an in-memory copy of `html_dir`, read once at start-up (restart to pick up changes) |
| `cross_origin_isolation` | bool | false | Static responses carry COOP / COEP headers, so pages are cross-origin isolated (`SharedArrayBuffer`, e.g. WebAssembly threads). Every subresource must then be same-origin or CORP-marked. `.wasm` is always served as `application/wasm` |
| `healthz_token` | string | — | Shared secret for an application's health-check endpoint (`?token=…`), read with `Config.getInstance().getHealthzToken()` |
| `db_token` | string | — | Server-side secret ("pepper") an application can mix into hashes of tokens it stores, read with `Config.getInstance().getDbToken()` |
| _anything else_ | string | — | Stored in the servlet context as `key → value`, readable via `Servlet.getFromContext(key)` |

Built in, no configuration:

- **Request limits.** The request line must be `METHOD /target HTTP/1.x` (GET, HEAD and POST; other methods get 501).
  Headers end at an empty line; at most 128 header lines and 128 KB of headers (413). A request with
  `Transfer-Encoding` (e.g. chunked), two different `Content-Length`s, folded or malformed header lines, or a GET/HEAD
  with a body is refused with 400 and the connection is closed, so no unread bytes can be taken for the next request
  on a kept-alive socket (behind a proxy that socket is shared by many users). At most 1,000 parameters per request
  (query string and form body together, or multipart parts; 413). Malformed `%` escapes give 400.
- **Scanner filter** (class `ProbeFilter`, in memory). Requests for known scanner paths (`.env`, `wp-admin`, `.git`, …)
  are dropped without a response and the client is banned for 24 hours; 20 or more 4xx responses within 5 minutes
  ban it too. An IPv6 client is banned by its /64. A ban is enforced when that client opens a new connection
  directly; behind a reverse proxy every connection comes from the proxy, so there the filter only drops the probe
  requests. Both lists are capped at 10,000 entries (the soonest-expiring are evicted) and cleaned up once a minute.
- **Request body memory.** A POST reserves 5 × its Content-Length (11 × for an `application/x-www-form-urlencoded`
  form, whose decoding needs more for a moment) in a server-wide budget (`body_budget`, a quarter of the heap by
  default) before any of its body is read, and gives it back when its servlet returns (a POST that long-polls holds it
  until then); 5 copies of a body are alive at once while it is read and handled (bytes, decoded text, `content`, the
  servlet's `getContent()`). After the servlet returns, the request's body, parameters and uploads are cleared.
  Bodies over 64 KB may together use at most 3/4 of the budget, so small requests (forms, JSON) always have room.
  A body that does not fit within 2 s gets 503 with `Retry-After: 2`, unread; one bigger than the large share is
  clamped to it and runs alone. The startup log says how many `max_req_len` bodies fit at once, with a warning when
  only one does. A POST with a body to a path no servlet or JSP takes gets 405, unread. A body that falls behind
  `body_min_rate` after its 5 s grace gets 408 (not counted by the scanner filter). Not counted in the budget: what a
  servlet allocates while it works (a decoded image, a JSON tree), responses, and per-connection buffers. One log line
  a minute reports 503s, 408s and the peak, when there were any or the peak passed half the budget.
- **Client address.** When the connection comes from a private or loopback address (a reverse proxy on the same
  machine or network), the client is the LAST `X-Forwarded-For` entry, the one the proxy appended, and only if it is
  an IP literal; everything before it is whatever the client sent. Otherwise it is the TCP peer. This address is used
  in the log and by the scanner filter.

---

## 3. Writing a servlet

All servlets extend `com.tinyserver.servlet.Servlet` and implement a single method:

```java
public void service(HTTPRequest request, HTTPResponse response)
```

### Minimal example — JSON response

```java
// ExampleWebApp/src/com/example/web/JsonServlet.java

package com.example.web;

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
map /testjson com.example.web.JsonServlet
```

Now `GET /testjson` returns a JSON object.

### Minimal example — form handler that forwards to a JSP

```java
// ExampleWebApp/src/com/example/web/TestServlet.java

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
map /TestServlet com.example.web.TestServlet
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
      return new com.example.web.TestClass().getMessage() + indx;
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
| `parameters` | `Hashtable<String,String>` | Query string (any method), form (POST `application/x-www-form-urlencoded`) or multipart text fields; at most 1,000 per request |
| `attributes` | `Hashtable<String,String>` | Arbitrary key/value pairs for inter-servlet communication |
| `content` | `char[]` | POST body decoded as UTF-8 (empty for multipart requests) |
| `uploads` | `List<UploadedFile>` | Files of a `multipart/form-data` request, in order: `field()`, `filename()`, `contentType()`, `data()` |
| `getUpload(field)` | `UploadedFile` | The first file of a form field, or `null` |
| `files` | `Hashtable<String, Hashtable<String,byte[]>>` | The same files as field → file name → bytes |
| `getContent()` | `String` | `content` as a `String` |
| `getMethod()` | `String` | The URL path, e.g. `/TestServlet` |
| `forwardTo(path, response)` | `void` | Dispatch to another servlet or JSP |
| `getServlet(path)` | `Servlet` | Resolve a path to its `Servlet` instance (rarely called directly) |

### File uploads (`multipart/form-data`)

A form with `enctype="multipart/form-data"` (or `fetch` with a `FormData` body) is parsed before the servlet runs
(RFC 7578, class `Multipart`): text fields go to `parameters` (UTF-8), files to `uploads` / `files`, byte for byte.
File names lose any directory part (`C:\a\photo.png` → `photo.png`); a file input left empty is skipped; a part
without a Content-Type gets `application/octet-stream`. Bodies are read from the socket as raw bytes, never through a
character decoder, so binary files are intact and kept-alive connections stay in step. `Expect: 100-continue` (curl,
other HTTP clients) is answered. A body larger than `max_req_len` gets 413; a malformed multipart body gets 400.

```java
public void service(HTTPRequest request, HTTPResponse response) {
    UploadedFile photo = request.getUpload("photo");       // <input type="file" name="photo">
    String caption = request.parameters.get("caption");    // <input name="caption">
    if (photo != null) Files.write(dir.resolve(photo.filename()), photo.data());   // check the name before using it as a path
}
```

The whole body is held in memory (at most `max_req_len` bytes), so set `max_req_len` to the largest upload you accept;
uploads reserve 5 × their size in the body budget while they are read and served (see §2, "Request body memory").
Tests: `MultipartTest` (parser cases, plus end to end against a running server: a 3 MB binary file, curl `-F`,
keep-alive after an upload, malformed bodies).

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

- All declared fields are inspected and set, private ones included (no getters/setters); `getObjFrom` should only be
  given classes whose fields may all come from the JSON.
- Nested objects in lists must have a no-arg constructor.
- `boolean`, `double`, `float`, and `Map` are not supported — they throw `IllegalArgumentException`.
- `JsonParser` refuses a document (`parse` returns false) nested deeper than 64 levels, with more than 200,000 values,
  or whose key paths add up to more than 8 million characters (every value stores its full key path).

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

`Log.sanitize(msg)` removes `\n` and `\r` and replaces other control characters (except TAB, e.g. terminal escape sequences) with `?`, to prevent log injection. It is called automatically for all `Log.info()` / `Log.debug()` / `Log.error()` calls. Logging never blocks a request: entries go through a queue to a writer thread, and if the queue is full the entry is dropped and counted ("N log lines dropped").

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
