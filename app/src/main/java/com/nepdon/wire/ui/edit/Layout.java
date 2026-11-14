package com.nepdon.wire.ui.edit;

import android.content.Context;
import android.content.SharedPreferences;

import com.nepdon.wire.config.Prefs;

/**
 * The placed geometry the setup editor produces: two swipe lines and one fire
 * button. Pure data plus load/save — no Android view code, so the renderer, the
 * injector and the editor can all read it without depending on each other.
 *
 * <h3>Everything is stored NORMALISED, never in pixels</h3>
 *
 * <p>This device reports a panel density of 480 while its actual override is
 * <b>398</b>, and a hand-computed pixel position from the reported value has
 * already shipped wrong once. A fraction of the screen survives a density
 * change, a rotation, and a different handset; a pixel does not. Everything
 * here is a fraction of screen width (x, lengths, radii) or height (y), and the
 * only place pixels appear is the {@code *Px} accessors, which take the live
 * surface size as an argument rather than reading a cached one.</p>
 *
 * <h3>A line is two endpoints, not centre+angle+length</h3>
 *
 * <p>Both describe the same segment, but the endpoint pair is what the editor's
 * handles manipulate directly, so storing it avoids a round trip through
 * trigonometry on every drag — and avoids the class of bug where a length of
 * zero destroys the angle and the element can never be recovered. Centre, angle
 * and length are derived on demand instead.</p>
 *
 * <h3>Why two lines and not one origin point</h3>
 *
 * <p>The old model was a single plant point plus a travel cap, which assumes the
 * game's look region is isotropic around that point. It is not: the usable drag
 * area is bounded by the fire button, the crouch/jump cluster and the user's own
 * thumb, and those bounds are different horizontally and vertically. Two
 * independently placed segments let the horizontal correction use the wide
 * corridor and the vertical correction use the tall one. Each line's own length
 * IS its travel budget, so there is no separate number that can disagree with
 * what is drawn.</p>
 *
 * <h3>Not authoritative until the user has placed it</h3>
 *
 * <p>{@link #isPlaced()} is false until the editor saves. Readers must fall back
 * to the old {@code SWIPE_X_FRAC}/{@code SWIPE_Y_FRAC} statics while it is
 * false — a new field that silently overrides an old one is exactly the trap
 * the house notes warn about, so the old answer stays reachable.</p>
 */
public final class Layout {

    private Layout() { }

    // ==================================================================
    // Live values. Volatile because the editor writes them on the UI
    // thread and the renderer reads them on the Choreographer frame.
    // ==================================================================

    /** Horizontal-correction swipe path, normalised endpoints. */
    public static volatile float lineXx1 = 0.62f, lineXy1 = 0.55f;
    public static volatile float lineXx2 = 0.82f, lineXy2 = 0.55f;

    /** Vertical-correction swipe path, normalised endpoints. */
    public static volatile float lineYx1 = 0.72f, lineYy1 = 0.34f;
    public static volatile float lineYx2 = 0.72f, lineYy2 = 0.56f;

    /** Fire button: centre (w,h fractions) and radius (width fraction). */
    public static volatile float fireCx = 0.88f, fireCy = 0.72f;
    public static volatile float fireR  = 0.055f;

    /**
     * True once the user has saved a layout from the editor.
     *
     * <p>The defaults above are a plausible right-handed placement, not a
     * measurement. Until this flips, nothing should treat them as the user's
     * intent.</p>
     */
    public static volatile boolean placed = false;

    /**
     * Draw the elements on the ESP surface. The editor forces this on while it
     * is open and restores it on exit, because the owner asked for the elements
     * to be invisible during play — they are input geometry, not decoration,
     * and a permanent line across the look area is a tell.
     */
    public static volatile boolean visible = false;

    // ==================================================================
    // Bounds. Enforced on load AND on save, because a stored value can be
    // stale from an earlier build with a different range.
    // ==================================================================

