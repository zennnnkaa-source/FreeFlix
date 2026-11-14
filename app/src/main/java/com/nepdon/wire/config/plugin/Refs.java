package com.nepdon.wire.config.plugin;

import com.nepdon.wire.config.EspConfig;

/**
 * Accessors that bind a declared {@link Setting} to the field that actually
 * holds its value.
 *
 * <h3>Why a binding rather than storage</h3>
 *
 * <p>The obvious plugin design gives each setting a slot in a map and has
 * consumers read it from there. <b>That is exactly wrong for this codebase.</b>
 * {@code EspRenderView.onDraw} runs at 120 Hz over live gameplay and
 * {@code EspEngine.classify} runs once per entity per packet; a map lookup,
 * an autobox or an interface dispatch on those paths is real cost for no
 * benefit.</p>
 *
 * <p>So a {@code Setting} owns no value at all. It owns a <i>reference</i> to
 * the plain {@code static} that already exists, and is used only by the two
 * cold paths that need it: load/save, and drawing the settings row. The hot
 * path keeps reading {@code EspConfig.SHOW_BOXES} directly, exactly as it
 * does today, and pays nothing.</p>
 *
 * <p>Anonymous classes rather than lambdas: Java 8 source level, and it
 * matches the {@code BoolKnob}/{@code FloatKnob} idiom already in
 * {@code SetupPanel}.</p>
 */
public final class Refs {

    private Refs() {}

    /** Reads and writes a boolean setting's real home. */
    public interface BoolRef {
        boolean get();
        void set(boolean v);
    }

    /** Reads and writes a float setting's real home. */
    public interface FloatRef {
        float get();
        void set(float v);
    }

    /** Reads and writes an int setting's real home. */
    public interface IntRef {
        int get();
        void set(int v);
    }

    /** Reads and writes a string setting's real home. */
    public interface StringRef {
        String get();
        void set(String v);
    }
}
