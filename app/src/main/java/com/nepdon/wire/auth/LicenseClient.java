package com.nepdon.wire.auth;

import android.content.Context;
import android.util.Base64;

import com.nepdon.wire.net.Crypto;
import com.nepdon.wire.net.Http;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Talks to the licensing server: activate, refresh, version, and the config
 * envelope open. Wire contract mirrors server/app (docs/LOGIN_AND_SERVER.md).
 * All methods are blocking — call them off the main thread.
 */
public final class LicenseClient {

    private LicenseClient() {}

    /** A successful auth result. */
    public static final class Bundle {
        public final String accessToken, refreshToken, lease, tier;
        public final int configVersion;
        Bundle(String a, String r, String l, String t, int cv) {
            accessToken = a; refreshToken = r; lease = l; tier = t; configVersion = cv;
        }
    }

    /** Auth failure carrying a short machine code and a user-facing message. */
    public static final class AuthException extends Exception {
        public final String code;
        public AuthException(String code, String message) { super(message); this.code = code; }
    }

    /**
     * Fetch a fresh challenge nonce bound to this device key and sign it with the
     * hardware Keystore key. Proof-of-possession is required on BOTH activate and
     * refresh, so a token exfiltrated without the private key cannot be replayed
     * from another host. Returns {nonce, signatureB64}.
     */
    private static String[] signedChallenge(String pub) throws Exception {
        Http.Resp cr = Http.postJson("/auth/challenge",
                new JSONObject().put("device_pubkey", pub).toString(), null);
        if (!cr.ok()) throw mapError(cr);
        String nonce = new JSONObject(cr.body).getString("nonce");
        byte[] sig = DeviceIdentity.signChallenge(Crypto.unhex(nonce));
        if (sig == null) {
            throw new AuthException("device",
                    "This device can't produce a secure signature (needs Android 6+).");
        }
        return new String[]{nonce, Crypto.b64(sig)};
    }

    // ---- activate (with proof-of-possession) ----
    public static Bundle activate(Context ctx, String licenseKey) throws AuthException {
        try {
            String pub = DeviceIdentity.publicKeyB64(ctx);
            String[] ch = signedChallenge(pub);
            JSONObject fp = new JSONObject()
                    .put("ssaid", DeviceIdentity.ssaid(ctx))
                    .put("device_pubkey", pub);
            JSONObject body = new JSONObject()
                    .put("license_key", licenseKey.trim())
                    .put("fingerprint_inputs", fp)
                    .put("app_version", AuthConfig.APP_VERSION)
                    .put("challenge_nonce", ch[0])
                    .put("signature", ch[1]);
            Http.Resp r = Http.postJson("/auth/activate", body.toString(), null);
            if (!r.ok()) throw mapError(r);
            return parseBundle(new JSONObject(r.body));
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("network", "Can't reach the server. Check your connection.");
        }
    }

    // ---- refresh (also proof-of-possession, per the 2026-09-17 audit) ----
    public static Bundle refresh(Context ctx, String refreshToken) throws AuthException {
        try {
            String pub = DeviceIdentity.publicKeyB64(ctx);
            // Sign a fresh challenge with the hardware key: refreshing now requires
            // the private key on the bound device, so a lifted refresh token alone
            // (root dump → curl) cannot mint access tokens off-device.
            String[] ch = signedChallenge(pub);
            // Re-prove device binding: the server recomputes the fingerprint from
            // these and checks it against the token's bound device.
            JSONObject fp = new JSONObject()
                    .put("ssaid", DeviceIdentity.ssaid(ctx))
                    .put("device_pubkey", pub);
            JSONObject body = new JSONObject()
                    .put("refresh_token", refreshToken)
                    .put("fingerprint_inputs", fp)
                    .put("challenge_nonce", ch[0])
                    .put("signature", ch[1]);
            Http.Resp r = Http.postJson("/auth/refresh", body.toString(), null);
            if (!r.ok()) throw mapError(r);
            return parseBundle(new JSONObject(r.body));
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("network", "Can't reach the server.");
        }
    }

