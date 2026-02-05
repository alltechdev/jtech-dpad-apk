package com.android.cts.jtech;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.UUID;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "JtechPrefs";
    private static final String PREF_SCREEN_SIZE = "screen_size";
    private static final String PREF_FIRST_LAUNCH = "first_launch";
    private static final String BASE_URL = "https://forums.jtechforums.org/dumb";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;
    private static final int FILE_CHOOSER_CODE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean useFullscreen = true; // Default to fullscreen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Check preference and set theme BEFORE super.onCreate()
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true);

        if (!isFirstLaunch) {
            // Load saved preference and set theme
            String screenSize = prefs.getString(PREF_SCREEN_SIZE, "small");
            useFullscreen = screenSize.equals("small");
            setTheme(useFullscreen ? R.style.AppTheme : R.style.AppTheme_Normal);
        } else {
            // First launch - use normal theme for dialog
            setTheme(R.style.AppTheme_Normal);
        }

        super.onCreate(savedInstanceState);

        // Request notification permission (Android 13+)
        requestNotificationPermission();

        if (isFirstLaunch) {
            // First launch - show screen size selection dialog
            showScreenSizeDialog(prefs);
        } else {
            // Not first launch - setup UI normally
            setupUI();
        }
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

    private void showScreenSizeDialog(SharedPreferences prefs) {
        // Create custom layout with buttons
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Convert dp to pixels for button size
        int widthPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200, getResources().getDisplayMetrics());
        int heightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, getResources().getDisplayMetrics());

        // Small Screen button
        Button smallButton = new Button(this);
        smallButton.setText("Small Screen (no notch)");
        smallButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        smallButton.setTextColor(0xFFFFFFFF); // White text
        smallButton.setFocusable(true);
        smallButton.setFocusableInTouchMode(true);
        smallButton.setBackgroundResource(R.drawable.button_selector);
        LinearLayout.LayoutParams smallButtonParams = new LinearLayout.LayoutParams(widthPx, heightPx);
        smallButtonParams.setMargins(0, 0, 0, 15);
        smallButton.setLayoutParams(smallButtonParams);
        smallButton.setOnClickListener(v -> {
            prefs.edit()
                .putString(PREF_SCREEN_SIZE, "small")
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply();
            recreate();
        });

        // Normal Screen button
        Button normalButton = new Button(this);
        normalButton.setText("Normal Screen (has notch)");
        normalButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        normalButton.setTextColor(0xFFFFFFFF); // White text
        normalButton.setFocusable(true);
        normalButton.setFocusableInTouchMode(true);
        normalButton.setBackgroundResource(R.drawable.button_selector);
        LinearLayout.LayoutParams normalButtonParams = new LinearLayout.LayoutParams(widthPx, heightPx);
        normalButton.setLayoutParams(normalButtonParams);
        normalButton.setOnClickListener(v -> {
            prefs.edit()
                .putString(PREF_SCREEN_SIZE, "normal")
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply();
            recreate();
        });

        layout.addView(smallButton);
        layout.addView(normalButton);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.CompactDialog)
            .setTitle("Screen Type")
            .setView(layout)
            .setCancelable(false)
            .create();

        dialog.show();

        // Force focus on first button to show outline immediately
        smallButton.setFocusableInTouchMode(true);
        smallButton.requestFocusFromTouch();
    }

    private void setupUI() {
        if (!useFullscreen) {
            // Normal mode with action bar
            // Clear any fullscreen flags
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Hide title text and icon in action bar
            if (getActionBar() != null) {
                getActionBar().setDisplayShowTitleEnabled(false);
                getActionBar().setDisplayShowHomeEnabled(false);
            }

            // Create container with fitsSystemWindows to position below action bar
            FrameLayout container = new FrameLayout(this);
            container.setFitsSystemWindows(true);
            container.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            webView = new WebView(this);
            webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            container.addView(webView);
            setContentView(container);
        } else {
            // Fullscreen mode
            webView = new WebView(this);
            setContentView(webView);
            hideSystemUI();
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Add JavaScript interface for push notifications
        webView.addJavascriptInterface(new PushInterface(), "NtfyBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                Intent intent = params.createIntent();
                startActivityForResult(intent, FILE_CHOOSER_CODE);
                return true;
            }
        });

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE) {
            if (fileChooserCallback != null) {
                Uri[] results = (resultCode == RESULT_OK && data != null)
                    ? new Uri[]{data.getData()} : null;
                fileChooserCallback.onReceiveValue(results);
                fileChooserCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
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
        if (hasFocus && useFullscreen) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        if (webView != null) {
            webView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
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
