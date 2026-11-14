package com.nepdon.wire.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.nepdon.wire.auth.AuthConfig;
import com.nepdon.wire.config.Prefs;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * Loads and live-updates the Rust packet core (libnuvola_native.so).
 *
 * <p>Trust: the server signs a small manifest (version, sha256, validity, …)
 * with the Ed25519 key whose public half is pinned in
 * {@link AuthConfig#SERVER_ED25519_PUB_HEX} — the SAME key the offline lease
 * uses, so no new trust root. The client verifies that signature AND the file's
 * SHA-256 before it ever {@code System.load()}s the file, refuses a downgrade,
 * and always has the APK-bundled copy as a fail-closed fallback.</p>
 *
 * <p>Load safety on modern Android: the file is marked read-only before load
 * (a warning on targetSdk 34, a hard {@code UnsatisfiedLinkError} on 37), stored
 * in {@code getNoBackupFilesDir()} so a backup restore can't reintroduce a stale
 * or planted lib. A boot breadcrumb + a native self-test + two-phase promotion
 * mean a bad push auto-rolls-back even if it hard-crashes the process on load.</p>
 *
 * <p>All work runs off the packet hot path (launch-time, background thread) and
 * never blocks the app: any failure falls through to the bundled lib.</p>
 */
public final class NativeLoader {

    private static final String TAG = "NativeLoader";
    private static final String ABI = "arm64-v8a";
    private static final String LIB = "nuvola_native";   // bundled fallback name

    /** Absolute ceiling for a native-lib download. The real .so is ~1.3 MB; this
     *  is the same 16 MiB artifact limit the build enforces, so it is generous
     *  while still bounding an OOM DoS from a hostile/compromised origin. */
    private static final long MAX_NATIVE_BYTES = 16L * 1024 * 1024;

    private static volatile boolean ready;

    private NativeLoader() {}

    /** True once some copy of the lib is loaded and passed its self-test. */
    public static boolean isReady() {
        return ready;
    }

    /** Fire-and-forget launch init: load the best lib, then check for an update. */
    public static void init(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    loadBest(app);
                } catch (Throwable t) {
                    Log.w(TAG, "loadBest failed", t);
                }
                try {
                    checkForUpdate(app);
                } catch (Throwable t) {
                    Log.w(TAG, "update check failed", t);
                }
            }
        }, "nuvola-native-loader").start();
    }

    // ---- load ----

    private static synchronized void loadBest(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);

        // Crash-loop detection: a version left "attempting" bricked the process
        // during load last launch (a SIGSEGV no try/catch could catch).
        int attempting = p.getInt(Prefs.KEY_NATIVE_ATTEMPTING, 0);
        if (attempting != 0) {
            quarantine(ctx, attempting);
            if (p.getInt(Prefs.KEY_NATIVE_ACTIVE, 0) == attempting) {
                p.edit().putInt(Prefs.KEY_NATIVE_ACTIVE, 0).apply();
            }
            if (p.getInt(Prefs.KEY_NATIVE_CANDIDATE, 0) == attempting) {
                p.edit().putInt(Prefs.KEY_NATIVE_CANDIDATE, 0).apply();
            }
            p.edit().remove(Prefs.KEY_NATIVE_ATTEMPTING).apply();
        }

        int candidate = p.getInt(Prefs.KEY_NATIVE_CANDIDATE, 0);
        int active = p.getInt(Prefs.KEY_NATIVE_ACTIVE, 0);
        int[] order = new int[]{candidate, active};
        for (int v : order) {
            if (v == 0 || isQuarantined(ctx, v)) continue;
            if (tryLoadVerified(ctx, v)) {
                if (v == candidate) {
                    // Promote on first clean launch and raise the high-water mark.
                    SharedPreferences.Editor e = p.edit();
                    e.putInt(Prefs.KEY_NATIVE_ACTIVE, v);
                    e.remove(Prefs.KEY_NATIVE_CANDIDATE);
                    if (v > p.getInt(Prefs.KEY_NATIVE_HIGHWATER, 0)) {
                        e.putInt(Prefs.KEY_NATIVE_HIGHWATER, v);
                    }
                    e.apply();
                }
                ready = true;
                Log.i(TAG, "loaded downloaded native lib v" + v);
                return;
            }
            quarantine(ctx, v);
        }

        // Fallback: the APK-bundled copy via the platform loader (always allowed,
        // never subject to the read-only check).
        try {
            System.loadLibrary(LIB);
            if (NativeBridge.nnSelftest() == NativeBridge.EXPECTED_SELFTEST) {
                ready = true;
                Log.i(TAG, "loaded bundled native lib v" + NativeBridge.BUNDLED_VERSION);
            } else {
                Log.w(TAG, "bundled lib self-test mismatch");
            }
        } catch (Throwable t) {
            Log.w(TAG, "no native lib available", t);
        }
    }

    private static boolean tryLoadVerified(Context ctx, int version) {
        try {
            JSONObject m = readCachedManifest(ctx, version);
            if (m == null) return false;
            if (version < Prefs.get(ctx).getInt(Prefs.KEY_NATIVE_HIGHWATER, 0)) return false;
            if (m.optInt("min_loader", 0) > NativeBridge.LOADER_ABI) return false;

            File so = libFile(ctx, version);
            if (!so.isFile()) return false;
            so.setReadOnly();                                   // BEFORE hashing (close TOCTOU) + REQUIRED for load (API34 warn / API37 hard)
            if (!sha256Hex(so).equalsIgnoreCase(m.optString("sha256", ""))) return false;
            if (!verifyManifestSig(m)) return false;
            if (!withinValidity(m)) return false;               // signed not_before/not_after window
            Prefs.get(ctx).edit().putInt(Prefs.KEY_NATIVE_ATTEMPTING, version).commit();
            System.load(so.getAbsolutePath());                  // may SIGSEGV → breadcrumb survives
            long got = NativeBridge.nnSelftest();
            if (got != NativeBridge.EXPECTED_SELFTEST) return false;
            Prefs.get(ctx).edit().remove(Prefs.KEY_NATIVE_ATTEMPTING).commit();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "verified load v" + version + " failed", t);
            return false;
        }
    }

    // ---- update ----

    private static void checkForUpdate(Context ctx) {
        try {
            Http.Resp vr = Http.get("/version");
            if (!vr.ok()) return;
            JSONObject v = new JSONObject(vr.body);
            JSONObject nat = v.optJSONObject("native");
            if (nat == null) return;
            int serverVer = nat.optInt("version", 0);
            if (serverVer <= have(ctx)) return;

            Http.Resp mr = Http.get("/native/manifest?abi=" + ABI);
            if (!mr.ok()) return;
            JSONObject m = new JSONObject(mr.body);
            if (!verifyManifestSig(m)) return;                  // verify BEFORE trusting kill/rollout
            if (!withinValidity(m)) return;
            if (m.optBoolean("kill", false)) { revertToBundled(ctx); return; }
            if (m.optInt("version", 0) <= Prefs.get(ctx).getInt(Prefs.KEY_NATIVE_HIGHWATER, 0)) return;

            // Staged rollout: only this fraction of installs takes it early.
            long bucket = rolloutBucket(ctx);
            if (bucket >= m.optInt("rollout_permille", 1000)) return;

            int version = m.optInt("version", 0);
            // Bound the download by the signed size (with a hard absolute cap),
            // so a hostile body can't OOM us before the SHA-256 check runs.
            long signedSize = m.optLong("size", 0);
            if (signedSize <= 0 || signedSize > MAX_NATIVE_BYTES) return;
            byte[] bytes = Http.getBytes("/native/lib/" + version + "?abi=" + ABI, signedSize);
            if (bytes == null) return;
            if (bytes.length != signedSize) return;              // must match the signed length exactly
            if (!sha256Hex(bytes).equalsIgnoreCase(m.optString("sha256", ""))) return;

            File dir = nativeDir(ctx);
            if (!dir.exists() && !dir.mkdirs()) return;
            File tmp = File.createTempFile("nn", ".part", dir);
            FileOutputStream fos = new FileOutputStream(tmp);
            try { fos.write(bytes); fos.getFD().sync(); } finally { fos.close(); }
            tmp.setReadOnly();
            File dst = libFile(ctx, version);
            dst.getParentFile().mkdirs();
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) { tmp.delete(); return; }

            // Cache the signed manifest and mark the version a candidate; it is
            // verified again and promoted on the next clean launch.
            Prefs.get(ctx).edit()
                    .putString(Prefs.KEY_NATIVE_MANIFEST + version, m.toString())
                    .putInt(Prefs.KEY_NATIVE_CANDIDATE, version)
                    .apply();
            Log.i(TAG, "downloaded native lib v" + version + " (candidate)");
        } catch (Throwable t) {
            Log.w(TAG, "update check error", t);
        }
    }

    private static void revertToBundled(Context ctx) {
        Prefs.get(ctx).edit()
                .remove(Prefs.KEY_NATIVE_CANDIDATE)
                .remove(Prefs.KEY_NATIVE_ACTIVE)
                .apply();
    }

    // ---- verification helpers ----

    /** Rebuild the exact bytes the server signed and verify with the pinned key. */
    private static boolean verifyManifestSig(JSONObject m) {
        try {
            if (AuthConfig.SERVER_ED25519_PUB_HEX.isEmpty()) return false;  // fail closed
            // v2: the control fields (rollout_permille, kill, url) are signed too,
            // so a tampered transport can't force/suppress a kill or rollout.
            String raw = m.optString("schema") + "\n"
                    + "abi=" + m.optString("abi") + "\n"
                    + "version=" + m.optInt("version") + "\n"
                    + "semver=" + m.optString("semver") + "\n"
                    + "sha256=" + m.optString("sha256") + "\n"
                    + "size=" + m.optLong("size") + "\n"
                    + "min_loader=" + m.optInt("min_loader") + "\n"
                    + "not_before=" + m.optString("not_before") + "\n"
                    + "not_after=" + m.optString("not_after") + "\n"
                    + "rollout_permille=" + m.optInt("rollout_permille") + "\n"
                    + "kill=" + (m.optBoolean("kill") ? "true" : "false") + "\n"
                    + "url=" + m.optString("url") + "\n";
            byte[] pub = Crypto.unhex(AuthConfig.SERVER_ED25519_PUB_HEX);
            byte[] sig = Crypto.unb64(m.optString("sig"));
            return Crypto.ed25519Verify(pub, sig, raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Enforce the signed not_before/not_after window against the device clock.
     * Fail closed (reject → fall back to the bundled lib) on a malformed or
     * missing timestamp: a security window that cannot be evaluated is not
     * honoured open.
     */
    private static boolean withinValidity(JSONObject m) {
        long now = System.currentTimeMillis();
        long nb = parseIso8601(m.optString("not_before", ""));
        long na = parseIso8601(m.optString("not_after", ""));
        if (nb < 0 || na < 0) return false;
        return now >= nb && now <= na;
    }

    /** Parse "yyyy-MM-ddTHH:mm:ssZ" (UTC) to epoch millis, or -1 on failure. */
    private static long parseIso8601(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(s);
            return d != null ? d.getTime() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static JSONObject readCachedManifest(Context ctx, int version) {
        String s = Prefs.get(ctx).getString(Prefs.KEY_NATIVE_MANIFEST + version, null);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    // ---- state helpers ----

    private static int have(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);
        int h = Math.max(p.getInt(Prefs.KEY_NATIVE_ACTIVE, 0),
                p.getInt(Prefs.KEY_NATIVE_CANDIDATE, 0));
        return Math.max(h, NativeBridge.BUNDLED_VERSION);
    }

    private static long rolloutBucket(Context ctx) {
        SharedPreferences p = Prefs.get(ctx);
        String id = p.getString(Prefs.KEY_NATIVE_INSTALL_ID, null);
        if (id == null) {
            id = Long.toHexString(new java.security.SecureRandom().nextLong());
            p.edit().putString(Prefs.KEY_NATIVE_INSTALL_ID, id).apply();
        }
        CRC32 c = new CRC32();
        c.update(id.getBytes(StandardCharsets.UTF_8));
        return c.getValue() % 1000;
    }

    private static boolean isQuarantined(Context ctx, int version) {
        String q = Prefs.get(ctx).getString(Prefs.KEY_NATIVE_QUARANTINE, "");
        for (String s : q.split(",")) {
            if (!s.isEmpty() && Integer.parseInt(s) == version) return true;
        }
        return false;
    }

    private static void quarantine(Context ctx, int version) {
        if (isQuarantined(ctx, version)) return;
        String q = Prefs.get(ctx).getString(Prefs.KEY_NATIVE_QUARANTINE, "");
        q = q.isEmpty() ? String.valueOf(version) : q + "," + version;
        Prefs.get(ctx).edit().putString(Prefs.KEY_NATIVE_QUARANTINE, q).apply();
    }

    // ---- fs helpers ----

    private static File nativeDir(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), "native/" + ABI);
    }

    private static File libFile(Context ctx, int version) {
        return new File(nativeDir(ctx), "lib." + version + ".so");
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        return Crypto.hex(md.digest());
    }

    private static String sha256Hex(byte[] b) throws Exception {
        return Crypto.hex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
