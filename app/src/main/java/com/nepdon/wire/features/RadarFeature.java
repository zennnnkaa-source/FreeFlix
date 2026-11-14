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

/** The bearing dial in the corner. */
public final class RadarFeature implements Feature {

    private final Setting.Bool enabled = new Setting.Bool(
            Prefs.KEY_ESP_RADAR, "Radar", "Bearing dial with auto range",
            "Radar", Setting.APPLIES_LIVE, null, true,
            new Refs.BoolRef() {
                public boolean get() { return EspConfig.SHOW_RADAR; }
                public void set(boolean v) { EspConfig.SHOW_RADAR = v; }
            });

    @Override public String id() { return "radar"; }
    @Override public String title() { return "Radar"; }
    @Override public String summary() { return "Bearing dial, auto range"; }
    @Override public int schemaVersion() { return 1; }
    @Override public void migrate(int from, SharedPreferences.Editor ed) { }
    @Override public Setting.Bool enabledSetting() { return enabled; }

    @Override public void defineSettings(SpecBuilder b) {
        // RANGES AND DEFAULTS ARE EspTuning's, NOT NEW ONES.
        //
        // A Feature declaration is not a place to re-decide a value. These
        // duplicated EspTuning's keys with narrower ranges and different
        // defaults, and because Setting.Num.load CLAMPS on every launch, a
        // stored value outside the new range was silently rewritten:
        // a saved 4000 m radar ceiling came back as 500 m, every start. Worse,
        // the tune panel's row then writes the clamped value back, so one
        // press made the loss permanent.
        //
        // Sourcing them from EspTuning's constants means there is one
        // definition per number, which is the entire point of the exercise.
        b.dependsOn(Prefs.KEY_ESP_RADAR);
        b.note("The dial scales itself to contain everyone it can see, within these bounds.");
        b.num(Prefs.KEY_ESP_RADAR_MIN, "Minimum range",
                EspTuning.DEF_RADAR_MIN, EspTuning.RADAR_MIN_LO, EspTuning.RADAR_MIN_HI,
                5f, 25f, " m",
                new Refs.FloatRef() {
                    public float get() { return EspConfig.RADAR_MIN_RANGE_M; }
                    public void set(float v) { EspConfig.RADAR_MIN_RANGE_M = v; }
                });
        b.num(Prefs.KEY_ESP_RADAR_MAX, "Maximum range",
                EspTuning.DEF_RADAR_MAX, EspTuning.RADAR_MAX_LO, EspTuning.RADAR_MAX_HI,
                50f, 250f, " m",
                new Refs.FloatRef() {
                    public float get() { return EspConfig.RADAR_MAX_RANGE_M; }
                    public void set(float v) { EspConfig.RADAR_MAX_RANGE_M = v; }
                });
    }

    /** A dial whose floor exceeds its ceiling has no legal scale at all. */
    @Override public String validate() {
        if (EspConfig.RADAR_MIN_RANGE_M >= EspConfig.RADAR_MAX_RANGE_M) {
            return "Minimum range must be below maximum range.";
        }
        return null;
    }

    @Override public void onSettingsChanged() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
}
