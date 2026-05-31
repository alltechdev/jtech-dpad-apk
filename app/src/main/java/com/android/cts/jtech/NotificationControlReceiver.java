package com.android.cts.jtech;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class NotificationControlReceiver extends BroadcastReceiver {
    private static final String TAG = "NotifControl";
    private static final String ACTION = "com.jtech.forums.NOTIFICATION_CONTROL";
    private static final String PREFS_NAME = "push_prefs";

    public static final String PREF_MESSAGES_ENABLED = "notif_messages_enabled";
    public static final String PREF_SERVICE_ENABLED = "notif_service_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }

        String type = intent.getStringExtra("type");
        if (type == null) {
            Log.w(TAG, "Missing 'type' extra (expected 'messages' or 'service')");
            return;
        }

        if (!intent.hasExtra("enabled")) {
            Log.w(TAG, "Missing 'enabled' extra (expected boolean)");
            return;
        }

        boolean enabled = intent.getBooleanExtra("enabled", true);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        switch (type) {
            case "messages":
                prefs.edit().putBoolean(PREF_MESSAGES_ENABLED, enabled).apply();
                Log.i(TAG, "Message notifications " + (enabled ? "enabled" : "disabled"));
                break;

            case "service":
                prefs.edit().putBoolean(PREF_SERVICE_ENABLED, enabled).apply();
                Log.i(TAG, "Service notification " + (enabled ? "enabled" : "disabled"));

                // Only update an already-running (configured) service.
                // On API 26+, startService() to an existing foreground service is allowed
                // from a background context; starting a brand-new service is not.
                // Skipping when not configured prevents launching a zombie service.
                if (PushService.getTopic(context) != null) {
                    Intent serviceIntent = new Intent(context, PushService.class);
                    serviceIntent.setAction("UPDATE_FOREGROUND");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                }
                break;

            default:
                Log.w(TAG, "Unknown type: " + type + " (expected 'messages' or 'service')");
                break;
        }
    }
}
