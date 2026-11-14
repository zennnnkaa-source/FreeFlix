package com.nepdon.wire.decode.body;

/**
 * Turns one message body into a human-readable, greppable field dump.
 *
 * <h3>Two modes, and the unknown surface is a number either way</h3>
 * <ol>
 *   <li><b>Layout decode.</b> When {@link BodyDecoderTable} has a program for
 *       {@code (cmd, direction)} the body is walked with the game's own field
 *       types and emitted with real names where they are known:
 *       {@code + v0.attacker=1002 v7.HitBodyPart=1}.</li>
 *   <li><b>Positional decode.</b> Otherwise — and for any body whose layout
 *       desyncs or leaves residue — every value is emitted with each plausible
 *       reading: raw varint, zigzag-signed, and float-from-bits:
 *       {@code ? v0=1002 v1=94~47 v2=1065353216#1.0}. An id with no layout is
 *       therefore still analysable, which is the whole point.</li>
 * </ol>
 *
 * <p>The leading token says which happened, so the unknown surface can be
 * counted from the capture instead of inferred:</p>
 * <pre>
 *   +  layout decode, byte-exact (program consumed the body exactly)
 *   ~  layout decode desynced or left residue -&gt; positional dump follows
 *   ?  no layout for this (cmd, direction)     -&gt; positional dump follows
 *   !  body is not readable at all (truncated capture, or empty)
 * </pre>
 *
 * <h3>The two traps this exists to survive</h3>
 * <p>{@code ReadByte}/{@code ReadBoolean} consume <b>1 RAW byte</b> and
 * {@code ReadFixUInt16} consumes <b>2 RAW little-endian bytes</b>. Both break a
 * naive varint walk — the leading per-record entity id in Cmd 2001/2012 is a
 * raw byte, and any value &ge; 0x80 there desyncs the rest of the packet. The
 * programs in {@link BodyDecoderTable} encode the raw-byte case; the
 * {@code ReadFixUInt16} case has no declared-type signature and is handled by
 * the hand-written grammars below (Cmd 1900/1901), which are the only place it
 * is known to occur.</p>
 *
 * <h3>Threading</h3>
 * <p>Runs on the capture writer thread, never on a transport thread. It
 * allocates a {@link StringBuilder} per call and is otherwise allocation-light.
 * <b>No method here throws</b>; a malformed body produces a shorter string, not
 * an exception.</p>
 */
public final class BodyDecoderText {

    /** Longest note emitted; a long entity chain is truncated with a count. */
    public static final int MAX_NOTE = 1600;
    /** Refuse to iterate a list longer than this — a desync can decode a huge count. */
    private static final int MAX_LIST = 4096;
    /** Values emitted by the positional dump before it gives up. */
    private static final int MAX_POSITIONAL = 96;

    private BodyDecoderText() { }

    // ---- coverage counters (writer thread only; volatile for the reader) ----

    private static volatile long exactCount;
    private static volatile long residueCount;
    private static volatile long noLayoutCount;
    private static volatile long unreadableCount;
    private static volatile long failureCount;

    /** Zeroes the coverage counters. Call at capture start. */
    public static void resetCounters() {
        exactCount = 0;
        residueCount = 0;
        noLayoutCount = 0;
        unreadableCount = 0;
        failureCount = 0;
    }

    /** "decoded=12/34 (exact 10, residue 2, nolayout 20, unreadable 2)". */
    public static String coverage() {
        long e = exactCount, r = residueCount, n = noLayoutCount, u = unreadableCount;
        long total = e + r + n + u;
        StringBuilder sb = new StringBuilder(128);
        sb.append("bodies=").append(total)
          .append(" exact=").append(e)
          .append(" residue=").append(r)
          .append(" nolayout=").append(n)
          .append(" unreadable=").append(u)
          .append(" layouts=").append(BodyDecoderTable.COUNT);
        if (failureCount != 0) sb.append(" decodeErrors=").append(failureCount);
        if (total > 0) sb.append(" exactPct=").append((int) (100L * e / total));
        return sb.toString();
    }

    // ================================================================
    // Entry point
    // ================================================================

    /**
     * Decodes one body.
     *
     * @param cmd      the command id from the parsed header
     * @param outbound true for client-to-server. <b>Never decode on {@code cmd}
     *                 alone</b> — the same id is a different struct per
     *                 direction.
     * @param buf      readable bytes
     * @param off      first body byte
     * @param len      body length
     * @return a field dump, or null when there is nothing to say
     */
    public static String decode(int cmd, boolean outbound, byte[] buf, int off, int len) {
        try {
            return decode0(cmd, outbound, buf, off, len);
        } catch (Throwable t) {
            failureCount++;
            return null;
        }
    }

