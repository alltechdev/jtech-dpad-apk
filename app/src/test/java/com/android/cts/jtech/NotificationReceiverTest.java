package com.android.cts.jtech;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for NotificationControlReceiver intent parsing.
 * Covers: type=messages, type=service, missing extras, unknown type, wrong action.
 * No device needed — Robolectric shadows BroadcastReceiver context.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NotificationReceiverTest {

    private static final String ACTION = "com.jtech.forums.NOTIFICATION_CONTROL";
    private static final String PREFS = "push_prefs";

    private Context context;
    private NotificationControlReceiver receiver;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        receiver = new NotificationControlReceiver();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().clear().commit();
    }

    // --- type=messages ---

    @Test
    public void onReceive_messagesEnabled_true() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(NotificationControlReceiver.PREF_MESSAGES_ENABLED, false).commit();

        Intent intent = makeIntent("messages", true);
        receiver.onReceive(context, intent);

        assertTrue(PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void onReceive_messagesEnabled_false() {
        Intent intent = makeIntent("messages", false);
        receiver.onReceive(context, intent);

        assertFalse(PushService.isMessagesNotifEnabled(context));
    }

    // --- type=service ---

    @Test
    public void onReceive_serviceEnabled_false() {
        Intent intent = makeIntent("service", false);
        receiver.onReceive(context, intent);

        assertFalse(PushService.isServiceNotifEnabled(context));
    }

    @Test
    public void onReceive_serviceEnabled_true() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(NotificationControlReceiver.PREF_SERVICE_ENABLED, false).commit();

        Intent intent = makeIntent("service", true);
        receiver.onReceive(context, intent);

        assertTrue(PushService.isServiceNotifEnabled(context));
    }

    // --- edge cases ---

    @Test
    public void onReceive_wrongAction_doesNothing() {
        Intent intent = new Intent("com.jtech.forums.SOME_OTHER_ACTION");
        intent.putExtra("type", "messages");
        intent.putExtra("enabled", false);

        receiver.onReceive(context, intent);

        assertTrue("Wrong action should not change prefs", PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void onReceive_missingTypeExtra_doesNothing() {
        Intent intent = new Intent(ACTION);
        intent.putExtra("enabled", false);
        // No "type" extra

        receiver.onReceive(context, intent);

        assertTrue("Missing type should not change prefs", PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void onReceive_missingEnabledExtra_doesNothing() {
        Intent intent = new Intent(ACTION);
        intent.putExtra("type", "messages");
        // No "enabled" extra — hasExtra("enabled") returns false

        receiver.onReceive(context, intent);

        assertTrue("Missing enabled should not change prefs", PushService.isMessagesNotifEnabled(context));
    }

    @Test
    public void onReceive_unknownType_doesNothing() {
        Intent intent = new Intent(ACTION);
        intent.putExtra("type", "unknown_type");
        intent.putExtra("enabled", false);

        receiver.onReceive(context, intent);

        assertTrue("Unknown type should not change prefs", PushService.isMessagesNotifEnabled(context));
    }

    // --- service case: null topic → no service start ---

    @Test
    public void onReceive_serviceType_nullTopic_doesNotCrash() {
        // When PushService is not configured (topic is null), the service case
        // must NOT crash — it should silently skip the startService call.
        // Prefs cleared in setUp; getTopic() returns null.
        receiver.onReceive(context, makeIntent("service", false));
        // Pref must still be updated:
        assertFalse(PushService.isServiceNotifEnabled(context));
    }

    // --- helpers ---

    private Intent makeIntent(String type, boolean enabled) {
        Intent intent = new Intent(ACTION);
        intent.putExtra("type", type);
        intent.putExtra("enabled", enabled);
        return intent;
    }
}
