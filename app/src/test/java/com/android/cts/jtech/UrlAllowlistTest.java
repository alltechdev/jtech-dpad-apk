package com.android.cts.jtech;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Exhaustive whitelist tests for MainActivity.isAllowed().
 * Robolectric runner required for android.net.Uri.parse().
 * Covers every case documented in docs/threat-model.md §3.4.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class UrlAllowlistTest {

    // --- HTTPS allowed ---

    @Test public void https_allowedDomain_allowed() {
        assertTrue(MainActivity.isAllowed("https://forums.jtechforums.org/dumb"));
    }
    @Test public void https_allowedRootDomain_allowed() {
        assertTrue(MainActivity.isAllowed("https://jtechforums.org/"));
    }
    @Test public void https_wwwPrefix_allowed() {
        assertTrue(MainActivity.isAllowed("https://www.jtechforums.org/"));
    }
    @Test public void https_subdomain_allowed() {
        assertTrue(MainActivity.isAllowed("https://sub.jtechforums.org/path"));
    }
    @Test public void https_github_allowed() {
        assertTrue(MainActivity.isAllowed("https://github.com/user/repo/releases"));
    }
    @Test public void https_releaseAssets_allowed() {
        assertTrue(MainActivity.isAllowed("https://release-assets.githubusercontent.com/file.apk"));
    }
    @Test public void https_googleDrive_allowed() {
        assertTrue(MainActivity.isAllowed("https://drive.google.com/file/d/abc"));
    }
    @Test public void https_driveUcontent_allowed() {
        assertTrue(MainActivity.isAllowed("https://drive.usercontent.google.com/file"));
    }
    @Test public void https_dropbox_allowed() {
        assertTrue(MainActivity.isAllowed("https://dropbox.com/s/file"));
    }

    // --- HTTP blocked (FIX: scheme must be https) ---

    @Test public void http_allowedDomain_blocked() {
        assertFalse("http:// must be blocked even for allowed domains",
                MainActivity.isAllowed("http://forums.jtechforums.org/dumb"));
    }
    @Test public void http_github_blocked() {
        assertFalse(MainActivity.isAllowed("http://github.com/path"));
    }

    // --- Evil domains blocked ---

    @Test public void https_evilDomain_blocked() {
        assertFalse(MainActivity.isAllowed("https://evil.com/path"));
    }
    @Test public void https_prefixAttack_blocked() {
        // "eviljtechforums.org" must NOT match "jtechforums.org" via endsWith
        assertFalse(MainActivity.isAllowed("https://eviljtechforums.org/path"));
    }
    @Test public void https_suffixAttack_blocked() {
        // "jtechforums.org.evil.com" must NOT match
        assertFalse(MainActivity.isAllowed("https://jtechforums.org.evil.com/path"));
    }

    // --- Scheme attacks ---

    @Test public void javascript_blocked() {
        assertFalse(MainActivity.isAllowed("javascript:alert(1)"));
    }
    @Test public void data_blocked() {
        assertFalse(MainActivity.isAllowed("data:text/html,<h1>hi</h1>"));
    }
    @Test public void file_blocked() {
        assertFalse(MainActivity.isAllowed("file:///etc/passwd"));
    }
    @Test public void blob_blocked() {
        assertFalse(MainActivity.isAllowed("blob:https://forums.jtechforums.org/uuid"));
    }

    // --- Malformed / edge cases ---

    @Test public void emptyString_blocked() {
        assertFalse(MainActivity.isAllowed(""));
    }
    @Test public void httpsEmptyAuthority_blocked() {
        // "https://" has empty host — must be rejected
        assertFalse(MainActivity.isAllowed("https://"));
    }
    @Test public void httpsSlashSlashOnly_blocked() {
        assertFalse(MainActivity.isAllowed("https:///path"));
    }
    @Test public void nullHostUrl_blocked() {
        assertFalse(MainActivity.isAllowed("not-a-url"));
    }

    // --- Userinfo@ (§3.4 in threat-model.md) ---

    @Test public void userinfoAtAllowedHost_allowed_connectsToAllowedHost() {
        // https://evil.com@jtechforums.org — getHost() returns "jtechforums.org"
        // Browser connects to jtechforums.org (the real host). Not a bypass.
        assertTrue("Userinfo before @ is ignored; host is jtechforums.org",
                MainActivity.isAllowed("https://evil.com@jtechforums.org/path"));
    }
    @Test public void userinfoAtEvilHost_blocked() {
        // https://jtechforums.org@evil.com — getHost() returns "evil.com"
        assertFalse("Host is evil.com after @, must be blocked",
                MainActivity.isAllowed("https://jtechforums.org@evil.com/path"));
    }

    // --- IPv6 ---

    @Test public void ipv6Localhost_blocked() {
        assertFalse(MainActivity.isAllowed("https://[::1]/path"));
    }
    @Test public void ipv6Public_blocked() {
        assertFalse(MainActivity.isAllowed("https://[2001:db8::1]/path"));
    }
}
