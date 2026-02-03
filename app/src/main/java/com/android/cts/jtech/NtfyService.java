package com.android.cts.jtech;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NtfyService extends Service {
    private static final String TAG = "NtfyService";
    private static final String CHANNEL_ID = "ntfy_channel";
    private static final String PREFS_NAME = "ntfy_prefs";
    private static final String PREF_TOPIC = "topic";
    private static final String PREF_SERVER = "server";
    private static final int NOTIFICATION_ID = 1;
    private static final int RECONNECT_DELAY_MS = 5000;

    private volatile boolean running = false;
    private Thread sseThread;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private int notificationCounter = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NtfyService::WakeLock");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildForegroundNotification());

        if (!running) {
            running = true;
            startSSE();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (sseThread != null) {
            sseThread.interrupt();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            // Foreground service channel (silent)
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Notification Service",
                NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Keeps push notifications active");
            nm.createNotificationChannel(serviceChannel);

            // Message channel (with sound)
            NotificationChannel msgChannel = new NotificationChannel(
                "ntfy_messages",
                "Forum Notifications",
                NotificationManager.IMPORTANCE_HIGH
            );
            msgChannel.setDescription("New replies and messages");
            nm.createNotificationChannel(msgChannel);
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder
            .setContentTitle("JtechForums")
            .setContentText("Listening for notifications")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void startSSE() {
        sseThread = new Thread(() -> {
            while (running) {
                try {
                    if (wakeLock != null && !wakeLock.isHeld()) {
                        wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
                    }
                    connectAndListen();
                } catch (Exception e) {
                    Log.e(TAG, "SSE error: " + e.getMessage());
                }

                if (running) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
        sseThread.start();
    }

    private void connectAndListen() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String topic = prefs.getString(PREF_TOPIC, null);
        String server = prefs.getString(PREF_SERVER, "https://ntfy.sh");

        if (topic == null || topic.isEmpty()) {
            Log.w(TAG, "No topic configured");
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            return;
        }

        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            String sseUrl = server + "/" + topic + "/sse";
            Log.i(TAG, "Connecting to: " + sseUrl);

            URL url = new URL(sseUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(0); // No read timeout for SSE

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

            String line;
            StringBuilder eventData = new StringBuilder();
            String eventType = "message";

            while (running && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    eventData.append(line.substring(5).trim());
                } else if (line.isEmpty() && eventData.length() > 0) {
                    // End of event
                    if ("message".equals(eventType)) {
                        handleMessage(eventData.toString());
                    }
                    eventData.setLength(0);
                    eventType = "message";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection error: " + e.getMessage());
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void handleMessage(String json) {
        try {
            Log.d(TAG, "Received JSON: " + json);
            JSONObject obj = new JSONObject(json);

            // Check event type - filter out non-message events
            String event = obj.optString("event", "");
            if (!event.isEmpty() && !"message".equals(event)) {
                Log.d(TAG, "Ignoring non-message event: " + event);
                return;
            }

            String title = obj.optString("title", "");
            String message = obj.optString("message", "");
            String click = obj.optString("click", "");

            // Skip if no actual message content
            if (message.isEmpty()) {
                Log.d(TAG, "Ignoring message with no content");
                return;
            }

            // Skip messages that look like topic names (connection artifacts)
            if (message.startsWith("dumbcourse-") && message.length() < 50) {
                Log.d(TAG, "Ignoring topic name message: " + message);
                return;
            }

            if (title.isEmpty()) {
                title = "JtechForums";
            }

            showNotification(title, message, click.isEmpty() ? null : click);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse message: " + e.getMessage());
        }
    }

    private void showNotification(String title, String message, String clickUrl) {
        mainHandler.post(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            if (clickUrl != null && !clickUrl.isEmpty()) {
                intent.putExtra("open_url", clickUrl);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notificationCounter, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, "ntfy_messages");
            } else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            Notification notification = builder
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(notificationCounter++, notification);
        });
    }

    public static void configure(Context context, String server, String topic) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(PREF_SERVER, server)
            .putString(PREF_TOPIC, topic)
            .apply();
    }

    public static String getTopic(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(PREF_TOPIC, null);
    }

    public static String getServer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(PREF_SERVER, "https://ntfy.sh");
    }
}
