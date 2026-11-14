package com.nepdon.wire.net;

import android.util.Base64;

import com.google.crypto.tink.subtle.AesGcmJce;
import com.google.crypto.tink.subtle.Ed25519Verify;
import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Client-side crypto for the login/config protocol, mirroring the server's raw
 * operations exactly (server/app/crypto.py). Uses Tink's audited subtle
 * primitives rather than hand-rolled or JCA-XDH (X25519 is JCA-available only on
 * API 33+; this app's minSdk is 21).
 *
 * <p>Config-delivery open path:
 * shared = X25519(client_sk, server_pub);
 * session = HKDF-SHA256(shared, salt=client_nonce, info="nuvola-config-v1", 32);
 * data_key = AES-GCM-open(session, wrap_nonce || wrapped, aad);
 * blob = AES-GCM-open(data_key, ct_nonce || ciphertext, aad).</p>
 */
public final class Crypto {

    public static final byte[] CONFIG_INFO = "nuvola-config-v1".getBytes();
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    // ---- encodings ----
    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    | Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    public static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    // ---- X25519 ----
    public static byte[] x25519Private() {
        return X25519.generatePrivateKey();
    }

    public static byte[] x25519Public(byte[] priv) throws GeneralSecurityException {
        return X25519.publicFromPrivate(priv);
    }

    public static byte[] x25519Shared(byte[] priv, byte[] peerPub) throws GeneralSecurityException {
        return X25519.computeSharedSecret(priv, peerPub);
    }

    // ---- HKDF-SHA256 ----
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len)
            throws GeneralSecurityException {
        return Hkdf.computeHkdf("HMACSHA256", ikm, salt, info, len);
    }

    // ---- AES-256-GCM open. `nonce`(12) is prepended to `ct` (ct||tag) as Tink expects. ----
    public static byte[] aesGcmOpen(byte[] key, byte[] nonce, byte[] ct, byte[] aad)
            throws GeneralSecurityException {
        byte[] joined = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, joined, 0, nonce.length);
        System.arraycopy(ct, 0, joined, nonce.length, ct.length);
        return new AesGcmJce(key).decrypt(joined, aad);
    }

    // ---- SHA-256 (hex), mirrors server crypto.sha256_hex on a UTF-8 string ----
    public static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return hex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";   // SHA-256 is always present on Android; empty fails the compare closed
        }
    }

    // ---- Ed25519 lease verification ----
    public static boolean ed25519Verify(byte[] pub32, byte[] sig, byte[] msg) {
        try {
            new Ed25519Verify(pub32).verify(sig, msg);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Best-effort wipe of a secret buffer. */
    public static void wipe(byte[] b) {
        if (b != null) java.util.Arrays.fill(b, (byte) 0);
    }
}
