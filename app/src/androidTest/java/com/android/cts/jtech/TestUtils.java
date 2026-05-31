package com.android.cts.jtech;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

/** Shared helpers for instrumented tests. */
class TestUtils {

    /** Walk the view tree and return the first WebView found, or null. */
    static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Set SharedPrefs so MainActivity skips the first-launch dialog and goes straight to WebView. */
    static void setNonFirstLaunch(Context ctx, boolean fullscreen) {
        ctx.getSharedPreferences("JtechPrefs", Context.MODE_PRIVATE)
           .edit()
           .putBoolean("first_launch", false)
           .putString("screen_size", fullscreen ? "small" : "normal")
           .commit();
    }

    /** Set SharedPrefs so MainActivity shows the first-launch dialog. */
    static void setFirstLaunch(Context ctx) {
        ctx.getSharedPreferences("JtechPrefs", Context.MODE_PRIVATE)
           .edit()
           .putBoolean("first_launch", true)
           .remove("screen_size")
           .commit();
    }
}
