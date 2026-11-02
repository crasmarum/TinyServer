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

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
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
}
