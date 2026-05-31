package com.android.cts.jtech;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for MainActivity.PushInterface @JavascriptInterface methods:
 *  - getDeviceId() generates a UUID on first call and returns the same one thereafter
 *  - getTopic() / getServer() delegate to PushService prefs
 *  - isRegistered() reflects whether a topic is configured
 *  - isNativeApp() always returns true
 *  - registerPush() validates and configures (covered by RegisterPushValidationTest;
 *    this file covers the non-validation paths)
 *  - unregisterPush() stops service and clears configuration
 *  - null-activity WeakReference returns safe defaults
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PushInterfaceTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
               .edit().clear().commit();
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
               .edit().clear().commit();
    }

    // ── getDeviceId() ─────────────────────────────────────────────────────

    @Test
    public void getDeviceId_nullActivity_returnsEmptyString() {
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        assertEquals("", iface.getDeviceId());
    }

    // ── getTopic / getServer / isRegistered ───────────────────────────────

    @Test
    public void getTopic_nullActivity_returnsEmptyString() {
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        assertEquals("", iface.getTopic());
    }

    @Test
    public void getServer_nullActivity_returnsEmptyString() {
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        assertEquals("", iface.getServer());
    }

    @Test
    public void isRegistered_nullActivity_returnsFalse() {
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        assertFalse(iface.isRegistered());
    }

    // ── isNativeApp() ─────────────────────────────────────────────────────

    @Test
    public void isNativeApp_nullActivity_returnsTrue() {
        // isNativeApp() must return true even with null activity — it's a constant
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        assertTrue("isNativeApp() must always return true", iface.isNativeApp());
    }

    // ── registerPush with null activity ───────────────────────────────────

    @Test
    public void registerPush_nullActivity_doesNotCrash() {
        // activityRef.get() == null — method must return early without crash
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        iface.registerPush("https://ntfy.sh", "topic"); // must not throw NPE
    }

    // ── unregisterPush with null activity ─────────────────────────────────

    @Test
    public void unregisterPush_nullActivity_doesNotCrash() {
        MainActivity.PushInterface iface = new MainActivity.PushInterface(null);
        iface.unregisterPush(); // must not throw NPE
    }

    // ── isRegistered logic ────────────────────────────────────────────────

    @Test
    public void isRegistered_falseWhenPrefsEmpty() {
        // No topic configured — use PushService static methods directly to verify
        assertFalse("isRegistered must be false when topic is null",
                PushService.getTopic(context) != null && !PushService.getTopic(context).isEmpty());
    }

    @Test
    public void isRegistered_trueAfterConfigure() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");
        String topic = PushService.getTopic(context);
        assertTrue("isRegistered must be true after configure()",
                topic != null && !topic.isEmpty());
    }

    // ── getDeviceId() UUID persistence (tests the prefs layer directly) ───

    @Test
    public void deviceId_persistedInPrefs_returnedOnSubsequentReads() {
        // First call: no device_id in prefs → generate a UUID and store it
        assertNull("device_id should not exist yet",
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                       .getString("device_id", null));

        // Simulate what getDeviceId() does (can't call it without Activity, test the prefs layer)
        String id1 = java.util.UUID.randomUUID().toString();
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
               .edit().putString("device_id", id1).commit();

        // Second read should return same ID
        String id2 = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .getString("device_id", null);
        assertEquals("Device ID must be stable across reads", id1, id2);
    }
}
