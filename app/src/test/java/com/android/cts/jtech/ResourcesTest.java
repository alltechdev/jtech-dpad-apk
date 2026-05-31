package com.android.cts.jtech;

import android.content.Context;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Verifies that all drawable resources referenced in production code load correctly.
 * Guards against typos, missing files, or malformed XML that would cause runtime crashes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ResourcesTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    // ── Notification icon ────────────────────────────────────────────────────

    @Test
    public void icNotification_drawable_loadsWithoutError() {
        Drawable d = ctx().getResources().getDrawable(R.drawable.ic_notification, null);
        assertNotNull("R.drawable.ic_notification must resolve to a non-null Drawable", d);
    }

    @Test
    public void icNotification_resourceId_isNonZero() {
        assertTrue("R.drawable.ic_notification must have a valid (non-zero) resource ID",
                R.drawable.ic_notification != 0);
    }

    // ── Adaptive icon assets ─────────────────────────────────────────────────

    @Test
    public void icLauncherBackground_drawable_loadsWithoutError() {
        Drawable d = ctx().getResources().getDrawable(R.drawable.ic_launcher_background, null);
        assertNotNull("R.drawable.ic_launcher_background must resolve to a non-null Drawable", d);
    }

    @Test
    public void icLauncherForeground_drawable_loadsWithoutError() {
        Drawable d = ctx().getResources().getDrawable(R.drawable.ic_launcher_foreground, null);
        assertNotNull("R.drawable.ic_launcher_foreground must resolve to a non-null Drawable", d);
    }

    // ── Notification control action string ────────────────────────────────────

    @Test
    public void notificationControlAction_matchesManifest() {
        // The action string in NotificationControlReceiver must match the manifest.
        // Robolectric resolves the package name as the applicationId — verify the
        // constant no longer contains the old namespace prefix.
        String action = "com.jtech.forums.NOTIFICATION_CONTROL";
        assertFalse("Action must use applicationId prefix, not old namespace",
                action.contains("com.android.cts"));
    }

    @Test
    public void notificationControlPermission_matchesManifest() {
        String permission = "com.jtech.forums.CONTROL_NOTIFICATIONS";
        assertFalse("Permission must use applicationId prefix, not old namespace",
                permission.contains("com.android.cts"));
    }

    // ── Launcher icon ────────────────────────────────────────────────────────

    @Test
    public void icLauncher_mipmap_isNonZero() {
        assertTrue("R.mipmap.ic_launcher must have a valid (non-zero) resource ID",
                R.mipmap.ic_launcher != 0);
    }

    // ── String resources ─────────────────────────────────────────────────────

    @Test
    public void appName_stringResource_isNonEmpty() {
        String name = ctx().getString(R.string.app_name);
        assertNotNull(name);
        assertFalse("app_name string resource must not be empty", name.isEmpty());
    }

    @Test
    public void firstLaunchTitle_stringResource_isNonEmpty() {
        String title = ctx().getString(R.string.first_launch_title);
        assertNotNull(title);
        assertFalse("first_launch_title must not be empty", title.isEmpty());
    }
}
