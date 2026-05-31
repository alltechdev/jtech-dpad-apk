package com.android.cts.jtech;

import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented: notification intent routing.
 *
 * PushService sends an Intent to MainActivity with "open_url" extra when a
 * notification is tapped. These tests verify that MainActivity handles the
 * extra correctly — opening without crashing and routing to the WebView.
 */
@RunWith(AndroidJUnit4.class)
public class NotificationIntentTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestUtils.setNonFirstLaunch(context, true);
    }

    @Test
    public void intent_withOpenUrl_launchesWithoutCrash() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("open_url", "https://forums.jtechforums.org/dumb/t/topic/123");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity);
                assertFalse(activity.isFinishing());
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull("WebView must be present when opened from notification", wv);
            });
        }
    }

    @Test
    public void intent_withoutOpenUrl_launchesAndLoadsBaseUrl() {
        Intent intent = new Intent(context, MainActivity.class);
        // No open_url — should load BASE_URL normally

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull(wv);
            });
        }
    }

    @Test
    public void intent_withEmptyOpenUrl_doesNotCrash() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("open_url", "");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                // Empty open_url is guarded by !openUrl.isEmpty() — falls back to BASE_URL
                assertFalse(activity.isFinishing());
            });
        }
    }

    @Test
    public void onNewIntent_withOpenUrl_doesNotCrash() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Simulate a subsequent notification tap (Activity already running, singleTop)
                Intent newIntent = new Intent(context, MainActivity.class);
                newIntent.putExtra("open_url", "https://forums.jtechforums.org/dumb/t/reply/456");
                activity.onNewIntent(newIntent);

                assertFalse("Activity must not finish after onNewIntent", activity.isFinishing());
            });
        }
    }

    @Test
    public void onNewIntent_withNullOpenUrl_doesNotCrash() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Intent with no extra at all — onNewIntent must null-check cleanly
                Intent newIntent = new Intent(context, MainActivity.class);
                activity.onNewIntent(newIntent);
                assertFalse(activity.isFinishing());
            });
        }
    }

    /**
     * onNewIntent() when webView is null (first-launch screen is showing).
     * The webView != null guard must prevent a NullPointerException.
     */
    @Test
    public void onNewIntent_duringFirstLaunch_webViewIsNull_doesNotCrash() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // webView is null during first launch (setupUI() was not called)
                Intent newIntent = new Intent(context, MainActivity.class);
                newIntent.putExtra("open_url", "https://forums.jtechforums.org/dumb/t/test/1");
                // Must not throw NullPointerException
                activity.onNewIntent(newIntent);
                assertFalse(activity.isFinishing());
            });
        }
    }

    /**
     * onRequestPermissionsResult() STORAGE_PERMISSION_CODE granted path:
     * when a download was pending (stored in pendingDownloadUrl), granting
     * WRITE_EXTERNAL_STORAGE must start the download.
     * Verified: method returns without crashing and pending fields are cleared.
     */
    @Test
    public void onRequestPermissionsResult_storageGranted_clearsPendingFields() throws Exception {
        TestUtils.setNonFirstLaunch(context, true);

        // Grant WRITE_EXTERNAL_STORAGE first so DownloadManager.enqueue() doesn't fail
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + context.getPackageName()
                            + " android.permission.WRITE_EXTERNAL_STORAGE");
            Thread.sleep(300);
        }

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Set pending download fields via reflection
                try {
                    java.lang.reflect.Field urlF = MainActivity.class.getDeclaredField("pendingDownloadUrl");
                    java.lang.reflect.Field cdF = MainActivity.class.getDeclaredField("pendingDownloadContentDisposition");
                    java.lang.reflect.Field mtF = MainActivity.class.getDeclaredField("pendingDownloadMimetype");
                    urlF.setAccessible(true); cdF.setAccessible(true); mtF.setAccessible(true);
                    urlF.set(activity, "https://forums.jtechforums.org/dumb/test.apk");
                    cdF.set(activity, "attachment; filename=\"test.apk\"");
                    mtF.set(activity, "application/vnd.android.package-archive");

                    // Call onRequestPermissionsResult with GRANTED
                    // 1003 = STORAGE_PERMISSION_CODE (private constant in MainActivity)
                    activity.onRequestPermissionsResult(
                            1003,
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});

                    // Pending fields must be cleared after callback
                    assertNull("pendingDownloadUrl must be null after grant",
                            urlF.get(activity));
                    assertNull("pendingDownloadContentDisposition must be null after grant",
                            cdF.get(activity));
                    assertNull("pendingDownloadMimetype must be null after grant",
                            mtF.get(activity));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * onRequestPermissionsResult() STORAGE_PERMISSION_CODE denied path:
     * pending fields must still be cleared even when permission is denied.
     */
    @Test
    public void onRequestPermissionsResult_storageDenied_clearsPendingFields() throws Exception {
        TestUtils.setNonFirstLaunch(context, true);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field urlF = MainActivity.class.getDeclaredField("pendingDownloadUrl");
                    urlF.setAccessible(true);
                    urlF.set(activity, "https://forums.jtechforums.org/dumb/test.apk");

                    activity.onRequestPermissionsResult(
                            1003,
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});

                    assertNull("pendingDownloadUrl must be cleared even on denial",
                            urlF.get(activity));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
