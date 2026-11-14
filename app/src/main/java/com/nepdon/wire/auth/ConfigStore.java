package com.nepdon.wire.auth;

import android.os.SystemClock;

import com.nepdon.wire.decode.body.CmdCatalogue;
import com.nepdon.wire.net.Crypto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * RAM-only holder for the server-delivered Bucket-B knowledge blob
 * (docs/SECURITY_MODEL.md §3). The decrypted blob is parsed once, its sections
 * are pushed into the decode-layer tables (auth → decode, never the reverse),
 * and the raw bytes are wiped. Nothing here is ever written to disk.
 *
 * <p>Phase 1 section: {@code cmd_catalogue}. Phase 2 adds the wire-layout table
 * and the remaining constant maps.</p>
 */
public final class ConfigStore {

    private static volatile boolean loaded;
    private static volatile int configVersion;
    private static volatile String watermark = "";

    // ---- server-gated decode profile (anti-crack) ----------------------------
    // The native decoder's structural parameters + a validity window, delivered
    // ONLY inside this authenticated blob (never baked into the APK). The native
    // layer arms its decoders from these; without a live profile the decoder
    // fail-closes and nothing decodes. RAM-only, zeroed on clear(), never
    // persisted. See docs/SERVER_GATED_DECODE_DESIGN.md.
    private static volatile long decodeValidUntil;   // unix seconds; 0 = absent
    private static volatile int chainMaxLen;
    private static volatile int chainMaxRecords;
    private static volatile int chainMinRecordBytes;
    private static volatile long chainMinRecordX;
    private static volatile long chainMaxEntityId;

    // ---- field-semantic maps (server-owned decode knowledge; RAM-only) -------
    // The "which v[i] means what" tables the consumers (PositionSync/AimFilter/
    // PriSync/MarkFilter/EspEngine) read instead of baked constants. Delivered in
    // /config's decode_profile; held only here in RAM and nulled on clear(). A
    // consumer fail-closes when its map is null (no live profile => can't decode).
    private static volatile JSONArray chainRecordFields;
    private static volatile JSONObject keepaliveVMap;
    private static volatile JSONObject priKeyLayout;
    private static volatile JSONObject mark148Layout;
    private static volatile JSONObject vehicleLayout;
    private static volatile JSONObject decodeGates;

    // Keepalive (Cmd 2001) v-index map, resolved to primitives at load so the hot
    // decode path reads plain ints, not JSON — zero per-packet parse cost. Only
    // read when keepaliveVMap != null (i.e. a live profile is armed). -1 = unset.
    private static volatile int kaPosX = -1, kaPosZ = -1, kaPosY = -1;
    private static volatile int kaAirborne = -1, kaPose = -1;
    private static volatile int kaCamX = -1, kaCamY = -1, kaCamZ = -1;
    private static volatile int kaRtt = -1, kaTick = -1, kaBodyVarints = -1;
    // Cmd 1900 EPRI player-namespace ids, resolved to primitives at load. -1 = unset.
    private static volatile int priCurHp = -1, priMaxHp = -1, priVest = -1, priHelmet = -1;
    private static volatile int priOnHand = -1, priCurEp = -1, priMaxEp = -1;
    private static volatile int priFiring = -1, priTotalDmg = -1, priSighting = -1;
    // Ghost/Freeze/Teleport carrier id-sets, resolved to int[] at load for the hot
    // path (Cmd.isOwnPositionCarrier/isWorldStateStream). null until a profile arms.
    private static volatile int[] gateOwnPos, gateMounted, gateWorldState;

    // Camera/projection calibration (the il2cpp-DECRYPTION-derived rig: FOV, boom,
    // shoulder). These are the highest-RE-resistance decode knowledge in the tree --
    // the shipped libil2cpp.so has encrypted method bodies, so an APK-only attacker
    // cannot recover them by decompilation; they came from the decrypted image. Held
    // as the getFloat DEFAULT source in EspTuning (a saved Prefs value still wins).
    // NaN = unset; calibrationArmed() is the (server-present) gate. See EspTuning.
    private static volatile float calFovXDeg = Float.NaN;
    private static volatile float calBoomM = Float.NaN;
    private static volatile float calShoulderM = Float.NaN;