    private static String decode0(int cmd, boolean outbound, byte[] buf, int off, int len) {
        if (buf == null || off < 0 || len <= 0 || off + len > buf.length) {
            unreadableCount++;
            return "!";
        }

        // Hand-written grammars first: these use ReadFixUInt16, which no
        // declared type can express.
        if (cmd == 1900 || cmd == 1901) {
            StringBuilder sb = new StringBuilder(256);
            if (decodeVarSync(sb, buf, off, len, cmd == 1900)) {
                exactCount++;
                return clamp(sb);
            }
            residueCount++;
            StringBuilder p = new StringBuilder(256);
            p.append('~');
            positional(p, buf, off, len);
            return clamp(p);
        }

        String prog = BodyDecoderTable.program(cmd, outbound);
        if (prog != null) {
            StringBuilder sb = new StringBuilder(256);
            sb.append('+').append(' ');
            Cur c = new Cur(buf, off, off + len);
            String[] names = BodyDecoderTable.names(cmd, outbound);
            Slot slot = new Slot();
            boolean ok = run(prog, c, sb, names, slot, 0);
            if (ok && !c.bad && c.i == c.end) {
                exactCount++;
                return clamp(sb);
            }
            // The layout did not account for the body exactly. Say so, and fall
            // back rather than publish a decode we do not trust.
            residueCount++;
            StringBuilder p = new StringBuilder(256);
            p.append('~').append(" used=").append(c.i - off).append('/').append(len).append(' ');
            positional(p, buf, off, len);
            return clamp(p);
        }

        noLayoutCount++;
        StringBuilder p = new StringBuilder(256);
        p.append('?').append(' ');
        positional(p, buf, off, len);
        return clamp(p);
    }

