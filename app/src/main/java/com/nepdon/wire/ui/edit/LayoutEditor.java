package com.nepdon.wire.ui.edit;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;

import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * The full-screen layout editor window: owns its own overlay, swallows every
 * touch while it is open, and exists only while it is open.
 *
 * <h3>Why a separate window and not a flag flip</h3>
 *
 * <p>The obvious design is to make the ESP overlay touchable for the duration.
 * It is the wrong one. Mutating flags on a live window through
 * {@code updateViewLayout} does work, but it is reported to flicker and to
 * animate a reposition on some ROMs when the size changes with it, and — worse
 * here — it puts the app's only always-on window one bug away from eating the
 * player's touches for the rest of the match. A window that is <b>created on
 * open and removed on exit</b> cannot leave that state behind: if anything
 * throws, the window is gone and the game has its input back.</p>
 *
 * <p>It also gives the "elements are invisible outside edit mode" requirement
 * for free. They are drawn by a view that does not exist the rest of the time,
 * so there is no visibility flag to leave in the wrong state.</p>
 *
 * <h3>Swallowing touches without stealing focus</h3>
 *
 * <p>The flags are {@code NOT_FOCUSABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS}
 * and, deliberately, <b>not</b> {@code FLAG_NOT_TOUCHABLE}. Input routing on
 * Android is per <i>window</i>: a touchable window receives every pointer inside
 * its bounds, and an unhandled event is dropped rather than passed to the window
 * behind. The bounds here are the whole display, so nothing reaches the game.</p>
 *
 * <p>{@code NOT_FOCUSABLE} costs only key events, and buys the thing that
 * matters most: the game never loses window focus, so it cannot pause itself and
 * its immersive mode is untouched. That is why there is an EXIT chip and no BACK
 * handling — becoming focusable to catch BACK is exactly the change that risks
 * pausing a live match.</p>
 *
 * <p>One honest limit: the system gesture-navigation areas cannot be swallowed.
 * {@code setSystemGestureExclusionRects} opts out of the back gesture only, and
 * at most 200dp per edge; home and quick-switch are not excludable by any app.
 * A home swipe will still leave the game. That is acceptable — leaving the app
 * is not a leaked touch that fires a weapon — but it is a limit, not an
 * oversight.</p>
 *
 * <h3>Blur is a bonus, never the backdrop</h3>
 *
 * <p>Three tiers, and the bottom one always runs:</p>
 * <ol>
 *   <li>API 31+ <i>and</i> {@code isCrossWindowBlurEnabled()} —
 *       {@code FLAG_BLUR_BEHIND} at radius 20 plus a light dim and a light
 *       self-drawn scrim.</li>
 *   <li>Otherwise {@code FLAG_DIM_BEHIND} at 0.4 plus a heavy scrim.</li>
 *   <li>The self-drawn scrim, which is a single full-screen blend and works
 *       everywhere including an emulator.</li>
 * </ol>
 *
 * <p>Blur is not merely version-gated: the platform silently disables it in
 * battery saver, under thermal pressure, in tunnelled video, when the developer
 * option is off, and on any device whose
 * {@code ro.surface_flinger.supports_background_blur} is false — which is the
 * default. A long match on a hot phone can cross the thermal threshold and kill
 * the blur mid-session with no error, which is why the listener is registered
 * rather than the state being sampled once. LDPlayer lands on tier 2 or 3 and
 * looks correct there.</p>
 */
public final class LayoutEditor {

    /** AOSP's own "good depth of field" blur-behind radius. */
    private static final int BLUR_RADIUS_PX = 20;

    private final Context context;
    private final WindowManager wm;

    private EditSurface surface;
    private WindowManager.LayoutParams params;
    private Object blurListener;          // Consumer<Boolean>, API 31+
    private boolean open;

