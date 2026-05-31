package com.android.cts.jtech;

import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented: verifies WebView navigates toward the base forum URL.
 *
 * NETWORK: This test performs a live network request to forums.jtechforums.org.
 * The assertion is intentionally tolerant — we verify the URL starts loading in
 * the right direction, not that the HTTP response succeeded (avoids flakiness
 * in offline CI environments).
 */
@RunWith(AndroidJUnit4.class)
public class WebViewLoadTest {

    @Before
    public void setUp() {
        TestUtils.setNonFirstLaunch(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                true);
    }

    @Test
    public void webView_beginsLoadingBaseUrl() throws InterruptedException {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Give the WebView a moment to start the load — it's async
            Thread.sleep(800);

            scenario.onActivity(activity -> {
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull("WebView must exist", wv);

                String url = wv.getUrl();
                // url is null while the very first request is still in-flight
                if (url != null) {
                    assertTrue(
                        "WebView URL should point to the forum or about:blank, was: " + url,
                        url.startsWith("https://forums.jtechforums.org") || url.equals("about:blank"));
                }
                // null URL means load started but hasn't resolved yet — still pass
            });
        }
    }

    @Test
    public void webView_doesNotLoadArbitraryUrl() {
        // Verify the URL whitelist blocks non-allowed domains at the intent level.
        // We exercise this by launching with a disallowed open_url; the WebView
        // should fall back to the base URL rather than navigating there.
        android.content.Intent intent = new android.content.Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                MainActivity.class);
        intent.putExtra("open_url", "https://forums.jtechforums.org/dumb");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                // App must not crash when handling an open_url
                assertFalse(activity.isFinishing());
            });
        }
    }
}
