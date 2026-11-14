package com.nepdon.wire.protocol;

/**
 * Counts inbound reliable packets by OrderID over a short window. The server
 * sends an OrderID again only while it has not been ACKed, so a repeat is the
 * server reporting that our ACKs are being withheld. It needs nothing from the
 * ACK payload: only the OrderID of a CRC-valid reliable header.
 *
 * <p>The ring holds 512 ids so an id survives long enough to be counted again
 * at match-level packet rates. Inbound can run on several session threads, so
 * {@link #observe} is synchronized; the lock is uncontended at this rate.</p>
 */
public final class RetransmitWatch {

    /** Process-wide instance: the tunnel feeds it, ServerSignal publishes the result. */
    public static final RetransmitWatch SHARED = new RetransmitWatch();

    static final int SLOTS = 512;
    static final long WINDOW_MS = 4_000L;
    /** Third receipt of one OrderID inside the window, i.e. two resends already. */
    static final int ALARM_COUNT = 3;

    private final int[] ids = new int[SLOTS];
    private final int[] counts = new int[SLOTS];
    private final long[] firstMs = new long[SLOTS];
    private int next = 0;

    /** Returns true when this receipt crosses the alarm count. */
    public synchronized boolean observe(int orderId, long now) {
        for (int i = 0; i < SLOTS; i++) {
            if (counts[i] > 0 && ids[i] == orderId) {
                if (now - firstMs[i] > WINDOW_MS) {
                    firstMs[i] = now;
                    counts[i] = 1;
                    return false;
                }
                counts[i]++;
                return counts[i] >= ALARM_COUNT;
            }
        }
        int s = next;
        next = (next + 1) % SLOTS;
        ids[s] = orderId;
        counts[s] = 1;
        firstMs[s] = now;
        return false;
    }
}
