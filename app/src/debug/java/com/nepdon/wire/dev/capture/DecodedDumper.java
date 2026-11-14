package com.nepdon.wire.dev.capture;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exhaustive offline-analysis dump written at capture-stop, alongside the raw
 * capture. The goal is that a fresh analysis session never has to re-derive a
 * protocol fact from raw hex and never has to guess what a byte was doing.
 *
 * <p>Protocol model (see {@code docs/PROTOCOL_VARINT_DISCOVERY.md}):
 * b0 counter, b1 nonce, b2-7 XOR-masked header window holding
 * [flags16][length16][channel16] cyclically rotated by a per-session
 * constant (length16 = payload len + 8), b8-11 connection token, then the
 * body as an <b>untagged LEB128 varint stream</b>.</p>
 *
 * <p>Files written per capture stop (all prefixed with the same stamp):</p>
 * <ul>
 *   <li><b>summary_.txt</b> — mask + rotation, full family census with varint
 *       parse rates, own-state stats, entity census, phase timeline, and the
 *       open-question checklist scored against this capture.</li>
 *   <li><b>frames_.csv</b> — EVERY packet: header decode, unmasked b2-7,
 *       token, taxonomy, and the varint field count at body starts 8/10/12.</li>
 *   <li><b>varints_.csv</b> — every packet whose body parses, all field
 *       values (up to 48) — nothing is summarised away.</li>
 *   <li><b>own_.csv</b> — the 23-varint keepalive decode plus derived yaw,
 *       pitch, |dir|, speed, and per-sample deltas.</li>
 *   <li><b>entities_.csv</b> — every entity record of every chain packet:
 *       id, X/Z/Y, all extra varints, and the record's raw hex.</li>
 *   <li><b>pairs_.csv</b> — each entity record joined to the time-aligned own
 *       state. This is the dataset that settles the enemy-vs-own frame/scale
 *       question offline.</li>
 *   <li><b>bytemap_.txt</b> — per-family, per-offset distinct-value and
 *       change-rate map (the byte-level "what moves where" picture).</li>
 *   <li><b>unknown_.txt</b> — full hex samples of every family that never
 *       decodes, so nothing stays invisible.</li>
 * </ul>
 */
public final class DecodedDumper {

    /** Keepalive (own state) payload length window. */
    /**
     * Keepalive length window. 45-64 lost 532 real keepalives (521 at 65-67 B,
     * 11 at 40-42). The production engine gates on shape and an exact 23-varint
     * body instead, which is why it finds 42 244 where this found 41 516.
     */
    private static final int KA_MIN = 32, KA_MAX = 96;
    /** Max data rows per CSV, so a long match cannot produce a huge file. */
    private static final int MAX_ROWS = 400000;
    /** Max varint columns emitted in varints_.csv. */
    private static final int MAX_VCOLS = 48;
    /** Families byte-mapped in bytemap_.txt (top N by packet count). */
    private static final int BYTEMAP_FAMILIES = 40;
    /** Offsets byte-mapped per family. */
    private static final int BYTEMAP_OFFSETS = 96;
    /** Hex samples dumped per undecoded family. */
    private static final int UNKNOWN_SAMPLES = 12;

    private final List<CaptureRecord> records;
    private final long droppedByCap;

    private long t0;
    private int[] mask;            // 6 bytes, or null
    private int maskMinPct, maskSamples;
    private int rotation = -1;     // index 2..7 of the length low byte
    private int rotationHits, rotationTests;

    /** Own-state timeline used to join entity records against own position. */
    private long[] ownTs = new long[0];
    private double[][] ownVals = new double[0][];   // x, z, y, dx, dy, dz

    public DecodedDumper(List<CaptureRecord> records, long droppedByCap) {
        this.records = records;
        this.droppedByCap = droppedByCap;
    }

    /** Writes every decoded output into {@code dir}; returns the summary file. */
    public File dump(File dir) {
        if (!dir.exists()) dir.mkdirs();
        t0 = records.isEmpty() ? 0L : records.get(0).timestampMs;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        recoverMask();
        findRotation();
        buildOwnTimeline();
        safe(new File(dir, "frames_" + stamp + ".csv"), 1);
        safe(new File(dir, "varints_" + stamp + ".csv"), 2);
        safe(new File(dir, "own_" + stamp + ".csv"), 3);
        safe(new File(dir, "entities_" + stamp + ".csv"), 4);
        safe(new File(dir, "pairs_" + stamp + ".csv"), 5);
        safe(new File(dir, "bytemap_" + stamp + ".txt"), 6);
        safe(new File(dir, "unknown_" + stamp + ".txt"), 7);
        File s = new File(dir, "summary_" + stamp + ".txt");
        safe(s, 8);
        return s;
    }