    // ---- config envelope open → ConfigStore ----
    public static void fetchConfig(String accessToken) throws AuthException {
        byte[] session = null, dataKey = null, clientPriv = null, shared = null;
        try {
            clientPriv = Crypto.x25519Private();
            byte[] clientPub = Crypto.x25519Public(clientPriv);
            byte[] nonce = Crypto.randomBytes(16);
            JSONObject body = new JSONObject()
                    .put("client_x25519_pub", Crypto.hex(clientPub))
                    .put("client_nonce", Crypto.hex(nonce));
            Http.Resp r = Http.postJson("/config", body.toString(), accessToken);
            if (!r.ok()) throw mapError(r);
            JSONObject resp = new JSONObject(r.body);

            String jti = jwtJti(accessToken);
            int cfgv = resp.getInt("config_version");
            long expiry = resp.getLong("expiry");
            byte[] aad = (jti + "|" + cfgv + "|" + expiry).getBytes(StandardCharsets.UTF_8);

            shared = Crypto.x25519Shared(clientPriv,
                    Crypto.unhex(resp.getString("server_x25519_pub")));
            session = Crypto.hkdf(shared, nonce, Crypto.CONFIG_INFO, 32);
            dataKey = Crypto.aesGcmOpen(session,
                    Crypto.unhex(resp.getString("wrap_nonce")),
                    Crypto.unhex(resp.getString("wrapped_data_key")), aad);
            byte[] blob = Crypto.aesGcmOpen(dataKey,
                    Crypto.unhex(resp.getString("ct_nonce")),
                    Crypto.unhex(resp.getString("ciphertext")), aad);
            ConfigStore.load(blob);   // wipes blob internally
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("config", "Failed to load config from server.");
        } finally {
            Crypto.wipe(session);
            Crypto.wipe(dataKey);
            Crypto.wipe(clientPriv);
            Crypto.wipe(shared);
        }
    }

    // ---- continuous-auth heartbeat → ConfigStore.armRolling ----
    /**
     * One heartbeat beat: POST /auth/heartbeat, open the sealed ACK (same envelope
     * as fetchConfig), and re-arm the rolling decode profile. The AAD
     * {@code jti|epoch|valid_until} uses the plaintext, GCM-authenticated epoch +
     * valid_until response fields (exactly as fetchConfig uses expiry|config_version).
     * Returns the advertised next interval (seconds) on success; throws
     * AuthException — {@code unauthorized} is terminal (revoked/deactivated),
     * {@code network}/{@code server} is a benign miss the caller may retry.
     */
    public static int heartbeat(String accessToken) throws AuthException {
        byte[] session = null, dataKey = null, clientPriv = null, shared = null, blob = null;
        try {
            clientPriv = Crypto.x25519Private();
            byte[] clientPub = Crypto.x25519Public(clientPriv);
            byte[] nonce = Crypto.randomBytes(16);
            JSONObject body = new JSONObject()
                    .put("client_x25519_pub", Crypto.hex(clientPub))
                    .put("client_nonce", Crypto.hex(nonce))
                    .put("epoch_have", ConfigStore.rollingEpoch());
            Http.Resp r = Http.postJson("/auth/heartbeat", body.toString(), accessToken);
            if (!r.ok()) throw mapError(r);
            JSONObject resp = new JSONObject(r.body);

            String jti = jwtJti(accessToken);
            long epoch = resp.getLong("epoch");
            long validUntil = resp.getLong("valid_until");
            int interval = resp.optInt("interval", 240);
            byte[] aad = (jti + "|" + epoch + "|" + validUntil).getBytes(StandardCharsets.UTF_8);

            shared = Crypto.x25519Shared(clientPriv,
                    Crypto.unhex(resp.getString("server_x25519_pub")));
            session = Crypto.hkdf(shared, nonce, Crypto.CONFIG_INFO, 32);
            dataKey = Crypto.aesGcmOpen(session,
                    Crypto.unhex(resp.getString("wrap_nonce")),
                    Crypto.unhex(resp.getString("wrapped_data_key")), aad);
            blob = Crypto.aesGcmOpen(dataKey,
                    Crypto.unhex(resp.getString("ct_nonce")),
                    Crypto.unhex(resp.getString("ciphertext")), aad);
            JSONObject prof = new JSONObject(new String(blob, StandardCharsets.UTF_8))
                    .getJSONObject("decode_profile");
            long serverNow = prof.getLong("server_now");
            boolean armed = ConfigStore.armRolling(validUntil, serverNow, epoch);
            com.nepdon.wire.diag.Diag.hit(armed
                    ? com.nepdon.wire.diag.Slot.AUTH_HB_OK
                    : com.nepdon.wire.diag.Slot.AUTH_HB_STALE_ACK);
            return interval;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("network", "Heartbeat failed.");
        } finally {
            Crypto.wipe(session);
            Crypto.wipe(dataKey);
            Crypto.wipe(clientPriv);
            Crypto.wipe(shared);
            Crypto.wipe(blob);
        }
    }

