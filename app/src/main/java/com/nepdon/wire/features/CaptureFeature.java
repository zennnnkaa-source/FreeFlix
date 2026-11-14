package com.nepdon.wire.features;

import android.content.SharedPreferences;

import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.config.plugin.SpecBuilder;

/**
 * Packet capture — the one feature with <b>no master switch</b>.
 *
 * <p>{@link #enabledSetting} returns null on purpose, and the registry
 * understands that to mean "always on, not toggleable". A control here could
 * only ever turn <i>off</i> the recording that every diagnosis in this project
 * depends on: the CS blackout, the mounted-camera freeze and the false
 * RENDER_BLIND alarms were all found by reading a capture afterwards, and none
 * of them would have been found if the capture had been off because someone
 * tidied it away.</p>
 *
 * <p>It still appears in the settings list, so its absence is visible and
 * deliberate rather than looking like an oversight. It shows REC and offers
 * nothing to press.</p>
 */
public final class CaptureFeature implements Feature {

    @Override public String id() { return "capture"; }
    @Override public String title() { return "Capture"; }
    @Override public String summary() { return "Always on — never switchable"; }
    @Override public int schemaVersion() { return 1; }
    @Override public void migrate(int from, SharedPreferences.Editor ed) { }

    /** Null: see the class note. This is the feature, not an omission. */
    @Override public Setting.Bool enabledSetting() { return null; }

    @Override public void defineSettings(SpecBuilder b) { }
    @Override public String validate() { return null; }
    @Override public void onSettingsChanged() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
}
