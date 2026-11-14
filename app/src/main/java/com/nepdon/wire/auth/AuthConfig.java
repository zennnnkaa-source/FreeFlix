package com.nepdon.wire.auth;

import com.nepdon.wire.BuildConfig;

/**
 * Login / server endpoint configuration. See docs/LOGIN_AND_SERVER.md.
 *
 * <p>Debug builds talk cleartext to a locally adb-reversed dev server
 * ({@code adb reverse tcp:8777 tcp:8777}); release builds talk TLS to the VPS
 * with the self-signed cert pinned by SPKI. The config blob is AES-GCM-encrypted
 * end to end regardless of transport, so the cleartext debug path never exposes
 * the Bucket-B knowledge.</p>
 */
public final class AuthConfig {

    private AuthConfig() {}

    /** True in release, false in debug — from app/build.gradle buildConfigField. */
    public static final boolean ENFORCE = BuildConfig.AUTH_ENFORCE;

    /** Base URL of the licensing server. */
    public static final String BASE_URL =
            BuildConfig.DEBUG ? "http://127.0.0.1:8777" : "https://66.154.107.27";

    /**
     * OkHttp SPKI pin for the VPS self-signed cert (release only). Empty in debug
     * (cleartext, no pinning). Fill from `docs/SERVER_OPS.md` once the cert exists:
     * {@code sha256/BASE64==}.
     */
    public static final String CERT_PIN = "sha256/U4qYO+IF0lgNBYxZvNsIv1nQcahtrvXkz01teUq3nLM=";

    /**
     * The server's Ed25519 public key (raw 32 bytes, hex) used to verify the
     * offline lease. MUST match the server's signing key. Filled per environment.
     */
    public static final String SERVER_ED25519_PUB_HEX = "347cea01803a3faf372540b50d0c263e3c53a8e1907ac8b0876ca2cfa8da353f";

    /** This client's protocol/app version, sent to the server's version gate. */
    public static final int APP_VERSION = BuildConfig.VERSION_CODE;

    /** Refresh the access token this many seconds before it expires. */
    public static final long REFRESH_SKEW_SEC = 60L;

    /**
     * Continuous-auth heartbeat master switch. While the tunnel is up and a
     * session is authorized, a background loop hits {@code /auth/heartbeat} to
     * re-arm the rolling decode profile. Off the packet thread and the game
     * socket; it only re-arms the leash and moves the {@code AUTH_HB_*} Diag
     * counters. Enforcement (whether decode REQUIRES the rolling arm) is separate
     * and stays behind {@code NativeBridge.DECODE_LEASH}. Default on so the beat
     * is observable in a live match before enforcement is switched on.
     */
    public static final boolean HEARTBEAT_ENABLED = true;

    /** Fallback beat interval (s) used until the server advertises its own. */
    public static final int HEARTBEAT_FALLBACK_SEC = 240;
}
