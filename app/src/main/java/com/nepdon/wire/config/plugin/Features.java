package com.nepdon.wire.config.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * The feature registry: every capability the app has, and the load/save/reset
 * machinery that works the same for all of them.
 *
 * <h3>Discovery is a hand-written array</h3>
 *
 * <p>Not {@code ServiceLoader}, not annotation processing, not a classpath
 * scan. All three are worse here for concrete, measured reasons:</p>
 *
 * <ul>
 *   <li><b>{@code ServiceLoader} on Android</b> reads {@code META-INF/services}
 *       at runtime and is a known startup cost — one well-documented case took
 *       a library's init from 250 ms to 500 ms — and the entries are fragile
 *       under shrinking, where {@code META-INF} can be stripped and class
 *       names mangled.</li>
 *   <li><b>Annotation processing</b> adds a new class of silent build failure
 *       to a build whose existing failure mode is already "it looked like it
 *       worked, check the APK timestamp".</li>
 *   <li><b>Reflection</b> costs on the order of hundreds of nanoseconds per
 *       read, breaks outright under {@code minifyEnabled true}, and makes the
 *       99 key strings invisible to grep — directly against this project's
 *       "grep every reader" rule, which exists because ignoring it caused four
 *       separate bugs in one session.</li>
 * </ul>
 *
 * <p>An array is compile-checked, costs nothing at startup, survives R8, and
 * you can read the whole system in one screen.</p>
 */
public final class Features {

    private Features() {}

    /** Where the schema version of each feature is remembered. */
    private static final String SCHEMA_KEY_PREFIX = "nv_schema_";

    private static volatile Feature[] ALL = new Feature[0];
    private static volatile Setting[] FLAT = new Setting[0];
    private static SharedPreferences prefs;

    /**
     * Installs the registry. Called once, early, from the application or the
     * first component that needs settings.
     *
     * <p>The array is passed in rather than built here so that {@code config/}
     * keeps depending on nothing: the feature implementations reference
     * {@code ui/}, {@code esp/} and {@code filter/}, and if this class named
     * them directly it would invert the dependency the 2026-09-15 reorg
     * removed.</p>
     */
    public static void install(Context ctx, Feature[] features) {
        if (features == null) features = new Feature[0];
        prefs = com.nepdon.wire.config.Prefs.get(ctx);
        ALL = features;

        SpecBuilder b = new SpecBuilder();
        for (int i = 0; i < features.length; i++) {
            Feature f = features[i];
            b.section(f.title());
            Setting.Bool en = f.enabledSetting();
            if (en != null) b.add(en);
            f.defineSettings(b);
        }
        FLAT = b.build();
        loadAll();
    }

    /** Every registered feature, in declaration order. */
    public static Feature[] all() { return ALL; }

    /** Every setting of every feature, flattened. */
    public static Setting[] settings() { return FLAT; }

    /** A feature by id, or null. */
    public static Feature byId(String id) {
        Feature[] a = ALL;
        for (int i = 0; i < a.length; i++) {
            if (a[i].id().equals(id)) return a[i];
        }
        return null;
    }

    /** The settings belonging to one feature. */
    public static Setting[] settingsOf(Feature f) {
        Setting[] a = FLAT;
        int n = 0;
        for (int i = 0; i < a.length; i++) if (f.title().equals(a[i].section)) n++;
        Setting[] out = new Setting[n];
        int j = 0;
        for (int i = 0; i < a.length; i++) if (f.title().equals(a[i].section)) out[j++] = a[i];
        return out;
    }

    // =====================================================================

