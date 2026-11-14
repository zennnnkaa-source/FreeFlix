package com.nepdon.wire.dev.capture;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes a packet capture and answers: "what changed when freeze was turned
 * on?". It splits the records into BEFORE (freeze off) and FREEZE (freeze on)
 * phases and diffs them along the axes that matter for the freeze effect:
 *
 * <ul>
 *   <li>Volume: packets/sec and bytes/sec per direction.</li>
 *   <li>Fate: how many packets PASS vs DROP vs HOLD in each phase.</li>
 *   <li>Drop profile: which payload-length ranges got dropped inbound (the
 *       freeze policy drops inbound packets of length 0x15..0x1c2).</li>
   *   <li>Pattern fingerprint: for each (dir, payloadLen) shape, the packet rate
 *       in each phase — showing which game traffic shapes freeze kills or lets
 *       through.</li>
 *   <li>Directional flow: per (dip, dp) destination rates in each phase.</li>
 * </ul>
 */
public final class FreezeDiffAnalyzer {

    private static final class Bucket {
        long packets;
        long bytes;
        long pass;
        long drop;
        long hold;
        final HashMap<Integer, Long> dropByLen = new HashMap<>();
        final HashMap<String, Long> shapeRate = new HashMap<>();
        final HashMap<String, Long> destRate = new HashMap<>();
        /** Per event-kind counts: key "dir/family/kind" → [pass, drop, hold]. */
        final HashMap<String, long[]> kindStats = new HashMap<>();

        void add(CaptureRecord r, long spanMs) {
            packets++;
            bytes += r.payloadLen;
            switch (r.decision) {
                case PASS: pass++; break;
                case DROP: drop++; break;
                case HOLD: hold++; break;
                default: break;
            }
            if (r.decision == CaptureRecord.Decision.DROP) {
                Long old = dropByLen.get(r.payloadLen);
                dropByLen.put(r.payloadLen, old == null ? 1L : old + 1L);
            }
            String shape = r.dirName() + "/" + r.payloadLen;
            Long oldShape = shapeRate.get(shape);
            shapeRate.put(shape, oldShape == null ? 1L : oldShape + 1L);
            String dest = r.dirName() + "->" + ipToString(r.dip) + ":" + r.dp;
            Long oldDest = destRate.get(dest);
            destRate.put(dest, oldDest == null ? 1L : oldDest + 1L);
            String key = r.dirName() + "/" + r.familyName() + "/" + r.kindName();
            long[] st = kindStats.get(key);
            if (st == null) {
                st = new long[3];
                kindStats.put(key, st);
            }
            switch (r.decision) {
                case PASS: st[0]++; break;
                case DROP: st[1]++; break;
                case HOLD: st[2]++; break;
                default: break;
            }
        }
    }

    private final List<CaptureRecord> records;
    private final long droppedByCap;

    public FreezeDiffAnalyzer(List<CaptureRecord> records, long droppedByCap) {
        this.records = records;
        this.droppedByCap = droppedByCap;
    }

