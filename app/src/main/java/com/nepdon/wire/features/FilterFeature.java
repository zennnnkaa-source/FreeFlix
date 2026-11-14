package com.nepdon.wire.features;

import android.content.SharedPreferences;

import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Refs;
import com.nepdon.wire.config.plugin.Setting;
import com.nepdon.wire.config.plugin.SpecBuilder;
import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * A packet effect — ghost, freeze, teleport — as a feature.
 *
 * <h3>Why one class covers all three</h3>
 *
 * <p>They already share an interface ({@code filter.PacketFilter}) and differ
 * only in their key, their name and their copy. Writing three near-identical
 * classes would be the kind of ceremony that makes people stop adding
 * features; this keeps the declaration to one constructor call in
 * {@link Registry}.</p>
 *
 * <h3>The switch does not start the effect</h3>
 *
 * <p>It records that the user wants it available. The effect itself is armed
 * by the floating button during a match, because these change what the server
 * sees and arming one from a settings screen you are not looking at is how
 * people get disconnected mid-fight. {@link #onEnable} therefore only makes
 * the button appear.</p>
 */
public final class FilterFeature implements Feature {

    private final String id;
    private final String title;
    private final String summary;
    private final Setting.Bool enabled;
    private final String caution;

    public FilterFeature(String id, String title, String summary, String caution,
                         String key, boolean def, Refs.BoolRef ref) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.caution = caution;
        this.enabled = new Setting.Bool(key, title, summary, title,
                Setting.APPLIES_LIVE, null, def, ref);
    }

    @Override public String id() { return id; }
    @Override public String title() { return title; }
    @Override public String summary() { return summary; }
    @Override public int schemaVersion() { return 2; }
    @Override public void migrate(int from, SharedPreferences.Editor ed) {
        if (from < 2) ed.putBoolean(enabled.key, false);
    }
    @Override public Setting.Bool enabledSetting() { return enabled; }

    @Override public void defineSettings(SpecBuilder b) {
        if (caution != null) {
            b.dependsOn(enabled.key);
            b.note(caution);
        }
    }

    @Override public String validate() { return null; }
    @Override public void onSettingsChanged() { }

    @Override public void onEnable() {
        Diag.hit(Slot.CFG_FEATURE_ENABLED);
    }

    @Override public void onDisable() {
        Diag.hit(Slot.CFG_FEATURE_DISABLED);
    }
}