    // ---- rolling decode arm (continuous-auth heartbeat) ----------------------
    // A MONOTONIC deadline (SystemClock.elapsedRealtime ms) re-armed by every
    // /auth/heartbeat ACK. Monotonic on purpose: a device date-rollback cannot
    // extend it (the wall-clock decodeValidUntil above can, which is why the
    // enforcing leash will move to this). rollingEpoch is monotonic across ACKs so
    // a replayed older ACK is rejected. RAM-only, zeroed on clear().
    private static volatile long rollingDeadlineElapsedMs;   // 0 = not armed
    private static volatile long rollingEpoch;               // 0 = none held

    private ConfigStore() {}

    /** Parse a decrypted blob (JSON bytes) and install its sections. */
    public static synchronized void load(byte[] blob) throws Exception {
        // Wipe the raw plaintext bytes no matter how parsing exits. (org.json still
        // builds heap Strings we cannot reach — that residue is inherent to a
        // local decoder and accepted per docs/SECURITY_MODEL.md; this at least
        // guarantees the byte[] buffer itself does not linger on an exception.)
        try {
            JSONObject root = new JSONObject(new String(blob, "UTF-8"));
            configVersion = root.optInt("config_version", 0);
            watermark = root.optString("wm", "");
            JSONObject sections = root.optJSONObject("sections");
            if (sections != null) {
                JSONObject cat = sections.optJSONObject("cmd_catalogue");
                if (cat != null) {
                    JSONArray ids = cat.optJSONArray("ids");
                    if (ids != null) {
                        Map<Integer, String> m = new HashMap<>(ids.length() * 2);
                        for (int i = 0; i < ids.length(); i++) {
                            JSONObject e = ids.optJSONObject(i);
                            if (e != null) m.put(e.optInt("id"), e.optString("name"));
                        }
                        CmdCatalogue.loadServerNames(m);
                    }
                }
            }
            // Server-gated decode profile: arm the native decoder for this
            // session. Absent → the fields stay 0 → the native layer fail-closes.
            JSONObject dp = root.optJSONObject("decode_profile");
            if (dp != null) {
                JSONObject chain = dp.optJSONObject("chain");
                if (chain != null) {
                    decodeValidUntil = dp.optLong("valid_until", 0);
                    chainMaxLen = chain.optInt("max_len", 0);
                    chainMaxRecords = chain.optInt("max_records", 0);
                    chainMinRecordBytes = chain.optInt("min_record_bytes", 0);
                    chainMinRecordX = chain.optLong("min_record_x", 0);
                    chainMaxEntityId = chain.optLong("max_entity_id", 0);
                    chainRecordFields = chain.optJSONArray("record_fields");
                }
                // The field-semantic maps (server-owned decode knowledge).
                keepaliveVMap = dp.optJSONObject("keepalive_v_map");
                if (keepaliveVMap != null) {
                    JSONArray pos = keepaliveVMap.optJSONArray("pos");
                    JSONArray cam = keepaliveVMap.optJSONArray("cam_dir");
                    if (pos != null && pos.length() >= 3) {
                        kaPosX = pos.optInt(0, -1);
                        kaPosZ = pos.optInt(1, -1);
                        kaPosY = pos.optInt(2, -1);
                    }
                    if (cam != null && cam.length() >= 3) {
                        kaCamX = cam.optInt(0, -1);
                        kaCamY = cam.optInt(1, -1);
                        kaCamZ = cam.optInt(2, -1);
                    }
                    kaAirborne = keepaliveVMap.optInt("phys_state", -1);
                    kaPose = keepaliveVMap.optInt("pose", -1);
                    kaRtt = keepaliveVMap.optInt("rtt", -1);
                    kaTick = keepaliveVMap.optInt("tick", -1);
                    kaBodyVarints = keepaliveVMap.optInt("body_varints", -1);
                }
                priKeyLayout = dp.optJSONObject("pri_key_layout");
                if (priKeyLayout != null) {
                    priCurHp = priKeyLayout.optInt("cur_hp", -1);
                    priMaxHp = priKeyLayout.optInt("max_hp", -1);
                    priVest = priKeyLayout.optInt("vest", -1);
                    priHelmet = priKeyLayout.optInt("helmet", -1);
                    priOnHand = priKeyLayout.optInt("on_hand", -1);
                    priCurEp = priKeyLayout.optInt("cur_ep", -1);
                    priMaxEp = priKeyLayout.optInt("max_ep", -1);
                    priFiring = priKeyLayout.optInt("firing", -1);
                    priTotalDmg = priKeyLayout.optInt("total_dmg", -1);
                    priSighting = priKeyLayout.optInt("sighting", -1);
                }
                mark148Layout = dp.optJSONObject("mark148_layout");
                vehicleLayout = dp.optJSONObject("vehicle_layout");
                decodeGates = dp.optJSONObject("gates");
                if (decodeGates != null) {
                    gateOwnPos = intArray(decodeGates.optJSONArray("own_pos_carriers"));
                    gateMounted = intArray(decodeGates.optJSONArray("mounted_carriers"));
                    gateWorldState = intArray(decodeGates.optJSONArray("world_state_stream"));
                }
                JSONObject cal = dp.optJSONObject("calibration");
                if (cal != null) {
                    calFovXDeg = (float) cal.optDouble("fov_x_deg", Double.NaN);
                    calBoomM = (float) cal.optDouble("boom_m", Double.NaN);
                    calShoulderM = (float) cal.optDouble("shoulder_m", Double.NaN);
                }
            }
            loaded = true;
        } finally {
            Crypto.wipe(blob);
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static int configVersion() { return configVersion; }
    public static String watermark() { return watermark; }

    // ---- decode-profile accessors (read by the native-arming layer) ----------
    /** Unix seconds the server-gated decode profile is valid until; 0 = none. */
    public static long decodeValidUntil() { return decodeValidUntil; }
    public static int chainMaxLen() { return chainMaxLen; }
    public static int chainMaxRecords() { return chainMaxRecords; }
    public static int chainMinRecordBytes() { return chainMinRecordBytes; }
    public static long chainMinRecordX() { return chainMinRecordX; }
    public static long chainMaxEntityId() { return chainMaxEntityId; }
    /** True when a decode profile is present and unexpired at {@code nowSec}. */
    public static boolean decodeArmed(long nowSec) {
        return decodeValidUntil != 0 && nowSec < decodeValidUntil;
    }

    // ---- rolling arm (heartbeat) accessors -----------------------------------
    /**
     * Install a rolling arm from a heartbeat ACK. {@code validUntilSec}/{@code
     * serverNowSec} are the server's wall-clock window; only their DIFFERENCE is
     * used, converted into the local monotonic {@code elapsedRealtime} domain, so
     * a device clock change cannot extend the lease. Rejects a stale/replayed ACK
     * (epoch not greater than the one already held). Returns true if it armed.
     */
    public static synchronized boolean armRolling(long validUntilSec, long serverNowSec, long epoch) {
        if (rollingEpoch != 0 && epoch <= rollingEpoch) return false;   // stale/replayed ACK
        long remainMs = (validUntilSec - serverNowSec) * 1000L;
        if (remainMs <= 0) return false;
        rollingDeadlineElapsedMs = SystemClock.elapsedRealtime() + remainMs;
        rollingEpoch = epoch;
        return true;
    }

    /** True while the rolling heartbeat arm is live (monotonic clock, rollback-proof). */
    public static boolean rollingArmed() {
        long d = rollingDeadlineElapsedMs;
        return d != 0 && SystemClock.elapsedRealtime() < d;
    }

    /** The highest rolling epoch held (0 = none); sent as {@code epoch_have}. */
    public static long rollingEpoch() { return rollingEpoch; }

    // ---- field-map accessors (a null return => the consumer must fail-closed) --
    public static JSONArray chainRecordFields() { return chainRecordFields; }
    public static JSONObject keepaliveVMap() { return keepaliveVMap; }
    public static JSONObject priKeyLayout() { return priKeyLayout; }
    public static JSONObject mark148Layout() { return mark148Layout; }
    public static JSONObject vehicleLayout() { return vehicleLayout; }
    public static JSONObject decodeGates() { return decodeGates; }

    /** True when the carrier id-sets are armed from a live profile. */
    public static boolean gatesArmed() { return gateOwnPos != null && gateWorldState != null; }
    public static int[] gateOwnPos() { return gateOwnPos; }
    public static int[] gateMounted() { return gateMounted; }
    public static int[] gateWorldState() { return gateWorldState; }

    /** True when a live profile supplied the camera/projection calibration. */
    public static boolean calibrationArmed() {
        return !Float.isNaN(calFovXDeg) && !Float.isNaN(calBoomM) && !Float.isNaN(calShoulderM);
    }
    public static float calFovXDeg() { return calFovXDeg; }
    public static float calBoomM() { return calBoomM; }
    public static float calShoulderM() { return calShoulderM; }

    /** Parse a JSON int array into int[], or null when absent/empty. */
    private static int[] intArray(JSONArray a) {
        if (a == null || a.length() == 0) return null;
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optInt(i, -1);
        return out;
    }

    // Keepalive v-index accessors (primitives resolved at load; hot-path safe).
    public static int kaPosX() { return kaPosX; }
    public static int kaPosZ() { return kaPosZ; }
    public static int kaPosY() { return kaPosY; }
    public static int kaAirborne() { return kaAirborne; }
    public static int kaPose() { return kaPose; }
    public static int kaCamX() { return kaCamX; }
    public static int kaCamY() { return kaCamY; }
    public static int kaCamZ() { return kaCamZ; }
    public static int kaRtt() { return kaRtt; }
    public static int kaTick() { return kaTick; }
    public static int kaBodyVarints() { return kaBodyVarints; }
    /** True when a complete keepalive v-map is armed (every index resolved). */
    public static boolean keepaliveMapArmed() {
        return keepaliveVMap != null && kaPosX >= 0 && kaPosZ >= 0 && kaPosY >= 0
                && kaAirborne >= 0 && kaPose >= 0 && kaCamX >= 0 && kaCamY >= 0
                && kaCamZ >= 0 && kaRtt >= 0 && kaTick >= 0 && kaBodyVarints > 0;
    }

    // Cmd 1900 EPRI id accessors (primitives resolved at load).
    public static int priCurHp() { return priCurHp; }
    public static int priMaxHp() { return priMaxHp; }
    public static int priVest() { return priVest; }
    public static int priHelmet() { return priHelmet; }
    public static int priOnHand() { return priOnHand; }
    public static int priCurEp() { return priCurEp; }
    public static int priMaxEp() { return priMaxEp; }
    public static int priFiring() { return priFiring; }
    public static int priSighting() { return priSighting; }
    public static int priTotalDmg() { return priTotalDmg; }
    /** True when the EPRI id layout is armed (the health-critical ids resolved). */
    public static boolean priLayoutArmed() {
        return priKeyLayout != null && priCurHp >= 0 && priMaxHp >= 0
                && priOnHand >= 0 && priCurEp >= 0;
    }

    /** Drop the in-RAM config (logout / background timeout). */
    public static synchronized void clear() {
        loaded = false;
        configVersion = 0;
        watermark = "";
        // Disarm the native decoder: on logout/background the leash closes, so a
        // backgrounded session cannot keep decoding on a stale profile.
        decodeValidUntil = 0;
        chainMaxLen = 0;
        chainMaxRecords = 0;
        chainMinRecordBytes = 0;
        chainMinRecordX = 0;
        chainMaxEntityId = 0;
        // Disarm the rolling heartbeat lease too, so a logout/background stops decode.
        rollingDeadlineElapsedMs = 0;
        rollingEpoch = 0;
        // Release the server-owned decode maps (RAM-only knowledge).
        chainRecordFields = null;
        keepaliveVMap = null;
        priKeyLayout = null;
        mark148Layout = null;
        vehicleLayout = null;
        decodeGates = null;
        kaPosX = kaPosZ = kaPosY = kaAirborne = kaPose = -1;
        kaCamX = kaCamY = kaCamZ = kaRtt = kaTick = kaBodyVarints = -1;
        priCurHp = priMaxHp = priVest = priHelmet = priOnHand = -1;
        priCurEp = priMaxEp = priFiring = priTotalDmg = priSighting = -1;
        gateOwnPos = gateMounted = gateWorldState = null;
        calFovXDeg = calBoomM = calShoulderM = Float.NaN;
        // Actually release the crown-jewel table too — clearing only the flags
        // left the whole catalogue resident for the process lifetime, which made
        // the "zeroed on logout/background" contract in docs/LOGIN_AND_SERVER.md
        // false (2026-09-17 audit, MED).
        CmdCatalogue.clearServerNames();
    }
}
