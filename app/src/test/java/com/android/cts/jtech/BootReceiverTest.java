package com.android.cts.jtech;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.*;

/**
 * Unit tests for BootReceiver.onReceive():
 *  - When topic is configured: service must be started (startForegroundService on API 26+,
 *    startService on API 23-25)
 *  - When topic is null/empty: service must NOT be started
 *  - Pre-API-26 code path is explicitly exercised via @Config(sdk = 23)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class BootReceiverTest {

    private Context context;
    private BootReceiver receiver;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        receiver = new BootReceiver();
        // Clear any prior push config
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
               .edit().clear().commit();
    }

    @Test
    public void onReceive_whenConfigured_startsService() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");

        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        ShadowApplication shadow = Shadows.shadowOf((Application) context);
        Intent started = shadow.getNextStartedService();
        assertNotNull("PushService must be started on BOOT_COMPLETED when configured", started);
        assertEquals("android.intent.action.BOOT_COMPLETED triggered wrong service",
                PushService.class.getName(),
                started.getComponent().getClassName());
    }

    @Test
    public void onReceive_whenTopicNull_doesNotStartService() {
        // Prefs cleared in setUp — topic is null
        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        ShadowApplication shadow = Shadows.shadowOf((Application) context);
        assertNull("PushService must NOT be started when topic is null",
                shadow.getNextStartedService());
    }

    @Test
    public void onReceive_whenTopicEmpty_doesNotStartService() {
        PushService.configure(context, "https://ntfy.sh", "");

        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        ShadowApplication shadow = Shadows.shadowOf((Application) context);
        assertNull("PushService must NOT be started when topic is empty",
                shadow.getNextStartedService());
    }

    @Test
    public void onReceive_wrongAction_doesNotStartService() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");

        receiver.onReceive(context, new Intent("SOME_OTHER_ACTION"));

        ShadowApplication shadow = Shadows.shadowOf((Application) context);
        assertNull("PushService must NOT be started for a non-BOOT intent",
                shadow.getNextStartedService());
    }

    /**
     * Explicitly exercises the pre-API-26 startService() branch (not startForegroundService).
     * This is the path taken on API 23-25 devices.
     */
    @Test
    @Config(sdk = 23)
    public void onReceive_api23_usesStartService_notForeground() {
        PushService.configure(context, "https://ntfy.sh", "myTopic");

        receiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        ShadowApplication shadow = Shadows.shadowOf((Application) context);
        Intent started = shadow.getNextStartedService();
        assertNotNull("Service must be started on API 23 via startService()", started);
        assertEquals(PushService.class.getName(), started.getComponent().getClassName());
    }
}
