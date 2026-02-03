package com.android.cts.jtech;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "JtechPrefs";
    private static final String PREF_SCREEN_SIZE = "screen_size";
    private static final String PREF_FIRST_LAUNCH = "first_launch";

    private WebView webView;
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

        if (isFirstLaunch) {
            // First launch - show screen size selection dialog
            showScreenSizeDialog(prefs);
        } else {
            // Not first launch - setup UI normally
            setupUI();
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

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("https://forums.jtechforums.org/dumb");
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
}
