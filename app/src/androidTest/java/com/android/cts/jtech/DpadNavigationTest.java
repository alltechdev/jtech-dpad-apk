package com.android.cts.jtech;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented: D-pad navigation — NO SKIPS, runs on ALL supported API levels (23-36)
 * and on physical devices.
 *
 * First-launch tests use scenario.onActivity() for all view access rather than Espresso's
 * onView(). This is necessary because Espresso 3.7.0's InputManagerEventInjectionStrategy
 * is incompatible with API 36 (InputManager.getInstance() was removed), causing every
 * onView() call to throw NoActivityResumedException on API 36+. scenario.onActivity()
 * runs directly on the main thread and is independent of Espresso's injection layer.
 *
 * D-pad key events use sendKeyDownUpSync(), which sends to the Activity's own window
 * (not via Espresso). Since the first-launch screen is now part of the Activity's view
 * hierarchy (setContentView), keys reach the focused button reliably on all platforms.
 *
 * Normal-mode tests are straightforward (no Espresso view interactions needed).
 */
@RunWith(AndroidJUnit4.class)
public class DpadNavigationTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    // ── First-launch selection screen ─────────────────────────────────────

    /**
     * The first button (Small Screen) has focus immediately after the selection screen
     * is inflated. setInTouchMode(false) exits touch mode so keyboard focus is visible
     * and getCurrentFocus() returns the correct view on all API levels.
     */
    @Test
    public void firstLaunchScreen_firstButtonHasFocus() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View focused = activity.getCurrentFocus();
                assertNotNull("A button must hold focus immediately on first launch", focused);
                assertTrue("Focused view must be a Button", focused instanceof Button);
                assertEquals(context.getString(R.string.btn_small_screen),
                        ((Button) focused).getText().toString());
            });
        }
    }

    /**
     * DPAD_DOWN moves focus from Small Screen to Normal Screen.
     *
     * Uses View.focusSearch(FOCUS_DOWN) + requestFocus() on the main thread — this is
     * exactly what the Android framework does internally when a DPAD_DOWN key event is
     * received by a focused view. Key-injection (sendKeyDownUpSync) is not used because
     * it doesn't trigger focus traversal in touch mode on API 33+. The focusSearch()
     * approach tests the focus chain directly and works on all API levels.
     */
    @Test
    public void firstLaunchScreen_dpadDown_movesFocusToNormalScreen() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View focused = activity.getCurrentFocus();
                assertNotNull("Small Screen must have initial focus", focused);
                assertEquals(context.getString(R.string.btn_small_screen),
                        ((Button) focused).getText().toString());

                // Android uses focusSearch(FOCUS_DOWN) internally when DPAD_DOWN fires
                View next = focused.focusSearch(View.FOCUS_DOWN);
                assertNotNull("focusSearch(FOCUS_DOWN) must find Normal Screen button", next);
                next.requestFocus();

                View nowFocused = activity.getCurrentFocus();
                assertNotNull(nowFocused);
                assertEquals("DPAD_DOWN must move focus to Normal Screen",
                        context.getString(R.string.btn_normal_screen),
                        ((Button) nowFocused).getText().toString());
            });
        }
    }

    /**
     * DPAD_UP from Normal Screen returns focus to Small Screen.
     */
    @Test
    public void firstLaunchScreen_dpadUp_returnsFocusToSmallScreen() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View focused = activity.getCurrentFocus();
                // Navigate down to Normal Screen
                View next = focused.focusSearch(View.FOCUS_DOWN);
                assertNotNull("focusSearch(FOCUS_DOWN) must find Normal Screen", next);
                next.requestFocus();

                // Navigate up — must return to Small Screen
                View prev = next.focusSearch(View.FOCUS_UP);
                assertNotNull("focusSearch(FOCUS_UP) must find Small Screen", prev);
                prev.requestFocus();

                assertEquals("DPAD_DOWN+UP must return focus to Small Screen",
                        context.getString(R.string.btn_small_screen),
                        ((Button) activity.getCurrentFocus()).getText().toString());
            });
        }
    }

    /**
     * Clicking Small Screen saves "small" pref and clears first_launch.
     * Uses performClick() on the main thread to avoid Espresso injection.
     */
    @Test
    public void firstLaunchScreen_smallScreenButton_savesSmallPref() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button btn = findButtonByText(activity,
                        context.getString(R.string.btn_small_screen));
                assertNotNull("Small Screen button must be present", btn);
                btn.performClick(); // triggers apply() then recreate()
            });

            // onActivity() after recreate() runs on the new Activity's main thread —
            // SharedPreferences in-memory state is guaranteed to reflect the apply() call.
            scenario.onActivity(activity -> {
                SharedPreferences prefs = activity.getSharedPreferences("JtechPrefs",
                        Context.MODE_PRIVATE);
                assertEquals("small", prefs.getString("screen_size", null));
                assertFalse(prefs.getBoolean("first_launch", true));
            });
        }
    }

    /**
     * Clicking Normal Screen saves "normal" pref and clears first_launch.
     */
    @Test
    public void firstLaunchScreen_normalScreenButton_savesNormalPref() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button btn = findButtonByText(activity,
                        context.getString(R.string.btn_normal_screen));
                assertNotNull("Normal Screen button must be present", btn);
                btn.performClick();
            });

            scenario.onActivity(activity -> {
                SharedPreferences prefs = activity.getSharedPreferences("JtechPrefs",
                        Context.MODE_PRIVATE);
                assertEquals("normal", prefs.getString("screen_size", null));
                assertFalse(prefs.getBoolean("first_launch", true));
            });
        }
    }

    /**
     * Arbitrary DPAD keys during the first-launch screen must not crash the app.
     */
    @Test
    public void firstLaunchScreen_dpadKeys_doNotCrash() {
        TestUtils.setFirstLaunch(context);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int key : new int[]{
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);
            }
            scenario.onActivity(a -> assertFalse(a.isFinishing()));
        }
    }

    // ── Normal mode (WebView) ─────────────────────────────────────────────

    @Test
    public void normalMode_webViewIsFocusable() {
        TestUtils.setNonFirstLaunch(context, true);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView wv = TestUtils.findWebView(activity.getWindow().getDecorView());
                assertNotNull(wv);
                assertTrue("WebView must be focusable for D-pad navigation", wv.isFocusable());
            });
        }
    }

    @Test
    public void normalMode_dpadKeys_doNotCrashApp() {
        TestUtils.setNonFirstLaunch(context, true);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Use dispatchKeyEvent() on the main thread instead of sendKeyDownUpSync().
            // sendKeyDownUpSync() requires INJECT_EVENTS permission when the focused window
            // is a WebView (rendered in a separate process) on API 29+.
            // dispatchKeyEvent() dispatches directly to the Activity's window — no permission needed.
            scenario.onActivity(activity -> {
                for (int code : new int[]{
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_CENTER}) {
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
                assertFalse(activity.isFinishing());
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Traverse the view hierarchy and return the first Button with matching text. */
    private static Button findButtonByText(android.app.Activity activity, String text) {
        return findButtonByText(activity.getWindow().getDecorView(), text);
    }

    private static Button findButtonByText(View root, String text) {
        if (root instanceof Button && text.equals(((Button) root).getText().toString())) {
            return (Button) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButtonByText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