    public LayoutEditor(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    public boolean isOpen() {
        return open;
    }

    /** Adds the editor window. Idempotent. */
    public void open() {
        if (open) return;

        EditSurface v = new EditSurface(context, new EditSurface.Listener() {
            @Override
            public void onExit() {
                close();
            }
        });

        int type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // FLAG_NOT_TOUCHABLE is deliberately ABSENT -- its absence is what makes
        // this window swallow input. FLAG_NOT_TOUCH_MODAL is also absent, and
        // would be dead code next to NOT_FOCUSABLE, which already implies it.
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        // Set width/height by NAME. The five-argument constructor takes
        // (w, h, type, flags, format), and passing a flag constant as the first
        // argument -- a widely copied snippet does exactly this with
        // FLAG_FULLSCREEN -- silently produces a 1024x1024 window that renders
        // perfectly and is not full-screen.
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                type, flags, PixelFormat.TRANSLUCENT);
        p.width = WindowManager.LayoutParams.MATCH_PARENT;
        p.height = WindowManager.LayoutParams.MATCH_PARENT;
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 0;
        p.dimAmount = 0.4f;

        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = (Build.VERSION.SDK_INT >= 30)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            wm.addView(v, p);
        } catch (Throwable t) {
            // The one failure that looks exactly like the gesture not working:
            // the taps landed, open() ran, and nothing appeared.
            Diag.hit(Slot.SETUP_EDITOR_ADD_FAILED);
            return;
        }

        surface = v;
        params = p;
        open = true;
        Diag.hit(Slot.SETUP_EDITOR_OPEN);

        applyBlurTier();
    }

    /** Persists the layout and removes the window. Idempotent. */
    public void close() {
        if (!open) return;
        open = false;

        try {
            Layout.clampAll();
            Layout.save(context);
            Diag.hit(Slot.SETUP_EDITOR_SAVE);
        } catch (Throwable ignored) {
        }

        detachBlurListener();

        if (surface != null) {
            try {
                // Immediate, not queued: this window is holding every touch on
                // the device and the player is in a live match.
                wm.removeViewImmediate(surface);
            } catch (Throwable t) {
                try {
                    wm.removeView(surface);
                } catch (Throwable ignored) {
                }
            }
        }
        surface = null;
        params = null;
    }

    // ==================================================================
    // Blur
    // ==================================================================

    private void applyBlurTier() {
        if (Build.VERSION.SDK_INT < 31) {
            setBlurring(false);
            return;
        }
        try {
            setBlurring(Api31.isEnabled(wm));
            blurListener = Api31.attach(wm, this);
        } catch (Throwable t) {
            setBlurring(false);
        }
    }

    private void detachBlurListener() {
        if (blurListener != null && Build.VERSION.SDK_INT >= 31) {
            try {
                Api31.detach(wm, blurListener);
            } catch (Throwable ignored) {
            }
        }
        blurListener = null;
    }

    /** Package-private so {@link Api31}'s callback can reach it. */
    void onBlurEnabledChanged(boolean enabled) {
        setBlurring(enabled);
    }

    /**
     * Everything that names an API-31 type, kept in its own class.
     *
     * <p>{@code java.util.function.Consumer} is API 24 and the blur calls are
     * API 31, while this module's {@code minSdk} is 21. A {@code SDK_INT} guard
     * protects a <i>call</i>, but not the <i>type</i> a method signature or an
     * anonymous class mentions — that resolves when the enclosing class is
     * verified. Holding them here means this class is never loaded below 31, so
     * there is nothing to resolve.</p>
     */
    private static final class Api31 {
        private Api31() { }

        static boolean isEnabled(WindowManager wm) {
            return wm.isCrossWindowBlurEnabled();
        }

        static Object attach(WindowManager wm, final LayoutEditor owner) {
            java.util.function.Consumer<Boolean> l =
                    new java.util.function.Consumer<Boolean>() {
                        @Override
                        public void accept(Boolean enabled) {
                            owner.onBlurEnabledChanged(enabled != null && enabled);
                        }
                    };
            wm.addCrossWindowBlurEnabledListener(l);
            return l;
        }

        @SuppressWarnings("unchecked")
        static void detach(WindowManager wm, Object listener) {
            wm.removeCrossWindowBlurEnabledListener(
                    (java.util.function.Consumer<Boolean>) listener);
        }

        static void setRadius(WindowManager.LayoutParams p, int radius) {
            p.setBlurBehindRadius(radius);
        }
    }

    /**
     * Moves between tiers. The dim and the self-drawn scrim both get lighter
     * when the system is genuinely blurring, because blur under a heavy wash
     * reads as fog rather than glass.
     */
    private void setBlurring(boolean blurring) {
        if (!open || params == null || surface == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (blurring) {
                    params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    // The flag alone does nothing: radius 0 means no blur, with
                    // no error and no log.
                    Api31.setRadius(params, BLUR_RADIUS_PX);
                } else {
                    params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    Api31.setRadius(params, 0);
                }
            }
            params.dimAmount = blurring ? 0.10f : 0.40f;
            wm.updateViewLayout(surface, params);
            surface.setBlurring(blurring);
        } catch (Throwable ignored) {
        }
    }
}