    /**
     * Dry run: would this body decode byte-exactly against its modelled layout?
     *
     * <p>Builds no string and touches no counter, so it is safe to call
     * speculatively — which is what the annotator does to decide whether a body
     * that <i>claims</i> to be plaintext is really ciphertext. Answers false
     * whenever there is no layout to check against.</p>
     */
    public static boolean isExactlyDecodable(int cmd, boolean outbound,
                                             byte[] buf, int off, int len) {
        try {
            if (buf == null || off < 0 || len <= 0 || off + len > buf.length) return false;
            if (cmd == 1900 || cmd == 1901) {
                return probeVarSync(buf, off, len, cmd == 1900);
            }
            String prog = BodyDecoderTable.program(cmd, outbound);
            if (prog == null) return false;
            Cur c = new Cur(buf, off, off + len);
            return probe(prog, c, 0) && !c.bad && c.i == c.end;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@link #run} without the string building. */
    private static boolean probe(String prog, Cur c, int depth) {
        if (depth > 8) return false;
        int n = prog.length();
        int i = 0;
        while (i < n) {
            if (c.bad) return false;
            char op = prog.charAt(i);
            if (op == BodyDecoderTable.OP_LIST_BEGIN) {
                int j = matching(prog, i);
                if (j < 0) return false;
                String body = prog.substring(i + 1, j);
                long count = c.zigzag();
                if (c.bad || count < 0 || count > MAX_LIST) return false;
                for (long k = 0; k < count; k++) {
                    if (!probe(body, c, depth + 1)) return false;
                }
                i = j + 1;
                continue;
            }
            switch (op) {
                case BodyDecoderTable.OP_UINT:
                case BodyDecoderTable.OP_FLOAT:
                    c.varint();
                    break;
                case BodyDecoderTable.OP_ZIGZAG:
                    c.zigzag();
                    break;
                case BodyDecoderTable.OP_BYTE:
                    c.raw();
                    break;
                case BodyDecoderTable.OP_STRING: {
                    long slen = c.zigzag();
                    if (c.bad || slen < 0 || c.i + slen > c.end) return false;
                    c.i += (int) slen;
                    break;
                }
                default:
                    return false;
            }
            if (c.bad) return false;
            i++;
        }
        return true;
    }

    /** {@link #decodeVarSync} without the string building. */
    private static boolean probeVarSync(byte[] buf, int off, int len, boolean hasOwner) {
        Cur c = new Cur(buf, off, off + len);
        int groups = 0;
        while (c.i < c.end) {
            if (hasOwner) c.varint();
            if (c.bad) return false;
            int blockLen = c.fixU16();
            if (c.bad) return false;
            int stop = c.i + blockLen;
            if (stop > c.end) return false;
            while (c.i < stop) {
                c.raw();
                c.varint();
                if (c.bad) return false;
            }
            if (c.i != stop) return false;
            if (++groups > 256) return false;
        }
        return !c.bad && c.i == c.end;
    }

    private static String clamp(StringBuilder sb) {
        if (sb.length() <= MAX_NOTE) return sb.toString();
        int cut = sb.length() - MAX_NOTE;
        return sb.substring(0, MAX_NOTE) + "..+" + cut + "ch";
    }

    // ================================================================
    // The reader — every primitive is bounds-checked and never throws
    // ================================================================

    /** A cursor over a body. {@code bad} latches once the body runs out. */
    private static final class Cur {
        final byte[] b;
        int i;
        final int end;
        boolean bad;

        Cur(byte[] b, int i, int end) {
            this.b = b;
            this.i = i;
            this.end = end;
        }

        /** LEB128 varint. Sets {@link #bad} on truncation or overlong encoding. */
        long varint() {
            long v = 0;
            int s = 0;
            while (i < end) {
                int c = b[i++] & 0xFF;
                v |= ((long) (c & 0x7F)) << s;
                if ((c & 0x80) == 0) return v;
                s += 7;
                if (s > 63) { bad = true; return 0L; }
            }
            bad = true;
            return 0L;
        }

        /** Varint then the zigzag transform the game's {@code ReadInt*} applies. */
        long zigzag() {
            long v = varint();
            return com.nepdon.wire.util.ZigZag.decode(v);
        }

        /** ONE RAW BYTE — {@code ReadByte} / {@code ReadBoolean}. */
        int raw() {
            if (i >= end) { bad = true; return 0; }
            return b[i++] & 0xFF;
        }

        /** TWO RAW LITTLE-ENDIAN BYTES — {@code ReadFixUInt16}. */
        int fixU16() {
            if (i + 2 > end) { bad = true; return 0; }
            int lo = b[i++] & 0xFF;
            int hi = b[i++] & 0xFF;
            return lo | (hi << 8);
        }
    }

    /** Mutable slot counter, so nested calls share one numbering. */
    private static final class Slot {
        int index;
        int records;
    }

    // ================================================================
    // Program interpreter
    // ================================================================

    private static boolean run(String prog, Cur c, StringBuilder sb, String[] names,
                               Slot slot, int depth) {
        if (depth > 8) return false;
        int n = prog.length();
        int i = 0;
        while (i < n) {
            if (c.bad) return false;
            char op = prog.charAt(i);
            if (op == BodyDecoderTable.OP_LIST_BEGIN) {
                int j = matching(prog, i);
                if (j < 0) return false;
                String body = prog.substring(i + 1, j);
                long count = c.zigzag();
                if (c.bad || count < 0 || count > MAX_LIST) return false;
                int base = slot.index;
                for (long k = 0; k < count; k++) {
                    slot.index = base;
                    slot.records++;
                    sb.append("r").append(k).append('{');
                    if (!run(body, c, sb, names, slot, depth + 1)) return false;
                    sb.append("} ");
                }
                slot.index = base + BodyDecoderTable.slotCount(body);
                i = j + 1;
                continue;
            }
            int idx = slot.index;
            String name = (names != null && idx >= 0 && idx < names.length) ? names[idx] : null;
            sb.append('v').append(idx);
            if (name != null) sb.append('.').append(name);
            sb.append('=');
            switch (op) {
                case BodyDecoderTable.OP_UINT:
                    sb.append(c.varint());
                    break;
                case BodyDecoderTable.OP_ZIGZAG:
                    sb.append(c.zigzag());
                    break;
                case BodyDecoderTable.OP_BYTE:
                    sb.append(c.raw());
                    break;
                case BodyDecoderTable.OP_FLOAT:
                    sb.append(Float.intBitsToFloat((int) c.varint()));
                    break;
                case BodyDecoderTable.OP_STRING: {
                    long slen = c.zigzag();
                    if (c.bad || slen < 0 || c.i + slen > c.end) return false;
                    sb.append('"');
                    appendAscii(sb, c.b, c.i, (int) slen);
                    sb.append('"');
                    c.i += (int) slen;
                    break;
                }
                default:
                    return false;
            }
            if (c.bad) return false;
            sb.append(' ');
            slot.index = idx + 1;
            i++;
        }
        return true;
    }

    /** Index of the {@code ')'} matching the {@code '('} at {@code open}, or -1. */
    private static int matching(String prog, int open) {
        int depth = 0;
        for (int i = open; i < prog.length(); i++) {
            char c = prog.charAt(i);
            if (c == BodyDecoderTable.OP_LIST_BEGIN) depth++;
            else if (c == BodyDecoderTable.OP_LIST_END && --depth == 0) return i;
        }
        return -1;
    }

    // ================================================================
    // Positional dump — the fallback that keeps an unknown id analysable
    // ================================================================

    /**
     * Walks the body as a flat varint stream and prints every plausible reading
     * of each value.
     *
     * <pre>
     *   v3=94          plain varint
     *   v3=94~47       ~ is the zigzag reading
     *   v3=…#1.0       # is the float32-from-bits reading, when it is plausible
     * </pre>
     *
     * <p>This is a <b>guess by construction</b> — it assumes every field is a
     * varint, which is false wherever the message holds a raw byte. Its purpose
     * is to make an unmodelled id analysable offline, not to be authoritative;
     * the leading {@code ?} or {@code ~} in the note says as much.</p>
     */
    public static void positional(StringBuilder sb, byte[] buf, int off, int len) {
        Cur c = new Cur(buf, off, off + len);
        int k = 0;
        while (c.i < c.end && k < MAX_POSITIONAL) {
            long v = c.varint();
            if (c.bad) break;
            sb.append('v').append(k).append('=').append(v);
            if (v != 0) {
                long z = com.nepdon.wire.util.ZigZag.decode(v);
                if (z != v) sb.append('~').append(z);
                float f = Float.intBitsToFloat((int) v);
                if (plausibleFloat(f)) sb.append('#').append(f);
            }
            sb.append(' ');
            k++;
        }
        if (c.i < c.end) {
            sb.append("tail=").append(c.end - c.i).append("B ");
            int show = Math.min(16, c.end - c.i);
            appendHex(sb, buf, c.i, show);
            sb.append(' ');
        }
    }

    /** A float worth printing: finite, and inside the range game data lives in. */
    private static boolean plausibleFloat(float f) {
        if (f != f || Float.isInfinite(f)) return false;
        float a = Math.abs(f);
        return a == 0.0f || (a >= 1e-4f && a <= 1e7f);
    }

    private static void appendAscii(StringBuilder sb, byte[] b, int off, int len) {
        for (int i = 0; i < len && off + i < b.length; i++) {
            int ch = b[off + i] & 0xFF;
            sb.append(ch >= 0x20 && ch < 0x7F && ch != '"' ? (char) ch : '.');
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static void appendHex(StringBuilder sb, byte[] b, int off, int len) {
        for (int i = 0; i < len && off + i < b.length; i++) {
            int v = b[off + i] & 0xFF;
            sb.append(HEX[v >> 4]).append(HEX[v & 0xF]);
        }
    }

    // ================================================================
    // Cmd 1900 / 1901 — the property-sync grammar
    // ================================================================

    /**
     * The keyed property stream, where health and armour actually live.
     *
     * <pre>
     *   body   := group+
     *   group  := varint  ownerReplicationId        (1900 only; 1901 omits it)
     *             u16 LE  blockLen                  ReadFixUInt16 -- 2 RAW BYTES
     *             record*                           exactly blockLen bytes
     *   record := u8      varId                     ReadByte -- 1 RAW BYTE
     *             varint  value
     * </pre>
     *
     * <p>Authority {@code GCommon.ReplicationDataPool::SyncReplicationData};
     * measured <b>6 709/6 709</b> packets parsing with exact byte consumption
     * and zero residue across three keyed sessions, plus 46/46 for 1901 — see
     * {@code docs/COMBAT_AND_HEALTH.md} §1. An unknown {@code varId} is skipped
     * as exactly one varint, so the walk cannot desync on a new property.</p>
     *
     * @return true when the body was consumed exactly
     */
    private static boolean decodeVarSync(StringBuilder sb, byte[] buf, int off, int len,
                                         boolean hasOwner) {
        Cur c = new Cur(buf, off, off + len);
        sb.append('+').append(' ');
        int groups = 0;
        while (c.i < c.end) {
            long owner = hasOwner ? c.varint() : -1L;
            if (c.bad) return false;
            int blockLen = c.fixU16();
            if (c.bad) return false;
            int stop = c.i + blockLen;
            if (stop > c.end) return false;
            sb.append('g').append(groups).append('{');
            if (hasOwner) sb.append("owner=").append(owner).append(' ');
            while (c.i < stop) {
                int varId = c.raw();
                long value = c.varint();
                if (c.bad) return false;
                sb.append(epriName(varId)).append('=').append(value).append(' ');
            }
            if (c.i != stop) return false;
            sb.append("} ");
            groups++;
            if (groups > 256) return false;
        }
        return !c.bad && c.i == c.end;
    }

    /**
     * The {@code EPRI_*} property ids that have been confirmed against the HUD.
     * Ids 0..98 are the reserved property range of the same enum the command ids
     * come from, so {@link CmdCatalogue} already holds every name; only the
     * confirmed ones are shortened here for readability.
     */
    private static String epriName(int varId) {
        switch (varId) {
            case 0:  return "curHp";
            case 1:  return "maxHp";
            case 2:  return "vest";       // (itemId << 32) | durability
            case 3:  return "helmet";     // same packing
            case 4:  return "onHand";     // (inventoryItemId << 32) | weaponId
            case 6:  return "curEp";
            case 7:  return "maxEp";
            case 21: return "firing";
            case 31: return "totalDmg";
            default: break;
        }
        String n = CmdCatalogue.name(varId);
        return n != null ? n : ("var" + varId);
    }
}
