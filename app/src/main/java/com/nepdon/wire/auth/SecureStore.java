package com.nepdon.wire.auth;

import android.content.Context;
import android.os.Build;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;

import java.nio.charset.StandardCharsets;

import android.util.Base64;

/**
 * Tink-AEAD-backed string encryption for at-rest secrets (the refresh token).
 * The keyset lives in its own SharedPreferences under an Android Keystore master
 * key (API 23+); on older APIs Tink falls back to a cleartext keyset. This
 * replaces the deprecated EncryptedSharedPreferences (removed April 2025) —
 * docs/LOGIN_AND_SERVER.md §2.
 *
 * <p>On a rooted device this does not defeat a memory dump; it defeats offline
 * APK+data extraction and non-root attackers, and the refresh token is
 * short-lived + server-revocable, so the exposure is bounded.</p>
 */
public final class SecureStore {

    private static final String KEYSET_NAME = "nepdon_wire_auth_keyset";
    private static final String PREF_FILE   = "nepdon_wire_auth_pref";
    private static final String MASTER_URI  = "android-keystore://nepdon_wire_auth_master";

    private final Aead aead;

    private SecureStore(Aead aead) { this.aead = aead; }

    public static SecureStore get(Context ctx) throws Exception {
        AeadConfig.register();
        AndroidKeysetManager.Builder b = new AndroidKeysetManager.Builder()
                .withSharedPref(ctx, KEYSET_NAME, PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Hardware-backed master key. If the Keystore genuinely fails here we
            // let the exception propagate (caller forces re-login) rather than
            // silently persisting a CLEARTEXT keyset — the whole point is at-rest
            // protection of the refresh token.
            b.withMasterKeyUri(MASTER_URI);
        }
        // API < 23 only: no reliable Keystore AES master key, so Tink stores the
        // keyset without one (unavoidable on those devices; minSdk is 21).
        KeysetHandle handle = b.build().getKeysetHandle();
        return new SecureStore(handle.getPrimitive(Aead.class));
    }

    public String encrypt(String plaintext) throws Exception {
        byte[] ct = aead.encrypt(plaintext.getBytes(StandardCharsets.UTF_8),
                KEYSET_NAME.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    public String decrypt(String b64) throws Exception {
        byte[] pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP),
                KEYSET_NAME.getBytes(StandardCharsets.UTF_8));
        return new String(pt, StandardCharsets.UTF_8);
    }
}
