package com.nepdon.wire.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.nepdon.wire.config.Prefs;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Owns the login session: activation, token refresh, offline-lease fallback, and
 * the authorization gate the engine checks before starting. Tokens live in RAM;
 * only the refresh token (Tink-encrypted) and the signed lease are persisted.
 *
 * <p>Blocking methods (activate/resume/ensureFresh) must be called off the main
 * thread. {@link #isAuthorized()} is a cheap RAM check safe anywhere.</p>
 */
public final class SessionManager {

    private static volatile String accessToken;
    private static volatile long accessExpiry;     // unix secs
    private static volatile String refreshToken;   // RAM copy
    private static volatile String tier = "standard";
    private static volatile boolean authorized;
    /** Set when the server was unreachable but a valid signed offline lease exists. */
    private static volatile boolean leaseAuthorized;

    private SessionManager() {}

    /**
     * Cheap gate for MainActivity / engine start. In non-enforcing builds, always
     * true. Online: requires a live session AND the loaded config blob. Offline:
     * a valid signed lease admits the user within its grace window (config-gated
     * features still check {@link ConfigStore#isLoaded()} themselves).
     */
    public static boolean isAuthorized() {
        if (!AuthConfig.ENFORCE) return true;
        return (authorized && ConfigStore.isLoaded()) || leaseAuthorized;
    }

    public static String tier() { return tier; }

    /** Activate a license key end-to-end: bind device, get tokens, load config. */
    public static void activate(Context ctx, String key) throws LicenseClient.AuthException {
        LicenseClient.Bundle b = LicenseClient.activate(ctx, key);
        adopt(ctx, b);
        LicenseClient.fetchConfig(accessToken);
        authorized = true;
        leaseAuthorized = false;
        // Persist the key (encrypted) so a later benign session loss can re-activate
        // silently instead of dumping the user back to the key form.
        saveKey(ctx, key);
    }

    /**
     * Resume a prior session on launch (online-required): refresh the token and
     * reload config. Falls back to the signed offline lease within its grace
     * window if the server is unreachable. Returns true if the app may proceed.
     */
    public static boolean resume(Context ctx) {
        String stored = loadRefresh(ctx);
        if (stored == null) {
            // No refresh token — but a prior soft failure may have left a still-valid
            // offline lease, or we may be able to silently re-activate. Try both
            // before declaring the session dead.
            if (silentReactivate(ctx)) return true;
            leaseAuthorized = leaseValid(ctx);
            return leaseAuthorized;
        }
        try {
            LicenseClient.Bundle b = LicenseClient.refresh(ctx, stored);
            adopt(ctx, b);
            LicenseClient.fetchConfig(accessToken);
            authorized = true;
            leaseAuthorized = false;
            return true;
        } catch (LicenseClient.AuthException e) {
            if ("invalid".equals(e.code)) {
                // The license itself is revoked/expired (403). Nothing recovers this
                // — forget everything and force a fresh key.
                logout(ctx);
                return false;
            }
            if ("unauthorized".equals(e.code)) {
                // A 401 here is usually a BENIGN dropped-rotation race (the server
                // rejected a refresh token whose successor we never received), not a
                // dead license. Re-activate silently with the stored key: it re-grabs
                // the same device seat and heals the token family invisibly. Only if
                // that also fails do we fall back to the offline lease, and only then
                // to a forced re-login — the lease is NOT wiped on a soft 401 anymore.
                if (silentReactivate(ctx)) return true;
                leaseAuthorized = leaseValid(ctx);
                if (leaseAuthorized) return true;
                logout(ctx);
                return false;
            }
            // Network / server / config error: honour the offline lease if valid.
            leaseAuthorized = leaseValid(ctx);
            return leaseAuthorized;
        }
    }

    /**
     * Re-activate from the stored (encrypted) license key without user interaction.
     * Recovers a session after a benign refresh-token family loss: a re-activation
     * re-enrolls the SAME device seat (SSAID-based on the server, so no seat is
     * burned) and issues a fresh token family. Returns false if there is no stored
     * key or the server refuses (revoked key, device limit, unreachable).
     */
    private static boolean silentReactivate(Context ctx) {
        String key = loadKey(ctx);
        if (key == null) return false;
        try {
            activate(ctx, key);
            return true;
        } catch (LicenseClient.AuthException e) {
            return false;
        }
    }

    /** Refresh the access token if it is expired or close to it. */
    public static void ensureFresh(Context ctx) throws LicenseClient.AuthException {
        long now = System.currentTimeMillis() / 1000L;
        if (accessToken != null && now < accessExpiry - AuthConfig.REFRESH_SKEW_SEC) return;
        String stored = loadRefresh(ctx);
        if (stored == null) throw new LicenseClient.AuthException("unauthorized", "No session.");
        LicenseClient.Bundle b = LicenseClient.refresh(ctx, stored);
        adopt(ctx, b);
    }

    /**
     * One continuous-auth beat: refresh the access token if near expiry, then hit
     * /auth/heartbeat to re-arm the rolling decode profile. Off the main thread.
     * Returns the server-advertised next interval (seconds). Throws on a terminal
     * auth failure ({@code unauthorized}) so the loop can stop re-arming; a
     * {@code network}/{@code server} miss just propagates for a benign retry.
     */
    public static int heartbeat(Context ctx) throws LicenseClient.AuthException {
        ensureFresh(ctx);
        if (accessToken == null) {
            throw new LicenseClient.AuthException("unauthorized", "No session.");
        }
        return LicenseClient.heartbeat(accessToken);
    }

    public static void logout(Context ctx) {
        accessToken = null; refreshToken = null; accessExpiry = 0;
        authorized = false; leaseAuthorized = false;
        ConfigStore.clear();
        Prefs.get(ctx).edit()
                .remove(Prefs.KEY_AUTH_REFRESH)
                .remove(Prefs.KEY_AUTH_LEASE)
                .remove(Prefs.KEY_AUTH_TIER)
                .remove(Prefs.KEY_AUTH_KEY)
                .commit();
    }

    // ---- stored license key (for silent re-activation) ----
    private static void saveKey(Context ctx, String key) {
        // Only persist where SecureStore is hardware-backed (API 23+). On 21-22
        // Tink falls back to a cleartext keyset, so storing the long-lived license
        // key there would widen an offline-extraction compromise (2026-09-18
        // review M4). Those devices simply re-type the key after a hard failure.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        try {
            Prefs.get(ctx).edit()
                    .putString(Prefs.KEY_AUTH_KEY, SecureStore.get(ctx).encrypt(key))
                    .commit();
        } catch (Exception ignored) {
            // No secure storage on this device → no silent re-activation; the user
            // re-enters the key on the next hard failure. Non-fatal.
        }
    }

    private static String loadKey(Context ctx) {
        String enc = Prefs.get(ctx).getString(Prefs.KEY_AUTH_KEY, null);
        if (enc == null) return null;
        try {
            return SecureStore.get(ctx).decrypt(enc);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- internals ----
    private static void adopt(Context ctx, LicenseClient.Bundle b) {
        accessToken = b.accessToken;
        accessExpiry = jwtExp(b.accessToken);
        refreshToken = b.refreshToken;
        tier = b.tier;
        SharedPreferences.Editor e = Prefs.get(ctx).edit();
        e.putString(Prefs.KEY_AUTH_LEASE, b.lease);
        e.putString(Prefs.KEY_AUTH_TIER, b.tier);
        try {
            e.putString(Prefs.KEY_AUTH_REFRESH, SecureStore.get(ctx).encrypt(b.refreshToken));
        } catch (Exception ex) {
            // If secure storage fails, keep the RAM token only (session survives
            // this launch; a relaunch will require re-activation).
            e.remove(Prefs.KEY_AUTH_REFRESH);
        }
        e.commit();
    }

    private static String loadRefresh(Context ctx) {
        if (refreshToken != null) return refreshToken;
        String enc = Prefs.get(ctx).getString(Prefs.KEY_AUTH_REFRESH, null);
        if (enc == null) return null;
        try {
            refreshToken = SecureStore.get(ctx).decrypt(enc);
            return refreshToken;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean leaseValid(Context ctx) {
        String lease = Prefs.get(ctx).getString(Prefs.KEY_AUTH_LEASE, null);
        if (lease == null) return false;
        JSONObject p = LicenseClient.verifyLease(ctx, lease);
        if (p == null) return false;
        long now = System.currentTimeMillis() / 1000L;
        boolean active = "active".equals(p.optString("status"));
        boolean unexpired = p.optLong("valid_until", 0) > now;
        // Offline hold: authorized true, but config is NOT loaded, so an ENFORCE
        // build still gates any feature that needs the blob.
        return active && unexpired;
    }

    private static long jwtExp(String jwt) {
        try {
            String mid = jwt.split("\\.")[1];
            byte[] p = Base64.decode(mid, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(p, StandardCharsets.UTF_8)).getLong("exp");
        } catch (Exception e) {
            return 0;
        }
    }
}
