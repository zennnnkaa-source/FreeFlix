package com.nepdon.wire.filter;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.esp.MarkTarget;
import com.nepdon.wire.decode.crypt.BodyCrypt;
import com.nepdon.wire.decode.frame.HeaderField;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.decode.frame.MsgKey;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.decode.crypt.Tea;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retargets the squad map-mark (Cmd 148 {@code RUDP_MAPMARK_CHANGED}) at an
 * ESP-derived enemy position.
 *
 * <p>When the player taps the game's own "enemy spotted" button, the client
 * emits a 148 carrying the world point they pointed at. This filter rewrites
 * that point to the position of the entity the ESP is currently tracking, and
 * forces the mark type to {@code NoticeEnemy}. The pin the whole squad sees
 * then lands on a real enemy rather than wherever the finger went.</p>
 *
 * <h3>Why a rewrite and not an injection</h3>
 *
 * <p>148 is reliable, and injecting a NEW reliable message mints an OrderID the
 * reliability layer never issued — the window desyncs and the session drops.
 * The established rule in this codebase is <b>rewrite in place, never
 * inject</b>. So this filter is strictly passive: it changes a packet the game
 * decided to send, on the player's own button press, and creates nothing. If
 * the player never taps mark, this filter never does anything.</p>
 *
 * <p><b>Consequence the user should know:</b> the pin is therefore STATIC. It
 * is placed where the target stood at the instant of the tap and does not
 * follow them, because following would mean emitting packets the game did not
 * ask for. One accurate pin per press.</p>
 *
 * <h3>Wire layout (docs/MARK_REVEAL_SURFACE.md §4.1)</h3>
 *
 * <p>Client&rarr;server class {@code message.PAOAHDNENHN}, nine elements:</p>
 * <pre>
 *   U  author entity id        (squad&lt;&lt;24)|slot
 *   B  isDel                   raw byte, 1 removes the mark
 *   B  mark type               raw byte, EItemMarkPointType; 1 = NoticeEnemy
 *   Z  position X              zigzag varint, 1000/m
 *   Z  position UP             zigzag varint, 1000/m
 *   Z  position Z              zigzag varint, 1000/m
 *   B  author slot 0-3         raw byte
 *   B  playMarkSound           raw byte
 *   B  unresolved, always 0    raw byte
 * </pre>
 *
 * <p><b>The three {@code B} fields are RAW BYTES, not varints.</b> That is the
 * single easiest way to corrupt this record: a varint reader walking over a
 * raw byte whose value happens to be &ge; 0x80 consumes the following byte too
 * and every field after it shifts. Parsing here is therefore explicit about
 * which elements are varints and which are single bytes.</p>
 *
 * <h3>Mechanics</h3>
 *
 * <p>148 is reliable and therefore TEA-encrypted, so the edit is
 * decrypt &rarr; rewrite &rarr; re-encrypt &rarr; repair {@code DATA_LEN}
 * &rarr; reseal CRC-7 — the same path {@link AimFilter} uses for Cmd 106.
 * Two length subtleties, both handled:</p>
 * <ul>
 *   <li>the new coordinates are zigzag varints and may encode to a different
 *       number of bytes than the originals, so the plaintext length changes;</li>
 *   <li>TEA pads to whole blocks, so the CIPHERTEXT length can change by a
 *       block even when the plaintext barely moves.</li>
 * </ul>
 *
 * <p>Both are absorbed by re-deriving {@link Tea#qqTeaEncryptLen}, writing the
 * repaired {@code DATA_LEN} and calling {@link PacketContext#setOutLength}.
 * CRC-7 is resealed by the transport's egress guard
 * ({@code PacketCodec.refreshCrcIfMatchTraffic}), which every rewritten packet
 * already passes through — editing any byte at offset &ge; 2 without that
 * makes the server silently discard the packet.</p>
 *
 * <h3>Unverified</h3>
 *
 * <p><b>Whether the server validates the position is NOT known</b> and cannot
 * be determined from the client binary (docs/MARK_REVEAL_SURFACE.md §10.1).
 * Three outcomes are possible on first use: the pin appears at the rewritten
 * point (no validation), appears at the real tap point (server re-derives it),
 * or nothing appears. This filter cannot tell the difference — it only knows
 * the packet left correctly — so the first run is an experiment, and the
 * counters here exist to prove the rewrite happened even if the pin does not
 * move.</p>
 */
public final class MarkFilter implements PacketFilter {

    /** {@code RUDP_MAPMARK_CHANGED}. Not in the Cmd enum; used as a literal. */
    public static final int CMD_MAPMARK_CHANGED = 148;

    /** {@code EItemMarkPointType_NoticeEnemy} — the squad-visible enemy pin. */
    public static final int TYPE_NOTICE_ENEMY = 1;

    /** A target older than this is not worth pinning. */
    private static final long MAX_AGE_MS = 3000L;

    private final DevLogger logger;

    /**
     * Master switch, a static so the tune panel can reach it the same way it
     * reaches the renderer's knobs, without threading a filter reference
     * through the service. Default OFF: this rewrites a packet other players
     * see, so it should never turn itself on.
     */
    public static volatile boolean ENABLED = false;

    /** Force the mark type to NoticeEnemy as well as moving the position. */
    public static volatile boolean FORCE_ENEMY_TYPE = true;

    private final AtomicInteger seen = new AtomicInteger();
    private final AtomicInteger rewritten = new AtomicInteger();
    private final AtomicInteger passedNoTarget = new AtomicInteger();
    private final AtomicInteger passedNoKey = new AtomicInteger();
    private final AtomicInteger passedBad = new AtomicInteger();
    private volatile String lastNote = "";

    private byte[] plainScratch = new byte[512];
    private byte[] outScratch = new byte[512];
    private byte[] cipherScratch = new byte[512];

    public MarkFilter(DevLogger logger) {
        this.logger = logger;
    }

    @Override public String name() { return "mark"; }
    @Override public boolean isActive() { return ENABLED; }
    @Override public void start() { ENABLED = true; }
    @Override public void stop() { ENABLED = false; }
    @Override public void shutdown() { ENABLED = false; }
    @Override public FilterDecision onInbound(PacketContext ctx) { return FilterDecision.PASS; }

    /** One-line readout for the tune panel. */
    public String status() {
        return "MARK " + (ENABLED ? "on" : "off")
                + " seen=" + seen.get()
                + " hit=" + rewritten.get()
                + " noTgt=" + passedNoTarget.get()
                + " noKey=" + passedNoKey.get()
                + " bad=" + passedBad.get()
                + (lastNote.isEmpty() ? "" : " " + lastNote);
    }

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        if (!ENABLED) return FilterDecision.PASS;
        // Server-gated leash: without a live decode profile, do not rewrite (PASS
        // the packet byte-for-byte). Compile-time-false flag => R8 strips this.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && !com.nepdon.wire.auth.ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return FilterDecision.PASS;
        }
        if (ctx == null || !ctx.hasCmd() || ctx.cmd != CMD_MAPMARK_CHANGED) {
            return FilterDecision.PASS;
        }
        seen.incrementAndGet();
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.FLT_MARK_SEEN);

        // Never rewrite toward a target we are not currently confident in. A
        // stale pin is worse than an honest one -- it sends the squad to where
        // somebody used to be.
        if (!MarkTarget.isValid() || MarkTarget.age() > MAX_AGE_MS) {
            passedNoTarget.incrementAndGet();
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.FLT_MARK_NO_TARGET);
            note("pass noTarget");
            return FilterDecision.PASS;
        }

        int msgKey = ctx.buf[ctx.off] & 0xFF;
        int headerSize = MsgKey.headerSize(msgKey);
        if (headerSize != MsgKey.HEADER_RELIABLE) {
            passedBad.incrementAndGet();
            note("pass notReliable hs=" + headerSize);
            return FilterDecision.PASS;
        }

        int[] sched = BodyCrypt.schedule();
        if (sched == null) {
            passedNoKey.incrementAndGet();
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.FLT_MARK_NO_KEY);
            note("pass noKey");
            return FilterDecision.PASS;
        }

        HeaderLayout layout = PacketPipeline.current() == null
                ? null : PacketPipeline.current().layout();
        if (layout == null) {
            passedBad.incrementAndGet();
            note("pass noLayout");
            return FilterDecision.PASS;
        }

        int payOff = ctx.off + headerSize;
        int payLen = ctx.plen - headerSize;
        if (payLen < Tea.MIN_CIPHERTEXT || (payLen & 7) != 0 || payLen > BodyCrypt.MAX_BODY) {
            passedBad.incrementAndGet();
            note("pass badPayLen=" + payLen);
            return FilterDecision.PASS;
        }

        // PROVE THE LAYOUT DECODES *THIS* PACKET BEFORE TRUSTING IT TO ENCODE.
        // The layout carries a per-session mask and field permutation read from
        // a process-wide static; if it is stale (key rotation at a match or
        // room transition) then writeShort would mask DATA_LEN with the wrong
        // byte and the server would read a body length that does not match the
        // datagram. A run of malformed packets is a protocol error that ends
        // the session. The check is free: on an outbound packet, the declared
        // Length must equal the payload bytes actually present.
        int declared = layout.readShort(ctx.buf, ctx.off, HeaderField.DATA_LEN, true);
        if (declared != payLen) {
            passedBad.incrementAndGet();
            note("pass layoutStale declared=" + declared + " real=" + payLen);
            return FilterDecision.PASS;
        }

        byte[] plain = ensure(plainScratch, payLen);
        plainScratch = plain;
        int plainLen;
        try {
            plainLen = Tea.qqTeaDecrypt(ctx.buf, payOff, payLen, sched, plain, 0);
        } catch (Throwable t) {
            passedBad.incrementAndGet();
            note("pass decryptThrew");
            return FilterDecision.PASS;
        }
        if (plainLen <= 0) {
            passedBad.incrementAndGet();
            note("pass decryptFail");
            return FilterDecision.PASS;
        }

        // ---- parse: U B B Z Z Z B B B ----
        int p = 0;
        int authorEnd = skipVarint(plain, p, plainLen);
        if (authorEnd < 0) return passBad("parse author");
        p = authorEnd;
        if (p + 2 > plainLen) return passBad("parse flags");
        int isDelPos = p;            // raw byte
        int typePos = p + 1;         // raw byte
        p += 2;
        int posStart = p;
        for (int i = 0; i < 3; i++) {
            p = skipVarint(plain, p, plainLen);
            if (p < 0) return passBad("parse pos" + i);
        }
        int posEnd = p;
        int tailLen = plainLen - posEnd;
        // Three trailing raw bytes are expected; tolerate a different tail
        // rather than refuse, since only their length matters to us.
        if (tailLen < 0) return passBad("parse tail");

        // A delete ("unmark") must not be retargeted -- it would delete a mark
        // that was never placed and leave the real one standing.
        if ((plain[isDelPos] & 0xFF) != 0) {
            passedBad.incrementAndGet();
            note("pass isDel");
            return FilterDecision.PASS;
        }

        // ---- rebuild ----
        long nx = Math.round(MarkTarget.wireX());
        long nup = Math.round(MarkTarget.wireUp());
        long ny = Math.round(MarkTarget.wireY());

        int worst = plainLen + 24;   // three varints can only grow so far
        byte[] out = ensure(outScratch, worst);
        outScratch = out;

        int q = 0;
        System.arraycopy(plain, 0, out, q, posStart); q += posStart;
        if (FORCE_ENEMY_TYPE) out[typePos] = (byte) TYPE_NOTICE_ENEMY;
        q = writeZigzag(out, q, nx);
        q = writeZigzag(out, q, nup);
        q = writeZigzag(out, q, ny);
        System.arraycopy(plain, posEnd, out, q, tailLen); q += tailLen;
        int newPlainLen = q;

        int encLen = Tea.qqTeaEncryptLen(newPlainLen);
        int newTotal = headerSize + encLen;
        if (ctx.off + newTotal > ctx.buf.length) {
            passedBad.incrementAndGet();
            note("pass noRoom");
            return FilterDecision.PASS;
        }

        byte[] cipher = ensure(cipherScratch, encLen);
        cipherScratch = cipher;
        int cl;
        try {
            cl = Tea.qqTeaEncrypt(out, 0, newPlainLen, sched, cipher, 0);
        } catch (Throwable t) {
            passedBad.incrementAndGet();
            note("pass encryptThrew");
            return FilterDecision.PASS;
        }
        if (cl != encLen) {
            passedBad.incrementAndGet();
            note("pass encLenMismatch");
            return FilterDecision.PASS;
        }

        // Commit: ciphertext, repaired Length, then the send length. CRC-7 is
        // resealed by the transport's egress guard.
        System.arraycopy(cipher, 0, ctx.buf, payOff, cl);
        layout.writeShort(ctx.buf, ctx.off, HeaderField.DATA_LEN, true, cl);
        ctx.setOutLength(newTotal);

        rewritten.incrementAndGet();
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.FLT_MARK_REWRITTEN);
        note("hit e" + MarkTarget.id() + " @" + nx + "," + nup + "," + ny
                + " " + payLen + "->" + cl + "B");
        if (logger != null) {
            logger.logControl("MARK_REWRITE",
                    "e=" + MarkTarget.id() + " dist=" + (int) MarkTarget.distM()
                            + "m age=" + MarkTarget.age() + "ms"
                            + " pos=" + nx + "," + nup + "," + ny
                            + " len=" + payLen + "->" + cl);
        }
        return FilterDecision.PASS;
    }

    private FilterDecision passBad(String why) {
        passedBad.incrementAndGet();
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.FLT_MARK_BAD);
        note("pass " + why);
        return FilterDecision.PASS;
    }

    private void note(String s) { lastNote = s; }

    /** Index just past one LEB128 varint, or -1 when truncated. */
    private static int skipVarint(byte[] b, int i, int end) {
        int n = 0;
        while (i < end) {
            int c = b[i++] & 0xFF;
            if ((c & 0x80) == 0) return i;
            if (++n > 9) return -1;
        }
        return -1;
    }

    /** Writes a signed value as a canonical zigzag LEB128 varint. */
    private static int writeZigzag(byte[] b, int p, long v) {
        long u = (v << 1) ^ (v >> 63);
        while (true) {
            int c = (int) (u & 0x7FL);
            u >>>= 7;
            if (u != 0) {
                b[p++] = (byte) (c | 0x80);
            } else {
                b[p++] = (byte) c;
                return p;
            }
        }
    }

    private static byte[] ensure(byte[] b, int need) {
        return (b != null && b.length >= need) ? b : new byte[Math.max(need, 512)];
    }
}
