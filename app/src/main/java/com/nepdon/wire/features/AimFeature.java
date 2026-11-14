package com.nepdon.wire.features;

import com.nepdon.wire.config.EspConfig;

import android.content.SharedPreferences;

import com.nepdon.wire.config.EspTuning;
import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Refs;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.config.plugin.SpecBuilder;
import com.nepdon.wire.ui.aim.AimConfig;
import com.nepdon.wire.ui.render.EspRenderView;

/**
 * Input-side aim assist: acquisition rules and the corrective swipe.
 *
 * <h3>What this is, precisely</h3>
 *
 * <p>It moves the player's own crosshair by planting a synthetic finger through
 * the {@code nvmux} uinput clone and dragging it. <b>It touches no packet.</b>
 * It does not forge hits, does not claim damage, and the deleted Cmd-106
 * {@code AimFilter} stays deleted. Everything below is geometry and touch.</p>
 *
 * <h3>Why every one of these is a setting and not a constant</h3>
 *
 * <p>Two of them are genuinely device-specific and cannot be derived:</p>
 * <ul>
 *   <li><b>Swipe origin</b> — the finger must plant in the game's look region,
 *       clear of the overlay button cluster AND of wherever the user's own
 *       thumbs rest. Get it wrong and the swipe drags a button instead of
 *       turning the view, which looks identical to "the aim is broken".</li>
 *   <li><b>px per degree</b> — {@code 300 / (15 + 20*sens)} from the binary is
 *       10.0 at GAME SENS 75, but Free Fire moved to a 0-200 scale with six
 *       per-scope sliders and those constants are compiled fallbacks. The
 *       user's actual slider value is user state we cannot read.</li>
 * </ul>
 *
 * <p>The rest are policy the owner should own: how wide to acquire, how far, how
 * still is still enough.</p>
 *
 * <h3>Keys are reused verbatim, never renamed</h3>
 *
 * <p>Every key here is a surviving {@code Prefs.KEY_AIM_*} string whose meaning
 * already matches the new aim ({@code aim_fov_deg}, {@code aim_swipe_px_per_deg},
 * {@code aim_max_travel_px}, {@code aim_swipe_x_frac}, {@code aim_swipe_y_frac},
 * ...). They are persisted on device, so renaming one silently resets the
 * user's tuning (hard rule 5). A {@code Setting} owns no value — it binds a
 * reference to the existing {@code static}, so {@code onDraw} keeps its plain
 * field load and allocates nothing.</p>
 */
public final class AimFeature implements Feature {

    private final Setting.Bool enabled = new Setting.Bool(
            Prefs.KEY_AIM_ENABLED, "Aim assist",
            "Corrective swipe onto a still, server-confirmed enemy", "Aim assist",
            Setting.APPLIES_LIVE, null, true,
            new Refs.BoolRef() {
                public boolean get() { return AimConfig.AIM_ENABLED; }
                public void set(boolean v) { AimConfig.AIM_ENABLED = v; }
            });

    @Override public String id() { return "aim"; }
    @Override public String title() { return "Aim assist"; }
    @Override public String summary() {
        return "One-shot swipe at a stationary enemy; never tracks a runner";
    }
    @Override public int schemaVersion() { return 1; }
    @Override public void migrate(int from, SharedPreferences.Editor ed) { }
    @Override public Setting.Bool enabledSetting() { return enabled; }