    /** Runs one section; a failure in one never costs the others. */
    private void safe(File f, int which) {
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"), 1 << 16);
            switch (which) {
                case 1: writeFrames(w); break;
                case 2: writeVarints(w); break;
                case 3: writeOwn(w); break;
                case 4: writeEntities(w); break;
                case 5: writePairs(w); break;
                case 6: writeByteMap(w); break;
                case 7: writeUnknown(w); break;
                default: writeSummary(w); break;
            }
        } catch (Throwable t) {
            try {
                if (w != null) w.write("\n# SECTION FAILED: " + t + "\n");
            } catch (Exception ignored) { }
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) { }
        }
    }

    // ================================================================
    // Session mask + header rotation
    // ================================================================

    private void recoverMask() {
        int[][] counts = new int[6][256];
        int samples = 0;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (r.dir != CaptureRecord.Dir.OUT || r.payloadLen != 24) continue;
            if (r.payload.length < 8) continue;
            for (int k = 0; k < 6; k++) counts[k][r.payload[2 + k] & 0xff]++;
            samples++;
        }
        if (samples < 16) { mask = null; return; }
        mask = new int[6];
        maskMinPct = 100;
        maskSamples = samples;
        for (int k = 0; k < 6; k++) {
            int best = 0, bestC = 0, total = 0;
            for (int v = 0; v < 256; v++) {
                total += counts[k][v];
                if (counts[k][v] > bestC) { bestC = counts[k][v]; best = v; }
            }
            mask[k] = best;
            if (total > 0) maskMinPct = Math.min(maskMinPct, (int) (100L * bestC / total));
        }
    }

    /**
     * The header window is rotated per session: find the offset in b2-7 whose
     * decoded byte equals (payload length + 8) across the whole capture.
     */
    private void findRotation() {
        if (mask == null) return;
        int[] hits = new int[8];
        int tests = 0;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (r.payload.length < 8 || r.payloadLen > 247) continue;
            tests++;
            int want = (r.payloadLen + 8) & 0xff;
            for (int j = 2; j < 8; j++) {
                if (((r.payload[j] & 0xff) ^ mask[j - 2]) == want) hits[j]++;
            }
        }
        int best = -1, bestC = 0;
        for (int j = 2; j < 8; j++) if (hits[j] > bestC) { bestC = hits[j]; best = j; }
        rotation = bestC > 0 ? best : -1;
        rotationHits = bestC;
        rotationTests = tests;
    }

    private int pt(CaptureRecord r, int i) {
        int b = r.payload[i] & 0xff;
        if (mask != null && i >= 2 && i < 8) b ^= mask[i - 2];
        return b;
    }

    // ================================================================
    // Varint primitives
    // ================================================================

    /** Parses LEB128 varints from payload[start..end); null when truncated. */
    private static long[] varints(byte[] p, int start, int end) {
        long[] tmp = new long[64];
        int n = 0, i = start;
        while (i < end) {
            long v = 0;
            int s = 0;
            boolean done = false;
            while (i < end) {
                int c = p[i++] & 0xff;
                v |= (long) (c & 0x7f) << s;
                s += 7;
                if ((c & 0x80) == 0) { done = true; break; }
                if (s > 63) return null;
            }
            if (!done) return null;
            if (n == tmp.length) tmp = Arrays.copyOf(tmp, n * 2);
            tmp[n++] = v;
        }
        return Arrays.copyOf(tmp, n);
    }

    private static float f32(long u) {
        return Float.intBitsToFloat((int) (u & 0xffffffffL));
    }

    /** Whole payload visible (not clipped by the capture's byte cap)? */
    private static boolean whole(CaptureRecord r) {
        return r.payload.length >= r.payloadLen;
    }

    private static int bodyEnd(CaptureRecord r) {
        return Math.min(r.payloadLen, r.payload.length);
    }

    // ================================================================
    // Family keys
    // ================================================================

    /** Stable family key: direction, unmasked b2-3, b8, and the b9 shape. */
    private String familyKey(CaptureRecord r) {
        if (r.payload.length < 10) return r.dirName() + ":short" + r.payloadLen;
        return r.dirName() + ":" + hex2(pt(r, 2)) + hex2(pt(r, 3))
                + ":b8=" + hex2(r.payload[8] & 0xff);
    }

    /** The CHAIN0001 entity carrier: b8 == 0 and unmasked b2-3 == 00 01. */
    /**
     * Index just past {@code count} LEB128 varints starting at {@code i}, or
     * -1 if the data runs out.
     *
     * <p><b>The body does not start at a fixed offset.</b> It follows the
     * leading object-handle varint at b8, which is 4 OR 5 bytes depending on
     * the value. The old fixed b12 produced 24 varints instead of 23 whenever
     * the handle needed five bytes — which is 100% of the lobby/training
     * session, where every row of {@code own_*.csv} came out blank while still
     * looking populated.</p>
     */
    static int skipVarints(byte[] data, int i, int end, int count) {
        for (int k = 0; k < count; k++) {
            boolean done = false;
            int sh = 0;
            while (i < end) {
                int c = data[i++] & 0xff;
                if ((c & 0x80) == 0) { done = true; break; }
                sh += 7;
                if (sh > 63) return -1;
            }
            if (!done) return -1;
        }
        return i;
    }

    /** Body offset for a handle-led message (keepalive, damage, events). */
    static int bodyOffset(CaptureRecord r) {
        if (r.payload == null || r.payload.length < 10) return -1;
        return skipVarints(r.payload, 8, Math.min(r.payloadLen, r.payload.length), 1);
    }

    private boolean isChain(CaptureRecord r) {
        if (r.dir != CaptureRecord.Dir.IN) return false;
        if (r.payload.length < 12 || r.payloadLen < 20) return false;
        // Must NOT test the masked b2-7 window: it is rotated by a
        // per-session constant (length low byte seen at b3, b4 and b5 in
        // three consecutive sessions), so a fixed "b2==00 && b3==01" test
        // matches nothing in most sessions. b8/b9 sit outside the mask.
        if ((r.payload[8] & 0xff) != 0) return false;
        int b9 = r.payload[9] & 0xff;
        if ((b9 & 1) != 0 || b9 < 2 || b9 > 128) return false;
        // Byte-fit instead of a roster-size cap: the old "b9 <= 16" ceiling
        // (8 records, the Clash Squad roster) made every survival chain
        // invisible in the dumps, which in turn made the analysis of those
        // dumps circular — the record counts looked capped at 8 because the
        // dumper discarded everything above it.
        int declared = b9 / 2;
        // 16 bytes/record, not 4: the encrypted transport uses (b8,b9) as a
        // channel id, and when that id is (0, even) it is shaped exactly like
        // a chain. 4 let 6 490 ciphertext packets into capture 173746. Real
        // records measure 19.29 bytes at the floor; the encrypted cluster
        // 5.33. See EspEngine.MIN_RECORD_BYTES.
        return r.payloadLen >= 10 + declared * 16;
    }

    private static boolean isKeepalive(CaptureRecord r) {
        return r.dir == CaptureRecord.Dir.OUT
                && r.payloadLen >= KA_MIN && r.payloadLen <= KA_MAX;
    }

    private static String hex2(int b) {
        final String H = "0123456789ABCDEF";
        return "" + H.charAt((b >> 4) & 0xf) + H.charAt(b & 0xf);
    }

    private static String hexOf(byte[] d, int from, int to) {
        StringBuilder sb = new StringBuilder(Math.max(0, (to - from) * 2));
        for (int i = from; i < to && i < d.length; i++) sb.append(hex2(d[i] & 0xff));
        return sb.toString();
    }

    // ================================================================
    // 1. frames_.csv — every packet
    // ================================================================

    private void writeFrames(BufferedWriter w) throws Exception {
        w.write("seq,rel_ms,phase,dir,decision,sp,dp,len,visible,truncated,"
                + "b0,b1,pb2,pb3,pb4,pb5,pb6,pb7,hdr_len16,hdr_len_ok,token_b8_11,"
                + "family,kind,famkey,nv8,nv10,nv12,chain,b9_reccount\n");
        int rows = 0;
        for (int i = 0; i < records.size() && rows < MAX_ROWS; i++) {
            CaptureRecord r = records.get(i);
            int end = bodyEnd(r);
            int len16 = -1;
            if (mask != null && rotation >= 2 && r.payload.length >= 8) {
                int lo = pt(r, rotation);
                int hi = pt(r, rotation == 7 ? 2 : rotation + 1);
                len16 = lo | (hi << 8);
            }
            StringBuilder sb = new StringBuilder(220);
            sb.append(i).append(',').append(r.timestampMs - t0).append(',')
              .append(r.phaseName()).append(',').append(r.dirName()).append(',')
              .append(r.decisionName()).append(',').append(r.sp).append(',')
              .append(r.dp).append(',').append(r.payloadLen).append(',')
              .append(r.payload.length).append(',').append(whole(r) ? 0 : 1).append(',');
            for (int b = 0; b < 8; b++) {
                sb.append(r.payload.length > b ? String.valueOf(pt(r, b)) : "").append(',');
            }
            sb.append(len16).append(',')
              .append(len16 == r.payloadLen + 8 ? 1 : 0).append(',')
              .append(r.payload.length >= 12 ? hexOf(r.payload, 8, 12) : "").append(',')
              .append(r.familyName()).append(',').append(r.kindName()).append(',')
              .append(familyKey(r)).append(',');
            for (int st : new int[]{8, 10, 12}) {
                long[] v = (r.payload.length > st && end > st) ? varints(r.payload, st, end) : null;
                sb.append(v == null ? -1 : v.length).append(',');
            }
            boolean ch = isChain(r);
            sb.append(ch ? 1 : 0).append(',')
              .append(r.payload.length > 9 ? String.valueOf((r.payload[9] & 0xff) / 2) : "")
              .append('\n');
            w.write(sb.toString());
            rows++;
        }
    }

    // ================================================================
    // 2. varints_.csv — every parsing body, all field values
    // ================================================================

    private void writeVarints(BufferedWriter w) throws Exception {
        StringBuilder h = new StringBuilder("seq,rel_ms,phase,dir,len,famkey,token_b8_11,start,n");
        for (int i = 0; i < MAX_VCOLS; i++) h.append(",v").append(i);
        w.write(h.append('\n').toString());
        int rows = 0;
        for (int i = 0; i < records.size() && rows < MAX_ROWS; i++) {
            CaptureRecord r = records.get(i);
            int end = bodyEnd(r);
            if (end <= 12 || !whole(r)) continue;
            long[] v = varints(r.payload, 12, end);
            int start = 12;
            if (v == null) { v = varints(r.payload, 10, end); start = 10; }
            if (v == null) { v = varints(r.payload, 8, end); start = 8; }
            if (v == null || v.length == 0) continue;
            StringBuilder sb = new StringBuilder(320);
            sb.append(i).append(',').append(r.timestampMs - t0).append(',')
              .append(r.phaseName()).append(',').append(r.dirName()).append(',')
              .append(r.payloadLen).append(',').append(familyKey(r)).append(',')
              .append(r.payload.length >= 12 ? hexOf(r.payload, 8, 12) : "").append(',')
              .append(start).append(',').append(v.length);
            for (int k = 0; k < MAX_VCOLS; k++) {
                sb.append(',');
                if (k < v.length) sb.append(v[k]);
            }
            w.write(sb.append('\n').toString());
            rows++;
        }
    }

    // ================================================================
    // 3. own_.csv — the 23-field keepalive decode
    // ================================================================

    private void writeOwn(BufferedWriter w) throws Exception {
        StringBuilder h = new StringBuilder("seq,rel_ms,phase,decision,len,nfields");
        for (int i = 0; i < 23; i++) h.append(",v").append(i);
        h.append(",dirx,diry,dirz,dir_norm,yaw_deg,pitch_deg,tick_ms,"
                + "dt_ms,dx,dz,dy,speed_units_per_s,move_head_deg,head_minus_yaw\n");
        w.write(h.toString());
        long pts = -1;
        double px = 0, pz = 0, py = 0;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (!isKeepalive(r) || !whole(r)) continue;
            long[] v = varints(r.payload, Math.max(12, bodyOffset(r)), bodyEnd(r));
            StringBuilder sb = new StringBuilder(300);
            sb.append(i).append(',').append(r.timestampMs - t0).append(',')
              .append(r.phaseName()).append(',').append(r.decisionName()).append(',')
              .append(r.payloadLen).append(',').append(v == null ? -1 : v.length);
            if (v == null || v.length != 23) {
                for (int k = 0; k < 23 + 14; k++) sb.append(',');
                w.write(sb.append('\n').toString());
                continue;
            }
            for (int k = 0; k < 23; k++) sb.append(',').append(v[k]);
            float dx = f32(v[13]), dy = f32(v[14]), dz = f32(v[15]);
            double norm = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            double yaw = Math.toDegrees(Math.atan2(dx, dz));
            double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dy))));
            sb.append(',').append(fmt(dx)).append(',').append(fmt(dy)).append(',').append(fmt(dz))
              .append(',').append(fmt((float) norm)).append(',').append(f1(yaw))
              .append(',').append(f1(pitch)).append(',').append(v[22]);
            long ts = r.timestampMs;
            if (pts > 0) {
                long dt = ts - pts;
                double ddx = v[0] - px, ddz = v[1] - pz, ddy = v[2] - py;
                double sp = dt > 0 ? Math.sqrt(ddx * ddx + ddy * ddy) * 1000.0 / dt : 0;
                double mh = Math.toDegrees(Math.atan2(ddx, ddy));
                double diff = mh - yaw;
                while (diff < 0) diff += 360;
                while (diff >= 360) diff -= 360;
                sb.append(',').append(dt).append(',').append((long) ddx).append(',')
                  .append((long) ddz).append(',').append((long) ddy).append(',')
                  .append(f1(sp)).append(',').append(f1(mh)).append(',').append(f1(diff));
            } else {
                sb.append(",,,,,,,");
            }
            pts = ts; px = v[0]; pz = v[1]; py = v[2];
            w.write(sb.append('\n').toString());
        }
    }

    private void buildOwnTimeline() {
        ArrayList<long[]> ts = new ArrayList<>();
        ArrayList<double[]> vals = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (!isKeepalive(r) || !whole(r)) continue;
            long[] v = varints(r.payload, Math.max(12, bodyOffset(r)), bodyEnd(r));
            if (v == null || v.length != 23) continue;
            ts.add(new long[]{r.timestampMs});
            vals.add(new double[]{v[0], v[1], v[2], f32(v[13]), f32(v[14]), f32(v[15])});
        }
        ownTs = new long[ts.size()];
        ownVals = new double[vals.size()][];
        for (int i = 0; i < ts.size(); i++) { ownTs[i] = ts.get(i)[0]; ownVals[i] = vals.get(i); }
    }

    /** Nearest own-state sample to {@code t}, or null. */
    private double[] ownAt(long t) {
        if (ownTs.length == 0) return null;
        int lo = 0, hi = ownTs.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ownTs[mid] < t) lo = mid + 1; else hi = mid;
        }
        if (lo > 0 && Math.abs(ownTs[lo - 1] - t) < Math.abs(ownTs[lo] - t)) lo--;
        return ownVals[lo];
    }

    private long ownAtAge(long t) {
        if (ownTs.length == 0) return -1;
        int lo = 0, hi = ownTs.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ownTs[mid] < t) lo = mid + 1; else hi = mid;
        }
        if (lo > 0 && Math.abs(ownTs[lo - 1] - t) < Math.abs(ownTs[lo] - t)) lo--;
        return Math.abs(ownTs[lo] - t);
    }

    // ================================================================
    // 4/5. entities_.csv and pairs_.csv — the chain records
    // ================================================================

    /** One decoded entity record. */
    private static final class Rec {
        int seq; long ts; String phase; int pktLen; int declared; int idx;
        int id; long x, z, y; long[] extra; String hex; int startOff, endOff;
        /** Fixed-width pose bytes 7/8/9; only set by the structural parse. */
        int bodyYaw, aimYaw, aimPitch, mode, state;
        boolean structural;
    }

    /** Candidate id ceilings, tightest first (mirrors EspEngine). */
    private static final int[] ID_CEILINGS = { 8, 16, 32, 64 };
    /** Candidate Z floors, strictest first. */
    private static final int[] Z_FLOORS = { 500, 0 };
    /** Minimum X for a record start. */
    private static final long MIN_RECORD_X = 500L;

    /**
     * Splits one chain packet into entity records.
     *
     * <p><b>Rewritten 2026-09-11 to match the production parser.</b> This had
     * drifted badly behind {@code EspEngine} and was still carrying two
     * constants the engine had already replaced: {@code id <= 8} (the Clash
     * Squad roster) and {@code X >= 10000} (which deletes the pre-drop
     * aeroplane phase, where the whole lobby sits near map origin).</p>
     *
     * <p>Measured cost of that drift over 14 in-match sessions: the dumper
     * emitted <b>98 192 of 122 888 declared records (79.9%)</b> against the
     * engine's 99.9%, so <b>24 574 entity records — one in five — never
     * reached {@code entities_*.csv}</b>. In four sessions the loss was
     * 61-99.6%.</p>
     *
     * <p>Worse than the loss was the circularity: because the ceiling was 8,
     * the maximum {@code ent_id} in every dump was 8 <i>by construction</i>,
     * while the engine was seeing ids up to 61. Any census taken from these
     * dumps concluded that ids stop at 8 — which is exactly how the
     * game-mode portability bug hid for as long as it did.</p>
     *
     * <p>Two passes, structural first. The structural layout is authoritative
     * (§4.5): seven varints, then three FIXED-WIDTH pose bytes, then four more
     * varints. The heuristic scan is kept only as a fallback for packets the
     * structure does not fit.</p>
     */
    private List<Rec> parseChain(CaptureRecord r, int seq) {
        ArrayList<Rec> out = new ArrayList<>();
        int end = bodyEnd(r);
        if (end <= 10) return out;
        int declared = (r.payload[9] & 0xff) / 2;
        if (declared < 1 || declared > 64) return out;
        // Byte-fit floor: rejects the ECB transport, which reuses (b8,b9) as a
        // channel id and can collide with chain shape.
        if (r.payloadLen < 10 + declared * 16) return out;

        int recStart = skipVarints(r.payload, 8, end, 2);
        if (recStart < 0) return out;

        if (parseChainStructured(r, seq, declared, recStart, end, out)) return out;

        out.clear();
        long[] v = varints(r.payload, recStart, end);
        if (v == null || v.length < 4) return out;

        // Adaptive (id ceiling, Z floor): keep the first pair that reproduces
        // the free b9 record-count oracle. A fixed ceiling is wrong in both
        // directions - 8 finds nothing in survival, 64 regresses Clash 161940.
        int idMax = 0, zmin = 0;
        outer:
        for (int c = 0; c < ID_CEILINGS.length; c++) {
            for (int zi = 0; zi < Z_FLOORS.length; zi++) {
                int n = 0;
                for (int i = 0; i + 3 < v.length; i++) {
                    if (v[i] >= 1 && v[i] <= ID_CEILINGS[c]
                            && v[i + 1] >= MIN_RECORD_X && v[i + 2] >= Z_FLOORS[zi]) n++;
                }
                if (n == declared) { idMax = ID_CEILINGS[c]; zmin = Z_FLOORS[zi]; break outer; }
            }
        }
        if (idMax == 0) { idMax = ID_CEILINGS[ID_CEILINGS.length - 1]; zmin = Z_FLOORS[0]; }

        ArrayList<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 3 < v.length; i++) {
            if (v[i] >= 1 && v[i] <= idMax && v[i + 1] >= MIN_RECORD_X && v[i + 2] >= zmin) {
                starts.add(i);
            }
        }
        for (int k = 0; k < starts.size(); k++) {
            int st = starts.get(k);
            int e = (k + 1 < starts.size()) ? starts.get(k + 1) : v.length;
            Rec rec = new Rec();
            rec.seq = seq; rec.ts = r.timestampMs; rec.phase = r.phaseName();
            rec.pktLen = r.payloadLen; rec.declared = declared; rec.idx = k;
            rec.id = (int) v[st]; rec.x = v[st + 1]; rec.z = v[st + 2]; rec.y = v[st + 3];
            int nx = Math.max(0, Math.min(24, e - st - 4));
            rec.extra = new long[nx];
            for (int j = 0; j < nx; j++) rec.extra[j] = v[st + 4 + j];
            rec.startOff = st; rec.endOff = e;
            rec.hex = hexRange(r.payload, recStart, end);
            rec.structural = false;
            out.add(rec);
        }
        return out;
    }

    /**
     * Structural parse: seven varints, three fixed-width pose bytes, four
     * varints. Returns false without emitting anything if the layout does not
     * hold, so the caller can fall back.
     *
     * <p>The pose bytes matter for the dumps specifically: the old parser
     * varint-decoded them, so columns e3/e4/e5 were noise and <b>no pose data
     * existed offline at all</b>. Body yaw, aim yaw and aim pitch are now
     * emitted as their own columns.</p>
     */
    private boolean parseChainStructured(CaptureRecord r, int seq, int declared,
                                         int start, int end, List<Rec> out) {
        int[] pos = { start };
        ArrayList<Rec> tmp = new ArrayList<>();
        for (int k = 0; k < declared; k++) {
            long id = vi(r.payload, pos, end);
            if (id < 1 || id > 64) return false;
            long x = vi(r.payload, pos, end);
            long z = vi(r.payload, pos, end);
            long y = vi(r.payload, pos, end);
            if (x < MIN_RECORD_X) return false;
            Rec rec = new Rec();
            rec.seq = seq; rec.ts = r.timestampMs; rec.phase = r.phaseName();
            rec.pktLen = r.payloadLen; rec.declared = declared; rec.idx = k;
            rec.id = (int) id; rec.x = x; rec.z = z; rec.y = y;
            rec.startOff = pos[0]; rec.structural = true;

            long vx = vi(r.payload, pos, end);
            long vz = vi(r.payload, pos, end);
            long vy = vi(r.payload, pos, end);
            if (pos[0] + 3 > end) return false;
            rec.bodyYaw = r.payload[pos[0]] & 0xff;
            rec.aimYaw = r.payload[pos[0] + 1] & 0xff;
            rec.aimPitch = r.payload[pos[0] + 2];        // signed
            pos[0] += 3;
            long mode = vi(r.payload, pos, end);
            long state = vi(r.payload, pos, end);
            vi(r.payload, pos, end);
            vi(r.payload, pos, end);
            if (pos[0] > end) return false;
            rec.mode = (int) mode; rec.state = (int) state;
            rec.extra = new long[] { vx, vz, vy };
            rec.endOff = pos[0];
            rec.hex = "";
            tmp.add(rec);
        }
        int left = end - pos[0];
        if (left < 1 || left > 3) return false;      // must end on the tick varint
        out.addAll(tmp);
        return true;
    }

    /** Zigzag decode (protobuf sint32): the encoding of every numeric body field. */
    /**
     * Zigzag decode. The shift MUST be {@code >>>}, not {@code >>}.
     *
     * <p>This was a literal transliteration of the Python reference in
     * {@code docs/PROTOCOL_MASTER.md} §2.1, which is correct in Python because
     * its integers are unbounded, and wrong in Java for any value with bit 63
     * set: an arithmetic shift drags the sign bit down and the result is
     * negative where it must be positive. Five sibling copies in this repo all
     * use {@code >>>}.</p>
     *
     * <p>Unreachable today -- the trailing-tick check rejects truncated records
     * before any 64-bit-wide value reaches here -- so this is a latent bug
     * fixed, not a behaviour change.</p>
     */
    static long zz(long v) { return (v >>> 1) ^ -(v & 1); }

    /** Cursor-based varint read; parks the cursor and returns -1 on failure. */
    private static long vi(byte[] d, int[] pos, int end) {
        long v = 0; int sh = 0; int i = pos[0];
        while (i < end) {
            int c = d[i++] & 0xff;
            v |= (long) (c & 0x7f) << sh;
            if ((c & 0x80) == 0) { pos[0] = i; return v; }
            sh += 7;
            if (sh > 63) break;
        }
        pos[0] = end;
        return -1;
    }

    /** Hex of a byte range, for the rec_hex column that was never filled. */
    private static String hexRange(byte[] d, int from, int to) {
        if (d == null || from < 0 || to > d.length || from >= to) return "";
        StringBuilder sb = new StringBuilder((to - from) * 2);
        for (int i = from; i < to && i - from < 96; i++) sb.append(hex2(d[i] & 0xff));
        return sb.toString();
    }


    private void writeEntities(BufferedWriter w) throws Exception {
        StringBuilder h = new StringBuilder("seq,rel_ms,phase,pkt_len,declared_recs,rec_idx,"
                + "ent_id,x,z,y,x_zz,z_zz,y_zz,structural,"
                + "body_yaw_deg,aim_yaw_deg,aim_pitch_deg,mode,state,"
                + "n_extra,start_off,end_off,rec_bytes,plausible");
        for (int i = 0; i < 24; i++) h.append(",e").append(i);
        w.write(h.append(",rec_hex\n").toString());
        int rows = 0;
        for (int i = 0; i < records.size() && rows < MAX_ROWS; i++) {
            CaptureRecord r = records.get(i);
            if (!isChain(r)) continue;
            List<Rec> rs = parseChain(r, i);
            for (int k = 0; k < rs.size(); k++) {
                Rec rec = rs.get(k);
                // Was `rec.x >= 10000`, which deleted the pre-drop aeroplane
                // phase a second time after parseChain had already dropped it.
                boolean plaus = rec.x >= 500 && !(rec.z < 1000 && rec.y < 1000);
                StringBuilder sb = new StringBuilder(320);
                sb.append(rec.seq).append(',').append(rec.ts - t0).append(',')
                  .append(rec.phase).append(',').append(rec.pktLen).append(',')
                  .append(rec.declared).append(',').append(rec.idx).append(',')
                  .append(rec.id).append(',').append(rec.x).append(',')
                  .append(rec.z).append(',').append(rec.y).append(',')
                  // Emit BOTH raw and zigzag-decoded. The dumps used to carry
                  // raw only, with the encoding recorded in a doc rather than
                  // the file, and every analysis that forgot cost a cycle.
                  .append(zz(rec.x)).append(',')
                  .append(zz(rec.z)).append(',')
                  .append(zz(rec.y)).append(',')
                  .append(rec.structural ? 1 : 0).append(',');
                if (rec.structural) {
                    sb.append(String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,",
                            rec.bodyYaw * (360.0 / 256.0),
                            rec.aimYaw * (360.0 / 256.0),
                            rec.aimPitch * 1.29))
                      .append(rec.mode).append(',').append(rec.state);
                } else {
                    sb.append(",,,,");
                }
                sb.append(',')
                  .append(rec.extra.length).append(',').append(rec.startOff).append(',')
                  .append(rec.endOff).append(',').append(rec.endOff - rec.startOff).append(',')
                  .append(plaus ? 1 : 0);
                for (int e = 0; e < 24; e++) {
                    sb.append(',');
                    if (e < rec.extra.length) sb.append(rec.extra[e]);
                }
                w.write(sb.append(',').append(rec.hex).append('\n').toString());
                rows++;
            }
        }
    }

    /**
     * The frame-solving dataset: every plausible entity record joined to the
     * nearest own-state sample. Offline this answers whether enemy
     * coordinates share the own frame (and at what scale/offset).
     */
    private void writePairs(BufferedWriter w) throws Exception {
        w.write("rel_ms,ent_id,ex,ez,ey,own_x,own_z,own_y,own_age_ms,"
                + "dx,dz,dy,dist_xy,bearing_deg,own_dirx,own_diry,own_dirz,own_yaw_deg,"
                + "rel_bearing_deg,ez_minus_ownz\n");
        int rows = 0;
        for (int i = 0; i < records.size() && rows < MAX_ROWS; i++) {
            CaptureRecord r = records.get(i);
            if (!isChain(r)) continue;
            double[] o = ownAt(r.timestampMs);
            if (o == null) continue;
            long age = ownAtAge(r.timestampMs);
            List<Rec> rs = parseChain(r, i);
            for (int k = 0; k < rs.size(); k++) {
                Rec rec = rs.get(k);
                if (rec.x < 10000 || (rec.z < 1000 && rec.y < 1000)) continue;
                double dx = rec.x - o[0], dz = rec.z - o[1], dy = rec.y - o[2];
                double dist = Math.sqrt(dx * dx + dy * dy);
                double brg = Math.toDegrees(Math.atan2(dx, dy));
                if (brg < 0) brg += 360;
                double yaw = Math.toDegrees(Math.atan2(o[3], o[5]));
                double rel = brg - yaw;
                while (rel < -180) rel += 360;
                while (rel >= 180) rel -= 360;
                StringBuilder sb = new StringBuilder(220);
                sb.append(rec.ts - t0).append(',').append(rec.id).append(',')
                  .append(rec.x).append(',').append(rec.z).append(',').append(rec.y).append(',')
                  .append((long) o[0]).append(',').append((long) o[1]).append(',')
                  .append((long) o[2]).append(',').append(age).append(',')
                  .append((long) dx).append(',').append((long) dz).append(',')
                  .append((long) dy).append(',').append((long) dist).append(',')
                  .append(f1(brg)).append(',').append(fmt((float) o[3])).append(',')
                  .append(fmt((float) o[4])).append(',').append(fmt((float) o[5])).append(',')
                  .append(f1(yaw)).append(',').append(f1(rel)).append(',')
                  .append((long) dz).append('\n');
                w.write(sb.toString());
                rows++;
            }
        }
    }

    // ================================================================
    // 6. bytemap_.txt — per-family per-offset behaviour
    // ================================================================

    private void writeByteMap(BufferedWriter w) throws Exception {
        Map<String, List<CaptureRecord>> byFam = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (r.payload.length < 10) continue;
            String k = familyKey(r) + ":len" + r.payloadLen;
            List<CaptureRecord> l = byFam.get(k);
            if (l == null) { l = new ArrayList<>(); byFam.put(k, l); }
            if (l.size() < 4000) l.add(r);
        }
        List<Map.Entry<String, List<CaptureRecord>>> es = new ArrayList<>(byFam.entrySet());
        Collections.sort(es, new Comparator<Map.Entry<String, List<CaptureRecord>>>() {
            public int compare(Map.Entry<String, List<CaptureRecord>> a,
                               Map.Entry<String, List<CaptureRecord>> b) {
                return b.getValue().size() - a.getValue().size();
            }
        });
        w.write("# Per-offset byte behaviour per (family, length).\n");
        w.write("# Legend per offset: '.' = constant, 1-9/a-f = that many distinct\n");
        w.write("#   values (minus one), '#' = 17+ distinct. Second line = percent of\n");
        w.write("#   consecutive packets in which the byte CHANGED (0-9, each = 10%).\n");
        w.write("# Offsets 0-1 are counter/nonce; 2-7 are the masked header window;\n");
        w.write("# 8-11 the connection token; 12+ the varint body.\n\n");
        int fam = 0;
        for (int e = 0; e < es.size() && fam < BYTEMAP_FAMILIES; e++) {
            List<CaptureRecord> l = es.get(e).getValue();
            if (l.size() < 8) continue;
            fam++;
            int n = 0;
            for (int i = 0; i < l.size(); i++) n = Math.max(n, Math.min(l.get(i).payload.length, BYTEMAP_OFFSETS));
            boolean[][] seen = new boolean[n][256];
            int[] distinct = new int[n];
            int[] changed = new int[n];
            int pairs = 0;
            byte[] prev = null;
            for (int i = 0; i < l.size(); i++) {
                byte[] p = l.get(i).payload;
                for (int o = 0; o < n && o < p.length; o++) {
                    int b = p[o] & 0xff;
                    if (!seen[o][b]) { seen[o][b] = true; distinct[o]++; }
                }
                if (prev != null) {
                    pairs++;
                    for (int o = 0; o < n && o < p.length && o < prev.length; o++) {
                        if (p[o] != prev[o]) changed[o]++;
                    }
                }
                prev = p;
            }
            w.write("== " + es.get(e).getKey() + "  n=" + l.size() + "\n");
            StringBuilder a = new StringBuilder("   distinct: ");
            StringBuilder b = new StringBuilder("   change% : ");
            for (int o = 0; o < n; o++) {
                int d = distinct[o];
                a.append(d <= 1 ? '.' : (d <= 16 ? "0123456789abcdef".charAt(d - 1) : '#'));
                int pct = pairs == 0 ? 0 : (int) (10L * changed[o] / pairs);
                b.append(pct <= 0 ? '.' : (char) ('0' + Math.min(9, pct)));
            }
            w.write(a.append('\n').toString());
            w.write(b.append('\n').toString());
            w.write("   ruler:    ");
            for (int o = 0; o < n; o++) w.write(o % 10 == 0 ? String.valueOf((o / 10) % 10) : ".");
            w.write("\n   sample:   " + hexOf(l.get(0).payload, 0, Math.min(n, 64)) + "\n\n");
        }
    }

    // ================================================================
    // 7. unknown_.txt — full hex of everything that never decodes
    // ================================================================

    private void writeUnknown(BufferedWriter w) throws Exception {
        Map<String, int[]> stat = new LinkedHashMap<>();     // ok, fail, total
        Map<String, List<CaptureRecord>> samples = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (r.payload.length < 12) continue;
            String k = familyKey(r);
            int[] st = stat.get(k);
            if (st == null) { st = new int[3]; stat.put(k, st); }
            st[2]++;
            boolean ok = whole(r) && varints(r.payload, Math.max(12, bodyOffset(r)), bodyEnd(r)) != null;
            if (ok) st[0]++; else st[1]++;
            List<CaptureRecord> l = samples.get(k);
            if (l == null) { l = new ArrayList<>(); samples.put(k, l); }
            if (l.size() < UNKNOWN_SAMPLES) l.add(r);
        }
        w.write("# Families that rarely or never parse as a varint body — i.e. the\n");
        w.write("# encrypted/opaque ones. Full payload hex so nothing is invisible.\n");
        w.write("# A ~50% parse rate is what random bytes give; <20% means keystream.\n\n");
        for (Map.Entry<String, int[]> e : stat.entrySet()) {
            int[] st = e.getValue();
            if (st[2] < 4) continue;
            double rate = (double) st[0] / st[2];
            if (rate > 0.80) continue;   // decoded families are covered elsewhere
            w.write("== " + e.getKey() + "  n=" + st[2]
                    + "  varint_ok=" + st[0] + " (" + (int) (100 * rate) + "%)\n");
            List<CaptureRecord> l = samples.get(e.getKey());
            for (int i = 0; i < l.size(); i++) {
                CaptureRecord r = l.get(i);
                w.write("   +" + (r.timestampMs - t0) + "ms " + r.dirName()
                        + " len=" + r.payloadLen + " vis=" + r.payload.length
                        + " " + r.kindName() + "\n      "
                        + hexOf(r.payload, 0, r.payload.length) + "\n");
            }
            w.write("\n");
        }
    }

    // ================================================================
    // 8. summary_.txt
    // ================================================================

    private void writeSummary(BufferedWriter w) throws Exception {
        long span = records.isEmpty() ? 0
                : records.get(records.size() - 1).timestampMs - t0;
        w.write("# Verbose decoded capture summary\n");
        w.write("# records=" + records.size() + " dropped_by_cap=" + droppedByCap
                + " span_ms=" + span + "\n\n");

        // ---- mask + rotation ----
        if (mask == null) {
            w.write("MASK: NOT RECOVERABLE (too few OUT/24 samples)\n");
        } else {
            StringBuilder m = new StringBuilder();
            for (int i = 0; i < 6; i++) m.append(hex2(mask[i]));
            w.write("MASK: " + m + "  (min mode share " + maskMinPct + "% over "
                    + maskSamples + " OUT/24 samples)\n");
            w.write("HEADER ROTATION: length16 low byte at b" + rotation
                    + "  (" + rotationHits + "/" + rotationTests
                    + " packets satisfy decoded == len+8)\n");
        }

        // ---- truncation ----
        int trunc = 0, trWorst = 0;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (!whole(r)) { trunc++; trWorst = Math.max(trWorst, r.payloadLen - r.payload.length); }
        }
        w.write("TRUNCATION: " + trunc + " of " + records.size()
                + " packets clipped (worst " + trWorst + " bytes missing)\n");

        // ---- phase timeline ----
        w.write("\nPHASE TIMELINE (effect changes):\n");
        String cur = null;
        for (int i = 0; i < records.size(); i++) {
            String p = records.get(i).phaseName();
            if (!p.equals(cur)) {
                w.write("  +" + ((records.get(i).timestampMs - t0) / 1000) + "s  " + p + "\n");
                cur = p;
            }
        }
        int[] dec = new int[3];
        for (int i = 0; i < records.size(); i++) {
            switch (records.get(i).decision) {
                case PASS: dec[0]++; break;
                case DROP: dec[1]++; break;
                default: dec[2]++; break;
            }
        }
        w.write("  decisions: PASS=" + dec[0] + " DROP=" + dec[1] + " HOLD=" + dec[2] + "\n");

        // ---- family census with parse rates ----
        w.write("\nFAMILY CENSUS (famkey/len: n, varint-parse rate at b12):\n");
        TreeMap<String, int[]> fam = new TreeMap<>();
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            String k = familyKey(r) + "/len" + r.payloadLen;
            int[] st = fam.get(k);
            if (st == null) { st = new int[2]; fam.put(k, st); }
            st[0]++;
            if (whole(r) && r.payload.length > 12 && varints(r.payload, Math.max(12, bodyOffset(r)), bodyEnd(r)) != null) st[1]++;
        }
        for (Map.Entry<String, int[]> e : fam.entrySet()) {
            int[] st = e.getValue();
            if (st[0] < 3) continue;
            w.write("  " + e.getKey() + ": " + st[0]
                    + "  parse=" + (int) (100.0 * st[1] / st[0]) + "%\n");
        }

        // ---- own state ----
        w.write("\nOWN STATE (OUT " + KA_MIN + "-" + KA_MAX + " B keepalive):\n");
        int ok = 0, bad = 0, unit = 0;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double maxSpeed = 0, pathLen = 0;
        long prevTick = -1; int tickSteps = 0, tickGood = 0;
        double px = 0, py = 0; long pts = -1;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (!isKeepalive(r)) continue;
            long[] v = whole(r) ? varints(r.payload, Math.max(12, bodyOffset(r)), bodyEnd(r)) : null;
            if (v == null || v.length != 23) { bad++; continue; }
            ok++;
            float dx = f32(v[13]), dy = f32(v[14]), dz = f32(v[15]);
            double nn = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            if (Math.abs(nn - 1.0) < 0.001) unit++;
            minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]);
            minZ = Math.min(minZ, v[1]); maxZ = Math.max(maxZ, v[1]);
            minY = Math.min(minY, v[2]); maxY = Math.max(maxY, v[2]);
            if (prevTick >= 0) { tickSteps++; if (v[22] - prevTick > 0 && v[22] - prevTick < 400) tickGood++; }
            prevTick = v[22];
            if (pts > 0) {
                long dt = r.timestampMs - pts;
                double d = Math.hypot(v[0] - px, v[2] - py);
                if (d < 200000) pathLen += d;
                if (dt > 0) maxSpeed = Math.max(maxSpeed, d * 1000.0 / dt);
            }
            px = v[0]; py = v[2]; pts = r.timestampMs;
        }
        w.write("  23-varint parse: " + ok + " ok / " + bad + " bad\n");
        w.write("  |dir| == 1.000: " + unit + " / " + ok
                + (ok > 0 ? "  (" + (int) (100.0 * unit / ok) + "%)" : "") + "\n");
        if (ok > 0) {
            w.write("  X range " + (long) minX + ".." + (long) maxX
                    + "   Z(height) " + (long) minZ + ".." + (long) maxZ
                    + "   Y " + (long) minY + ".." + (long) maxY + "\n");
            w.write("  path length " + (long) pathLen + " units, peak speed "
                    + (long) maxSpeed + " units/s\n");
            w.write("  tick monotonic: " + tickGood + "/" + tickSteps + "\n");
        }

        // ---- entity census ----
        w.write("\nENTITY CENSUS (CHAIN0001 records):\n");
        Map<Integer, long[]> ents = new TreeMap<>();   // id -> n, first, last, frozen, moved
        Map<Integer, double[]> rng = new HashMap<>();  // id -> minX,maxX,minZ,maxZ,minY,maxY,path
        Map<Integer, long[]> last = new HashMap<>();
        Map<Integer, Long> lastTs = new HashMap<>();
        long maxGap = 0;
        int chainPkts = 0, recTotal = 0, declaredTotal = 0, declaredMatch = 0;
        for (int i = 0; i < records.size(); i++) {
            CaptureRecord r = records.get(i);
            if (!isChain(r)) continue;
            chainPkts++;
            int declared = (r.payload[9] & 0xff) / 2;
            List<Rec> rs = parseChain(r, i);
            declaredTotal++;
            if (rs.size() == declared) declaredMatch++;
            for (int k = 0; k < rs.size(); k++) {
                Rec rec = rs.get(k);
                if (rec.x < 10000 || (rec.z < 1000 && rec.y < 1000)) continue;
                recTotal++;
                long[] st = ents.get(rec.id);
                if (st == null) { st = new long[]{0, rec.ts, rec.ts, 0, 0}; ents.put(rec.id, st); }
                st[0]++; st[2] = rec.ts;
                double[] rr = rng.get(rec.id);
                if (rr == null) {
                    rr = new double[]{rec.x, rec.x, rec.z, rec.z, rec.y, rec.y, 0};
                    rng.put(rec.id, rr);
                } else {
                    rr[0] = Math.min(rr[0], rec.x); rr[1] = Math.max(rr[1], rec.x);
                    rr[2] = Math.min(rr[2], rec.z); rr[3] = Math.max(rr[3], rec.z);
                    rr[4] = Math.min(rr[4], rec.y); rr[5] = Math.max(rr[5], rec.y);
                }
                long[] lp = last.get(rec.id);
                if (lp != null) {
                    double d = Math.hypot(rec.x - lp[0], rec.y - lp[1]);
                    if (d == 0) st[3]++; else st[4]++;
                    if (d < 200000) rr[6] += d;
                }
                last.put(rec.id, new long[]{rec.x, rec.y});
                Long lt = lastTs.get(rec.id);
                if (lt != null) maxGap = Math.max(maxGap, rec.ts - lt);
                lastTs.put(rec.id, rec.ts);
            }
        }
        w.write("  chain packets " + chainPkts + ", records " + recTotal
                + ", b9 record-count oracle matched " + declaredMatch + "/" + declaredTotal + "\n");
        w.write("  id   n     rate_hz  span_s   frozen%  X range              Z range        path\n");
        for (Map.Entry<Integer, long[]> e : ents.entrySet()) {
            long[] st = e.getValue();
            double[] rr = rng.get(e.getKey());
            double sp = (st[2] - st[1]) / 1000.0;
            long tot = st[3] + st[4];
            w.write(String.format(Locale.US,
                    "  %-4d %-5d %-8.2f %-8.1f %-8.0f %-9d..%-9d %-6d..%-6d %d\n",
                    e.getKey(), st[0], sp > 0 ? st[0] / sp : 0, sp,
                    tot > 0 ? 100.0 * st[3] / tot : 0,
                    (long) rr[0], (long) rr[1], (long) rr[2], (long) rr[3], (long) rr[6]));
        }

        // ---- the frame question, scored ----
        w.write("\nENEMY-vs-OWN FRAME CHECK (the ESP blocker):\n");
        if (ok > 0 && !ents.isEmpty()) {
            double eMinX = Double.MAX_VALUE, eMaxX = -Double.MAX_VALUE;
            double eMinZ = Double.MAX_VALUE, eMaxZ = -Double.MAX_VALUE;
            double eMinY = Double.MAX_VALUE, eMaxY = -Double.MAX_VALUE;
            for (double[] rr : rng.values()) {
                eMinX = Math.min(eMinX, rr[0]); eMaxX = Math.max(eMaxX, rr[1]);
                eMinZ = Math.min(eMinZ, rr[2]); eMaxZ = Math.max(eMaxZ, rr[3]);
                eMinY = Math.min(eMinY, rr[4]); eMaxY = Math.max(eMaxY, rr[5]);
            }
            w.write("  own   X " + (long) minX + ".." + (long) maxX
                    + "  Z " + (long) minZ + ".." + (long) maxZ
                    + "  Y " + (long) minY + ".." + (long) maxY + "\n");
            w.write("  enemy X " + (long) eMinX + ".." + (long) eMaxX
                    + "  Z " + (long) eMinZ + ".." + (long) eMaxZ
                    + "  Y " + (long) eMinY + ".." + (long) eMaxY + "\n");
            double sx = (maxX - minX) > 0 ? (eMaxX - eMinX) / (maxX - minX) : 0;
            double sz = (maxZ - minZ) > 0 ? (eMaxZ - eMinZ) / (maxZ - minZ) : 0;
            double sy = (maxY - minY) > 0 ? (eMaxY - eMinY) / (maxY - minY) : 0;
            w.write(String.format(Locale.US,
                    "  span ratio enemy/own: X %.3f  Z %.3f  Y %.3f"
                    + "  (all ~1.0 => same frame and scale)\n", sx, sz, sy));
            w.write("  -> settle it in pairs_*.csv: if the frames match, the nearest\n");
            w.write("     entity's dist_xy should collapse toward 0 during a close fight.\n");
        } else {
            w.write("  not enough data (need both keepalives and chain records)\n");
        }

        // ---- open-question scoreboard ----
        w.write("\nOPEN QUESTIONS — status from THIS capture:\n");
        w.write("  own position          " + (ok > 100 ? "OK (" + ok + " samples)" : "WEAK") + "\n");
        w.write("  view direction        " + (unit > 0.99 * ok && ok > 100
                ? "OK (unit vector " + (int) (100.0 * unit / Math.max(1, ok)) + "%)" : "WEAK") + "\n");
        // The b9 record-count oracle is free and authoritative. It scored
        // 0/1624 in two sessions while this line printed OK purely because
        // recTotal > 500 — the one check that would have caught the biggest
        // bug in this file was present, correct, and outvoted. It gates now.
        double oracle = declaredTotal > 0 ? (double) declaredMatch / declaredTotal : 0.0;
        String posVerdict = (oracle < 0.95)
                ? "FAIL (b9 oracle " + declaredMatch + "/" + declaredTotal
                  + " = " + String.format(java.util.Locale.US, "%.1f%%", 100 * oracle)
                  + " - records are being dropped or mis-split)"
                : (recTotal > 500 ? "OK (" + recTotal + " records)" : "WEAK");
        w.write("  enemy positions       " + posVerdict + "\n");
        w.write("  enemy<->own frame     see the span ratios above + pairs_*.csv\n");
        w.write("  friend/foe            unresolved — needs a squadmate-follow window\n");
        w.write("  units per metre       unresolved — needs a known-distance walk\n");
        w.write("  FOV / projection      unresolved — needs a centred-target sighting\n");
        w.write("  full payload capture  " + (trunc == 0 ? "OK (nothing clipped)"
                : "PARTIAL (" + trunc + " clipped)") + "\n");
    }

    // ================================================================
    // helpers
    // ================================================================

    private static String fmt(float f) {
        return String.format(Locale.US, "%.5f", f);
    }

    private static String f1(double d) {
        return String.format(Locale.US, "%.2f", d);
    }
}
