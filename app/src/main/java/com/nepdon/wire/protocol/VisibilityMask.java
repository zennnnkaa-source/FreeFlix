package com.nepdon.wire.protocol;

import com.nepdon.wire.decode.frame.UdpPacket;

/**
 * Tracks {@code Cmd 135 RUDP_SYNC_PLAYERS_VISIBILITY} — the server's own
 * statement of which players it considers relevant to us.
 *
 * <h3>Why this is worth reading</h3>
 *
 * <p>We have spent a long time inferring whether the server limits which
 * enemies it tells us about. It does not need inferring: <b>it tells us,
 * explicitly, as a 64-bit bitmap, on its own schedule.</b>
 * {@link #visibleCount()} is the size of the server's relevance set, and
 * {@link #isVisible(int)} names exactly who is in it.</p>
 *
 * <h3>Semantics, settled from the binary</h3>
 *
 * <ul>
 *   <li><b>A SET bit means that player IS visible to us.</b> Confirmed three
 *       independent ways. Our earlier capture-side hunch that the sense looked
 *       inverted is contradicted by the code — the likeliest cause was indexing
 *       against roster position instead of entity id.</li>
 *   <li><b>Bit {@code i} is the player whose entity id is {@code i}.</b> No
 *       {@code +1}, no roster indirection. The id struct precomputes
 *       {@code 1ULL << id} into a dedicated field, which is not something you
 *       write unless the mask is first-class protocol.</li>
 *   <li>The payload is exactly <b>one plain LEB128 varint</b> (a {@code ulong},
 *       so up to 10 bytes) and nothing else.</li>
 * </ul>
 *
 * <p><b>What the mask does NOT do:</b> it does not gate the inbound position
 * stream client-side. The 2001/2012 handlers never consult it — a position for
 * a cleared-bit entity is still applied to the player object, merely not
 * rendered. Whether the <i>server</i> also withholds those positions is server
 * policy the binary cannot answer, and
 * {@link #streamedWhileHidden()} exists to measure it: it counts entity updates
 * that arrived for a player whose bit was clear. <b>If that stays at zero
 * across a match, the server really does gate the stream and this mask is a
 * faithful proxy for our effective range. If it climbs, the mask is a rendering
 * directive only and the range limit lives somewhere else.</b></p>
 *
 * <p>Watch one edge case while interpreting: {@code 1ULL << 64 == 1ULL << 0},
 * and the largest entity id we have measured is 64, which would alias onto
 * bit 0.</p>
 *
 * <p>See {@code docs/IL2CPP_FINDINGS.md} §5.4.</p>
 */
public final class VisibilityMask {

    /** Cmd 135 — players. Siblings: 136 vehicles, 386 move-platforms. */
    public static final int CMD_SYNC_PLAYERS_VISIBILITY = 135;

    private static volatile long mask;
    private static volatile long updates;
    private static volatile long lastMs;
    private static volatile int peakVisible;
    private static final java.util.concurrent.atomic.AtomicLong hiddenStream =
            new java.util.concurrent.atomic.AtomicLong();

    private VisibilityMask() { }

    /**
     * Feeds one inbound packet. Does nothing unless it is Cmd 135.
     *
     * @return true when the packet was a visibility sync and was consumed
     */
    public static boolean observe(UdpPacket p) {
        if (p == null || p.cmd() != CMD_SYNC_PLAYERS_VISIBILITY) return false;
        if (p.isEncrypted() || p.isZipped()) return false;
        byte[] b = p.buffer();
        int i = p.payloadOffset();
        int end = i + p.payloadLength();
        long v = 0;
        int shift = 0;
        while (i < end) {
            int c = b[i++] & 0xff;
            v |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) {
                // Must consume the payload exactly: the message is one varint
                // and nothing else, so a residue means we mis-identified it.
                if (i != end) return false;
                mask = v;
                updates++;
                lastMs = System.currentTimeMillis();
                int n = Long.bitCount(v);
                if (n > peakVisible) peakVisible = n;
                return true;
            }
            shift += 7;
            if (shift > 63) return false;
        }
        return false;
    }

    /** The most recent mask, or 0 before any has arrived. */
    public static long mask() {
        return mask;
    }

    /** How many players the server currently considers relevant to us. */
    public static int visibleCount() {
        return Long.bitCount(mask);
    }

    /** True when the server currently considers this entity id relevant. */
    public static boolean isVisible(int entityId) {
        if (entityId < 0) return false;
        return (mask & (1L << (entityId & 63))) != 0L;
    }

    /** True once at least one mask has arrived. */
    public static boolean hasMask() {
        return updates > 0;
    }

    /**
     * Records that an entity update arrived for a player the server had marked
     * hidden. <b>This is the measurement that decides whether the mask gates
     * the stream</b> — see the class comment.
     */
    public static void noteStreamedWhileHidden() {
        hiddenStream.incrementAndGet();
    }

    public static void reset() {
        mask = 0;
        updates = 0;
        peakVisible = 0;
        lastMs = 0;
        hiddenStream.set(0);
    }

    /** One line for the dev panel. */
    public static String debugLine() {
        if (updates == 0) return "no mask yet";
        long age = System.currentTimeMillis() - lastMs;
        return "vis=" + visibleCount()
                + " peak=" + peakVisible
                + " n=" + updates
                + " age=" + (age > 99999 ? 99999 : age) + "ms"
                + " hidstream=" + hiddenStream.get();
    }
}