    @Override public void defineSettings(SpecBuilder b) {

        // ---- WHO the aim is allowed to pick ----
        b.section("Acquisition");
        b.num(Prefs.KEY_AIM_FOV, "Cone",
                EspTuning.DEF_AIM_CONE_DEG, EspTuning.AIM_CONE_MIN, EspTuning.AIM_CONE_MAX, 0.5f, 5f, "°",
                new Refs.FloatRef() {
                    public float get() { return EspConfig.AIM_CONE_DEG; }
                    public void set(float v) { EspConfig.AIM_CONE_DEG = v; }
                });
        b.num(Prefs.KEY_AIM_HEIGHT, "Max range",
                EspTuning.DEF_AIM_RANGE_M, EspTuning.AIM_RANGE_MIN, EspTuning.AIM_RANGE_MAX, 5f, 25f, "m",
                new Refs.FloatRef() {
                    public float get() { return EspConfig.AIM_MAX_RANGE_M; }
                    public void set(float v) { EspConfig.AIM_MAX_RANGE_M = v; }
                });
        // The thesis in one number. Dead reckoning has measured RMS error
        // 0.573/0.622/0.679 over one/two/three ticks and a swipe takes
        // 100-200 ms, so a moving target outruns its own correction. Raising
        // this makes the aim pickier and more accurate, not weaker.
        b.integer(Prefs.KEY_AIM_LOCK, "Must be still for", 600, 0, 3000, 50, "ms",
                new Refs.IntRef() {
                    public int get() { return (int) EspConfig.AIM_STILL_MS; }
                    public void set(int v) { EspConfig.AIM_STILL_MS = v; }
                });

        // ---- WHERE the synthetic finger plants ----
        b.section("Swipe zone");
        b.note("Fraction of screen width. Keep it in the game's look area "
                + "and clear of your thumbs and the overlay buttons.");
        b.num(Prefs.KEY_AIM_SWIPE_X, "Swipe X",
                AimConfig.DEF_SWIPE_X_FRAC, AimConfig.FRAC_MIN, AimConfig.FRAC_MAX, 0.01f, 0.05f, "",
                new Refs.FloatRef() {
                    public float get() { return AimConfig.SWIPE_X_FRAC; }
                    public void set(float v) { AimConfig.SWIPE_X_FRAC = v; }
                });
        b.num(Prefs.KEY_AIM_SWIPE_Y, "Swipe Y",
                AimConfig.DEF_SWIPE_Y_FRAC, AimConfig.FRAC_MIN, AimConfig.FRAC_MAX, 0.01f, 0.05f, "",
                new Refs.FloatRef() {
                    public float get() { return AimConfig.SWIPE_Y_FRAC; }
                    public void set(float v) { AimConfig.SWIPE_Y_FRAC = v; }
                });
        b.note("Longest single drag before lifting and re-planting. The game's "
                + "look speeds up the further you drag (~3.4x at 300 px), so a "
                + "long drag overshoots. Lower this if the aim overshoots.");
        b.num(Prefs.KEY_AIM_REGRIP, "Max travel",
                AimConfig.DEF_MAX_TRAVEL_PX, AimConfig.MAX_TRAVEL_MIN, AimConfig.MAX_TRAVEL_MAX, 5f, 25f, "px",
                new Refs.FloatRef() {
                    public float get() { return AimConfig.MAX_TRAVEL_PX; }
                    public void set(float v) { AimConfig.MAX_TRAVEL_PX = v; }
                });

        // ---- HOW FAR a degree of error moves the finger ----
        b.section("Calibration");
        b.note("Pixels of swipe per degree of view rotation. 10.0 is the "
                + "binary's value at GAME SENS 75. If the aim consistently "
                + "undershoots raise it; if it overshoots lower it.");
        b.num(Prefs.KEY_AIM_SWIPE, "px per degree",
                AimConfig.DEF_PX_PER_DEG, AimConfig.PX_PER_DEG_MIN, AimConfig.PX_PER_DEG_MAX, 0.1f, 1f, "px/°",
                new Refs.FloatRef() {
                    public float get() { return AimConfig.PX_PER_DEG; }
                    public void set(float v) { AimConfig.PX_PER_DEG = v; }
                });
        b.note("Draw the swipe origin and the acquisition cone on screen so "
                + "you can place them without guessing.");
        b.bool(Prefs.KEY_AIM_CIRCLE, "Show aim overlay", false,
                new Refs.BoolRef() {
                    public boolean get() { return EspConfig.SHOW_AIM_EDITOR; }
                    public void set(boolean v) { EspConfig.SHOW_AIM_EDITOR = v; }
                });
    }

    @Override public String validate() { return null; }
    @Override public void onSettingsChanged() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
}
