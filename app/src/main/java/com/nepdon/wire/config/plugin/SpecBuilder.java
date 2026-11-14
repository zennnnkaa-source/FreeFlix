package com.nepdon.wire.config.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder a {@link Feature} uses to declare its settings.
 *
 * <p>Shape borrowed from Minecraft Forge's {@code ForgeConfigSpec}, which is
 * the closest thing in the Java world to what this needs: a builder with
 * {@code push}/{@code pop} sections and {@code defineInRange}, producing typed
 * handles the owner keeps in a field. No annotations, no reflection, no
 * classpath scan — all three of which are actively harmful here (R8 renames
 * fields under {@code minifyEnabled}, and reflective binding turns 99
 * greppable key constants into invisible metadata, which collides head-on
 * with this project's "grep every reader" rule).</p>
 *
 * <p>Usage:</p>
 * <pre>
 * b.section("Camera");
 * b.num(Prefs.KEY_ESP_FOV_X, "Field of view", 90f, 40f, 140f, 0.5f, 5f, "°", ref);
 * b.bool(Prefs.KEY_ESP_EXTRAPOLATE, "Prediction", false, ref);
 * b.dependsOn(Prefs.KEY_ESP_EXTRAPOLATE);
 * b.num(Prefs.KEY_ESP_LEAD_MS, "Lead", 0f, 0f, 400f, 5f, 25f, "ms", ref);
 * </pre>
 *
 * <p>{@code section}, {@code dependsOn} and {@code restart} are sticky: they
 * apply to every setting declared after them until changed. That keeps a
 * declaration block readable instead of repeating the same three arguments on
 * every line.</p>
 */
public final class SpecBuilder {

    private final List<Setting> out = new ArrayList<Setting>();
    private String section = "";
    private String dependsOn = null;
    private int applies = Setting.APPLIES_LIVE;
    private String summary = null;

    /** Everything declared from here on sits under this heading. */
    public SpecBuilder section(String name) {
        this.section = name;
        this.dependsOn = null;     // a new section never inherits a gate
        return this;
    }

    /** Gates every following setting on a boolean setting's key. */
    public SpecBuilder dependsOn(String boolKey) {
        this.dependsOn = boolKey;
        return this;
    }

    /** Clears a sticky {@link #dependsOn}. */
    public SpecBuilder independent() {
        this.dependsOn = null;
        return this;
    }

    /** Marks following settings as needing an engine restart. */
    public SpecBuilder restart() {
        this.applies = Setting.APPLIES_RESTART;
        return this;
    }

    /** Back to live-applying. */
    public SpecBuilder live() {
        this.applies = Setting.APPLIES_LIVE;
        return this;
    }

    /** One-shot: attaches a sub-label to the NEXT setting only. */
    public SpecBuilder note(String s) {
        this.summary = s;
        return this;
    }

    private String takeSummary() {
        String s = summary;
        summary = null;
        return s;
    }

    /**
     * Adds an already-built setting.
     *
     * <p>Used for a feature's master switch, which the feature constructs
     * itself so it can hold a typed reference to it, and which the registry
     * then folds into the same flat list as everything else — so enabling a
     * feature loads, saves, resets and renders through exactly one path.</p>
     */
    public SpecBuilder add(Setting s) {
        if (s != null) out.add(s);
        return this;
    }

    public SpecBuilder bool(String key, String title, boolean def, Refs.BoolRef ref) {
        out.add(new Setting.Bool(key, title, takeSummary(), section, applies, dependsOn, def, ref));
        return this;
    }

    public SpecBuilder num(String key, String title, float def, float min, float max,
                           float step, float bigStep, String unit, Refs.FloatRef ref) {
        out.add(new Setting.Num(key, title, takeSummary(), section, applies, dependsOn,
                def, min, max, step, bigStep, unit, ref));
        return this;
    }

    public SpecBuilder integer(String key, String title, int def, int min, int max,
                               int step, String unit, Refs.IntRef ref) {
        out.add(new Setting.Int(key, title, takeSummary(), section, applies, dependsOn,
                def, min, max, step, unit, ref));
        return this;
    }

    public SpecBuilder choice(String key, String title, int def, String[] labels,
                              Refs.IntRef ref) {
        out.add(new Setting.Choice(key, title, takeSummary(), section, applies, dependsOn,
                def, labels, ref));
        return this;
    }

    /**
     * Freezes the declaration.
     *
     * <p>Returns an array rather than a {@code List} because the packet and
     * render paths iterate settings with an indexed {@code for} — an
     * enhanced-for over a {@code List} allocates an iterator, and
     * {@code onDraw} allocates nothing.</p>
     */
    public Setting[] build() {
        return out.toArray(new Setting[out.size()]);
    }
}
