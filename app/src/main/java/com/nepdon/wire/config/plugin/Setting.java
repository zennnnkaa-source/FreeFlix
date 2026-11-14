package com.nepdon.wire.config.plugin;

import android.content.SharedPreferences;

/**
 * One declared setting: what it is called, what it defaults to, where its
 * value actually lives, and how a UI should draw it.
 *
 * <h3>The one declaration</h3>
 *
 * <p>A setting in this app used to be declared in <b>four</b> places at once —
 * a key string in {@code Prefs}, a mutable {@code public static} on whichever
 * class consumed it, a load line in {@code EspTuning}, and a hand-written row
 * in a panel or layout. Adding one meant touching all four, and 34 of the 99
 * keys had drifted: saved somewhere, loaded nowhere. Nothing fails at compile
 * time when that happens. See {@code docs/SETTINGS_INVENTORY.md}.</p>
 *
 * <p>A {@code Setting} collapses those four into one object. Load, save,
 * reset and the settings row are all derived from it.</p>
 *
 * <h3>It owns no value</h3>
 *
 * <p>Deliberately. The obvious design gives each setting a slot in a map, but
 * {@code EspRenderView.onDraw} runs at 120 Hz and {@code EspEngine.classify}
 * once per entity per packet — a map lookup or an autobox there is real cost
 * for nothing. So a {@code Setting} holds a <i>reference</i> to the plain
 * {@code static} that already exists, and is touched only on the two cold
 * paths that need it: load/save, and drawing a row. <b>The hot path keeps
 * reading the static directly and pays exactly what it pays today.</b></p>
 *
 * <h3>Presence, not value, means "the user chose this"</h3>
 *
 * <p>{@link #isOverridden} asks {@code prefs.contains(key)}. That distinction
 * matters: a flat preferences file cannot otherwise tell "the user chose
 * false" from "the default was false and got written out", so correcting a
 * bad default in code would never reach anyone who had ever opened the panel.
 * Reset is {@code remove(key)}, not {@code put(default)}.</p>
 */
public abstract class Setting {

    /** When a changed value takes effect. */
    public static final int APPLIES_LIVE = 0;
    /** Needs the engine restarted; the UI says so rather than lying. */
    public static final int APPLIES_RESTART = 1;

    /**
     * The persisted key. <b>Always an existing {@code Prefs.KEY_*} constant,
     * passed verbatim.</b> Never derived from a field or class name: R8
     * renames those, and a rename silently resets a user's tuning to default
     * rather than failing to build.
     */
    public final String key;
    /** Row label. */
    public final String title;
    /** One line under the label, or null. */
    public final String summary;
    /** Section heading this belongs under. */
    public final String section;
    /** {@link #APPLIES_LIVE} or {@link #APPLIES_RESTART}. */
    public final int applies;
    /**
     * Key of a boolean setting that must be true for this one to matter, or
     * null. Kept as a single key on purpose — see the class note on
     * {@code Feature.validate}: declarative schemas cannot express real
     * cross-field rules, and every system that pretends otherwise ends up
     * with fields that stay visible and editable when they do nothing.
     */
    public final String dependsOn;

    Setting(String key, String title, String summary, String section,
            int applies, String dependsOn) {
        this.key = key;
        this.title = title;
        this.summary = summary;
        this.section = section;
        this.applies = applies;
        this.dependsOn = dependsOn;
    }

    /** Pushes the stored value (or the default) into the field it binds. */
    public abstract void load(SharedPreferences sp);

    /** Writes the field's current value through to storage. */
    public abstract void save(SharedPreferences.Editor ed);

    /** Restores the coded default and forgets that the user ever chose. */
    public abstract void reset(SharedPreferences.Editor ed);

    /** The current value, formatted for a row. */
    public abstract String display();

    /** True once the user has set this explicitly. See the class note. */
    public final boolean isOverridden(SharedPreferences sp) {
        return sp != null && sp.contains(key);
    }

    // =====================================================================

    /** A switch. */
    public static final class Bool extends Setting {
        public final boolean def;
        public final Refs.BoolRef ref;

        public Bool(String key, String title, String summary, String section,
                    int applies, String dependsOn, boolean def, Refs.BoolRef ref) {
            super(key, title, summary, section, applies, dependsOn);
            this.def = def;
            this.ref = ref;
        }

