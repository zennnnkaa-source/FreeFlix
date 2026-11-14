package com.nepdon.wire.features;

import android.content.SharedPreferences;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Refs;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.config.plugin.SpecBuilder;
import com.nepdon.wire.esp.EspEngine;

/**
 * What the overlay uses for a camera while the player is riding something.
 *
 * <p>Its own feature rather than a checkbox under ESP, because the honest
 * explanation does not fit on a row: <b>there is no player look direction
 * anywhere on the wire while mounted.</b> Cmd 2001 stops, Cmd 2002 carries no
 * aim, and the server freezes the self-echo's camera vector for the whole ride
 * (byte-identical in 67 of 67 measured samples). The real choice is the
 * vehicle's heading versus a camera frozen at the instant of mounting — and
 * the frozen one is what users reported as "ESP dies in a vehicle".</p>
 *
 * <p>So this defaults ON, and the row says what it actually is. See
 * {@code docs/MOUNTED_ESP_FIX.md}.</p>
 */
public final class MountedCameraFeature implements Feature {

    private final Setting.Bool enabled = new Setting.Bool(
            Prefs.KEY_ESP_MOUNT_CAM, "Vehicle camera",
            "Use the vehicle's heading while riding", "Vehicle camera",
            Setting.APPLIES_LIVE, null, true,
            new Refs.BoolRef() {
                public boolean get() { return EspEngine.MOUNT_VEHICLE_CAMERA; }
                public void set(boolean v) { EspEngine.MOUNT_VEHICLE_CAMERA = v; }
            });

    @Override public String id() { return "mountcam"; }
    @Override public String title() { return "Vehicle camera"; }
    @Override public String summary() {
        return "Heading stands in for the camera while riding";
    }
    @Override public int schemaVersion() { return 1; }
    @Override public void migrate(int from, SharedPreferences.Editor ed) { }
    @Override public Setting.Bool enabledSetting() { return enabled; }

    @Override public void defineSettings(SpecBuilder b) {
        // Nothing further to tune. The heading is exact and first-hand; the
        // only knob would be pitch, which is not recoverable at all.
    }

    @Override public String validate() { return null; }
    @Override public void onSettingsChanged() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
}
