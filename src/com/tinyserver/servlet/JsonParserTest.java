package com.tinyserver.servlet;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for the five JsonParser bug fixes. No JUnit dep — the
 * TinyServer module ships without one, and pulling junit into the test
 * classpath just for assert/exit-code semantics is wasteful. Each test method
 * pushes pass/fail counts; main exits 0 iff everything passed.
 *
 * <p>Lives under TinyServer/src/test/, which create_jars.sh explicitly
 * excludes from the production sources.txt — running this file never leaks
 * test code into server.jar.</p>
 */
public class JsonParserTest {

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

    interface ThrowingBoolean {
        boolean get() throws Exception;
    }

    static void check(String label, ThrowingBoolean action) {
        try {
            check(label, action.get());
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + label + " — threw "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Runs the supplied parse-call in a daemon thread with a join deadline.
     * Pre-fix hangs (read_string EOF, nested-bracket cycle) leave the thread
     * spinning; post-fix it returns quickly. Returns the parse result, or
     * null if the deadline lapsed (interpreted as "hung").
     */
    static Boolean runWithTimeout(long ms, ThrowingBoolean action) {
        AtomicReference<Boolean> out = new AtomicReference<>(null);
        Thread t = new Thread(() -> {
            try { out.set(action.get()); } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
        try { t.join(ms); } catch (InterruptedException ignored) {}
        return t.isAlive() ? null : out.get();
    }

    public static void main(String[] args) {
        // Sanity: valid inputs still parse after the rewrites.
        testValidObject();
        testValidNestedArray();
        testEmptyObject();
        testEmptyArray();
        testDeepNestedValid();

        // Bug #1: read_string EOF infinite loop.
        testUnterminatedStringDoesNotHang();

        // Bug #2: is_ctrl / is_w_space array bounds.
        testHighBitPredicateBounds();

        // Bug #3: bracket_mismatch only tracking the last open bracket.
        testNestedBracketMismatchFails();
        testParserReuseClearsBracketStack();

        // Bug #4 + #5: read_unicode charset + surrogate pairs.
        testSurrogatePairEmoji();
        testUnpairedHighSurrogateFails();
        testUnpairedLowSurrogateFails();
        testInvalidHexInUnicodeEscapeFails();
        testBasicMultiplaneCharacter();

        // Generally-bad-input guards.
        testUnclosedObjectFails();
        testTrailingCommaFails();
        testTopLevelStringFails();

        // Bug #6: empty-string values silently skipped the listener
        // callback in add_value, leaving callers' fields unset on a
        // wire "k":"" — broke the empty-content delete-for-all signal.
        testEmptyStringValueFiresListener();
        // Companion to bug #6: the unmasked structural notifications
        // (empty J_Array / J_Object opener events) must NOT reach
        // leaf-oriented listeners, or they crash on list-typed fields.
        testStructuralOpenersDoNotFireListener();
        // Bug #7: read_string aborted on UTF-8 multi-byte chars because
        // the EOF check used `< 0` against a signed byte. Every emoji /
        // CJK char's bytes are negative as signed Java bytes, so the
        // parser bailed mid-string and lost every field after the first
        // non-ASCII string value.
        testNonAsciiStringParsesAllSubsequentFields();

        // Item 11: MySStream charset, "-0" numbers, toString of an empty
        // key list, getObjFrom ignoring the parse result.
        testMySStreamIsUtf8();
        testNegativeZeroNumbers();
        testEmptyKeyListToString();
        testGetObjFromReportsParseFailure();

        // Nesting depth: each level copies its parent's key path, so cost grows with the square of the depth.
        testNestingDepthLimit();
        // Long keys over many siblings cost as much as depth: the path and node budgets.
        testPathAndNodeBudgets();

        System.out.println();
        System.out.println("JsonParserTest: " + passed + " passed, " + failed + " failed.");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void testValidObject() {
        check("valid object {\"a\":\"b\",\"c\":42} parses",
                new JsonParser().parse("{\"a\":\"b\",\"c\":42}", new JsonObj()));
    }

    static void testValidNestedArray() {
        check("nested arrays [[1,2],[3,4]] parse",
                new JsonParser().parse("[[1,2],[3,4]]", new JsonObj()));
    }

    static void testEmptyObject() {
        check("empty object {} parses", new JsonParser().parse("{}", new JsonObj()));
    }

    static void testEmptyArray() {
        check("empty array [] parses", new JsonParser().parse("[]", new JsonObj()));
    }

    static void testDeepNestedValid() {
        check("deep nested {\"a\":[{\"b\":[1,2]}]} parses",
                new JsonParser().parse("{\"a\":[{\"b\":[1,2]}]}", new JsonObj()));
    }

    static void testUnterminatedStringDoesNotHang() {
        // Pre-fix bug: in read_string, peek() == -1 != '"', the control-char
        // check short-circuits (0xFFFF > 256), JSON_ESCAPE check is false,
        // baos.write(ss.get()) writes a junk byte, peek() stays -1 forever.
        // A 3s budget is generous — the fix should return in microseconds.
        Boolean result = runWithTimeout(3000, () ->
                new JsonParser().parse("{\"k\":\"unterminated", new JsonObj()));
        check("unterminated string returns false instead of hanging",
                result != null && !result);
    }

    static void testHighBitPredicateBounds() {
        // Defensive fix for the off-by-one on is_ctrl (255) and is_w_space
        // (254). Currently unreachable from MySStream (signed byte widens to
        // high char and the `< 256` guard catches it), but exposed if
        // MySStream is ever refactored to byte/int. Test calls the predicates
        // directly across the full guarded range.
        check("is_control / is_space safe across [0, 255]", () -> {
            for (int c = 0; c <= 255; c++) {
                JsonParser.is_control((char) c);
                JsonParser.is_space((char) c);
            }
            return true;
        });
    }

    static void testNestedBracketMismatchFails() {
        // Pre-fix `[[1,2]}` BOTH mis-tracked brackets AND caused an infinite
        // loop: the unconsumed '}' kept cycling through READ_ARRAY → READ_VALUE
        // → READ_COMMA → READ_ARRAY with no progress, because the old scalar
        // `last_open` had been reset by the inner `]` close. Post-fix
        // bracketStack_ remembers the outer '[' and the mismatch is caught.
        Boolean result = runWithTimeout(3000, () ->
                new JsonParser().parse("[[1,2]}", new JsonObj()));
        check("nested bracket mismatch [[1,2]} returns false (no hang)",
                result != null && !result);
    }

    static void testParserReuseClearsBracketStack() {
        // Reused JsonParser instance: a partial/erroring first parse must not
        // leave residual entries on bracketStack_ that would poison the next
        // call's first close-bracket match. This mirrors the existing
        // stack_.clear() at the top of parse().
        JsonParser p = new JsonParser();
        p.parse("{\"a\":1", new JsonObj()); // intentionally truncated
        check("reused parser handles fresh valid input after error",
                p.parse("[1,2,3]", new JsonObj()));
    }

    static void testSurrogatePairEmoji() {
        // U+1F600 (the grinning emoji). Pre-fix read_unicode converted each
        // backslash-u escape in isolation and emitted unpaired surrogates
        // (silent corruption). Post-fix the high surrogate triggers a second
        // escape read and Character.toCodePoint assembles the code point.
        check("surrogate pair (emoji 😀) parses",
                new JsonParser().parse("{\"k\":\"\\uD83D\\uDE00\"}", new JsonObj()));
    }

    static void testUnpairedHighSurrogateFails() {
        // High surrogate followed by a non-low-surrogate must be rejected.
        check("unpaired high surrogate followed by \\u0041 fails",
                !new JsonParser().parse("{\"k\":\"\\uD83D\\u0041\"}", new JsonObj()));
    }

    static void testUnpairedLowSurrogateFails() {
        // Lone low surrogate must be rejected.
        check("unpaired low surrogate \\uDE00 fails",
                !new JsonParser().parse("{\"k\":\"\\uDE00\"}", new JsonObj()));
    }

    static void testInvalidHexInUnicodeEscapeFails() {
        check("invalid hex \\uZZZZ fails",
                !new JsonParser().parse("{\"k\":\"\\uZZZZ\"}", new JsonObj()));
    }

    static void testBasicMultiplaneCharacter() {
        // BMP non-ASCII: U+00E9 = é. Single escape, no surrogate path.
        check("BMP char \\u00E9 (é) parses",
                new JsonParser().parse("{\"k\":\"\\u00E9\"}", new JsonObj()));
    }

    static void testUnclosedObjectFails() {
        check("unclosed object {\"a\":1 fails",
                !new JsonParser().parse("{\"a\":1", new JsonObj()));
    }

    static void testTrailingCommaFails() {
        check("trailing comma [1,2,] fails",
                !new JsonParser().parse("[1,2,]", new JsonObj()));
    }

    static void testTopLevelStringFails() {
        // The parser's START state only accepts '{' or '['; bare strings are
        // a no-op that should fall through to ERROR.
        check("top-level bare string \"foo\" fails",
                !new JsonParser().parse("\"foo\"", new JsonObj()));
    }

    static void testNonAsciiStringParsesAllSubsequentFields() {
        // Pre-fix: read_string aborted the moment it saw a byte with the
        // high bit set (every UTF-8 starter and continuation for non-
        // ASCII is negative as signed Java byte, and the EOF guard was
        // `< 0`). Effect: every field after the first emoji/CJK string
        // value was lost. Verified by parsing a JSON shape that mirrors
        // what the chat protocol's EMOJI envelope produces.
        JsonParser p = new JsonParser();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        p.add_listener(new JsonListener() {
            @Override public void on_data(JsonObj.JSON_Type type,
                                          java.util.List<String> key,
                                          String value) {
                seen.put(JsonListener.toString(key), value);
            }
        });
        // Note the literal 4-byte UTF-8 👍 (U+1F44D), not the JSON-
        // surrogate escape form (backslash-u-D-8-3-D, backslash-u-D-C-4-D).
        // The escape form avoided the bug because read_string sees ASCII
        // bytes for the escape characters. The bug is triggered by raw
        // UTF-8 bytes >= 0x80 in the string payload.
        String json = "{\"emoji\":\"👍\",\"messageId\":42,\"type\":\"EMOJI\"}";
        boolean ok = p.parse(json, new JsonObj());
        check("parse succeeds with literal 👍 in a string value", ok);
        check("emoji field reaches the listener",
                "👍".equals(seen.get("emoji")));
        check("messageId field after emoji string still parses",
                "42".equals(seen.get("messageId")));
        check("type field after emoji string still parses",
                "EMOJI".equals(seen.get("type")));
    }

    static void testStructuralOpenersDoNotFireListener() {
        // Companion to testEmptyStringValueFiresListener: the fix that
        // unmasked empty STRING leaves must NOT also unmask the structural
        // J_Array / J_Object markers, which add_value emits with an empty
        // value at every container start. ChatSerializer (and similar
        // leaf-oriented listeners) routes any non-list key through
        // setSimpleFields, which throws on a list-typed field — observed
        // breaking the E2E suite the moment empty arrays appeared in a
        // /receive response. Listener must only see scalar leaves.
        JsonParser p = new JsonParser();
        java.util.List<JsonObj.JSON_Type> seenTypes = new java.util.ArrayList<>();
        p.add_listener(new JsonListener() {
            @Override public void on_data(JsonObj.JSON_Type type,
                                          java.util.List<String> key,
                                          String value) {
                seenTypes.add(type);
            }
        });
        boolean ok = p.parse("{\"arr\":[],\"obj\":{},\"s\":\"x\"}", new JsonObj());
        check("parse succeeds for mixed containers + leaf", ok);
        check("listener never receives J_Array events",
                !seenTypes.contains(JsonObj.JSON_Type.J_Array));
        check("listener never receives J_Object events",
                !seenTypes.contains(JsonObj.JSON_Type.J_Object));
        check("listener still receives the scalar (J_String) event",
                seenTypes.contains(JsonObj.JSON_Type.J_String));
    }

    static void testEmptyStringValueFiresListener() {
        // Regression for the silent listener-skip in add_value: pre-fix,
        // a JSON `"k":""` parsed cleanly but never called on_data, so the
        // model field on the consumer (e.g. ChatSerializer's reflective
        // setter) kept its declared default (null) instead of "" — which
        // broke the empty-content delete-for-all signal in ml-go's chat
        // protocol. Post-fix, the listener is called for empty strings
        // alongside every other value.
        JsonParser p = new JsonParser();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        p.add_listener(new JsonListener() {
            @Override public void on_data(JsonObj.JSON_Type type,
                                          java.util.List<String> key,
                                          String value) {
                seen.put(JsonListener.toString(key), value);
            }
        });
        boolean ok = p.parse("{\"a\":\"\",\"b\":\"x\"}", new JsonObj());
        check("parse succeeds with an empty string value", ok);
        check("listener fired for empty string value (key 'a' present)",
                seen.containsKey("a"));
        check("empty value forwarded as \"\" to listener",
                "".equals(seen.get("a")));
        check("non-empty value still forwarded unchanged",
                "x".equals(seen.get("b")));
    }

    static void testMySStreamIsUtf8() {
        // Pre-fix str.getBytes() used the JVM default charset: US-ASCII under
        // -Dfile.encoding=COMPAT on a C locale, which turns 'é' into '?'.
        // The stream must always carry UTF-8 bytes, whatever the default.
        MySStream ss = new MySStream("é👍");
        byte[] want = "é👍".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        check("MySStream holds the UTF-8 bytes of its string",
                java.util.Arrays.equals(want, ss.stream));
        JsonParser p = new JsonParser();
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        p.add_listener(new JsonListener() {
            @Override public void on_data(JsonObj.JSON_Type type,
                                          java.util.List<String> key,
                                          String value) {
                seen.put(JsonListener.toString(key), value);
            }
        });
        check("non-ASCII value round-trips through the parser",
                p.parse("{\"k\":\"café\"}", new JsonObj()) && "café".equals(seen.get("k")));
    }

    static void testNegativeZeroNumbers() {
        // Pre-fix read_number demanded a '.' after "-0", rejecting the valid
        // JSON numbers -0 and -0e1.
        for (String num : new String[] { "-0", "-0.5", "-0e1", "-0E+1", "-0.0e-2", "0", "-1", "-10.5" }) {
            JsonParser p = new JsonParser();
            String[] got = new String[1];
            p.add_listener(new JsonListener() {
                @Override public void on_data(JsonObj.JSON_Type type,
                                              java.util.List<String> key,
                                              String value) {
                    got[0] = value;
                }
            });
            check("number " + num + " parses with its text intact",
                    p.parse("{\"n\":" + num + "}", new JsonObj()) && num.equals(got[0]));
            check("number " + num + " parses as an array element",
                    new JsonParser().parse("[" + num + " ]", new JsonObj()));
        }
        for (String num : new String[] { "-01", "-", "--1", "-.5", "-0e", "01", "-a" }) {
            Boolean result = runWithTimeout(3000, () ->
                    new JsonParser().parse("[" + num + "]", new JsonObj()));
            check("invalid number " + num + " is rejected", result != null && !result);
        }
    }

    static void testEmptyKeyListToString() {
        // Pre-fix: substring(1) on "" threw StringIndexOutOfBoundsException.
        check("JsonListener.toString(empty list) is \"\"",
                () -> "".equals(JsonListener.toString(new java.util.ArrayList<>())));
        check("JsonListener.toString(one key) unchanged",
                () -> "a".equals(JsonListener.toString(java.util.List.of("a"))));
        check("JsonListener.toString(three keys) unchanged",
                () -> "a:0:b".equals(JsonListener.toString(java.util.List.of("a", "0", "b"))));
    }

    /** Target for the getObjFrom tests (fields read by reflection). */
    static class Sample {
        String a;
        int n;
    }

    /** A static final field cannot be set: getObjFrom's listener flags it. */
    static class Frozen {
        static final String K = "fixed";
        String a;
    }

    static void testGetObjFromReportsParseFailure() {
        Sample ok = new Sample();
        check("getObjFrom on valid JSON returns true and sets the fields",
                () -> JsonSerializer.getObjFrom("{\"object\":\"Sample\",\"a\":\"x\",\"n\":-0}", ok)
                        && "x".equals(ok.a) && ok.n == 0);
        // Pre-fix the parser's false result was ignored and a truncated body
        // reported success with only some fields set.
        check("getObjFrom on truncated JSON returns false",
                () -> !JsonSerializer.getObjFrom("{\"object\":\"Sample\",\"a\":\"x\",\"n\":", new Sample()));
        check("getObjFrom on trailing garbage returns false",
                () -> !JsonSerializer.getObjFrom("{\"object\":\"Sample\",\"a\":\"x\"} x", new Sample()));
        check("getObjFrom on a bad number returns false",
                () -> !JsonSerializer.getObjFrom("{\"object\":\"Sample\",\"n\":01}", new Sample()));
        // The listener-flagged failure (IllegalAccessException) still gives false.
        check("getObjFrom still returns false when a field cannot be set",
                () -> !JsonSerializer.getObjFrom("{\"object\":\"Frozen\",\"K\":\"y\"}", new Frozen()));
        check("toJson output still round-trips through getObjFrom", () -> {
            Sample src = new Sample();
            src.a = "q\"é";
            src.n = -7;
            Sample dst = new Sample();
            return JsonSerializer.getObjFrom(JsonSerializer.toJson(src), dst)
                    && src.a.equals(dst.a) && dst.n == -7;
        });
    }

    static String nested(String open, String close, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append(open);
        sb.append("1");
        for (int i = 0; i < depth; i++) sb.append(close);
        return sb.toString();
    }

    static void testNestingDepthLimit() {
        int max = JsonParser.MAX_DEPTH;
        check("arrays nested exactly MAX_DEPTH deep parse",
                new JsonParser().parse(nested("[", "]", max), new JsonObj()));
        check("objects nested exactly MAX_DEPTH deep parse",
                new JsonParser().parse(nested("{\"a\":", "}", max), new JsonObj()));
        check("arrays nested MAX_DEPTH + 1 deep fail",
                !new JsonParser().parse(nested("[", "]", max + 1), new JsonObj()));
        check("objects nested MAX_DEPTH + 1 deep fail",
                !new JsonParser().parse(nested("{\"a\":", "}", max + 1), new JsonObj()));
        // Before the limit, 16,000 levels (about 96 KB) ran a 400 MB heap out of memory.
        long t0 = System.nanoTime();
        check("100,000 levels of objects fail fast instead of exhausting the heap",
                !new JsonParser().parse(nested("{\"a\":", "}", 100_000), new JsonObj())
                        && (System.nanoTime() - t0) < 2_000_000_000L);
        JsonParser reused = new JsonParser();
        check("a parser that hit the limit parses the next document",
                !reused.parse(nested("[", "]", max + 1), new JsonObj())
                        && reused.parse("{\"a\":[1,{\"b\":2}]}", new JsonObj()));
    }

    static void testPathAndNodeBudgets() {
        // ~100 KB: one 50,000-char key over 25,000 array items made 25,000 paths of 50,000 chars each
        String wide = "{\"" + "k".repeat(50_000) + "\":[" + "1,".repeat(24_999) + "1]}";
        long t0 = System.nanoTime();
        check("a long key over many array items fails fast (path budget)",
                !new JsonParser().parse(wide, new JsonObj()) && (System.nanoTime() - t0) < 2_000_000_000L);
        String many = "[" + "1,".repeat(JsonParser.MAX_NODES) + "1]";
        check("more than MAX_NODES values fail", !new JsonParser().parse(many, new JsonObj()));
        String atLimit = "[" + "1,".repeat(JsonParser.MAX_NODES - 2) + "1]";   // the array itself is a node too
        check("MAX_NODES values in total parse", new JsonParser().parse(atLimit, new JsonObj()));
        // a large ordinary document: 5,000 objects of 8 short fields (about 1.8 MB)
        StringBuilder sb = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"from\":\"user").append(i).append("\",\"to\":\"user7\",\"group\":\"g12\",\"ts\":").append(1793700000000L + i)
              .append(",\"data\":\"").append("QUJD".repeat(60)).append("\",\"iv\":\"aXZp\",\"type\":\"text\",\"n\":").append(i).append('}');
        }
        sb.append("]}");
        check("a large ordinary document (5,000 objects, 1.8 MB) still parses", new JsonParser().parse(sb.toString(), new JsonObj()));
        JsonParser reused = new JsonParser();
        check("a parser that ran out of budget parses the next document",
                !reused.parse(wide, new JsonObj()) && reused.parse("{\"a\":[1,2]}", new JsonObj()));
    }
}
