package com.tinyserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.tinyserver.servlet.Log;

/**
 * In-memory cache of every file under the configured {@code html_dir},
 * populated once at startup when {@code cache_html} is set in the
 * config file. When populated, {@link ServerEngine#deliverLocalContent}
 * short-circuits to serving cached bytes instead of hitting the disk on
 * every request — measurable win on high-QPS static-content workloads
 * (blog / how-to / white paper pages) where the filesystem was the
 * bottleneck under Loom.
 *
 * <p>Lookup keys are the request-side paths that arrive at
 * {@code deliverLocalContent} — a leading slash followed by the file
 * name relative to {@code html_dir} (e.g. {@code "/index.html"},
 * {@code "/imgs/logo.png"}). Path separators are
 * normalised to {@code /} so lookups match on Windows too.</p>
 *
 * <p>Behaviour is deliberately conservative:</p>
 * <ul>
 *   <li>The cache is loaded once at startup — no auto-reload. Restart
 *       the server after content edits in production. Development
 *       deployments should simply leave {@code cache_html} off.</li>
 *   <li>Empty and unreadable files are skipped with a warning, so a
 *       stray broken symlink doesn't abort the whole load.</li>
 *   <li>{@code Files.walk} is bounded to regular files — symlink loops
 *       are impossible via the {@code FOLLOW_LINKS} option being
 *       omitted.</li>
 * </ul>
 *
 * <p>Thread-safety: the map is populated from a single caller during
 * config init, then read from many request threads afterwards. It
 * behaves as an effectively-immutable structure after {@link #load};
 * the {@link ConcurrentHashMap} choice is defence-in-depth against a
 * future call site adding late entries.</p>
 */
public final class HtmlCache {

    /**
     * One cached file — the header helpers on {@code ServerEngine} take
     * a MIME type + length + lastModified triple, so we pre-compute
     * everything they need at load time and hand it out on lookup.
     */
    public static final class Entry {
        public final byte[] data;
        public final String mimeType;
        public final long lastModified;

        Entry(byte[] data, String mimeType, long lastModified) {
            this.data = data;
            this.mimeType = mimeType;
            this.lastModified = lastModified;
        }
    }

    /** Global singleton — null until {@link #load} completes. */
    private static volatile HtmlCache INSTANCE = null;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private long totalBytes = 0L;

    private HtmlCache() {}

    public static HtmlCache getInstance() {
        return INSTANCE;
    }

    /**
     * Walks {@code htmlDir} and slurps every regular file it finds into
     * memory, keyed on the request-side path (leading slash + path
     * relative to {@code htmlDir}). Idempotent: a second call replaces
     * the previous instance, but no live-reload runs by itself.
     *
     * @return the number of files loaded
     */
    public static int load(String htmlDir) throws IOException {
        if (htmlDir == null || htmlDir.isEmpty()) {
            throw new IllegalArgumentException("html_dir must be set before cache_html");
        }
        Path root = Paths.get(htmlDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("html_dir does not exist: " + root);
        }

        final HtmlCache next = new HtmlCache();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    // Skip 0-byte files — they'd send an empty response
                    // with a valid Content-Length and hide upstream
                    // issues (a truncated deploy, an interrupted write).
                    // Better to fall through to the disk path and let
                    // it surface as a real 404 / 500.
                    if (bytes.length == 0) {
                        Log.info("HtmlCache: skipping empty file " + p);
                        return;
                    }
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    String key = "/" + rel;
                    String mime = MimeUtils.getMimeType(key);
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    next.entries.put(key, new Entry(bytes, mime, modified));
                    next.totalBytes += bytes.length;
                } catch (IOException e) {
                    Log.info("HtmlCache: cannot read " + p + ": " + e.getMessage());
                }
            });
        }
        INSTANCE = next;
        // Two decimal places without pulling in String.format's locale
        // sensitivity — integer arithmetic on hundredths of a MB.
        long hundredthsOfMB = (next.totalBytes * 100L) / (1024L * 1024L);
        Log.info("HtmlCache: loaded " + next.entries.size() + " files from " + root
                + " (" + (hundredthsOfMB / 100) + "." + String.format("%02d", hundredthsOfMB % 100) + " MB)");
        return next.entries.size();
    }

    /** @return sum of all cached file sizes in bytes. */
    public long totalBytes() {
        return totalBytes;
    }

    /** @return the cached entry for {@code fileName} or {@code null} if uncached. */
    public Entry lookup(String fileName) {
        return entries.get(fileName);
    }

    /** For tests. */
    public int size() {
        return entries.size();
    }
}
