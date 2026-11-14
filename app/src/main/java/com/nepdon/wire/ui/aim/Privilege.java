package com.nepdon.wire.ui.aim;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * The Shizuku half of the aim: is there a privileged binder, and may we use it.
 *
 * <p>Separated from the daemon because the two fail in completely different
 * ways and for completely different reasons. Shizuku is a <i>user setup</i>
 * problem — not installed, not started, permission not granted — and its answer
 * belongs on the app's main screen. The daemon is an <i>engineering</i> problem.
 * Mixed together, "the aim does nothing" had two unrelated root causes and one
 * undifferentiated silence.</p>
 *
 * <p>The privileged process we spawn runs as <b>shell uid, not root</b>. That is
 * enough for {@code EVIOCGRAB} and {@code /dev/uinput} — measured on this device,
 * shell is in group 3011 (uhid) — and nothing here needs more.</p>
 */
public final class Privilege {

    private static final String TAG = "FakelagAim";

    private Privilege() { }

    // ---- setup states, for the app's Shizuku button ----
    public static final int STATE_NOT_INSTALLED = 0;
    public static final int STATE_NOT_RUNNING   = 1;
    public static final int STATE_NO_PERMISSION = 2;
    public static final int STATE_READY         = 3;

    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private static volatile boolean initialised = false;
    private static volatile boolean permGranted = false;

    /**
     * Cached answer to "may we spawn". Re-checked at most every
     * {@link #AVAIL_TTL_MS}: {@code pingBinder()} is a binder round trip and
     * the swipe path would otherwise make one per tick.
     */
    private static final long AVAIL_TTL_MS = 500L;
    private static volatile boolean availCache = false;
    private static volatile long availCheckedMs = -AVAIL_TTL_MS;

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /**
     * Registers the Shizuku listeners. Idempotent, and safe to call from any
     * thread — the work is posted to the daemon's worker.
     */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        Daemon.post(new Runnable() {
            @Override
            public void run() {
                register();
            }
        });
    }

    private static void register() {
        try {
            Shizuku.addRequestPermissionResultListener(PERM_LISTENER);
            Shizuku.addBinderReceivedListener(BINDER_RECEIVED);
            Shizuku.addBinderDeadListener(BINDER_DEAD);
        } catch (Throwable t) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.AIM_SETUP_STEP_FAILED);
        }
        refresh();
    }

    private static final Shizuku.OnRequestPermissionResultListener PERM_LISTENER =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    permGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                    invalidate();
                    Log.i(TAG, "Shizuku permission granted=" + permGranted);
                }
            };

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    Log.i(TAG, "Shizuku binder received");
                    check();
                    invalidate();
                }
            };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    Log.w(TAG, "Shizuku binder DIED");
                    permGranted = false;
                    invalidate();
                    // The daemon lived in that shell session; it is gone too.
                    Daemon.close();
                }
            };

    // ====================================================================
    // Queries
    // ====================================================================

    /** True when Shizuku is running and we hold its permission (cached). */
    public static boolean available() {
        long now = SystemClock.uptimeMillis();
        if (now - availCheckedMs < AVAIL_TTL_MS) return availCache;
        availCheckedMs = now;
        boolean ok;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) ok = false;
            else ok = permGranted || check();
        } catch (Throwable t) {
            ok = false;
        }
        availCache = ok;
        return ok;
    }

    /** Precise setup state, for the app's Shizuku button and the aim gate. */
    public static int state(Context ctx) {
        boolean running;
        try {
            running = Shizuku.pingBinder();
        } catch (Throwable t) {
            running = false;
        }
        if (!running) return isInstalled(ctx) ? STATE_NOT_RUNNING : STATE_NOT_INSTALLED;
        try {
            if (Shizuku.isPreV11()) return STATE_NO_PERMISSION;
        } catch (Throwable ignored) {
        }
        return check() ? STATE_READY : STATE_NO_PERMISSION;
    }

    /** Button label for the current state. */
    public static String stateLabel(Context ctx) {
        switch (state(ctx)) {
            case STATE_READY:         return "AIM: Shizuku READY ✓";
            case STATE_NO_PERMISSION: return "AIM: tap to grant Shizuku";
            case STATE_NOT_RUNNING:   return "AIM: start Shizuku (Wireless ADB)";
            default:                  return "AIM: install Shizuku";
        }
    }

    /** One line for the dev HUD. */
    public static String statusLine() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku: NOT RUNNING";
            if (Shizuku.isPreV11()) return "Shizuku: too old";
            if (!(permGranted || check())) return "Shizuku: NO PERMISSION";
            return "Shizuku: READY";
        } catch (Throwable t) {
            return "Shizuku: unavailable";
        }
    }

    public static boolean isInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ====================================================================
    // Mutations
    // ====================================================================

    /** Re-check permission, e.g. after the user has started Shizuku. */
    public static void refresh() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) check();
        } catch (Throwable ignored) {
        }
        invalidate();
    }

    /** Ask the user for permission (shows Shizuku's own dialog). */
    public static void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.isPreV11()) return;
            if (check()) return;
            Shizuku.requestPermission(1001);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku requestPermission failed: " + t);
        }
    }

    private static boolean check() {
        try {
            permGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            permGranted = false;
        }
        return permGranted;
    }

    private static void invalidate() {
        availCheckedMs = -AVAIL_TTL_MS;
    }
}
