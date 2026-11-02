package com.tinyserver;

import com.tinyserver.servlet.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight in-process bot probe filter. Two mechanisms:
 *
 * <ol>
 *   <li><b>Probe path drop</b> — requests whose path matches a known
 *       scanner pattern (<code>.env</code>, <code>wp-admin</code>,
 *       <code>.git</code>, …) ban the source IP for 24 hours and the
 *       socket is closed without a response. Cost downstream: zero.</li>
 *   <li><b>404 rate ban</b> — any IP that accumulates
 *       {@value #MAX_404S_PER_WINDOW}+ 404 responses within
 *       {@value #WINDOW_MS} ms gets the same 24-hour ban.</li>
 * </ol>
 *
 * <p>State is in-process. Banlists reset on server restart — fine for
 * the volumes a typical small-VPS server sees and immune to disk-
 * pressure failures. A misbehaving IP is forgotten after its 24 h TTL
 * (or sooner, on restart).
 *
 * <p>Compares case-insensitive on path. Tuned for shared-NAT realities:
 * 20 misses in 5 minutes is well above what any normal user produces.
 *
 * <p>Singleton because every accept thread / virtual thread shares
 * one banlist. Methods are concurrent-safe.
 */
public final class ProbeFilter {

    /** Total ban duration applied on probe-path match or 404-rate trip. */
    public static final long BAN_DURATION_MS = 24L * 60 * 60 * 1000;
    /** Window over which {@link #MAX_404S_PER_WINDOW} is measured. */
    public static final long WINDOW_MS = 5L * 60 * 1000;
    /** Number of 4xx responses within {@link #WINDOW_MS} that triggers a ban. */
    public static final int  MAX_404S_PER_WINDOW = 20;

    /**
     * Path substrings any of which triggers an immediate ban. Comparison
     * is lowercase-on-lowercase. Patterns drawn from production logs of
     * scanner traffic across the last few years — these are the URLs no
     * honest user ever requests.
     */
    private static final List<String> PROBE_SUBSTRINGS = List.of(
        ".env", ".git", ".aws", ".ssh", ".bash_history", ".npmrc",
        ".htaccess", ".htpasswd", ".svn", ".php",
        "wp-login", "wp-admin", "wp-config", "wp-includes", "/wp/",
        "xmlrpc.php", "phpmyadmin", "phpunit", "adminer",
        "/vendor/", "/cgi-bin/", "/.well-known/pki-validation/",
        "/owa/", "/ews/", "/exchange/",
        "/manager/html", "/jenkins", "/jmx-console",
        "/actuator", "/server-status", "/server-info", "/server.cfg",
        "/dbeaver", "/joomla", "/drupal", "/struts",
        "/eval-stdin", "/shellshock",
        "/portal/redlion", "/setup.cgi", "/HNAP1/", "/boaform/",
        "/api/jsonws/expandocolumn/",
        "/_ignition/", "/laravel/", "/console/",
        "/solr/", "/elasticsearch/",
        "/etcd/", "/.docker/", "/.kube/", "/.vscode/"
    );

    private final ConcurrentHashMap<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> errorCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> windowStart = new ConcurrentHashMap<>();

    /** At most one cleanup per CLEANUP_INTERVAL_MS across all callers. */
    private static final long CLEANUP_INTERVAL_MS = 60L * 1000;
    private volatile long lastCleanup = System.currentTimeMillis();

    private static final ProbeFilter INSTANCE = new ProbeFilter();

    public static ProbeFilter getInstance() { return INSTANCE; }

    private ProbeFilter() {}

    /** Cheap throttle so cleanup runs at most once a minute regardless of load. */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if ((now - lastCleanup) > CLEANUP_INTERVAL_MS) {
            lastCleanup = now;
            cleanup();
        }
    }

    /** True if {@code ip} is currently in the 24-hour banlist. */
    public boolean isBanned(String ip) {
        maybeCleanup();
        if (ip == null || ip.isEmpty()) return false;
        Long expiry = bannedUntil.get(ip);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            bannedUntil.remove(ip);
            return false;
        }
        return true;
    }

    /**
     * Returns true if {@code path} matches a known probe pattern. The caller
     * is expected to {@link #ban} the source IP and close the socket without
     * responding when this returns true.
     */
    public boolean isProbePath(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase();
        for (String s : PROBE_SUBSTRINGS) {
            if (lower.contains(s)) return true;
        }
        return false;
    }

    /**
     * Add {@code ip} to the banlist with a {@link #BAN_DURATION_MS} TTL.
     * Idempotent: parallel callers banning the same IP all update the
     * map (which is fine — the value is the same) but only the first one
     * (or one whose ban replaced an already-expired entry) writes a log
     * line. Without this, a bot that fires a dozen parallel connections
     * produces a dozen identical "banned" lines, masking real activity.
     */
    public void ban(String ip, String reason) {
        if (ip == null || ip.isEmpty()) return;
        long now = System.currentTimeMillis();
        // put returns the previous mapping atomically — exactly one
        // concurrent caller will see null/expired here, every other one
        // will see the future timestamp that was just written and skip
        // the log.
        Long previous = bannedUntil.put(ip, now + BAN_DURATION_MS);
        errorCount.remove(ip);
        windowStart.remove(ip);
        if (previous == null || previous < now) {
            Log.info("ProbeFilter: banned " + ip + " (" + reason + ")");
        }
    }

    /**
     * Record a 4xx response for {@code ip}. Once the IP accumulates
     * {@link #MAX_404S_PER_WINDOW} misses inside {@link #WINDOW_MS}, the
     * IP is auto-banned.
     */
    public void recordError(String ip) {
        maybeCleanup();
        if (ip == null || ip.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long start = windowStart.get(ip);
        if (start == null || (now - start) > WINDOW_MS) {
            windowStart.put(ip, now);
            errorCount.put(ip, new AtomicInteger(1));
            return;
        }
        AtomicInteger c = errorCount.computeIfAbsent(ip, k -> new AtomicInteger());
        if (c.incrementAndGet() >= MAX_404S_PER_WINDOW) {
            ban(ip, "404 rate");
        }
    }

    /** Drop expired entries from all three maps. Safe to call concurrently. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        bannedUntil.entrySet().removeIf(e -> e.getValue() < now);
        // Reuse the window key for both maps — when the window for an IP
        // has elapsed we drop both its window mark and its error count.
        for (Map.Entry<String, Long> e : windowStart.entrySet()) {
            if ((now - e.getValue()) > WINDOW_MS) {
                windowStart.remove(e.getKey(), e.getValue());
                errorCount.remove(e.getKey());
            }
        }
    }
}