    /** Runs the diff and writes a human-readable report; returns the file (or null). */
    public File analyze(File dir) {
        Bucket before = new Bucket();
        Bucket freeze = new Bucket();
        long beforeStart = -1, beforeEnd = -1;
        long freezeStart = -1, freezeEnd = -1;

        for (CaptureRecord r : records) {
            if (r.phase == CaptureRecord.Phase.FREEZE) {
                if (freezeStart < 0) freezeStart = r.timestampMs;
                freezeEnd = r.timestampMs;
                freeze.add(r, 0);
            } else if (r.phase == CaptureRecord.Phase.BEFORE) {
                if (beforeStart < 0) beforeStart = r.timestampMs;
                beforeEnd = r.timestampMs;
                before.add(r, 0);
            }
        }

        long beforeSpan = Math.max(1, beforeEnd - beforeStart);
        long freezeSpan = Math.max(1, freezeEnd - freezeStart);

        File out = new File(dir, "freeze_diff_" + stamp() + ".txt");
        try {
            if (!dir.exists()) dir.mkdirs();
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(out), "UTF-8"), 1 << 16);
            try {
                w.write("FREEZE DIFF REPORT\n");
                w.write("records=" + records.size() + " dropped_by_cap=" + droppedByCap + "\n\n");

                w.write("== PHASE WINDOWS ==\n");
                w.write("BEFORE: " + before.packets + " pkts over " + beforeSpan + " ms\n");
                w.write("FREEZE: " + freeze.packets + " pkts over " + freezeSpan + " ms\n\n");

                w.write("== VOLUME (per second) ==\n");
                writeVolume(w, "BEFORE", before, beforeSpan);
                writeVolume(w, "FREEZE", freeze, freezeSpan);
                writeVolumeDiff(w, before, beforeSpan, freeze, freezeSpan);

                w.write("\n== FATE (how the engine treated packets) ==\n");
                writeFate(w, "BEFORE", before);
                writeFate(w, "FREEZE", freeze);

                w.write("\n== PACKET EVENTS (which packet is which; per event kind) ==\n");
                w.write("legend: R=reliability(heartbeat/ACK/timers) S=state-sync(enemy/world) "
                        + "C=control/session\n");
                writeKindStats(w, before, beforeSpan, freeze, freezeSpan);

                w.write("\n== HEARTBEAT HEALTH CHECK (freeze must not touch family R) ==\n");
                writeHeartbeatCheck(w, before, freeze);

                w.write("\n== COMBAT LOG (your hits: zone, damage, weapon) ==\n");
                writeCombatLog(w, records);

                w.write("\n== INBOUND DROP PROFILE BY PAYLOAD LENGTH (FREEZE phase) ==\n");
                if (freeze.dropByLen.isEmpty()) {
                    w.write("(no inbound drops recorded)\n");
                } else {
                    List<Map.Entry<Integer, Long>> entries =
                            new ArrayList<>(freeze.dropByLen.entrySet());
                    entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                    for (Map.Entry<Integer, Long> e : entries) {
                        w.write("len=" + e.getKey() + " dropped=" + e.getValue() + "\n");
                    }
                }

                w.write("\n== TRAFFIC SHAPES: BEFORE vs FREEZE (packets per second) ==\n");
                writeShapes(w, before, beforeSpan, freeze, freezeSpan);

                w.write("\n== DESTINATIONS: BEFORE vs FREEZE (packets per second) ==\n");
                writeDests(w, before, beforeSpan, freeze, freezeSpan);

                w.write("\n== INTERPRETATION ==\n");
                writeInterpretation(w, before, beforeSpan, freeze, freezeSpan);
            } finally {
                w.close();
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    // ---- Report sections ----

    private static void writeVolume(BufferedWriter w, String label, Bucket b, long spanMs) throws Exception {
        double pps = b.packets * 1000.0 / spanMs;
        double bps = b.bytes * 1000.0 / spanMs;
        w.write(label + ": " + String.format("%.1f", pps) + " pkt/s, "
                + String.format("%.0f", bps) + " B/s\n");
    }

    private static void writeVolumeDiff(BufferedWriter w, Bucket before, long beforeSpan,
                                        Bucket freeze, long freezeSpan) throws Exception {
        double beforePps = before.packets * 1000.0 / beforeSpan;
        double freezePps = freeze.packets * 1000.0 / freezeSpan;
        if (beforePps > 0) {
            double pct = (freezePps - beforePps) / beforePps * 100.0;
            w.write(String.format("DELTA: %+.1f%% total packet rate (freeze vs before)%n", pct));
        }
    }

    private static void writeFate(BufferedWriter w, String label, Bucket b) throws Exception {
        w.write(label + ": PASS=" + b.pass + " DROP=" + b.drop + " HOLD=" + b.hold);
        if (b.packets > 0) {
            w.write(String.format(" (drop rate %.1f%%)", b.drop * 100.0 / b.packets));
        }
        w.write("\n");
    }

    private static void writeShapes(BufferedWriter w, Bucket before, long beforeSpan,
                                    Bucket freeze, long freezeSpan) throws Exception {
        HashMap<String, Long> merged = new HashMap<>(before.shapeRate);
        for (Map.Entry<String, Long> e : freeze.shapeRate.entrySet()) {
            Long old = merged.get(e.getKey());
            merged.put(e.getKey(), old == null ? e.getValue() : old + e.getValue());
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(merged.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Long> e : entries) {
            if (shown++ >= 30) {
                w.write("... (" + (entries.size() - 30) + " more shapes)\n");
                break;
            }
            String shape = e.getKey();
            double beforePps = count(before.shapeRate, shape) * 1000.0 / beforeSpan;
            double freezePps = count(freeze.shapeRate, shape) * 1000.0 / freezeSpan;
            w.write(String.format("%-18s before=%8.1f/s freeze=%8.1f/s%n", shape, beforePps, freezePps));
        }
    }

    private static void writeDests(BufferedWriter w, Bucket before, long beforeSpan,
                                   Bucket freeze, long freezeSpan) throws Exception {
        HashMap<String, Long> merged = new HashMap<>(before.destRate);
        for (Map.Entry<String, Long> e : freeze.destRate.entrySet()) {
            Long old = merged.get(e.getKey());
            merged.put(e.getKey(), old == null ? e.getValue() : old + e.getValue());
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(merged.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Long> e : entries) {
            if (shown++ >= 15) {
                w.write("... (" + (entries.size() - 15) + " more destinations)\n");
                break;
            }
            String dest = e.getKey();
            double beforePps = count(before.destRate, dest) * 1000.0 / beforeSpan;
            double freezePps = count(freeze.destRate, dest) * 1000.0 / freezeSpan;
            w.write(String.format("%-40s before=%8.1f/s freeze=%8.1f/s%n", dest, beforePps, freezePps));
        }
    }

    private static void writeKindStats(BufferedWriter w, Bucket before, long beforeSpan,
                                       Bucket freeze, long freezeSpan) throws Exception {
        HashMap<String, long[]> merged = new HashMap<>(before.kindStats);
        for (Map.Entry<String, long[]> e : freeze.kindStats.entrySet()) {
            long[] b = merged.get(e.getKey());
            if (b == null) {
                merged.put(e.getKey(), e.getValue());
            } else {
                b[0] += e.getValue()[0];
                b[1] += e.getValue()[1];
                b[2] += e.getValue()[2];
            }
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(merged.entrySet());
        // Sort by combined FREEZE-phase activity so the kinds freeze acts on
        // surface first.
        entries.sort((a, b) -> Long.compare(
                countKind(freeze.kindStats, b.getKey()),
                countKind(freeze.kindStats, a.getKey())));
        w.write(String.format("%-34s %10s %10s %8s %8s%n",
                "dir/family/kind", "BF_rate/s", "FR_rate/s", "FR_pass", "FR_drop"));
        for (Map.Entry<String, long[]> e : entries) {
            String key = e.getKey();
            long bf = countKind(before.kindStats, key);
            long fr = countKind(freeze.kindStats, key);
            long[] frSplit = splitKind(freeze.kindStats, key);
            w.write(String.format("%-34s %10.1f %10.1f %8d %8d%n",
                    key,
                    bf * 1000.0 / beforeSpan,
                    fr * 1000.0 / freezeSpan,
                    frSplit[0], frSplit[1]));
        }
        if (entries.isEmpty()) {
            w.write("(no classified events)\n");
        }
    }

    /**
     * Finds the zone-code byte within the hit payload. The field offset
     * rotates between sessions; the zone byte is the one among bytes 15..18
     * whose value is a known zone code (1, 2, 3, 9, 0x21). Returns the
     * offset, or -1 when none matches.
     */
    private static int findZoneByte(byte[] d, int plen) {
        for (int i = 15; i <= 18 && i < plen; i++) {
            int v = d[i] & 0xff;
            if (v == 1 || v == 2 || v == 3 || v == 9 || v == 0x21) return i;
        }
        return -1;
    }

    /**
     * Combat log: every hit confirmation decoded into (zone, damage, weapon)
     * and grouped into engagements. Zone codes: 1=torso, 2=limb, 3=extra,
     * 9=head (see PROTOCOL_MAP.md).
     *
     * <p>The hit packet's signature rotates per session, so the shape is
     * selected here with full-capture knowledge: every inbound packet that
     * passes the structural pre-filter (22-26 bytes, zero tail, small damage
     * byte, plausible zone code) is grouped by (len, header) and each
     * candidate shape is scored by "engagement cluster-ness" — hits arrive
     * in tight bursts with long quiet gaps between fights, while look-alike
     * world-state streams arrive steadily. The best-scoring shape wins.</p>
     */
    private static void writeCombatLog(BufferedWriter w, List<CaptureRecord> records)
            throws Exception {
        // Pass 1: collect structural candidates per shape. Field offsets and
        // masking rotate between sessions (damage is always the small byte
        // at plen-3 with a zero tail; the zone byte may be plaintext or
        // masked), so the pre-filter only requires the universal structure
        // and the cluster scoring picks the real hit stream.
        java.util.LinkedHashMap<String, List<CaptureRecord>> candidates = new java.util.LinkedHashMap<>();
        for (CaptureRecord r : records) {
            if (r.dir != CaptureRecord.Dir.IN) continue;
            byte[] d = r.payload;
            int plen = r.payloadLen;
            if (plen < 22 || plen > 26 || d.length < plen) continue;
            if ((d[plen - 1] & 0xff) != 0 || (d[plen - 2] & 0xff) != 0) continue;
            if ((d[plen - 3] & 0xff) > 130) continue;
            String shape = plen + "|" + Integer.toHexString((d[2] & 0xff))
                    + Integer.toHexString((d[4] & 0xff))
                    + Integer.toHexString((d[5] & 0xff));
            List<CaptureRecord> list = candidates.get(shape);
            if (list == null) {
                list = new ArrayList<>();
                candidates.put(shape, list);
            }
            list.add(r);
        }
        if (candidates.isEmpty()) {
            w.write("(no hit confirmations detected this capture)\n");
            return;
        }
        // Pass 2: score each shape by engagement cluster-ness plus damage
        // duplication. True hit streams repeat the same damage values (same
        // weapon, same body part); look-alike position packets carry random
        // bytes in the damage offset.
        String bestShape = null;
        double bestScore = -1;
        List<CaptureRecord> bestList = null;
        for (Map.Entry<String, List<CaptureRecord>> e : candidates.entrySet()) {
            List<CaptureRecord> list = e.getValue();
            if (list.size() < 3) continue;
            list.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));
            int shortGaps = 0, longGaps = 0, total = 0;
            for (int i = 1; i < list.size(); i++) {
                long gap = list.get(i).timestampMs - list.get(i - 1).timestampMs;
                total++;
                if (gap <= 600) shortGaps++;
                else if (gap >= 3000) longGaps++;
            }
            // Damage duplication: fraction of packets whose damage value
            // appears more than once in the stream.
            java.util.HashMap<Integer, Integer> dmgCount = new java.util.HashMap<>();
            for (CaptureRecord r : list) {
                int d = r.payload[r.payloadLen - 3] & 0xff;
                Integer old = dmgCount.get(d);
                dmgCount.put(d, old == null ? 1 : old + 1);
            }
            int duplicated = 0;
            for (CaptureRecord r : list) {
                int d = r.payload[r.payloadLen - 3] & 0xff;
                if (dmgCount.get(d) > 1) duplicated++;
            }
            double dupRatio = list.isEmpty() ? 0 : (double) duplicated / list.size();
            // Score: cluster timing x damage duplication x volume.
            double score = (total == 0) ? 0
                    : ((double) shortGaps / total) * (0.3 + 0.7 * ((double) longGaps / total))
                      * (0.3 + 0.7 * dupRatio)
                      * Math.min(1.0, list.size() / 20.0);
            if (score > bestScore) {
                bestScore = score;
                bestShape = e.getKey();
                bestList = list;
            }
        }
        if (bestList == null || bestList.isEmpty()) {
            w.write("(no hit confirmations detected this capture)\n");
            return;
        }
        w.write("hit-packet shape this session: " + bestShape
                + " (score " + String.format("%.2f", bestScore) + ")\n");
        w.write("zone codes: 1=torso 2=limb 3=extra 9=head\n");
        w.write("total hits: " + bestList.size() + "\n\n");

        // Pass 3: decode the winning shape into engagements.
        long firstTs = records.isEmpty() ? 0 : records.get(0).timestampMs;
        int engagement = 0;
        long lastTs = -1;
        int engHits = 0, engDmg = 0;
        int[] zoneCount = new int[16];
        int totalDmg = 0;
        StringBuilder current = new StringBuilder();
        for (CaptureRecord r : bestList) {
            byte[] d = r.payload;
            int plen = r.payloadLen;
            int zoneOff = findZoneByte(d, plen);
            int zone = zoneOff >= 0 ? (d[zoneOff] & 0xff) : -1;
            int dmg = d[plen - 3] & 0xff;
            int weapon = zoneOff >= 1 ? (d[zoneOff - 1] & 0xff) : (d[16] & 0xff);
            if (lastTs >= 0 && r.timestampMs - lastTs > 600) {
                if (engHits > 0) {
                    w.write("engagement " + engagement + ": " + engHits + " hits, "
                            + engDmg + " total dmg\n" + current + "\n");
                }
                engagement++;
                engHits = 0;
                engDmg = 0;
                current = new StringBuilder();
            }
            lastTs = r.timestampMs;
            engHits++;
            engDmg += dmg;
            totalDmg += dmg;
            if (zone >= 0 && zone < zoneCount.length) zoneCount[zone]++;
            String zoneStr = zoneOff >= 0
                    ? com.nepdon.wire.protocol.GamePacketTaxonomy.zoneName(zone)
                    : "MASKED";
            current.append(String.format("  +%6.2fs dmg=%3d zone=%s weapon=0x%02X%n",
                    (r.timestampMs - firstTs) / 1000.0, dmg, zoneStr, weapon));
        }
        if (engHits > 0) {
            w.write("engagement " + engagement + ": " + engHits + " hits, "
                    + engDmg + " total dmg\n" + current + "\n");
        }
        w.write("ZONE SUMMARY\n");
        for (int z = 0; z < zoneCount.length; z++) {
            if (zoneCount[z] > 0) {
                w.write("  " + com.nepdon.wire.protocol.GamePacketTaxonomy.zoneName(z)
                        + ": " + zoneCount[z] + " hits\n");
            }
        }
        w.write("TOTAL DAMAGE DEALT: " + totalDmg + "\n");
    }

    private static void writeHeartbeatCheck(BufferedWriter w, Bucket before, Bucket freeze)
            throws Exception {
        // Sum inbound family-R stats by scanning kind keys "IN/R/*".
        long bfTotal = 0, frTotal = 0, frDrop = 0, frPass = 0;
        for (Map.Entry<String, long[]> e : freeze.kindStats.entrySet()) {
            if (!e.getKey().startsWith("IN/R/")) continue;
            frPass += e.getValue()[0];
            frDrop += e.getValue()[1];
            frTotal += e.getValue()[0] + e.getValue()[1] + e.getValue()[2];
        }
        for (Map.Entry<String, long[]> e : before.kindStats.entrySet()) {
            if (!e.getKey().startsWith("IN/R/")) continue;
            bfTotal += e.getValue()[0] + e.getValue()[1] + e.getValue()[2];
        }
        if (frTotal == 0) {
            w.write("(no inbound R-family packets seen during FREEZE — nothing to check)\n");
            return;
        }
        double dropRate = frDrop * 100.0 / frTotal;
        w.write(String.format("inbound R-family during FREEZE: %d pkts, %d passed, %d dropped "
                + "(%.1f%% drop rate); BEFORE saw %d pkts%n",
                frTotal, frPass, frDrop, dropRate, bfTotal));
        if (frDrop == 0) {
            w.write("-> OK: heartbeat/timer stream fully preserved. Smart freeze is protecting "
                    + "the reliability layer.\n");
        } else if (dropRate < 10) {
            w.write("-> WARN: a few R-family packets were dropped (" + frDrop + "). If using "
                    + "legacy mode, switch to smart/sniper mode.\n");
        } else {
            w.write("-> !! HEARTBEAT LEAK: freeze is dropping the reliability layer. This "
                    + "starves game timers and can trigger disconnect flags. Use smart or "
                    + "sniper freeze mode.\n");
        }
    }

    private static long countKind(HashMap<String, long[]> map, String key) {
        long[] v = map.get(key);
        return v == null ? 0L : v[0] + v[1] + v[2];
    }

    private static long[] splitKind(HashMap<String, long[]> map, String key) {
        long[] v = map.get(key);
        return v == null ? new long[2] : new long[]{v[0], v[1]};
    }

    private static void writeInterpretation(BufferedWriter w, Bucket before, long beforeSpan,
                                            Bucket freeze, long freezeSpan) throws Exception {
        double beforeIn = 0, freezeIn = 0;
        for (Map.Entry<String, Long> e : before.shapeRate.entrySet()) {
            if (e.getKey().startsWith("IN/")) beforeIn += e.getValue() * 1000.0 / beforeSpan;
        }
        for (Map.Entry<String, Long> e : freeze.shapeRate.entrySet()) {
            if (e.getKey().startsWith("IN/")) freezeIn += e.getValue() * 1000.0 / freezeSpan;
        }
        if (beforeIn > 0) {
            double pct = (freezeIn - beforeIn) / beforeIn * 100.0;
            w.write(String.format("Inbound delivered rate: %+.1f%% (freeze vs before)%n", pct));
            if (pct < -50) {
                w.write("-> Freeze is heavily suppressing inbound traffic in the 0x15..0x1c2 "
                        + "length window; game state updates in that window are being dropped.\n");
            } else if (pct > -10) {
                w.write("-> Freeze had little effect on inbound volume; most game traffic may "
                        + "fall outside the drop window, or the phase window was too short.\n");
            } else {
                w.write("-> Freeze is partially suppressing inbound traffic.\n");
            }
        } else {
            w.write("(no BEFORE inbound traffic recorded — capture started after freeze was on?)\n");
        }
        if (freeze.drop == 0) {
            w.write("-> No DROPs recorded during FREEZE phase. Freeze either was not active "
                    + "in the engine while capture ran, or no inbound packets matched.\n");
        }
    }

    private static long count(HashMap<String, Long> map, String key) {
        Long v = map.get(key);
        return v == null ? 0L : v;
    }

    private static String ipToString(int ip) {
        return ((ip >> 24) & 0xff) + "." + ((ip >> 16) & 0xff) + "."
                + ((ip >> 8) & 0xff) + "." + (ip & 0xff);
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
