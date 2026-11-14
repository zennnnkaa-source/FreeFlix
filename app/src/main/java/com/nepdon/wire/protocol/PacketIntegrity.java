package com.nepdon.wire.protocol;

import com.nepdon.wire.decode.frame.PacketCodec;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The single door every packet mutation leaves by.
 *
 * <h3>Why this exists</h3>
 * <p>CRC-7 covers the packet <b>as transmitted</b>:</p>
 * <pre>
 *   packet[1] == crc7(packet[2:], seed 0)
 * </pre>
 * <p>Verified 100% over 25 596 packets, both directions, encrypted and
 * plaintext. It spans the masked header fields and the whole payload, so
 * rewriting <b>any</b> byte at offset &gt;= 2 — a coordinate, a flag, a length
 * — leaves byte 1 stale and the server treats the packet as corrupt. That is
 * the leading suspect for the "server sets us back" behaviour and the
 * distance-budget rejections in {@code docs/TELEPORT.md}.</p>
 *
 * <h3>Verify, then repair — never blind-rewrite</h3>
 * <p>{@link PacketCodec#refreshCrcIfMatchTraffic} on its own would happily
 * stamp byte 1 of anything whose byte 0 falls in the MsgKey range 90..110.
 * That range covers ~8% of arbitrary UDP payloads, so on a shared transport it
 * would corrupt other applications' traffic. {@link #seal} therefore checks
 * first and only writes when the packet both <i>is</i> match traffic and
 * <i>has</i> a stale checksum — which, by construction, only a mutation of our
 * own can produce.</p>
 *
 * <p>Cost: one CRC-7 pass over the packet. At the observed ~15 packets/sec on
 * the replay path that is nothing, and it allocates nothing.</p>
 */
public final class PacketIntegrity {

    /** Not Free Fire match traffic: nothing was touched. */
    public static final int SEAL_FOREIGN = 0;
    /** Match traffic whose checksum already agreed: nothing was written. */
    public static final int SEAL_INTACT = 1;
    /** Match traffic whose checksum was stale: byte 1 was recomputed. */
    public static final int SEAL_REPAIRED = 2;

    private static final AtomicLong SEALED = new AtomicLong();
    private static final AtomicLong REPAIRED = new AtomicLong();

    private PacketIntegrity() { }

    /**
     * Makes a packet safe to transmit after any in-place edit.
     *
     * <p>Call this immediately before handing bytes to the transport, on every
     * path that could have rewritten them: the ghost anchor replay, the
     * jitter/teleport replay, and any future coordinate rewrite.</p>
     *
     * @return one of {@link #SEAL_FOREIGN}, {@link #SEAL_INTACT},
     *         {@link #SEAL_REPAIRED}
     */
    public static int seal(byte[] pkt, int off, int len) {
        if (PacketCodec.isIntactMatchTraffic(pkt, off, len)) {
            SEALED.incrementAndGet();
            return SEAL_INTACT;
        }
        // Either foreign traffic (leave it alone — refreshCrcIfMatchTraffic
        // declines it) or ours with a checksum somebody invalidated.
        if (PacketCodec.refreshCrcIfMatchTraffic(pkt, off, len)) {
            SEALED.incrementAndGet();
            REPAIRED.incrementAndGet();
            return SEAL_REPAIRED;
        }
        return SEAL_FOREIGN;
    }

    /** Convenience for a whole-array packet. */
    public static int seal(byte[] pkt) {
        return pkt == null ? SEAL_FOREIGN : seal(pkt, 0, pkt.length);
    }

    /** Zeroes the counters (tunnel restart). */
    public static void resetCounters() {
        SEALED.set(0);
        REPAIRED.set(0);
    }
}
