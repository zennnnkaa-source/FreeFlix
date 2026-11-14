package com.nepdon.wire.net;

import java.nio.ByteBuffer;

/**
 * Java side of the Rust packet core (libnuvola_native.so). The native methods
 * are bound by {@code RegisterNatives} inside the lib's {@code JNI_OnLoad}, so
 * this class must NOT call {@code System.loadLibrary} itself — {@link NativeLoader}
 * decides which copy of the lib to load (a verified downloaded one, else the
 * bundled fallback) and loads it, which triggers the registration.
 *
 * <p>Every method is a no-op contract until a lib is loaded; call
 * {@link NativeLoader#isReady()} first.</p>
 */
public final class NativeBridge {

    private NativeBridge() {}

    /** The Rust↔Java contract version this build speaks (manifest.min_loader gate). */
    public static final int LOADER_ABI = 1;

    /** Version of the .so bundled inside this APK (the always-available fallback). */
    public static final int BUNDLED_VERSION = 1;

    /**
     * Value {@link #nnSelftest()} must return on a healthy lib whose ABI matches
     * this build: high 32 bits = native ABI/logic version, low 16 = "NV". Kept in
     * lockstep with {@code NATIVE_ABI_VERSION} in the Rust crate.
     */
    public static final long EXPECTED_SELFTEST = ((long) LOADER_ABI << 32) | 0x4E56L;

    /** Runs the core known-answer checks in native; returns {@link #EXPECTED_SELFTEST} or 0. */
    public static native long nnSelftest();

    /**
     * Recompute the CRC-7 checksum (packet byte 1) over {@code buf[2..len]} in
     * place, returning it (0..127) or -1 on a bad buffer. {@code buf} must be a
     * DIRECT ByteBuffer (allocateDirect), reused across packets to stay
     * allocation-free.
     */
    public static native int crc7Seal(ByteBuffer directBuf, int len);

    // ---- Phase 4 (STAGED, hard-off) ------------------------------------------
    // The server-gated native decode path. Bound in JNI_OnLoad's NON-FATAL second
    // registration, so if this contract is imperfect it cannot break the core
    // methods above. NOT on the live path yet: gated by NATIVE_DECODE below and
    // pending on-device verification against the live game (never wired blind).
    // See docs/SERVER_GATED_DECODE_DESIGN.md (step 4).

    /**
     * Master switch for the native decode routing. Stays {@code false} until the
     * path is verified on-device; flipping it on is the phase-4b step. While
     * false, {@link #chainDecode} is never called and EspEngine keeps its
     * existing Java decode.
     */
    public static final boolean NATIVE_DECODE = false;

    /**
     * Shadow-compare switch (phase-4b validation, distinct from {@link
     * #NATIVE_DECODE}). While true, {@link com.nepdon.wire.esp.EspEngine} runs
     * {@link #chainDecode} in PARALLEL with its Java parse on every structurally
     * parsed chain and compares the two record sets field-for-field via the
     * {@code ESP_CHAIN_NATIVE_*} Diag slots — Java stays authoritative, so this
     * is risk-free to enable in a live match to prove native parity before ever
     * flipping {@link #NATIVE_DECODE}. Being a compile-time constant, R8 strips
     * the whole shadow path from a release build while this is {@code false}.
     */
    public static final boolean NATIVE_SHADOW = false;

    /**
     * The server-gated decode leash (anti-crack). While true, the live Java
     * decode paths in {@link com.nepdon.wire.esp.EspEngine} fail-closed unless a
     * live, unexpired server decode profile is armed in {@link
     * com.nepdon.wire.auth.ConfigStore} — so a login-bypassed / repackaged client
     * (which never reaches the authenticated {@code /config} and so never gets a
     * profile) decodes nothing and renders no ESP. Rendering-neutral: the
     * renderer simply has no entities, it does not crash.
     *
     * <p>Default {@code false} pending an in-game confirmation that a legitimate
     * licensed session keeps the profile armed for a FULL match (the profile's
     * {@code valid_until} is config-expiry + 300 s, so the open question is
     * whether the session refreshes {@code /config} often enough — see
     * {@link com.nepdon.wire.diag.Slot#ESP_LEASH_CLOSED}). Flipping this true is
     * the release step that makes ESP genuinely server-dependent. As a
     * compile-time constant, R8 strips the check from a release build while
     * false. See docs/SERVER_GATED_DECODE_DESIGN.md.</p>
     */
    public static final boolean DECODE_LEASH = false;

    /**
     * Decode a raw Cmd-2012 chain from {@code pkt[off..off+len]} under the
     * server-issued decode profile, serializing the records into {@code out}
     * (a DIRECT ByteBuffer): a {@code u16} count then {@code count} fixed
     * {@value #CHAIN_RECORD_WIRE}-byte little-endian records. Returns the record
     * count (>= 0), or -1 on a bad buffer / not-a-chain / no live profile.
     *
     * <p>The profile fields come from {@link com.nepdon.wire.auth.ConfigStore}
     * (RAM-only, server-delivered); with no live profile the native side
     * fail-closes and returns -1, so an unlicensed session decodes nothing.</p>
     */
    public static native int chainDecode(ByteBuffer pkt, int off, int len, int ownId,
                                         int maxLen, int maxRecords, int minRecordBytes,
                                         long minRecordX, long maxEntityId,
                                         long validUntil, long now, ByteBuffer out);

    /** Bytes per serialized record from {@link #chainDecode} (Rust RECORD_WIRE). */
    public static final int CHAIN_RECORD_WIRE = 64;
}
