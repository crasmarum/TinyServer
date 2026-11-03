package com.tinyserver;

import com.tinyserver.servlet.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

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
 * <p>Clients are keyed by IPv4 address, and IPv6 clients by their /64
 * prefix (one allocation is one client, so rotating addresses inside it
 * neither dodges the 4xx count nor fills the maps).
 *
 * <p>Memory is bounded: each map holds at most {@value #MAX_TRACKED}
 * keys and keys longer than {@value #MAX_KEY_LENGTH} chars are ignored.
 * When a map is full and nothing in it has expired, the
 * 1/{@value #EVICT_DIVISOR} of its entries that expire soonest are evicted
 * to make room, so a flood of fresh addresses cannot switch off new bans
 * for a whole ban period. One thread evicts at a time; a key arriving
 * meanwhile is not tracked (fail open). One line per minute is logged.
 * The cap is soft by at most the number of threads inserting at the same
 * instant.
 *
 * <p>The hot paths ({@link #isBanned}, {@link #recordError}) do no sweep
 * work while there is room; expired entries are dropped by {@link #cleanup},
 * which the server calls from a background timer. Eviction in a full map
 * costs one sort of the map per MAX_TRACKED/EVICT_DIVISOR new keys.
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
    /** Most keys held in the ban map, and separately in the error-window map. */
    public static final int  MAX_TRACKED = 10_000;
    /** Keys (client IPs) longer than this are ignored outright. */
    public static final int  MAX_KEY_LENGTH = 64;
    /** A full map evicts this fraction (1/n) of its entries, soonest-expiring first. */
    public static final int  EVICT_DIVISOR = 10;

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

    /** Error window of one IP: when it started and how many 4xx it has seen. Immutable. */
    record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> errorWindows = new ConcurrentHashMap<>();

    /** At most one "cap hit" log line per CAP_LOG_INTERVAL_MS. */
    private static final long CAP_LOG_INTERVAL_MS = 60L * 1000;
    private final AtomicLong lastCapLog = new AtomicLong(Long.MIN_VALUE);
    /** Held by the one thread evicting from a full map; package-private for tests. */
    final ReentrantLock evictLock = new ReentrantLock();

    /** Time source; tests substitute their own. */
    private final LongSupplier clock;

    /** Bans that actually started (the ones logged); for tests. */
    final AtomicInteger bansStarted = new AtomicInteger();
    /** Times recordError tripped the 404-rate ban; for tests. */
    final AtomicInteger rateBans = new AtomicInteger();

    private static final ProbeFilter INSTANCE = new ProbeFilter(System::currentTimeMillis);

    public static ProbeFilter getInstance() { return INSTANCE; }

    /** Package-private so tests can build isolated instances with a fake clock. */
    ProbeFilter(LongSupplier clock) { this.clock = clock; }

    /** Null, empty and over-long keys are never tracked. */
    private static boolean usableKey(String ip) {
        return ip != null && !ip.isEmpty() && ip.length() <= MAX_KEY_LENGTH;
    }

    /**
     * The tracking key of a client address: IPv4 (and anything that is not
     * IPv6) unchanged, IPv6 reduced to its /64 prefix, written as
     * {@code "2001:db8:0:1::/64"}. A zone suffix ({@code %eth0}) is dropped.
     * An IPv6 string that does not parse is kept as it is.
     */
    static String clientKey(String ip) {
        if (ip.indexOf(':') < 0) return ip;
        String s = ip;
        int pct = s.indexOf('%');
        if (pct >= 0) s = s.substring(0, pct);
        String[] head, tail;
        int dc = s.indexOf("::");
        if (dc >= 0) {
            if (s.indexOf("::", dc + 1) >= 0) return ip;
            head = groups(s.substring(0, dc));
            tail = groups(s.substring(dc + 2));
        } else {
            head = groups(s);
            tail = new String[0];
        }
        if (head == null || tail == null) return ip;
        if (dc >= 0 && head.length > 0 && head[head.length - 1].indexOf('.') >= 0) return ip;   // "1.2.3.4::"
        int n = head.length + tail.length;
        // an embedded IPv4 tail (groups() allows it only last) is two groups
        if (head.length > 0 && head[head.length - 1].indexOf('.') >= 0) n++;
        if (tail.length > 0 && tail[tail.length - 1].indexOf('.') >= 0) n++;
        if (dc >= 0 ? n > 7 : n != 8) return ip;
        int[] g = new int[4];
        for (int i = 0; i < 4 && i < head.length; i++) {
            if (head[i].indexOf('.') >= 0) return ip;   // IPv4 only at the end
            g[i] = Integer.parseInt(head[i], 16);
        }
        // tail groups that land in the first four (e.g. "::1:2:3:4:5:6:7")
        int tailStart = 8 - n + head.length;
        for (int i = 0; i < tail.length; i++) {
            int pos = tailStart + i;
            if (pos < 4) {
                if (tail[i].indexOf('.') >= 0) return ip;
                g[pos] = Integer.parseInt(tail[i], 16);
            }
        }
        return Integer.toHexString(g[0]) + ":" + Integer.toHexString(g[1]) + ":"
            + Integer.toHexString(g[2]) + ":" + Integer.toHexString(g[3]) + "::/64";
    }

    /** colon-separated groups of 1-4 hex digits (the last may be dotted IPv4), or null; "" gives none */
    private static String[] groups(String s) {
        if (s.isEmpty()) return new String[0];
        String[] parts = s.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.indexOf('.') >= 0) {
                if (i != parts.length - 1) return null;
                continue;
            }
            if (p.isEmpty() || p.length() > 4) return null;
            for (int j = 0; j < p.length(); j++) {
                if (Character.digit(p.charAt(j), 16) < 0) return null;
            }
        }
        return parts;
    }

    /** The key to track {@code ip} under, or null if it is not tracked at all. */
    private static String key(String ip) {
        return usableKey(ip) ? clientKey(ip) : null;
    }

    /**
     * True if {@code map} has no room for the new key {@code key}. A full map
     * first evicts its soonest-expiring entries (see the class comment); the
     * key is refused only while another thread is evicting. Logs at most once
     * a minute.
     */
    private <V> boolean full(ConcurrentHashMap<String, V> map, String key, String which, ToLongFunction<V> expiry) {
        if (map.size() < MAX_TRACKED || map.containsKey(key)) return false;
        int evicted = -1;
        if (evictLock.tryLock()) {
            try {
                evicted = map.size() < MAX_TRACKED ? 0 : evict(map, expiry);
            } finally {
                evictLock.unlock();
            }
        }
        long now = clock.getAsLong();
        long last = lastCapLog.get();
        if ((last == Long.MIN_VALUE || now - last >= CAP_LOG_INTERVAL_MS) && lastCapLog.compareAndSet(last, now)) {
            Log.info("ProbeFilter: " + which + " map full (" + MAX_TRACKED + " entries), "
                + (evicted < 0 ? "new address not tracked" : "evicted " + evicted + " soonest-expiring entries"));
        }
        return evicted < 0;
    }

    /** Removes the 1/EVICT_DIVISOR of {@code map}'s entries that expire soonest; returns how many went. */
    private static <V> int evict(ConcurrentHashMap<String, V> map, ToLongFunction<V> expiry) {
        long[] times = new long[map.size() + 64];   // the map may grow while we look
        int n = 0;
        for (V v : map.values()) {
            if (n == times.length) break;
            times[n++] = expiry.applyAsLong(v);
        }
        if (n == 0) return 0;
        Arrays.sort(times, 0, n);
        int target = Math.max(1, n / EVICT_DIVISOR);
        long threshold = times[target - 1];
        int removed = 0;
        for (Map.Entry<String, V> e : map.entrySet()) {
            if (removed >= target) break;
            V v = e.getValue();
            // conditional, so an entry renewed meanwhile survives
            if (expiry.applyAsLong(v) <= threshold && map.remove(e.getKey(), v)) removed++;
        }
        return removed;
    }

    /** Expiry time of an error window. */
    private static long windowEnd(Window w) { return w.start() + WINDOW_MS; }

    /** True if {@code ip} is currently in the 24-hour banlist. */
    public boolean isBanned(String ip) {
        String k = key(ip);
        if (k == null) return false;
        Long expiry = bannedUntil.get(k);
        if (expiry == null) return false;
        if (clock.getAsLong() >= expiry) {
            // only this key; conditional so a concurrent fresh ban survives
            bannedUntil.remove(k, expiry);
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
     * An IPv6 address bans its /64. When the banlist is full the bans that
     * expire soonest make room (see the class comment).
     */
    public void ban(String ip, String reason) {
        String k = key(ip);
        if (k == null) return;
        if (full(bannedUntil, k, "ban", Long::longValue)) return;
        long now = clock.getAsLong();
        // put returns the previous mapping atomically — exactly one
        // concurrent caller will see null/expired here, every other one
        // will see the future timestamp that was just written and skip
        // the log.
        Long previous = bannedUntil.put(k, now + BAN_DURATION_MS);
        if (previous == null || previous <= now) {
            bansStarted.incrementAndGet();
            Log.info("ProbeFilter: banned " + k + " (" + reason + ")");
        }
    }

    /**
     * Record a 4xx response for {@code ip}. Once the IP accumulates
     * {@link #MAX_404S_PER_WINDOW} misses inside {@link #WINDOW_MS}, the
     * IP is auto-banned. The window is updated atomically, so concurrent
     * misses neither reset nor lose counts, and exactly one caller sees
     * the count reach the limit.
     */
    public void recordError(String ip) {
        String key = key(ip);
        if (key == null) return;
        if (full(errorWindows, key, "error-window", ProbeFilter::windowEnd)) return;
        long now = clock.getAsLong();
        Window w = errorWindows.compute(key, (k, old) ->
            (old == null || (now - old.start()) > WINDOW_MS)
                ? new Window(now, 1)
                : new Window(old.start(), old.count() + 1));
        if (w.count() == MAX_404S_PER_WINDOW) {
            rateBans.incrementAndGet();
            ban(key, "404 rate");
        }
    }

    /**
     * Drop expired entries from both maps. Safe to call concurrently with
     * everything else; run it periodically (the server does so every 60 s).
     */
    public void cleanup() {
        long now = clock.getAsLong();
        // removeIf on a CHM entry set removes only if the entry still has
        // the value tested, so a ban/window renewed meanwhile survives
        bannedUntil.entrySet().removeIf(e -> e.getValue() <= now);
        errorWindows.entrySet().removeIf(e -> (now - e.getValue().start()) > WINDOW_MS);
    }

    /** Number of keys in the ban map; for tests. */
    int bannedSize() { return bannedUntil.size(); }

    /** Number of keys in the error-window map; for tests. */
    int windowSize() { return errorWindows.size(); }

    /** Current window count of {@code ip}, 0 if untracked; for tests. */
    int errorCount(String ip) {
        String k = key(ip);
        Window w = k == null ? null : errorWindows.get(k);
        return w == null ? 0 : w.count();
    }
}
