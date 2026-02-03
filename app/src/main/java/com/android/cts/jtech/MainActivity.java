package com.android.cts.jtech;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.UUID;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String BASE_URL = "https://forums.jtechforums.org/dumb";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        webView = new WebView(this);
        setContentView(webView);

        hideSystemUI();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Add JavaScript interface for push notifications
        webView.addJavascriptInterface(new PushInterface(), "NtfyBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Request notification permission (Android 13+)
        requestNotificationPermission();

        // Check if opened from notification
        String openUrl = getIntent().getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty()) {
            webView.loadUrl(openUrl);
        } else {
            webView.loadUrl(BASE_URL);
        }

        // Start notification service if already configured
        startNtfyServiceIfConfigured();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            // Permission granted or denied - service will work either way on older Android
            // On Android 13+ without permission, notifications won't show but service still runs
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String openUrl = intent.getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty() && webView != null) {
            webView.loadUrl(openUrl);
        }
    }

    private void startNtfyServiceIfConfigured() {
        String topic = NtfyService.getTopic(this);
        if (topic != null && !topic.isEmpty()) {
            Intent serviceIntent = new Intent(this, NtfyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        webView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * JavaScript interface for push notification registration.
     * Call from JS: NtfyBridge.registerPush(server, topic)
     */
    public class PushInterface {

        @JavascriptInterface
        public String getDeviceId() {
            // Generate or retrieve a stable device ID
            String deviceId = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("device_id", null);
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString();
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("device_id", deviceId)
                    .apply();
            }
            return deviceId;
        }

        @JavascriptInterface
        public String getTopic() {
            return NtfyService.getTopic(MainActivity.this);
        }

        @JavascriptInterface
        public String getServer() {
            return NtfyService.getServer(MainActivity.this);
        }

        @JavascriptInterface
        public void registerPush(String server, String topic) {
            NtfyService.configure(MainActivity.this, server, topic);

            // Start the service
            Intent serviceIntent = new Intent(MainActivity.this, NtfyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        @JavascriptInterface
        public void unregisterPush() {
            // Stop service
            Intent serviceIntent = new Intent(MainActivity.this, NtfyService.class);
            serviceIntent.setAction("STOP");
            startService(serviceIntent);

            // Clear config
            NtfyService.configure(MainActivity.this, "", "");
        }

        @JavascriptInterface
        public boolean isRegistered() {
            String topic = NtfyService.getTopic(MainActivity.this);
            return topic != null && !topic.isEmpty();
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }
    }
}