        @Override public void load(SharedPreferences sp) {
            ref.set(sp.getBoolean(key, def));
        }
        @Override public void save(SharedPreferences.Editor ed) {
            ed.putBoolean(key, ref.get());
        }
        @Override public void reset(SharedPreferences.Editor ed) {
            ref.set(def);
            ed.remove(key);
        }
        @Override public String display() { return ref.get() ? "ON" : "OFF"; }

        public boolean get() { return ref.get(); }
        public void set(boolean v) { ref.set(v); }
    }

    /** A bounded float, stepped. */
    public static final class Num extends Setting {
        public final float def, min, max, step, bigStep;
        public final String unit;
        public final Refs.FloatRef ref;

        public Num(String key, String title, String summary, String section,
                   int applies, String dependsOn,
                   float def, float min, float max, float step, float bigStep,
                   String unit, Refs.FloatRef ref) {
            super(key, title, summary, section, applies, dependsOn);
            this.def = def; this.min = min; this.max = max;
            this.step = step; this.bigStep = bigStep;
            this.unit = unit; this.ref = ref;
        }

        /**
         * Clamps on the way in. A stored value outside the declared range is
         * corrected rather than refused: the range is the current opinion,
         * the stored number is the user's old one, and silently dropping
         * their setting is worse than pulling it to the nearest legal value.
         */
        @Override public void load(SharedPreferences sp) {
            ref.set(clamp(sp.getFloat(key, def)));
        }
        @Override public void save(SharedPreferences.Editor ed) {
            ed.putFloat(key, clamp(ref.get()));
        }
        @Override public void reset(SharedPreferences.Editor ed) {
            ref.set(def);
            ed.remove(key);
        }
        @Override public String display() {
            return unit == null ? fmt(ref.get()) : fmt(ref.get()) + unit;
        }

        public float clamp(float v) {
            if (v < min) return min;
            if (v > max) return max;
            return v;
        }
        public void nudge(float by) { ref.set(clamp(ref.get() + by)); }
        public float get() { return ref.get(); }

        private static String fmt(float v) {
            if (v == (long) v) return Long.toString((long) v);
            return String.format(java.util.Locale.US, "%.2f", v);
        }
    }

    /** A bounded integer, stepped. */
    public static final class Int extends Setting {
        public final int def, min, max, step;
        public final String unit;
        public final Refs.IntRef ref;

        public Int(String key, String title, String summary, String section,
                   int applies, String dependsOn,
                   int def, int min, int max, int step, String unit, Refs.IntRef ref) {
            super(key, title, summary, section, applies, dependsOn);
            this.def = def; this.min = min; this.max = max; this.step = step;
            this.unit = unit; this.ref = ref;
        }

        @Override public void load(SharedPreferences sp) {
            ref.set(clamp(sp.getInt(key, def)));
        }
        @Override public void save(SharedPreferences.Editor ed) {
            ed.putInt(key, clamp(ref.get()));
        }
        @Override public void reset(SharedPreferences.Editor ed) {
            ref.set(def);
            ed.remove(key);
        }
        @Override public String display() {
            return unit == null ? Integer.toString(ref.get()) : ref.get() + unit;
        }

        public int clamp(int v) { return v < min ? min : (v > max ? max : v); }
        public void nudge(int by) { ref.set(clamp(ref.get() + by)); }
        public int get() { return ref.get(); }
    }

    /** One of a fixed set, stored as its index. */
    public static final class Choice extends Setting {
        public final int def;
        public final String[] labels;
        public final Refs.IntRef ref;

        public Choice(String key, String title, String summary, String section,
                      int applies, String dependsOn,
                      int def, String[] labels, Refs.IntRef ref) {
            super(key, title, summary, section, applies, dependsOn);
            this.def = def; this.labels = labels; this.ref = ref;
        }

        @Override public void load(SharedPreferences sp) {
            ref.set(clamp(sp.getInt(key, def)));
        }
        @Override public void save(SharedPreferences.Editor ed) {
            ed.putInt(key, clamp(ref.get()));
        }
        @Override public void reset(SharedPreferences.Editor ed) {
            ref.set(def);
            ed.remove(key);
        }
        @Override public String display() {
            int i = clamp(ref.get());
            return labels[i];
        }

        public int clamp(int v) { return v < 0 ? 0 : (v >= labels.length ? labels.length - 1 : v); }
        public void cycle() { ref.set((clamp(ref.get()) + 1) % labels.length); }
        public int get() { return ref.get(); }
    }
}
