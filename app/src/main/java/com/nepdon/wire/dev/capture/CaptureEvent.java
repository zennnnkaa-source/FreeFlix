package com.nepdon.wire.dev.capture;

/**
 * The single event type for everything the dumper records — UDP, lobby TCP and
 * control/lifecycle events alike.
 *
 * <p><b>Mutable and pooled.</b> An instance is owned by exactly one thread at a
 * time: a hot thread fills it, hands it to the capture queue and forgets it; the
 * writer thread annotates it, writes it, and recycles it back into the pool.
 * Never retain a reference past the hand-off, and never share one across
 * threads except through the queue.</p>
 *
 * <p>The field set and the constants are the seam agreed in
 * {@code docs/DUMPER_REWRITE_BRIEF.md} §4.1. Fields may be added; nothing here
 * may be renamed, reordered out of the file, or removed — agents 2 and 3 code
 * against these names.</p>
 */
public final class CaptureEvent {

    public static final int SRC_UDP = 0, SRC_TCP = 1, SRC_CTRL = 2;
    public static final int OUT = 0, IN = 1;
    public static final int PASS = 0, DROP = 1, HOLD = 2, REWRITE = 3;

    // flag bits, filled by AGENT 2
    public static final int F_PARSED    = 1 << 0;   // header layout resolved
    public static final int F_CRC_OK    = 1 << 1;
    public static final int F_RELIABLE  = 1 << 2;
    public static final int F_ENCRYPTED = 1 << 3;
    public static final int F_DECRYPTED = 1 << 4;   // TEA succeeded
    public static final int F_ZIPPED    = 1 << 5;
    public static final int F_LEN_MATCH = 1 << 6;
    public static final int F_PRESOLVER = 1 << 7;   // captured before layout known

    public long   seq;
    public long   tsMs;
    public long   tsNanos;      // monotonic, for inter-packet timing
    public int    src;
    public int    dir;
    public int    peerIp, sp, dp;
    public int    len;          // TRUE on-wire length even if payload is capped
    public byte[] payload;      // copied bytes, valid over [0, copyLen)
    public int    copyLen;
    public int    decision;
    public int    filterMask;
    public int    teleType;

    // filled by AGENT 2, on the writer thread
    public int    cmd = -1;
    public int    orderId = -1;
    public int    flags;
    public String note;         // decoded field dump, nullable

    // filled by AGENT 3, on the writer thread
    public int    phaseId;      // SessionTimeline phase at capture time
    public int    matchSeq;     // increments per match joined

    // ---- additions by AGENT 1 (capture core). Nothing above is changed. ----

    /**
     * Control-event kind, only meaningful for {@link #SRC_CTRL} (e.g.
     * {@code "BLOCK_FREEZE"}, {@code "MODE_TELE_ON"}). Null for packets.
     */
    public String ctrlKind;

    /**
     * Effect-mask bit names, mirroring {@link CaptureRecord#F_FREEZE} and
     * friends. Kept here so the writer never has to reach into CaptureRecord.
     */
    public static final int FX_FREEZE = CaptureRecord.F_FREEZE;
    public static final int FX_GHOST = CaptureRecord.F_GHOST;
    public static final int FX_JITTER = CaptureRecord.F_JITTER;

    /**
     * Clears the per-record state so a pooled instance cannot leak a field from
     * the previous packet into the next one. The payload array is deliberately
     * KEPT (that is the whole point of pooling); only {@link #copyLen} is reset.
     */
    public void reset() {
        seq = 0L;
        tsMs = 0L;
        tsNanos = 0L;
        src = SRC_UDP;
        dir = OUT;
        peerIp = 0;
        sp = 0;
        dp = 0;
        len = 0;
        copyLen = 0;
        decision = PASS;
        filterMask = 0;
        teleType = 0;
        cmd = -1;
        orderId = -1;
        flags = 0;
        note = null;
        phaseId = 0;
        matchSeq = 0;
        ctrlKind = null;
    }

    /**
     * Guarantees {@link #payload} can hold {@code n} bytes. Reuses the existing
     * array whenever it is big enough, which is the steady state after warm-up
     * (zero allocations per record).
     */
    public void ensureCapacity(int n) {
        if (payload == null || payload.length < n) {
            int cap = 64;
            while (cap < n) cap <<= 1;
            payload = new byte[cap];
        }
    }

    public static String srcName(int src) {
        switch (src) {
            case SRC_TCP: return "TCP";
            case SRC_CTRL: return "CTRL";
            default: return "UDP";
        }
    }

    public static String dirName(int dir) {
        return dir == IN ? "IN" : "OUT";
    }

    public static String decisionName(int decision) {
        switch (decision) {
            case DROP: return "DROP";
            case HOLD: return "HOLD";
            case REWRITE: return "REWRITE";
            default: return "PASS";
        }
    }
}
