package com.nepdon.wire.features;

import com.nepdon.wire.config.EspConfig;

import android.content.SharedPreferences;

import com.nepdon.wire.config.EspTuning;
import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Refs;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.config.plugin.SpecBuilder;
import com.nepdon.wire.ui.render.EspRenderView;

/**
 * The enemy boxes: projection, prediction and what gets drawn on each one.
 *
 * <h3>Why the bindings point at the existing statics</h3>
 *
 * <p>Every {@code Refs} below reads and writes the {@code public static} that
 * {@code EspRenderView} already uses. That is the whole migration strategy:
 * the declaration moves here, the storage does not move at all, so
 * {@code onDraw} keeps doing a plain static field load at 120 Hz and the
 * renderer needs no edit to benefit. A setting that pointed somewhere new
 * would mean rewriting the hot path to match — for no gain, since a field
 * read is already free.</p>
 */
public final class EspFeature implements Feature {

    private final Setting.Bool enabled = new Setting.Bool(
            Prefs.KEY_ESP_BOXES, "ESP", "Boxes, health and names on each enemy",
            "ESP", Setting.APPLIES_LIVE, null, true,
            new Refs.BoolRef() {
                public boolean get() { return EspConfig.SHOW_BOXES; }
                public void set(boolean v) { EspConfig.SHOW_BOXES = v; }
            });

    @Override public String id() { return "esp"; }
    @Override public String title() { return "ESP"; }
    @Override public String summary() { return "Boxes, health and names"; }

    /**
     * v2: {@code KEY_ESP_FOV_X} changed meaning. It used to be read as a
     * vertical FOV and is now the horizontal one at the 16:9 reference, so a
     * value stored under v1 is off by the aspect ratio and looks like a
     * calibration error rather than a stale setting.
     */
    @Override public int schemaVersion() { return 2; }

    @Override public void migrate(int from, SharedPreferences.Editor ed) {
        if (from < 2) {
            // Drop it rather than convert it: the old value may itself have
            // been hand-tuned to compensate for the wrong reading, so the
            // conversion would preserve a correction for a bug that is gone.
            ed.remove(Prefs.KEY_ESP_FOV_X);
        }
    }

    @Override public Setting.Bool enabledSetting() { return enabled; }

    @Override public void defineSettings(SpecBuilder b) {
        b.dependsOn(Prefs.KEY_ESP_BOXES);

        b.note("Horizontal, at the 16:9 reference. 102.7° on a 19.5:9 phone.");
        b.num(Prefs.KEY_ESP_FOV_X, "Field of view",
                EspTuning.DEF_FOV, EspTuning.FOV_MIN, EspTuning.FOV_MAX, 0.5f, 5f, "°",
                new Refs.FloatRef() {
                    public float get() { return EspConfig.FOV_X_DEG; }
                    public void set(float v) { EspConfig.FOV_X_DEG = v; }
                });

        b.bool(Prefs.KEY_ESP_NAMES, "Names", true, new Refs.BoolRef() {
            public boolean get() { return EspConfig.SHOW_NAMES; }
            public void set(boolean v) { EspConfig.SHOW_NAMES = v; }
        });
        b.bool(Prefs.KEY_ESP_HP_BAR, "Health bars", true, new Refs.BoolRef() {
            public boolean get() { return EspConfig.HP_BAR; }
            public void set(boolean v) { EspConfig.HP_BAR = v; }
        });
        b.bool(Prefs.KEY_ESP_POSE, "Facing indicator", EspTuning.DEF_SHOW_POSE, new Refs.BoolRef() {
            public boolean get() { return EspConfig.SHOW_POSE; }
            public void set(boolean v) { EspConfig.SHOW_POSE = v; }
        });

        b.note("Entities whose side has not been decided yet.");
        b.bool(Prefs.KEY_ESP_SHOW_UNK, "Show unclassified", true, new Refs.BoolRef() {
            public boolean get() { return EspConfig.SHOW_UNCLASSIFIED; }
            public void set(boolean v) { EspConfig.SHOW_UNCLASSIFIED = v; }
        });

        // ---- prediction ----
        b.section("Prediction");
        b.note("Advances each box along its last velocity to cover the wire delay.");
        b.bool(Prefs.KEY_ESP_EXTRAPOLATE, "Predict movement", EspTuning.DEF_EXTRAPOLATE, new Refs.BoolRef() {
            public boolean get() { return EspConfig.EXTRAPOLATE; }
            public void set(boolean v) { EspConfig.EXTRAPOLATE = v; }
        });

        b.dependsOn(Prefs.KEY_ESP_EXTRAPOLATE);
        b.note("Measured from the self-echo. Overrides the manual lead below.");
        b.bool(Prefs.KEY_ESP_AUTO_LEAD, "Measure lead automatically", EspTuning.DEF_AUTO_LEAD, new Refs.BoolRef() {
            public boolean get() { return EspConfig.AUTO_LEAD; }
            public void set(boolean v) { EspConfig.AUTO_LEAD = v; }
        });
        // 215ms is MEASURED, not guessed - see EspTuning.DEF_LEAD_MS.
        b.num(Prefs.KEY_ESP_LEAD_MS, "Manual lead",
                EspTuning.DEF_LEAD_MS, EspTuning.LEAD_MIN, EspTuning.LEAD_MAX,
                5f, 25f, "ms",
                new Refs.FloatRef() {
                    public float get() { return (float) EspConfig.LEAD_MS; }
                    public void set(float v) { EspConfig.LEAD_MS = v; }
                });
    }

    /**
     * The one rule a {@code dependsOn} cannot express.
     *
     * <p>With prediction on, auto-lead off and the manual lead at zero, the
     * boxes sit exactly where the packet said they were — which is the same
     * place they would sit with prediction off, except the user believes
     * prediction is doing something. That combination has already been
     * reported once as "prediction is broken". Say so instead.</p>
     */
    @Override public String validate() {
        if (EspConfig.EXTRAPOLATE && !EspConfig.AUTO_LEAD
                && EspConfig.LEAD_MS <= 0) {
            return "Prediction is on but the lead is 0 ms, so boxes will not move "
                 + "ahead at all. Turn on automatic lead, or raise the manual lead.";
        }
        return null;
    }

    @Override public void onSettingsChanged() {
        // Nothing to push: the bindings wrote straight into the fields the
        // renderer reads, and it picks them up on the next frame.
    }

    @Override public void onEnable() { }
    @Override public void onDisable() { }
}
