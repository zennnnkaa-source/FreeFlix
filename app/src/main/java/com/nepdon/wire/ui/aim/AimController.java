package com.nepdon.wire.ui.aim;

import android.util.Log;

import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;
import com.nepdon.wire.protocol.ScopeState;
import com.nepdon.wire.ui.edit.Layout;

/**
 * When to take the panel, when to steer, and by how much.
 *
 * <p>All the policy, none of the plumbing. {@link Daemon} owns the process and
 * the pipe; {@link Privilege} owns Shizuku; {@link AimConfig} owns the numbers;
 * this decides.</p>
 *
 * <h3>Two rules that cost a whole session to learn</h3>
 *
 * <p><b>1. Never grab or release while a finger is on the glass.</b>
 * {@code EVIOCGRAB} mid-gesture takes the device away from the framework
 * without delivering an "up" for the contacts it is already tracking, so it
 * keeps a pointer <i>frozen at the touch-down point forever</i> while the same
 * finger reappears as a new pointer on the clone. Two pointers: one stuck, one
 * live. A look bound to the first pointer down is then bound to the one that
 * can never move again — which is exactly why steering did nothing while
 * scoped, and why an overlay button under the frozen pointer dragged itself
 * around. Releasing mid-touch is the identical bug mirrored. So the grab is
 * taken <b>once, on an empty screen</b>, and held; the daemon defers it if a
 * finger happens to be down, and its lease still covers a crash.</p>
 *
 * <p><b>2. Steer the finger that is already down; never add one.</b> Scoped,
 * the game drags the view with the contact already held. A second synthetic
 * contact beside it is not the pointer the scoped look listens to, so the drag
 * ran perfectly and turned nothing — while unscoped it sometimes worked, which
 * is what made it look intermittent rather than wrong.</p>
 *
 * <h3>Why the trigger is the scope latch and not the press</h3>
 *
 * <p>The press that opens the scope happens before {@link ScopeState} hears
 * about it — the latch is inferred from outbound Cmd 137/138, which is a wire
 * signal. So the daemon is told to steer <i>the finger nearest the fire
 * button</i>, which is already down by the time we ask. Waiting for a new press
 * inside a placed circle would wait for one that already happened.</p>
 */
public final class AimController {

    private static final String TAG = "FakelagAim";

    private AimController() { }

    /** Poll interval for the scope edge. */
    private static final long SCOPE_POLL_MS = 80L;

    private static volatile int screenW = 1080, screenH = 2340;

    private static volatile boolean armed = false;
    private static volatile boolean watching = false;
    private static Runnable scopeWatch;
    private static boolean wasScoped = false;
    private static long lastSteerMs = 0L;

    private static volatile String state = "off";
    private static volatile int steers = 0;

    // Scratch for the placed-layout basis. Static and reused: the swipe path
    // runs several times a second beside the renderer and must not allocate.
    private static final float[] dirX = new float[2];
    private static final float[] dirY = new float[2];
    private static final float[] plant = new float[2];

    private static volatile int shots = 0;
    private static volatile String lastBail = null;

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** Registers Shizuku listeners and starts the daemon's worker thread. */
    public static void init() {
        Privilege.init();
        Daemon.setListener(LINES);
    }

    /**
     * Take the grab now, while nothing is touching the screen.
     *
     * <p>Called at service start, NOT on scope-in — see rule 1. The daemon
     * defers the grab itself if a finger is down and takes it at the next
     * empty moment.</p>
     */
    public static void arm() {
        Daemon.post(new Runnable() {
            @Override
            public void run() {
                if (!Daemon.ensure()) {
                    state = "no daemon";
                    return;
                }
                Daemon.dims(screenW, screenH, (int) AimConfig.MAX_TRAVEL_PX);
                Daemon.startKeepAlive();
                armed = true;
                state = "armed";
                startScopeWatch();
            }
        });
    }

    /** Give the panel back. Only safe with nothing touching the screen. */
    public static void disarm() {
        Daemon.post(new Runnable() {
            @Override
            public void run() {
                armed = false;
                Daemon.watchFire(0, 0, 0, 0);
                Daemon.release();
                Daemon.stopKeepAlive();
                state = "off";
            }
        });
    }

    /** Kills the daemon outright (service teardown). */
    public static void shutdown() {
        stopScopeWatch();
        Daemon.close();
        armed = false;
        state = "off";
    }

