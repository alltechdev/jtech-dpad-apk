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
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

public class PushService extends Service {
    private static final String TAG = "PushService";
    private static final String CHANNEL_ID = "push_channel";
    private static final String PREFS_NAME = "push_prefs";
    private static final String PREF_TOPIC = "topic";
    private static final String PREF_SERVER = "server";
    private static final int NOTIFICATION_ID = 1;
    private static final int RECONNECT_DELAY_MS = 5000;
    // Memory-safety bounds: prevent OOM from malicious or misbehaving SSE servers
    private static final int MAX_SSE_LINE_LENGTH  = 65_536;   // 64 KB per SSE line
    private static final int MAX_SSE_EVENT_LENGTH = 262_144;  // 256 KB per complete SSE event
    private static final int MAX_TITLE_LENGTH     = 256;
    private static final int MAX_MESSAGE_LENGTH   = 1024;
    private static final int MAX_CLICK_URL_LENGTH = 2048;

    volatile boolean running = false; // package-private for unit testing
    private volatile HttpURLConnection currentConnection;
    private Thread sseThread;
    private Handler mainHandler;
    // AtomicInteger: makes intent explicit; all access is via mainHandler.post() (main thread)
    // but AtomicInteger's getAndUpdate() eliminates any JMM reordering concern.
    private final AtomicInteger notificationCounter = new AtomicInteger(100);

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && "UPDATE_FOREGROUND".equals(intent.getAction())) {
            if (isServiceNotifEnabled(this)) {
                startForeground(NOTIFICATION_ID, buildForegroundNotification());
            } else {
                stopForegroundCompat();
            }
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildForegroundNotification());

        if (!isServiceNotifEnabled(this)) {
            stopForegroundCompat();
        }

        if (!running) {
            running = true;
            startSSE();
        } else {
            // Service already running - force reconnect to pick up new server/topic from prefs
            forceReconnect();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        // Disconnect the socket first: interrupt() alone won't unblock a thread
        // sitting in blocking readLine() — the socket must be closed to release it.
        forceReconnect();
        if (sseThread != null) {
            sseThread.interrupt();
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            // stopForeground(int) added in API 24; use boolean overload on API 23
            stopForeground(true);
        }
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
                "push_messages",
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
                    connectAndListen();
                } catch (Throwable e) {
                    // Catch Throwable (not just Exception) so that a StackOverflowError from
                    // org.json recursive descent parsing does not silently kill this thread.
                    Log.e(TAG, "SSE error: " + e);
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
        String server = prefs.getString(PREF_SERVER, "");

        if (topic == null || topic.isEmpty()) {
            Log.w(TAG, "No topic configured");
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            return;
        }

        if (server == null || server.isEmpty()) {
            Log.w(TAG, "No server configured");
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            return;
        }

        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            String sseUrl = server + "/" + topic;
            Log.i(TAG, "Connecting to: " + sseUrl);

            URL url = new URL(sseUrl);
            // Re-validate scheme at connect time: registerPush() enforces https://,
            // but this guards against any direct prefs write that bypasses it.
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                Log.e(TAG, "SSE server must use https://; got: " + url.getProtocol());
                try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                return;
            }
            conn = (HttpURLConnection) url.openConnection();
            currentConnection = conn;
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(30000);
            // 90-second read timeout: if the server sends no data (including SSE keep-alive
            // comments) for 90s, assume the connection is dead and reconnect.
            // Without a timeout, a network partition blocks this thread indefinitely.
            conn.setReadTimeout(90_000);

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            processSSEStream(reader);
        } catch (Throwable e) {
            // Catch Throwable so StackOverflowError from nested JSON parsing propagates
            // to the outer reconnect loop rather than killing the SSE thread silently.
            Log.e(TAG, "Connection error: " + e);
        } finally {
            currentConnection = null;
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Parses an SSE stream line-by-line, dispatching message events to handleMessage().
     * Package-private so unit tests can inject a StringReader without a real HTTP connection.
     */
    void processSSEStream(BufferedReader reader) throws java.io.IOException {
        String line;
        StringBuilder eventData = new StringBuilder();
        String eventType = "message";

        while (running && (line = reader.readLine()) != null) {
            // readLine() allocates the full line regardless; discard immediately
            // if oversized so it cannot be used to fill the event buffer.
            if (line.length() > MAX_SSE_LINE_LENGTH) {
                Log.w(TAG, "Oversized SSE line dropped (" + line.length() + " chars)");
                eventData.setLength(0);
                eventType = "message";
                continue;
            }
            if (line.startsWith("event:")) {
                String rawType = line.substring(6).trim();
                // Valid SSE event type names are short identifiers; cap to 64 chars
                eventType = rawType.length() > 64 ? rawType.substring(0, 64) : rawType;
            } else if (line.startsWith("data:")) {
                // Guard against unbounded accumulation across many data: lines
                if (eventData.length() < MAX_SSE_EVENT_LENGTH) {
                    eventData.append(line.substring(5).trim());
                } else {
                    Log.w(TAG, "SSE event exceeds " + MAX_SSE_EVENT_LENGTH + " bytes; extra data lines dropped");
                }
            } else if (line.isEmpty() && eventData.length() > 0) {
                // End of event
                if ("message".equals(eventType)) {
                    handleMessage(eventData.toString());
                }
                eventData.setLength(0);
                eventType = "message";
            }
        }
    }

    private void forceReconnect() {
        HttpURLConnection conn = currentConnection;
        if (conn != null) {
            Log.i(TAG, "Forcing reconnect to pick up new prefs");
            try {
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Error disconnecting: " + e.getMessage());
            }
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

            String title   = truncate(obj.optString("title", ""), MAX_TITLE_LENGTH);
            String message = truncate(obj.optString("message", ""), MAX_MESSAGE_LENGTH);
            String click   = obj.optString("click", "");
            // Validate click URL at source: only https:// accepted; reject empty-host and oversized
            if (!click.isEmpty()) {
                try {
                    URL clickUrl = new URL(click);
                    if (!"https".equalsIgnoreCase(clickUrl.getProtocol())
                            || clickUrl.getHost() == null || clickUrl.getHost().isEmpty()
                            || click.length() > MAX_CLICK_URL_LENGTH) {
                        click = "";
                    }
                } catch (MalformedURLException e) {
                    click = "";
                }
            }

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
        } catch (Throwable e) {
            // Catch Throwable: org.json uses recursive descent — deeply nested JSON (e.g.
            // 3000 levels deep in 256 KB) throws StackOverflowError, not Exception.
            Log.e(TAG, "Failed to parse message: " + e);
        }
    }

    private void showNotification(String title, String message, String clickUrl) {
        if (!isMessagesNotifEnabled(this)) {
            Log.d(TAG, "Message notifications disabled, skipping");
            return;
        }
        mainHandler.post(() -> {
            // Claim the next notification ID. get()+set() is safe here because this Runnable
            // executes on the main thread and all notificationCounter access is via mainHandler.post().
            // AtomicInteger provides the visibility guarantees; getAndUpdate() (API 24+) is not used.
            int id = notificationCounter.get();
            notificationCounter.set(id >= Integer.MAX_VALUE ? 100 : id + 1);

            Intent intent = new Intent(this, MainActivity.class);
            if (clickUrl != null && !clickUrl.isEmpty()) {
                intent.putExtra("open_url", clickUrl);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, "push_messages");
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
            if (nm != null) nm.notify(id, notification);
        });
    }

    public static boolean isMessagesNotifEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(NotificationControlReceiver.PREF_MESSAGES_ENABLED, true);
    }

    public static boolean isServiceNotifEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(NotificationControlReceiver.PREF_SERVICE_ENABLED, true);
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
        return prefs.getString(PREF_SERVER, "");
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
