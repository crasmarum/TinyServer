package com.tinyserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Smoke test for {@link HtmlCache}. No JUnit — same convention as
 * {@code JsonParserTest} elsewhere in this module. Exits 0 iff every
 * check passes; the {@code create_jars.sh} build script excludes
 * {@code *Test.java} from the production jar.
 */
public class HtmlCacheTest {

    static int passed = 0, failed = 0;

    static void check(String label, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }

    public static void main(String[] args) throws IOException {
        Path tmp = Files.createTempDirectory("htmlcache-test");
        try {
            // A regular page, an asset in a nested folder, and an empty
            // file that must be skipped so cache misses fall through to
            // the disk branch instead of serving 0 bytes.
            Files.writeString(tmp.resolve("index.html"), "<html><body>hi</body></html>");
            Files.createDirectory(tmp.resolve("imgs"));
            byte[] png = {(byte)0x89, 'P', 'N', 'G', 0, 1, 2, 3};
            Files.write(tmp.resolve("imgs/logo.png"), png);
            Files.writeString(tmp.resolve("empty.txt"), "");

            int loaded = HtmlCache.load(tmp.toString());
            check("returns count of loaded files", loaded == 2);

            HtmlCache cache = HtmlCache.getInstance();
            check("singleton populated after load", cache != null);
            check("size matches non-empty count", cache.size() == 2);

            HtmlCache.Entry html = cache.lookup("/index.html");
            check("index.html lookup succeeds", html != null);
            check("index.html bytes match on disk",
                    html != null && new String(html.data).contains("<body>hi</body>"));
            check("index.html gets text/html mime",
                    html != null && html.mimeType != null && html.mimeType.startsWith("text/html"));

            HtmlCache.Entry logo = cache.lookup("/imgs/logo.png");
            check("nested asset lookup succeeds", logo != null);
            check("nested asset bytes match",
                    logo != null && Arrays.equals(logo.data, png));

            check("empty file skipped", cache.lookup("/empty.txt") == null);
            check("unknown key returns null", cache.lookup("/does-not-exist.html") == null);

            // Load twice: replaces snapshot, doesn't accumulate.
            Files.writeString(tmp.resolve("about.html"), "<h1>about</h1>");
            HtmlCache.load(tmp.toString());
            check("reload replaces instance",
                    HtmlCache.getInstance().lookup("/about.html") != null);
            check("reload count reflects new files",
                    HtmlCache.getInstance().size() == 3);
        } finally {
            // Rough recursive delete — test dir is small; no need for
            // FileVisitor gymnastics here.
            try (var stream = Files.walk(tmp)) {
                stream.sorted((a, b) -> b.compareTo(a))
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }

        System.out.println("\nHtmlCacheTest: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
