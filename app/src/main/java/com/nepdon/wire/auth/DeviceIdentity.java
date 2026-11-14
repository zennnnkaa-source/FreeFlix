package com.nepdon.wire.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.nepdon.wire.config.Prefs;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * Stable per-device identity for license binding. Provides:
 * <ul>
 *   <li>a stable EC public key (base64 X.509) — hardware-backed in the Android
 *       Keystore on API 23+, software-generated on 21–22;</li>
 *   <li>the SSAID ({@code Settings.Secure.ANDROID_ID}).</li>
 * </ul>
 * The server computes {@code fingerprint = HMAC(server_salt, SSAID)} (SSAID
 * only, since 2026-09-18 — the pubkey is the proof-of-possession credential,
 * verified per refresh and re-enrolled per activate, not part of the seat id).
 * The public key is still persisted so it is stable across launches; it changes
 * only on app-data wipe / factory reset.
 */
public final class DeviceIdentity {

    private static final String KS_ALIAS = "nepdon_wire_device";

    private DeviceIdentity() {}

    /** Returns the stable device public key (base64 X.509 DER), creating it once. */
    public static String publicKeyB64(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);
        String cached = p.getString(Prefs.KEY_DEVICE_PUBKEY, null);
        if (cached != null && !cached.isEmpty()) return cached;

        byte[] pub;
        try {
            pub = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? keystorePublic() : softwarePublic();
        } catch (Exception e) {
            try {
                pub = softwarePublic();
            } catch (Exception e2) {
                // Last resort: a random stable id so binding still works.
                pub = com.nepdon.wire.net.Crypto.randomBytes(33);
            }
        }
        String b64 = Base64.encodeToString(pub, Base64.NO_WRAP);
        p.edit().putString(Prefs.KEY_DEVICE_PUBKEY, b64).commit();
        return b64;
    }

    /**
     * Sign a proof-of-possession challenge with the hardware Keystore key
     * (ECDSA-P256/SHA256), proving this device holds the private key behind the
     * public key the server bound the seat to (H3). Returns null on API &lt; 23
     * (no hardware key was persisted there) or on any Keystore error.
     */
    public static byte[] signChallenge(byte[] nonce) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(KS_ALIAS, null);
            if (!(e instanceof KeyStore.PrivateKeyEntry)) return null;
            PrivateKey pk = ((KeyStore.PrivateKeyEntry) e).getPrivateKey();
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(pk);
            sig.update(nonce);
            return sig.sign();
        } catch (Exception ex) {
            return null;
        }
    }

    /** The Android SSAID, scoped per (signing key, device) since Android 8. */
    public static String ssaid(Context ctx) {
        String id = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id != null ? id : "unknown";
    }

    private static byte[] keystorePublic() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        KeyPair kp = kpg.generateKeyPair();
        return kp.getPublic().getEncoded();
    }

    private static byte[] softwarePublic() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair().getPublic().getEncoded();
    }
}
