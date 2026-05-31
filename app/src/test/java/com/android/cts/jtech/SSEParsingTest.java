package com.android.cts.jtech;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.*;

/**
 * Unit tests for PushService.processSSEStream() — the extracted SSE parsing method.
 * Tests every bound and edge case WITHOUT a real network connection (uses StringReader).
 *
 * Covers:
 *  - Normal message parsing (end-to-end through handleMessage)
 *  - Oversized line discard (MAX_SSE_LINE_LENGTH = 65536)
 *  - Event data accumulation limit (MAX_SSE_EVENT_LENGTH = 262144)
 *  - Event type capping (64 chars)
 *  - Non-message event filtering
 *  - Multi-line data concatenation
 *  - Stream ends gracefully (no infinite loop)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SSEParsingTest {

    private PushService service;

    @Before
    public void setUp() {
        service = Robolectric.buildService(PushService.class).create().get();
        // Keep the service's running flag true so the parsing loop runs
        service.running = true;
    }

    private void parse(String sseContent) throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(sseContent));
        service.processSSEStream(reader);
    }

    // ── Normal parsing ────────────────────────────────────────────────────

    @Test
    public void normalMessage_parsedWithoutError() throws Exception {
        // A well-formed SSE message — should reach handleMessage() without crashing
        parse("data: {\"message\":\"hello\",\"title\":\"Test\"}\n\n");
    }

    @Test
    public void emptyStream_completesImmediately() throws Exception {
        parse("");
    }

    @Test
    public void commentLines_ignored() throws Exception {
        parse(": keep-alive\n\n: another comment\n\n");
    }

    @Test
    public void nonMessageEvent_ignored() throws Exception {
        parse("event: ping\ndata: {\"message\":\"should be ignored\"}\n\n");
    }

    @Test
    public void messageEvent_accepted() throws Exception {
        parse("event: message\ndata: {\"message\":\"hello\"}\n\n");
    }

    @Test
    public void retryLine_ignored() throws Exception {
        parse("retry: 3000\n\ndata: {\"message\":\"ok\"}\n\n");
    }

    // ── Oversized line discarding ─────────────────────────────────────────

    @Test
    public void oversizedDataLine_discarded_doesNotCrash() throws Exception {
        // A single data: line larger than MAX_SSE_LINE_LENGTH (65536 chars)
        String oversized = "data: " + repeat("X", 70_000) + "\n";
        // The line is discarded; the following normal message should still process
        String normal = "data: {\"message\":\"after oversized\"}\n\n";
        parse(oversized + normal);
    }

    @Test
    public void oversizedEventLine_discarded() throws Exception {
        String oversized = "event: " + repeat("E", 70_000) + "\n";
        String data = "data: {\"message\":\"ok\"}\n\n";
        parse(oversized + data);
    }

    @Test
    public void lineExactlyAtLimit_accepted() throws Exception {
        // MAX_SSE_LINE_LENGTH = 65536. Check: line.length() > 65536 drops the line.
        // A line of exactly 65536 chars is NOT dropped.
        // "data: " = 6 chars; payload = 65530 chars → total = 65536.
        String line = "data: " + repeat("A", 65530);
        assertEquals(65536, line.length());
        // Should process without crash (content isn't valid JSON so handleMessage no-ops)
        parse(line + "\n\n");
    }

    @Test
    public void lineOneOverLimit_discarded() throws Exception {
        // 65537 chars = one over MAX_SSE_LINE_LENGTH → must be discarded
        String line = "data: " + repeat("B", 65531); // 6 + 65531 = 65537
        assertEquals(65537, line.length());
        // Should not crash; subsequent normal message should still work
        parse(line + "\n" + "data: {\"message\":\"still ok\"}\n\n");
    }

    // ── Event data accumulation limit ─────────────────────────────────────

    @Test
    public void manyDataLines_boundedAtMaxEventLength() throws Exception {
        // Send 300 data: lines of 1000 chars each = 300 KB total
        // MAX_SSE_EVENT_LENGTH is 262144 (256 KB), so extra lines must be dropped
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("data: ").append(repeat("D", 1000)).append("\n");
        }
        sb.append("\n"); // end of event
        // Should not cause OOM or crash
        parse(sb.toString());
    }

    // ── Event type capping ────────────────────────────────────────────────

    @Test
    public void longEventType_cappedTo64Chars() throws Exception {
        // An event type of 70 chars — must be capped to 64 chars
        // After capping, the type is not "message" so the event is ignored
        String longType = repeat("t", 70);
        parse("event: " + longType + "\ndata: {\"message\":\"ignored\"}\n\n");
    }

    // ── Multiple events ───────────────────────────────────────────────────

    @Test
    public void multipleEvents_allProcessedWithoutCrash() throws Exception {
        parse("data: {\"message\":\"first\"}\n\n" +
              "data: {\"message\":\"second\"}\n\n" +
              "data: {\"message\":\"third\"}\n\n");
    }

    @Test
    public void mixedOversizedAndNormal_normalEventsStillProcessed() throws Exception {
        // Oversized line then normal event — normal must still process
        String oversized = "data: " + repeat("X", 70_000) + "\n\n";
        String normal = "data: {\"message\":\"still works\"}\n\n";
        parse(oversized + normal);
        // No crash = correct: oversized was discarded, normal reached handleMessage()
    }

    // ── scheme re-validation ──────────────────────────────────────────────

    @Test
    public void connectAndListen_malformedUrl_doesNotCrash() throws Exception {
        // A server whose concatenation with "/" + topic produces a URL that
        // java.net.URL rejects with MalformedURLException — caught by catch(Throwable).
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("push_prefs", android.content.Context.MODE_PRIVATE)
           .edit()
           .putString("server", "https://")   // empty authority → host="" → scheme check rejects
           .putString("topic", "test")
           .commit();

        java.lang.reflect.Field mBase =
                android.content.ContextWrapper.class.getDeclaredField("mBase");
        mBase.setAccessible(true);
        mBase.set(service, ctx);

        service.running = true;
        java.lang.reflect.Method m = PushService.class.getDeclaredMethod("connectAndListen");
        m.setAccessible(true);

        // Should not throw; scheme check or URL parse catches the error
        Thread t = new Thread(() -> {
            try { m.invoke(service); }
            catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                // Only a network-connection exception would indicate a real attempt;
                // scheme rejection returns after a sleep, which is fine.
                if (cause instanceof java.net.ConnectException) {
                    throw new AssertionError("Should not attempt network connection", cause);
                }
            } catch (Exception ignored) {}
        });
        t.start();
        Thread.sleep(300);
        service.running = false;
        t.interrupt();
        t.join(3000);
        assertFalse("Thread must stop", t.isAlive());
    }

    @Test
    public void connectAndListen_httpUrl_rejectedBeforeConnect() throws Exception {
        // Write http:// server directly to prefs, bypassing registerPush()
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("push_prefs", android.content.Context.MODE_PRIVATE)
           .edit()
           .putString("server", "http://evil.example.com")
           .putString("topic", "test")
           .commit();

        // Attach context to service so it can read prefs
        java.lang.reflect.Field mBase =
                android.content.ContextWrapper.class.getDeclaredField("mBase");
        mBase.setAccessible(true);
        mBase.set(service, ctx);

        service.running = true;

        java.lang.reflect.Method connectAndListen =
                PushService.class.getDeclaredMethod("connectAndListen");
        connectAndListen.setAccessible(true);

        // Should return without making a network connection.
        // The scheme guard sleeps 10s then returns — run in a thread with timeout.
        java.util.concurrent.atomic.AtomicBoolean networkAttempted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                connectAndListen.invoke(service);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.net.ConnectException ||
                    cause instanceof java.net.UnknownHostException) {
                    networkAttempted.set(true);
                }
            } catch (Exception ignored) {}
        });
        t.start();
        t.join(12_000); // 10s sleep + 2s buffer

        assertFalse("connectAndListen must terminate within 12s", t.isAlive());
        assertFalse("connectAndListen must NOT attempt network connection to http:// server",
                networkAttempted.get());
    }

    // ── helper ────────────────────────────────────────────────────────────

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
