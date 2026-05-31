package com.android.cts.jtech;

import android.Manifest;
import android.annotation.SuppressLint;
import android.widget.TextView;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends ComponentActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "JtechPrefs";
    private static final String PREF_SCREEN_SIZE = "screen_size";
    private static final String PREF_FIRST_LAUNCH = "first_launch";
    private static final String BASE_URL = "https://forums.jtechforums.org/dumb";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;
    private static final int STORAGE_PERMISSION_CODE = 1003;

    // Domains whose URLs are allowed to load inside the WebView.
    private static final List<String> ALLOWED_DOMAINS = Arrays.asList(
        "jtechforums.org",
        "forums.jtechforums.org",
        "drive.usercontent.google.com",
        "drive.google.com",
        "dropbox.com",
        "github.com",
        "release-assets.githubusercontent.com"
    );

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean useFullscreen = true;
    private String pendingDownloadUrl;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimetype;

    // Replaces startActivityForResult + onActivityResult — registered before onStart()
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    // Prevents a second file-chooser from launching while one is already pending.
    // Two rapid JS file-input triggers would otherwise deliver the wrong result to the wrong callback.
    private boolean fileChooserActive = false;
    // Caps URI count from multi-select to prevent unbounded array allocation from untrusted picker content
    private static final int MAX_FILE_CHOOSER_URIS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true);

        if (!isFirstLaunch) {
            String screenSize = prefs.getString(PREF_SCREEN_SIZE, "small");
            useFullscreen = screenSize.equals("small");
            setTheme(useFullscreen ? R.style.AppTheme : R.style.AppTheme_Normal);
        } else {
            setTheme(R.style.AppTheme_Normal);
        }

        super.onCreate(savedInstanceState);

        // Register file-chooser launcher (must be before onStart)
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                fileChooserActive = false;  // always release the lock on result
                if (fileChooserCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    android.content.Intent data = result.getData();
                    if (data.getClipData() != null) {
                        // Cap item count to prevent unbounded array allocation from picker content
                        int count = Math.min(data.getClipData().getItemCount(), MAX_FILE_CHOOSER_URIS);
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                try {
                    fileChooserCallback.onReceiveValue(results);
                } finally {
                    // Ensure the reference is cleared even if onReceiveValue() throws,
                    // so the next onShowFileChooser doesn't invoke a stale callback.
                    fileChooserCallback = null;
                }
            });

        // Handle back presses: navigate WebView history if available, else finish.
        // When webView is null the first-launch selection screen is showing; pressing back
        // is silently consumed (same as the previous non-cancelable AlertDialog behaviour).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) return; // first-launch screen: non-cancellable
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        requestNotificationPermission();

        if (isFirstLaunch) {
            showFirstLaunchScreen(prefs);
        } else {
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
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingDownloadUrl != null) {
                    startDownload(pendingDownloadUrl, pendingDownloadContentDisposition, pendingDownloadMimetype);
                }
            } else {
                Toast.makeText(this, "Storage permission required for downloads", Toast.LENGTH_SHORT).show();
            }
            pendingDownloadUrl = null;
            pendingDownloadContentDisposition = null;
            pendingDownloadMimetype = null;
        }
    }

    /**
     * Shows the first-launch screen as part of the Activity's own view hierarchy
     * (not as an AlertDialog). Benefits over AlertDialog:
     *  - Lives in the Activity window → accessible to all test automation frameworks
     *    (Espresso, UIAutomator, scenario.onActivity) on ALL API levels and devices
     *  - No separate Dialog Window → no window-focus issues in automated tests
     *  - Back press absorbed by OnBackPressedCallback (same non-cancelable behaviour)
     *  - Visual appearance is identical: centred buttons, same theme, D-pad focusable
     */
    private void showFirstLaunchScreen(SharedPreferences prefs) {
        // AppTheme (set in onCreate) is already NoActionBar.Fullscreen — no explicit hide needed

        int widthPx  = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200, getResources().getDisplayMetrics());
        int heightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,  45, getResources().getDisplayMetrics());

        // ── Title ─────────────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText(R.string.first_launch_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, 24);
        title.setLayoutParams(titleParams);

        // ── Small Screen button ────────────────────────────────────────────
        Button smallButton = new Button(this);
        smallButton.setText(R.string.btn_small_screen);
        smallButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        smallButton.setTextColor(0xFFFFFFFF);
        smallButton.setFocusable(true);
        smallButton.setFocusableInTouchMode(true);
        smallButton.setBackgroundResource(R.drawable.button_selector);
        LinearLayout.LayoutParams smallParams = new LinearLayout.LayoutParams(widthPx, heightPx);
        smallParams.setMargins(0, 0, 0, 15);
        smallButton.setLayoutParams(smallParams);
        smallButton.setOnClickListener(v -> {
            prefs.edit()
                .putString(PREF_SCREEN_SIZE, "small")
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply();
            recreate();
        });

        // ── Normal Screen button ───────────────────────────────────────────
        Button normalButton = new Button(this);
        normalButton.setText(R.string.btn_normal_screen);
        normalButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        normalButton.setTextColor(0xFFFFFFFF);
        normalButton.setFocusable(true);
        normalButton.setFocusableInTouchMode(true);
        normalButton.setBackgroundResource(R.drawable.button_selector);
        normalButton.setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));
        normalButton.setOnClickListener(v -> {
            prefs.edit()
                .putString(PREF_SCREEN_SIZE, "normal")
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply();
            recreate();
        });

        // Assign stable IDs so nextFocusDown/Up work across all API levels
        int smallId  = View.generateViewId();
        int normalId = View.generateViewId();
        smallButton.setId(smallId);
        normalButton.setId(normalId);
        // Explicit D-pad focus chain: down from small → normal, up from normal → small
        smallButton.setNextFocusDownId(normalId);
        normalButton.setNextFocusUpId(smallId);

        // ── Outer layout — full-screen, centred ───────────────────────────
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(30, 30, 30, 30);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        outer.addView(title);
        outer.addView(smallButton);
        outer.addView(normalButton);

        setContentView(outer);

        // requestFocus() (keyboard focus, not touch-mode focus) allows DPAD_DOWN
        // to traverse to the next view on all API levels including API 33+.
        // requestFocusFromTouch() would lock the view into touch mode on newer Android,
        // which prevents D-pad traversal.
        smallButton.requestFocus();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupUI() {
        if (!useFullscreen) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

            if (getActionBar() != null) {
                getActionBar().setDisplayShowTitleEnabled(false);
                getActionBar().setDisplayShowHomeEnabled(false);
            }

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
            webView = new WebView(this);
            setContentView(webView);
            hideSystemUI();
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new PushInterface(this), "PushBridge");

        webView.setWebViewClient(new WebViewClient() {
            // Primary override: handles all requests on API 21+, including POST
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() != null ? request.getUrl().toString() : "";
                if (isAllowed(url)) return false;
                Toast.makeText(MainActivity.this, "Not allowed", Toast.LENGTH_SHORT).show();
                return true;
            }

            // Fallback for API 21-23 (String overload deprecated since API 24)
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isAllowed(url)) return false;
                Toast.makeText(MainActivity.this, "Not allowed", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                // Reject a concurrent request: a second launch before the first result
                // arrives would deliver the first result to the second callback.
                if (fileChooserActive) {
                    callback.onReceiveValue(null);
                    return false;
                }
                // Cancel any previous pending callback before replacing it.
                // Null the field FIRST so that if onReceiveValue() throws, the stale
                // reference is already cleared and cannot be called again on the next open.
                if (fileChooserCallback != null) {
                    ValueCallback<Uri[]> prev = fileChooserCallback;
                    fileChooserCallback = null;
                    try { prev.onReceiveValue(null); } catch (Exception ignored) {}
                }
                fileChooserCallback = callback;
                fileChooserActive = true;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    // ActivityNotFoundException (no file picker installed) or SecurityException.
                    // Null the field before calling so a throw cannot leave a stale reference.
                    Log.w(TAG, "File chooser launch failed: " + e);
                    fileChooserActive = false;
                    ValueCallback<Uri[]> cb = fileChooserCallback;
                    fileChooserCallback = null;
                    try { if (cb != null) cb.onReceiveValue(null); } catch (Exception ignored) {}
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            // WRITE_EXTERNAL_STORAGE is only needed on API 23-28 (API 29+ uses scoped storage)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = url;
                pendingDownloadContentDisposition = contentDisposition;
                pendingDownloadMimetype = mimetype;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                return;
            }
            startDownload(url, contentDisposition, mimetype);
        });

        // Validate open_url against the allowed-domain whitelist before loading.
        // loadUrl() bypasses shouldOverrideUrlLoading, so the check must be explicit here.
        String openUrl = getIntent().getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty() && isAllowed(openUrl)) {
            webView.loadUrl(openUrl);
        } else {
            webView.loadUrl(BASE_URL);
        }

        startPushServiceIfConfigured();
    }

    /**
     * Returns true if the URL is an https:// URL whose host is on the allowed-domains list.
     * http:// is rejected — the network security config also enforces HTTPS on API 24+,
     * but this check provides the same guarantee on API 23 and is the single source of truth.
     */
    static boolean isAllowed(String url) {
        Uri parsed = Uri.parse(url);
        if (!"https".equalsIgnoreCase(parsed.getScheme())) return false;
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String domain : ALLOWED_DOMAINS) {
            if (host.equals(domain) || host.equals("www." + domain)
                    || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Validate open_url: loadUrl() bypasses shouldOverrideUrlLoading
        String openUrl = intent.getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty() && webView != null && isAllowed(openUrl)) {
            webView.loadUrl(openUrl);
        }
    }

    @Override
    protected void onDestroy() {
        fileChooserActive = false;
        // Resolve any pending file-chooser callback so WebView doesn't hang.
        // Null the field before calling so a throw cannot leave a stale reference.
        if (fileChooserCallback != null) {
            ValueCallback<Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            try { cb.onReceiveValue(null); } catch (Exception ignored) {}
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void startPushServiceIfConfigured() {
        String topic = PushService.getTopic(this);
        if (topic != null && !topic.isEmpty()) {
            Intent serviceIntent = new Intent(this, PushService.class);
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

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void startDownload(String url, String contentDisposition, String mimetype) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, null);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimetype);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) {
            request.addRequestHeader("Cookie", cookie);
        }
        request.setTitle(fileName);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(this, "Download manager unavailable on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        // enqueue() is a synchronous binder IPC; run off the main thread to prevent ANR
        new Thread(() -> dm.enqueue(request)).start();
        Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
    }

    /**
     * JavaScript interface for push notification registration.
     * Static to avoid holding an implicit reference to the outer Activity.
     * Uses WeakReference so the Activity can be GC'd after it is destroyed.
     */
    public static class PushInterface {

        private final WeakReference<MainActivity> activityRef;

        PushInterface(MainActivity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @JavascriptInterface
        public String getDeviceId() {
            MainActivity a = activityRef.get();
            if (a == null) return "";
            android.content.SharedPreferences prefs =
                    a.getSharedPreferences("app_prefs", MODE_PRIVATE);
            // Synchronize on the prefs instance to prevent a race where two simultaneous
            // JS calls both see null, both generate different UUIDs, and the first caller
            // returns a UUID that isn't stored.
            synchronized (prefs) {
                String deviceId = prefs.getString("device_id", null);
                if (deviceId == null) {
                    deviceId = UUID.randomUUID().toString();
                    prefs.edit().putString("device_id", deviceId).commit(); // commit() for synchronous write
                }
                return deviceId;
            }
        }

        @JavascriptInterface
        public String getTopic() {
            MainActivity a = activityRef.get();
            return a != null ? PushService.getTopic(a) : "";
        }

        @JavascriptInterface
        public String getServer() {
            MainActivity a = activityRef.get();
            return a != null ? PushService.getServer(a) : "";
        }

        @JavascriptInterface
        public void registerPush(String server, String topic) {
            MainActivity a = activityRef.get();
            if (a == null || server == null || server.isEmpty() || topic == null || topic.isEmpty()) return;
            // Require HTTPS with a non-empty host: prevents cleartext interception and
            // rejects empty-authority URLs like "https://" (getHost() returns "" not null).
            Uri uri = Uri.parse(server);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()) {
                Log.w(TAG, "registerPush rejected: server must be a valid https:// URL");
                return;
            }
            PushService.configure(a, server, topic);
            Intent serviceIntent = new Intent(a, PushService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                a.startForegroundService(serviceIntent);
            } else {
                a.startService(serviceIntent);
            }
        }

        @JavascriptInterface
        public void unregisterPush() {
            MainActivity a = activityRef.get();
            if (a == null) return;
            Intent serviceIntent = new Intent(a, PushService.class);
            serviceIntent.setAction("STOP");
            a.startService(serviceIntent);
            PushService.configure(a, "", "");
        }

        @JavascriptInterface
        public boolean isRegistered() {
            MainActivity a = activityRef.get();
            if (a == null) return false;
            String topic = PushService.getTopic(a);
            return topic != null && !topic.isEmpty();
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }
    }
}