    /**
     * The true physical screen size, from {@code Display.getRealMetrics}.
     *
     * <p>Not the overlay view's size: the full-screen ESP view measures
     * 2117x1080 on this device while the display is 2340x1080 — 223 px of
     * cutout and nav-bar inset that the renderer corrects for internally and
     * the input side must not. The daemon maps raw panel coordinates onto the
     * whole physical panel, so a fire circle sized against the inset view lands
     * ~170 px left of the real button and every press reads as a miss.</p>
     */
    public static void setScreen(int w, int h) {
        boolean changed = (w > 0 && w != screenW) || (h > 0 && h != screenH);
        if (w > 0) screenW = w;
        if (h > 0) screenH = h;
        if (!changed || !Daemon.isUp()) return;
        Log.i(TAG, "screen -> " + screenW + "x" + screenH);
        Daemon.dims(screenW, screenH, (int) AimConfig.MAX_TRAVEL_PX);
    }

    // ====================================================================
    // Scope watch
    // ====================================================================

    private static synchronized void startScopeWatch() {
        if (watching) return;
        watching = true;
        // Seed from the CURRENT state, so arming while already scoped is not
        // mistaken for an edge and does not fire immediately.
        wasScoped = ScopeState.isScoped();
        scopeWatch = new Runnable() {
            @Override
            public void run() {
                if (!watching) return;
                try {
                    boolean now = ScopeState.isScoped();
                    if (now != wasScoped) {
                        wasScoped = now;
                        if (now) onScopeIn();
                        else onScopeOut();
                    }
                } catch (Throwable ignored) {
                }
                Daemon.postDelayed(this, SCOPE_POLL_MS);
            }
        };
        Daemon.post(scopeWatch);
    }

    private static synchronized void stopScopeWatch() {
        watching = false;
        Daemon.cancel(scopeWatch);
        scopeWatch = null;
    }

    /**
     * The AIM button's state. Nothing touches the screen while this is false.
     *
     * <p>Separate from {@link AimConfig#TEST_MODE}, which says the feature is
     * configured on; this says the user has it switched on <i>right now</i>.
     * Conflating the two meant AIM-off still steered on every scope.</p>
     */
    private static volatile boolean active = false;

    /** Master switch, driven by the AIM overlay button. */
    public static void setActive(boolean on) {
        active = on;
        if (!on) {
            // Cancel anything in flight and stop watching. The grab stays --
            // releasing mid-touch strands a pointer exactly as grabbing
            // mid-touch does.
            Daemon.post(new Runnable() {
                @Override
                public void run() {
                    Daemon.watchFire(0, 0, 0, 0);
                    state = "off";
                }
            });
        }
        Log.i(TAG, "aim " + (on ? "ON" : "OFF"));
    }

    public static boolean isActive() {
        return active;
    }

    private static void onScopeIn() {
        // BOTH gates. The button is the user's switch; TEST_MODE is the
        // feature's. Either one off means nothing moves.
        if (!active || !AimConfig.TEST_MODE) return;
        long now = System.currentTimeMillis();
        // Cmd 137/138 are measured NOT to always pair — one live test produced
        // a double START 0.51 s apart — so an edge is not proof the previous
        // steer ended. Without this a flapping latch restarts it on itself.
        if (now - lastSteerMs < AimConfig.TEST_HOLD_MS + 500L) return;
        lastSteerMs = now;
        boolean ok = steerNow(AimConfig.TEST_HOLD_MS);
        Log.i(TAG, "scope IN: steer " + (ok ? "started" : "refused"));
    }

    private static void onScopeOut() {
        // Disarm the watch, but KEEP the grab — see rule 1.
        Daemon.watchFire(0, 0, 0, 0);
        state = "idle (unscoped)";
        Log.i(TAG, "scope OUT: disarmed");
    }

    // ====================================================================
    // Actions
    // ====================================================================

    /**
     * Steer the finger nearest the fire button for {@code ms}.
     *
     * <p>Every gate removed: no target, no team byte, no stillness, no new
     * press. Either the view turns — in which case the contact takeover works
     * and only {@link AimConfig#PX_PER_DEG} is left — or it does not, and
     * nothing further up the stack is worth tuning.</p>
     */
    public static boolean steerNow(final int ms) {
        if (!Daemon.ensure()) {
            state = "no daemon";
            return false;
        }
        int cx, cy;
        if (Layout.isPlaced() && AimConfig.PLANT_ON_FIRE) {
            cx = Math.round(Layout.fireCxPx(screenW));
            cy = Math.round(Layout.fireCyPx(screenH));
        } else {
            cx = Math.round(screenW * AimConfig.SWIPE_X_FRAC);
            cy = Math.round(screenH * AimConfig.SWIPE_Y_FRAC);
        }
        Daemon.dims(screenW, screenH, (int) AimConfig.MAX_TRAVEL_PX);
        boolean ok = Daemon.steerNow(cx, cy, ms);
        if (ok) {
            Daemon.startKeepAlive();
            state = "steering @" + cx + "," + cy;
            steers++;
        } else {
            state = "sendfail";
        }
        return ok;
    }