    /** Shortest a line may get, as a width fraction (~35 px at 1080). */
    public static final float MIN_LINE_FRAC = 0.032f;
    /** Smallest fire radius, as a width fraction (~24 px at 1080 = 48 px across). */
    public static final float MIN_FIRE_R = 0.022f;
    /** Largest fire radius, so it cannot swallow the screen and trap the editor. */
    public static final float MAX_FIRE_R = 0.22f;

    public static boolean isPlaced() {
        return placed;
    }

    // ==================================================================
    // Derived geometry, in pixels, from a live surface size
    // ==================================================================

    public static float lineXcx(int w) { return (lineXx1 + lineXx2) * 0.5f * w; }
    public static float lineXcy(int h) { return (lineXy1 + lineXy2) * 0.5f * h; }
    public static float lineYcx(int w) { return (lineYx1 + lineYx2) * 0.5f * w; }
    public static float lineYcy(int h) { return (lineYy1 + lineYy2) * 0.5f * h; }

    /** Full pixel length of the horizontal-correction path. */
    public static float lineXlenPx(int w, int h) {
        float dx = (lineXx2 - lineXx1) * w;
        float dy = (lineXy2 - lineXy1) * h;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float lineYlenPx(int w, int h) {
        float dx = (lineYx2 - lineYx1) * w;
        float dy = (lineYy2 - lineYy1) * h;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Unit direction of a line, written into {@code out[0..1]}.
     *
     * <p>Takes a caller-owned array so a per-frame reader allocates nothing.
     * Returns false and leaves {@code out} untouched for a degenerate segment,
     * rather than emitting a NaN that would poison a swipe delta.</p>
     */
    public static boolean lineXdir(int w, int h, float[] out) {
        return dir((lineXx2 - lineXx1) * w, (lineXy2 - lineXy1) * h, out);
    }

    public static boolean lineYdir(int w, int h, float[] out) {
        return dir((lineYx2 - lineYx1) * w, (lineYy2 - lineYy1) * h, out);
    }

    private static boolean dir(float dx, float dy, float[] out) {
        float m = (float) Math.sqrt(dx * dx + dy * dy);
        if (m < 1e-3f || out == null || out.length < 2) return false;
        out[0] = dx / m;
        out[1] = dy / m;
        return true;
    }

    public static float fireCxPx(int w) { return fireCx * w; }
    public static float fireCyPx(int h) { return fireCy * h; }
    public static float fireRPx(int w)  { return fireR * w; }

    // ==================================================================
    // Persistence
    // ==================================================================

    /** Reads the stored layout, clamping every value. Never throws. */
    public static void load(Context ctx) {
        try {
            SharedPreferences sp = Prefs.get(ctx);
            lineXx1 = f(sp, Prefs.KEY_SETUP_LX1, lineXx1);
            lineXy1 = f(sp, Prefs.KEY_SETUP_LY1, lineXy1);
            lineXx2 = f(sp, Prefs.KEY_SETUP_LX2, lineXx2);
            lineXy2 = f(sp, Prefs.KEY_SETUP_LY2, lineXy2);
            lineYx1 = f(sp, Prefs.KEY_SETUP_VX1, lineYx1);
            lineYy1 = f(sp, Prefs.KEY_SETUP_VY1, lineYy1);
            lineYx2 = f(sp, Prefs.KEY_SETUP_VX2, lineYx2);
            lineYy2 = f(sp, Prefs.KEY_SETUP_VY2, lineYy2);
            fireCx  = f(sp, Prefs.KEY_SETUP_FCX, fireCx);
            fireCy  = f(sp, Prefs.KEY_SETUP_FCY, fireCy);
            fireR   = clamp(sp.getFloat(Prefs.KEY_SETUP_FR, fireR),
                    MIN_FIRE_R, MAX_FIRE_R);
            placed  = sp.getBoolean(Prefs.KEY_SETUP_PLACED, false);
        } catch (Throwable ignored) {
            // Defaults already hold; a bad stored value must not stop the
            // overlay coming up.
        }
    }

    /** Writes the current layout and marks it placed. */
    public static void save(Context ctx) {
        try {
            placed = true;
            Prefs.get(ctx).edit()
                    .putFloat(Prefs.KEY_SETUP_LX1, lineXx1)
                    .putFloat(Prefs.KEY_SETUP_LY1, lineXy1)
                    .putFloat(Prefs.KEY_SETUP_LX2, lineXx2)
                    .putFloat(Prefs.KEY_SETUP_LY2, lineXy2)
                    .putFloat(Prefs.KEY_SETUP_VX1, lineYx1)
                    .putFloat(Prefs.KEY_SETUP_VY1, lineYy1)
                    .putFloat(Prefs.KEY_SETUP_VX2, lineYx2)
                    .putFloat(Prefs.KEY_SETUP_VY2, lineYy2)
                    .putFloat(Prefs.KEY_SETUP_FCX, fireCx)
                    .putFloat(Prefs.KEY_SETUP_FCY, fireCy)
                    .putFloat(Prefs.KEY_SETUP_FR, fireR)
                    .putBoolean(Prefs.KEY_SETUP_PLACED, true)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Restores the shipped defaults and clears {@code placed}.
     *
     * <p>Removes the keys rather than writing the defaults back, because
     * {@code contains(key)} is how this codebase asks "did the user choose
     * this" — writing a default would make a reset indistinguishable from a
     * deliberate choice.</p>
     */
    public static void reset(Context ctx) {
        lineXx1 = 0.62f; lineXy1 = 0.55f; lineXx2 = 0.82f; lineXy2 = 0.55f;
        lineYx1 = 0.72f; lineYy1 = 0.34f; lineYx2 = 0.72f; lineYy2 = 0.56f;
        fireCx = 0.88f;  fireCy = 0.72f;  fireR = 0.055f;
        placed = false;
        try {
            Prefs.get(ctx).edit()
                    .remove(Prefs.KEY_SETUP_LX1).remove(Prefs.KEY_SETUP_LY1)
                    .remove(Prefs.KEY_SETUP_LX2).remove(Prefs.KEY_SETUP_LY2)
                    .remove(Prefs.KEY_SETUP_VX1).remove(Prefs.KEY_SETUP_VY1)
                    .remove(Prefs.KEY_SETUP_VX2).remove(Prefs.KEY_SETUP_VY2)
                    .remove(Prefs.KEY_SETUP_FCX).remove(Prefs.KEY_SETUP_FCY)
                    .remove(Prefs.KEY_SETUP_FR).remove(Prefs.KEY_SETUP_PLACED)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Pulls every element back inside the screen.
     *
     * <p>The editor calls this on open. Without it, a layout saved on one
     * surface size and reloaded on another (rotation, a different device, the
     * emulator) can put an element's only grab handle off-screen, and there is
     * then no gesture that recovers it — the user's only route back would be
     * clearing app data.</p>
     */
    public static void clampAll() {
        lineXx1 = clamp(lineXx1, 0f, 1f); lineXy1 = clamp(lineXy1, 0f, 1f);
        lineXx2 = clamp(lineXx2, 0f, 1f); lineXy2 = clamp(lineXy2, 0f, 1f);
        lineYx1 = clamp(lineYx1, 0f, 1f); lineYy1 = clamp(lineYy1, 0f, 1f);
        lineYx2 = clamp(lineYx2, 0f, 1f); lineYy2 = clamp(lineYy2, 0f, 1f);
        fireR = clamp(fireR, MIN_FIRE_R, MAX_FIRE_R);
        fireCx = clamp(fireCx, fireR, 1f - fireR);
        fireCy = clamp(fireCy, 0f, 1f);
    }

    private static float f(SharedPreferences sp, String key, float def) {
        return clamp(sp.getFloat(key, def), 0f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        if (v != v) return lo;              // NaN from a corrupted store
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