    // ---- version gate ----
    public static int[] version() throws AuthException {
        try {
            Http.Resp r = Http.get("/version");
            if (!r.ok()) throw mapError(r);
            JSONObject o = new JSONObject(r.body);
            return new int[]{o.getInt("min_supported"), o.getInt("latest")};
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("network", "Can't reach the server.");
        }
    }

    /**
     * Verify the offline lease signature and return its payload, or null.
     *
     * <p><b>Fail-closed:</b> if no server public key is configured we CANNOT
     * verify the signature, so we reject the lease rather than accept a
     * potentially forged one. (An enforcing release build must set
     * {@code SERVER_ED25519_PUB_HEX}; the build guard in build.gradle enforces
     * that.) A non-enforcing debug build simply has no offline path.</p>
     */
    public static JSONObject verifyLease(Context ctx, String lease) {
        try {
            if (AuthConfig.SERVER_ED25519_PUB_HEX.isEmpty()) return null;
            String[] parts = lease.split("\\.", 2);
            if (parts.length != 2) return null;
            byte[] raw = Base64.decode(parts[0], Base64.DEFAULT);
            byte[] sig = Crypto.unhex(parts[1]);
            byte[] pub = Crypto.unhex(AuthConfig.SERVER_ED25519_PUB_HEX);
            if (!Crypto.ed25519Verify(pub, sig, raw)) return null;
            JSONObject p = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            // Device-binding: the lease carries dpk = sha256(device_pubkey). A lease
            // copied to another device has a different pubkey → mismatch → reject,
            // so the offline grace window can't be shared across devices. Fail
            // closed if the field is absent (a pre-binding lease) or differs.
            String dpk = p.optString("dpk", "");
            String mine = Crypto.sha256Hex(DeviceIdentity.publicKeyB64(ctx));
            if (dpk.isEmpty() || !dpk.equals(mine)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- helpers ----
    private static Bundle parseBundle(JSONObject o) throws Exception {
        return new Bundle(
                o.getString("access_token"),
                o.getString("refresh_token"),
                o.getString("lease"),
                o.optString("tier", "standard"),
                o.optInt("config_version", 0));
    }

    private static String jwtJti(String jwt) {
        try {
            String mid = jwt.split("\\.")[1];
            byte[] p = Base64.decode(mid, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(p, StandardCharsets.UTF_8)).getString("jti");
        } catch (Exception e) {
            return "";
        }
    }

    private static AuthException mapError(Http.Resp r) {
        String detail = "";
        try { detail = new JSONObject(r.body).optString("detail", ""); } catch (Exception ignored) {}
        switch (r.code) {
            case 403: return new AuthException("invalid", "That license key is invalid or revoked.");
            case 409: return new AuthException("device_limit",
                    "This license is already active on the maximum number of devices.");
            case 426: return new AuthException("update", "This build is out of date. Update to continue.");
            case 429: return new AuthException("rate", "Too many attempts. Wait a minute and retry.");
            case 401: return new AuthException("unauthorized", "Session expired. Sign in again.");
            default:  return new AuthException("server",
                    "Server error (" + r.code + (detail.isEmpty() ? "" : ": " + detail) + ").");
        }
    }
}