    /**
     * Fire one corrective swipe at the currently acquired target.
     *
     * <p>One-shot and open-loop: the delta is computed once, here, from the
     * error the renderer measured on its last frame. Deliberately not a
     * tracking loop — while the swipe turns the camera the target's screen
     * position moves <i>because of the swipe</i>, so a closed-loop controller
     * would chase a projection it is itself moving.</p>
     *
     * @return true when a swipe was actually sent
     */
    public static boolean fireAtTarget() {
        long now = System.currentTimeMillis();
        // EVERY BAIL IS COUNTED. lastBail reaches the HUD, but nothing reached
        // the DIAG block -- and the DIAG block is where the aim's precondition
        // ladder is documented to live. The slots below were all declared and
        // had never been written, so "the aim did nothing" and "the aim was
        // never asked" read identically in a dump.
        Diag.hit(Slot.AIM_TICK);
        if (!AimConfig.AIM_ENABLED) {
            lastBail = "off";
            Diag.hit(Slot.AIM_DISABLED);
            return false;
        }
        if (!AimTarget.fresh(now, AimConfig.TARGET_MAX_AGE_MS)) {
            lastBail = "stale";
            Diag.hit(Slot.AIM_NO_TARGET);
            return false;
        }
        if (!Daemon.ensure()) {
            lastBail = "nodaemon";
            Diag.hit(Slot.AIM_PARKED_IDLE);
            return false;
        }

        float yaw = AimTarget.yawErrDeg;
        float pitch = AimTarget.pitchErrDeg;
        if (Math.abs(yaw) < AimConfig.DEADZONE_DEG
                && Math.abs(pitch) < AimConfig.DEADZONE_DEG) {
            lastBail = "onTarget";
            Diag.hit(Slot.AIM_ON_TARGET);
            return false;
        }

        // deg -> px. Screen-y grows downward and a positive pitch error means
        // "look up", so the vertical swipe is negated.
        // PX_PER_DEG is the HIP-FIRE figure. Scoped, the game damps the look by
        // roughly 0.2, so the same drag turns a fifth as far -- send the hip
        // number while scoped and the aim crawls toward the target instead of
        // reaching it.
        float gain = AimConfig.PX_PER_DEG;
        if (ScopeState.isScoped()) gain *= AimConfig.AIM_SCOPED_GAIN;
        float dx = yaw * gain;
        float dy = -pitch * gain;

        float sx = screenW * AimConfig.SWIPE_X_FRAC;
        float sy = screenH * AimConfig.SWIPE_Y_FRAC;

        // A placed layout overrides the plant point AND the axes: the X path's
        // direction carries yaw, the Y path's carries pitch, so (dx, dy)
        // becomes dx*uX + dy*uY. A horizontal X path and a vertical Y path —
        // the shipped default — reduce this to the screen axes exactly, so
        // placing a layout can only change what was deliberately changed.
        //
        // A path's LENGTH is NOT a travel cap. The lines mark where the finger
        // presses and which way each axis runs; the swipe may then run to the
        // edge of the screen.
        if (Layout.isPlaced()
                && Layout.lineXdir(screenW, screenH, dirX)
                && Layout.lineYdir(screenW, screenH, dirY)) {
            float px = dx * dirX[0] + dy * dirY[0];
            float py = dx * dirX[1] + dy * dirY[1];
            dx = px;
            dy = py;

            if (AimConfig.PLANT_ON_FIRE) {
                sx = Layout.fireCxPx(screenW);
                sy = Layout.fireCyPx(screenH);
            } else if (crossing(plant)) {
                sx = plant[0];
                sy = plant[1];
            } else {
                sx = Layout.lineXcx(screenW);
                sy = Layout.lineXcy(screenH);
            }
        }

        final float edge = 6f;
        sx = AimConfig.clamp(sx, edge, screenW - edge);
        sy = AimConfig.clamp(sy, edge, screenH - edge);

        // Scale the WHOLE delta by the largest factor that keeps its endpoint
        // on screen. Clamping the axes independently would keep the swipe on
        // screen and change its DIRECTION, turning an overshoot on one axis
        // into a miss on the other.
        float k = 1f;
        if (dx > 0f) k = Math.min(k, (screenW - edge - sx) / dx);
        if (dx < 0f) k = Math.min(k, (edge - sx) / dx);
        if (dy > 0f) k = Math.min(k, (screenH - edge - sy) / dy);
        if (dy < 0f) k = Math.min(k, (edge - sy) / dy);
        if (k < 0f) k = 0f;
        if (k < 1f) {
            dx *= k;
            dy *= k;
            Diag.hit(Slot.AIM_TRAVEL_CLAMPED);
        }

        Daemon.dims(screenW, screenH, (int) AimConfig.MAX_TRAVEL_PX);
        // STEER the finger the player already has down; do not plant one.
        //
        // This used to call Daemon.swipe(), which plants a synthetic contact,
        // drags it and lifts it. On a 120 ms loop with a 350 ms shot gap that
        // is a fresh tap three times a second -- the "tapping up up down down"
        // the owner reported, and nothing like the test spin, which steers the
        // real contact and is why the test always looked right while the aim
        // never did.
        //
        // Falls back to a planted swipe only when the daemon says there is no
        // finger down to steer, which is the one case a synthetic contact is
        // the only option.
        boolean ok = Daemon.steerBy(Math.round(dx), Math.round(dy),
                (int) sx, (int) sy);
        if (ok) {
            shots++;
            lastBail = null;
            Diag.hit(Slot.AIM_INJECTED);
        } else {
            lastBail = "sendfail";
            Diag.hit(Slot.AIM_INJECT_THREW);
        }
        return ok;
    }

