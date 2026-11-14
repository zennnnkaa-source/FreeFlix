package com.nepdon.wire.config.plugin;

/**
 * One user-facing capability, declared rather than hard-wired: ESP, radar, aim
 * assist, ghost, freeze, teleport, mark, capture, diagnostics.
 *
 * <h3>What a feature owes the system</h3>
 *
 * <p>An id, a master switch, a settings declaration, and the two lifecycle
 * calls. Everything else — loading, saving, resetting, the settings screen,
 * the enable toggle in the app, the row in the in-game panel — is derived from
 * those and written once, not per feature.</p>
 *
 * <h3>Lifecycle is deliberately two calls</h3>
 *
 * <p>Full plugin runtimes model this as a state machine with pending, loading,
 * active, failed and disposing states, because they load untrusted code out of
 * tree and hot-swap it. This app ships one APK with features its own author
 * wrote. Every state beyond on and off would be ceremony — and the runtimes
 * that have it warn that a stuck plugin "prints nothing, no crash, no partial
 * run, exits 0 silently", which is precisely the silent-skip failure this
 * codebase has been bitten by five times. So: two calls, and every refusal
 * inside them is a {@code Diag.hit}.</p>
 *
 * <h3>Ordering</h3>
 *
 * <p>There is none, on purpose. Features do not declare dependencies on each
 * other; the registry is a hand-written array and its order is the order. Soft
 * dependency graphs are where plugin systems reliably go wrong — the canonical
 * implementation resolves circular soft dependencies "arbitrarily". If two
 * features ever genuinely need sequencing, that is a bug in their design, not
 * a missing feature here.</p>
 */
public interface Feature {

    /** Stable identity. Used in diagnostics and as the settings heading. */
    String id();

    /** What the user sees. */
    String title();

    /** One line explaining what it does, for the card. */
    String summary();

    /**
     * Schema version. <b>Bump this when the MEANING of a stored value changes
     * under an unchanged key</b> — not when you add a setting.
     *
     * <p>This has already bitten the project once: {@code KEY_ESP_FOV_X} was
     * reinterpreted and the fix was a hand-rolled {@code KEY_ESP_CAM_SCHEMA}
     * marker. This generalises that. A stored version lower than this one
     * means the values under this feature's keys were written against
     * different semantics, and {@link #migrate} gets to fix or drop them.</p>
     */
    int schemaVersion();

    /**
     * Called once when the stored schema version is older than
     * {@link #schemaVersion}. Reset what no longer means what it did.
     * The default for most features is to do nothing.
     */
    void migrate(int from, android.content.SharedPreferences.Editor ed);

    /**
     * The master on/off switch, or null for a feature that cannot be turned
     * off. Capture returns null: a control for it could only ever disable the
     * recording every diagnosis depends on.
     */
    Setting.Bool enabledSetting();

    /** Declares every setting, once, at registry build. */
    void defineSettings(SpecBuilder b);

    /**
     * Cross-field checks, kept OUT of the declaration on purpose.
     *
     * <p>The declaration is also what the settings screen renders, and a
     * schema rich enough to express real rules is not a schema a UI can draw
     * — the systems that tried this ended up with fields that stay visible
     * and editable while doing nothing. So: one {@code dependsOn} in the
     * schema for the common gate, and anything harder stated here in code.</p>
     *
     * @return null when fine, else a message to show and refuse the write
     */
    String validate();

    /** Values just changed. Called on the UI thread, never on a packet path. */
    void onSettingsChanged();

    /** The master switch went on. */
    void onEnable();

    /** The master switch went off. */
    void onDisable();
}
