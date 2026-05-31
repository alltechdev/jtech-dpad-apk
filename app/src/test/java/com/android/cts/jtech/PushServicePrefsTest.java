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
 * Unit tests for PushService's public static prefs API.
 * Covers: configure(), getTopic(), getServer(), isMessagesNotifEnabled(), isServiceNotifEnabled().
 * No device needed — Robolectric shadows SharedPreferences.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PushServicePrefsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
               .edit().clear().commit();
    }

    @Test
    public void getTopic_returnsNullWhenNotConfigured() {
        assertNull(PushService.getTopic(context));
    }

    @Test
    public void getServer_returnsEmptyStringWhenNotConfigured() {
        assertEquals("", PushService.getServer(context));
    }

    @Test
    public void configure_storesServerAndTopic() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");
        assertEquals("https://ntfy.sh", PushService.getServer(context));
        assertEquals("myTopic", PushService.getTopic(context));
    }

    @Test
    public void configure_overwritesPreviousValues() {
        PushService.configure(context, "https://old.example.com", "oldTopic");
        PushService.configure(context, "https://new.example.com", "newTopic");
        assertEquals("https://new.example.com", PushService.getServer(context));
        assertEquals("newTopic", PushService.getTopic(context));
    }

    @Test
    public void configure_withEmptyStrings_clearsRegistration() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");
        PushService.configure(context, "", "");
        // After clearing, the service treats empty topic as "not registered"
        String topic = PushService.getTopic(context);
        assertTrue("Expected empty or null topic after clearing", topic == null || topic.isEmpty());
    }

    @Test
    public void isMessagesNotifEnabled_trueByDefault() {
        assertTrue(PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void isServiceNotifEnabled_trueByDefault() {
        assertTrue(PushService.isServiceNotifEnabled(context));
    }

    @Test
    public void isMessagesNotifEnabled_respectsStoredFalse() {
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
               .edit()
               .putBoolean(NotificationControlReceiver.PREF_MESSAGES_ENABLED, false)
               .commit();
        assertFalse(PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void isServiceNotifEnabled_respectsStoredFalse() {
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
               .edit()
               .putBoolean(NotificationControlReceiver.PREF_SERVICE_ENABLED, false)
               .commit();
        assertFalse(PushService.isServiceNotifEnabled(context));
    }
}
