package com.nepdon.wire.ui.aim;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * The <b>nvmux</b> daemon: spawning it, talking to it, and keeping its lease.
 *
 * <h3>What it is</h3>
 *
 * <p>Android allows one input <i>device</i> active per window, so injecting as a
 * second device cancels the user's finger and vice versa — you cannot walk and
 * aim at once. The fix is to stop being a second device: a native helper grabs
 * the real touchscreen with {@code EVIOCGRAB} and re-emits both the user's
 * contacts and our changes through a single {@code /dev/uinput} clone. Android
 * then sees one device, and nothing cancels.</p>
 *
 * <p>It is spawned via {@code Shizuku.newProcess} (shell uid, no root) and
 * driven over its <b>stdin</b>. An abstract unix socket does not work: an app
 * connecting to a shell-domain socket is an SELinux {@code connectto} denial.</p>
 *
 * <h3>The lease is the whole safety story</h3>
 *
 * <p>The daemon releases the panel {@link #LEASE_MS} after the last line it
 * receives, so a crashed app can never leave the touchscreen grabbed. That
 * makes the heartbeat <b>load-bearing</b>, not housekeeping: without it the grab
 * died 1.5 s after arming and every feature above became a silent no-op, because
 * a lapsed lease is not an error on either side. It is the designed response to
 * an app that stopped talking.</p>
 *
 * <h3>This class holds no policy</h3>
 *
 * <p>It does not know what a scope is, when to steer, or where the fire button
 * is. It owns the process, the pipe and the worker thread; {@link AimController}
 * decides what to say.</p>
 */
public final class Daemon {

    private static final String TAG = "FakelagAim";

    private Daemon() { }

    /**
     * The daemon's own release timeout. Mirrors {@code LEASE_MS} in
     * {@code native/touchmux/src/main.rs}; changing one without the other
     * either wastes margin or drops the grab mid-use.
     */
    static final long LEASE_MS = 1500L;
    /** Heartbeat cadence. Three missed beats of margin against the lease. */
    private static final long KEEPALIVE_MS = 400L;

    private static Handler worker;
    private static Process proc;
    private static OutputStream stdin;
    private static volatile String libDir;

    private static volatile boolean keepAliveOn = false;
    private static Runnable keepAlive;

    // ---- diagnostics ----
    private static volatile long sentCount = 0;
    private static volatile String lastError = null;
    private static volatile String lastBail = "-";
    private static volatile String lastLine = "-";
    private static volatile String lastDown = "-";

    /** Listens to the daemon's stdout. Set by {@link AimController}. */
    interface Listener {
        void onLine(String line);
    }

    private static volatile Listener listener;

    static void setListener(Listener l) {
        listener = l;
    }

    // ====================================================================
    // Thread
    // ====================================================================

    /**
     * Posts to the aim's worker thread, starting it on first use.
     *
     * <p>Everything that can block belongs here: the spawn waits ~300 ms for
     * the daemon to come up, and that must never land on the thread drawing the
     * overlay.</p>
     */
    public static synchronized void post(Runnable r) {
        if (worker == null) {
            HandlerThread t = new HandlerThread("aim-inject");
            t.start();
            worker = new Handler(t.getLooper());
        }
        worker.post(r);
    }

    /** Posts to the worker thread after a delay. */
    public static void postDelayed(Runnable r, long ms) {
        handler().postDelayed(r, ms);
    }

    /** Cancels a pending post. */
    public static void cancel(Runnable r) {
        if (worker != null && r != null) worker.removeCallbacks(r);
    }

    private static synchronized Handler handler() {
        if (worker == null) post(NO_OP);
        return worker;
    }

    private static final Runnable NO_OP = new Runnable() {
        @Override public void run() { }
    };

    /** Where the extracted daemon binary lives (nativeLibraryDir). */
    public static void setLibDir(String dir) {
        libDir = dir;
    }

    // ====================================================================
    // Process
    // ====================================================================

    /** True when the pipe is open. */
    public static boolean isUp() {
        return stdin != null;
    }

    /**
     * Ensures the daemon is spawned and its stdin is open.
     *
     * <p>Liveness is detected REACTIVELY: a write to a dead process fails in
     * {@link #send}, which closes, and the next call respawns. Do <b>not</b>
     * poll {@code exitValue()} — it does not throw while the remote process is
     * alive, so it read as "dead" and produced a respawn storm that killed the
     * daemon every tick before it could finish grabbing.</p>
     */
    static synchronized boolean ensure() {
        if (stdin != null) return true;
        if (!Privilege.available()) {
            // The one exit that used to be silent. A Shizuku with no binder
            // looked exactly like an aim that simply did nothing.
            lastBail = "shizuku down";
            lastError = "no shizuku binder";
            Log.w(TAG, "nvmux not spawned: Shizuku unavailable");
            Diag.hit(Slot.AIM_NO_SHIZUKU);
            return false;
        }
        try {
            String path = (libDir != null ? libDir : "") + "/libnvmux.so";
            // Shizuku.newProcess is @hide; reflection is the standard way
            // clients spawn a shell-uid process. The returned Process's
            // stdin/stdout ride Shizuku's own binder pipe.
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            proc = (Process) m.invoke(null,
                    new Object[]{ new String[]{ path }, null, null });
            stdin = proc.getOutputStream();
            drainStdout(proc);
            Log.i(TAG, "nvmux spawned: " + path);
            // Give it time to open the panel and build the clone before the
            // first command arrives.
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return true;
        } catch (Throwable t) {
            lastError = shortError(t);
            Log.w(TAG, "nvmux spawn failed: " + t);
            proc = null;
            stdin = null;
            return false;
        }
    }

    /** Stops the heartbeat and kills the daemon. */
    public static synchronized void close() {
        stopKeepAlive();
        try {
            if (stdin != null) {
                stdin.write("Q\n".getBytes());
                stdin.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (stdin != null) stdin.close();
        } catch (Throwable ignored) {
        }
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignored) {
        }
        stdin = null;
        proc = null;
    }

    private static void drainStdout(final Process p) {
        final InputStream in = p.getInputStream();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, "nvmux: " + line);
                        lastLine = line;
                        if (line.startsWith("DOWN")) lastDown = line;
                        Listener l = listener;
                        if (l != null) {
                            try {
                                l.onLine(line);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "nvmux-out");
        t.setDaemon(true);
        t.start();
    }

    // ====================================================================
    // Protocol
    // ====================================================================

    /** Screen dimensions + the re-grip cap, and arm the grab. */
    static boolean dims(int w, int h, int maxTravelPx) {
        return send("D " + w + " " + h + " " + maxTravelPx);
    }

    /** Watch this circle (px) for a real press; {@code r <= 0} disarms. */
    static boolean watchFire(int cx, int cy, int r, int holdMs) {
        return send("T " + cx + " " + cy + " " + r + " " + holdMs);
    }

    /** Steer the finger nearest (cx, cy) for {@code ms}, starting now. */
    static boolean steerNow(int cx, int cy, int ms) {
        return send("V " + cx + " " + cy + " " + ms);
    }

    /**
     * Steer the REAL finger nearest (cx, cy) by (dx, dy) px.
     *
     * <p>This is the aim's motion. It is NOT {@link #swipe}: a swipe plants a
     * synthetic contact, drags it and lifts it, so repeating it on the aim loop
     * produces a tap every cycle -- which the scoped look ignores, and which
     * the player sees as the finger stuttering up and down. Steering publishes
     * an offset on top of the contact already down, so the gesture stays ONE
     * continuous drag however often the aim re-solves.</p>
     */
    static boolean steerBy(int dx, int dy, int cx, int cy) {
        return send("G " + dx + " " + dy + " " + cx + " " + cy
                + " " + (int) AimConfig.AIM_STEER_TAU_MS);
    }

    /** One-shot swipe of (dx, dy) px with the finger planted at (sx, sy). */
    static boolean swipe(int sx, int sy, int dx, int dy) {
        return send("A " + sx + " " + sy + " " + dx + " " + dy);
    }

    /** Lift and release the panel. */
    static boolean release() {
        return send("U");
    }

    private static boolean send(String cmd) {
        OutputStream o = stdin;
        if (o == null) return false;
        try {
            o.write((cmd + "\n").getBytes());
            o.flush();
            sentCount++;
            return true;
        } catch (Throwable t) {
            lastError = shortError(t);
            Log.w(TAG, "nvmux send failed: " + t);
            close();
            return false;
        }
    }

    // ====================================================================
    // Lease
    // ====================================================================

    static synchronized void startKeepAlive() {
        if (keepAliveOn) return;
        keepAliveOn = true;
        keepAlive = new Runnable() {
            @Override
            public void run() {
                if (!keepAliveOn) return;
                if (stdin != null) send("P");
                handler().postDelayed(this, KEEPALIVE_MS);
            }
        };
        handler().post(keepAlive);
    }

    static synchronized void stopKeepAlive() {
        keepAliveOn = false;
        if (worker != null && keepAlive != null) worker.removeCallbacks(keepAlive);
        keepAlive = null;
    }

    // ====================================================================
    // Diagnostics
    // ====================================================================

    /** Shizuku state + pipe liveness, for the panel's DAEMON row. */
    public static String statusLine() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Privilege.statusLine());
        sb.append(stdin != null ? " mux=ok" : (proc != null ? " mux=dead" : " mux=-"));
        sb.append(keepAliveOn ? " held" : " free");
        sb.append(" tx=").append(sentCount);
        if (lastError != null) sb.append(" err=").append(lastError);
        else if (!"-".equals(lastBail)) sb.append(" bail=").append(lastBail);
        return sb.toString();
    }

    /** Most recent daemon line of any kind. */
    public static String lastLine() {
        return lastLine;
    }

    /** Most recent real press the daemon reported, and whether it hit. */
    public static String lastDown() {
        return lastDown;
    }

    private static String shortError(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null) return n;
        if (m.length() > 40) m = m.substring(0, 40);
        return n + ":" + m;
    }
}
