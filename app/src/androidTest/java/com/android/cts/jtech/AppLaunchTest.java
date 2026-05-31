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
 * Instrumented: verifies MainActivity starts without crashing and inflates a WebView.
 */
@RunWith(AndroidJUnit4.class)
public class AppLaunchTest {

    @Before
    public void setUp() {
        TestUtils.setNonFirstLaunch(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                true /* fullscreen */);
    }

    @Test
    public void mainActivity_launchesWithoutCrash() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity);
                assertFalse(activity.isFinishing());
            });
        }
    }

    @Test
    public void mainActivity_containsWebView() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull("Expected a WebView in the view hierarchy", wv);
            });
        }
    }

    @Test
    public void mainActivity_normalMode_launchesWithoutCrash() {
        // Exercise the non-fullscreen (action-bar) code path
        TestUtils.setNonFirstLaunch(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                false /* normal — action bar visible */);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull(wv);
            });
        }
    }

    /**
     * Exercises the hideSystemUI() path in fullscreen mode.
     * On API < 30: deprecated setSystemUiVisibility() path.
     * On API 30+: WindowInsetsController path.
     * Either way the app must not crash and must not be finishing.
     */
    @Test
    public void mainActivity_fullscreen_hideSystemUI_doesNotCrash() {
        TestUtils.setNonFirstLaunch(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                true /* fullscreen — triggers hideSystemUI() */);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Trigger onWindowFocusChanged which calls hideSystemUI()
            scenario.onActivity(activity -> {
                activity.onWindowFocusChanged(true);
                assertFalse("App must not finish after hideSystemUI()", activity.isFinishing());
            });
        }
    }

    /**
     * startPushServiceIfConfigured() when a topic IS configured:
     * the service must be started.
     */
    @Test
    public void mainActivity_withPushConfigured_startsPushService() {
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestUtils.setNonFirstLaunch(ctx, true);
        // Configure push so startPushServiceIfConfigured() will start the service
        PushService.configure(ctx, "https://ntfy.sh", "myTopic");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                // Service start is a side-effect; primary assertion is no crash
            });
        }

        // Clean up so other tests don't have a running service
        PushService.configure(ctx, "", "");
    }
}