    /**
     * Reads every setting out of storage into the field it binds, running any
     * schema migration first.
     */
    public static void loadAll() {
        SharedPreferences sp = prefs;
        if (sp == null) { Diag.hit(Slot.CFG_NO_PREFS); return; }

        Feature[] fs = ALL;
        SharedPreferences.Editor ed = null;
        for (int i = 0; i < fs.length; i++) {
            Feature f = fs[i];
            String vk = SCHEMA_KEY_PREFIX + f.id();
            int stored = sp.getInt(vk, f.schemaVersion());
            if (stored < f.schemaVersion()) {
                if (ed == null) ed = sp.edit();
                f.migrate(stored, ed);
                ed.putInt(vk, f.schemaVersion());
                Diag.hit(Slot.CFG_SCHEMA_MIGRATED);
            } else if (!sp.contains(vk)) {
                if (ed == null) ed = sp.edit();
                ed.putInt(vk, f.schemaVersion());
            }
        }
        if (ed != null) ed.apply();

        Setting[] ss = FLAT;
        for (int i = 0; i < ss.length; i++) {
            ss[i].load(sp);
        }
        Diag.hit(Slot.CFG_LOAD_ALL);

        for (int i = 0; i < fs.length; i++) {
            fs[i].onSettingsChanged();
        }
    }

    /**
     * Persists one setting and tells its feature.
     *
     * <p>Validation runs BEFORE the write, and a refusal leaves storage
     * untouched and returns the reason — a half-applied settings change is
     * the kind of plausible-looking corruption that costs a debugging
     * session.</p>
     *
     * @return null on success, else why it was refused
     */
    public static String commit(Feature f, Setting s) {
        SharedPreferences sp = prefs;
        if (sp == null) { Diag.hit(Slot.CFG_NO_PREFS); return "settings unavailable"; }

        String bad = (f == null) ? null : f.validate();
        if (bad != null) {
            s.load(sp);                       // roll the field back
            Diag.hit(Slot.CFG_VALIDATE_REFUSED);
            return bad;
        }
        SharedPreferences.Editor ed = sp.edit();
        s.save(ed);
        ed.apply();
        Diag.hit(Slot.CFG_SETTING_WRITTEN);
        if (f != null) f.onSettingsChanged();
        return null;
    }

    /**
     * Returns a setting to its coded default and forgets the user ever chose.
     *
     * <p>{@code remove}, not {@code put(default)} — see {@link Setting}. A
     * value written back would permanently pin this install to today's
     * default, so a later correction in code would never reach it.</p>
     */
    public static void reset(Feature f, Setting s) {
        SharedPreferences sp = prefs;
        if (sp == null) { Diag.hit(Slot.CFG_NO_PREFS); return; }
        SharedPreferences.Editor ed = sp.edit();
        s.reset(ed);
        ed.apply();
        Diag.hit(Slot.CFG_SETTING_RESET);
        if (f != null) f.onSettingsChanged();
    }

    /** Every setting of one feature, back to defaults. */
    public static void resetAll(Feature f) {
        SharedPreferences sp = prefs;
        if (sp == null) { Diag.hit(Slot.CFG_NO_PREFS); return; }
        Setting[] ss = settingsOf(f);
        SharedPreferences.Editor ed = sp.edit();
        for (int i = 0; i < ss.length; i++) ss[i].reset(ed);
        ed.apply();
        Diag.hit(Slot.CFG_SETTING_RESET, ss.length);
        f.onSettingsChanged();
    }

    /** Flips a feature's master switch and runs the matching lifecycle call. */
    public static void setEnabled(Feature f, boolean on) {
        Setting.Bool en = f.enabledSetting();
        if (en == null) { Diag.hit(Slot.CFG_NOT_TOGGLEABLE); return; }
        if (en.get() == on) return;
        en.set(on);
        commit(f, en);
        if (on) f.onEnable(); else f.onDisable();
        Diag.hit(on ? Slot.CFG_FEATURE_ENABLED : Slot.CFG_FEATURE_DISABLED);
    }

    /** True when the feature has no switch, or its switch is on. */
    public static boolean isEnabled(Feature f) {
        Setting.Bool en = f.enabledSetting();
        return en == null || en.get();
    }

    /** True once the user has explicitly set this setting. */
    public static boolean isOverridden(Setting s) {
        return s.isOverridden(prefs);
    }

    /** The backing store, for the few callers that still read it directly. */
    public static SharedPreferences prefs() { return prefs; }
}