    /**
     * Intersection of the two placed swipe paths, written to {@code out}.
     *
     * <p>Planting there gives both components room in both directions. Returns
     * false for near-parallel paths: their crossing runs off to infinity, and a
     * plant point outside the panel is clipped by the digitiser into a swipe
     * that silently under-turns.</p>
     */
    private static boolean crossing(float[] out) {
        float w = screenW, h = screenH;
        float ax = Layout.lineXx1 * w, ay = Layout.lineXy1 * h;
        float bx = Layout.lineXx2 * w, by = Layout.lineXy2 * h;
        float cx = Layout.lineYx1 * w, cy = Layout.lineYy1 * h;
        float dx = Layout.lineYx2 * w, dy = Layout.lineYy2 * h;

        float r1 = bx - ax, r2 = by - ay;
        float s1 = dx - cx, s2 = dy - cy;
        float den = r1 * s2 - r2 * s1;
        // |den| is |r||s|sin(theta); 1e-2 of the product is about 0.6 degrees.
        float scale = (float) Math.sqrt((r1 * r1 + r2 * r2) * (s1 * s1 + s2 * s2));
        if (scale < 1f || Math.abs(den) < scale * 1e-2f) return false;

        float t = ((cx - ax) * s2 - (cy - ay) * s1) / den;
        float px = ax + r1 * t;
        float py = ay + r2 * t;
        if (px < 0f || py < 0f || px > w || py > h) return false;
        out[0] = px;
        out[1] = py;
        return true;
    }

    // ====================================================================
    // Diagnostics
    // ====================================================================

    private static final Daemon.Listener LINES = new Daemon.Listener() {
        @Override
        public void onLine(String line) {
            if (line.startsWith("TEST_SPIN_START")) {
                state = "steering";
                Diag.hit(Slot.AIM_TEST_SPIN_START);
            } else if (line.startsWith("TEST_SPIN_END")) {
                state = "done";
                Diag.hit(Slot.AIM_TEST_SPIN_END);
            }
        }
    };

    /** Shots fired and why the last attempt bailed. */
    public static String aimLine() {
        StringBuilder sb = new StringBuilder(32);
        sb.append("shots=").append(shots);
        if (lastBail != null) sb.append(" bail=").append(lastBail);
        return sb.toString();
    }

    /** Steer state, for the panel's TEST row. */
    public static String steerLine() {
        return (active ? "AIM on" : "AIM off") + " / " + state + " steers=" + steers;
    }

    /**
     * What the app believes the screen is and where it put the fire circle.
     *
     * <p>Paired with {@link Daemon#lastDown()} — what the daemon actually saw —
     * because when the two disagree the circle is in the wrong coordinate space,
     * and that is invisible from either side alone.</p>
     */
    public static String geomLine() {
        return screenW + "x" + screenH
                + " fire=" + Math.round(Layout.fireCxPx(screenW))
                + "," + Math.round(Layout.fireCyPx(screenH))
                + " r=" + Math.round(Layout.fireRPx(screenW));
    }
}
