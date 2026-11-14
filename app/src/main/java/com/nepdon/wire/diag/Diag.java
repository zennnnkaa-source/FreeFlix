package com.nepdon.wire.diag;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The module-health registry: one counter per place the code can silently
 * decline to do its job, plus a small ring of rare-but-interesting events.
 *
 * <h3>Contract</h3>
 *
 * <p>{@link #hit(int)} is <b>always safe to call from any thread, on any
 * path</b>. It allocates nothing, formats nothing, takes no lock, and never
 * throws. It is designed to be callable from the TUN read loop (up to ~1000
 * packets/sec), from the per-session receive threads, from {@code classify()}
 * (per entity per packet) and from the 120 Hz render loop without anyone
 * having to think about cost.</p>
 *
 * <h3>Why atomics, and why that is not slow here</h3>
 *
 * <p>An {@code AtomicIntegerArray} increment is a single CAS — order 10 ns
 * uncontended. The worst realistic aggregate is roughly 10 000 hits/sec
 * (packets, entities and frames combined), so under 0.1 ms per second of
 * wall-clock. A plain {@code int[]} would be marginally faster but loses
 * increments under the six-plus threads this app runs, and a diagnostic that
 * under-reports is worse than one that costs 0.01%.</p>
 *
 * <p>{@code long} was rejected deliberately: {@code minSdk 21} admits 32-bit
 * ARM, where a non-atomic 64-bit write can tear. {@code int} wraps at ~2.1
 * billion, which at 1000/sec is 24 days — far beyond any session.</p>
 *
 * <h3>What this is NOT</h3>
 *
 * <p>Not a logger. A counter answers "how often", never "with what". When a
 * site needs detail, use {@link #event} — a bounded ring for things that
 * happen a handful of times a session (a revoke, a key rotation, a rejected
 * seed). Putting an event on a per-packet path defeats the whole design; the
 * ring is small on purpose so that mistake shows up as truncation rather than
 * as a memory problem.</p>
 *
 * <h3>Lifetime</h3>
 *
 * <p>Counters are cumulative for the process and are never reset by a match
 * boundary — {@link #snapshot} reports deltas instead, so a rate is visible
 * without anyone having to decide when "now" started. {@link #reset} exists
 * for tests and for an explicit operator request, nothing else.</p>
 */
public final class Diag {

    private Diag() {}

    private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(Slot.COUNT);

    /** Values at the previous {@link #snapshot}, for delta reporting. */
    private static final int[] PREV = new int[Slot.COUNT];

    /**
     * Deltas for the window being reported.
     *
     * <p>Computed up front, BEFORE {@link #PREV} is advanced, because
     * {@link Invariants} runs after the per-slot emission and would otherwise
     * see every delta as zero. Package-private and only meaningful inside a
     * {@link #snapshot} call.</p>
     */
    private static final int[] DELTAS = new int[Slot.COUNT];

    /** Delta for one slot within the window currently being reported. */
    static int deltaOf(int slot) {
        return (slot < 0 || slot >= Slot.COUNT) ? 0 : DELTAS[slot];
    }
    private static volatile long prevSnapshotMs = 0L;

    // ---- rare-event ring ----

    /** Deliberately small: an event on a hot path should hurt, visibly. */
    public static final int EVENT_RING = 64;

    private static final long[] evMs = new long[EVENT_RING];
    private static final int[] evSlot = new int[EVENT_RING];
    private static final String[] evText = new String[EVENT_RING];
    private static int evHead = 0;
    private static int evCount = 0;
    private static int evDropped = 0;
    private static final Object EV_LOCK = new Object();

    /** Records one occurrence. Safe everywhere; never throws. */
    public static void hit(int slot) {
        if (slot < 0 || slot >= Slot.COUNT) return;
        COUNTS.incrementAndGet(slot);
    }

    /** Records {@code n} occurrences at once, for batch paths. */
    public static void hit(int slot, int n) {
        if (slot < 0 || slot >= Slot.COUNT || n <= 0) return;
        COUNTS.addAndGet(slot, n);
    }

    /** Current cumulative value of one slot. */
    public static int get(int slot) {
        if (slot < 0 || slot >= Slot.COUNT) return 0;
        return COUNTS.get(slot);
    }

    /**
     * Records a rare event with detail, and counts it.
     *
     * <p>{@code text} should be short and already formatted. <b>Do not call
     * this from a per-packet or per-frame path</b> — it takes a lock and
     * retains a String.</p>
     */
    public static void event(int slot, String text) {
        hit(slot);
        if (text == null) return;
        synchronized (EV_LOCK) {
            if (evCount >= EVENT_RING) {
                evDropped++;
            }
            evMs[evHead] = System.currentTimeMillis();
            evSlot[evHead] = slot;
            evText[evHead] = text;
            evHead = (evHead + 1) % EVENT_RING;
            if (evCount < EVENT_RING) evCount++;
        }
    }

    /**
     * Renders the current state: every non-zero slot with its cumulative value
     * and its delta since the previous call, then the event ring, then any
     * alarms {@link Invariants} raises.
     *
     * <p>Calling this advances the delta baseline, so it is meant to be called
     * on a timer by one owner — not opportunistically from several places, or
     * the deltas become meaningless.</p>
     */
    public static synchronized String snapshot() {
        long now = System.currentTimeMillis();
        long sinceMs = (prevSnapshotMs == 0L) ? 0L : (now - prevSnapshotMs);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("# DIAG window_ms=").append(sinceMs).append('\n');

        // Compute every delta FIRST. Invariants run after the emission loop and
        // need this window's deltas, which are gone once PREV is advanced.
        for (int i = 0; i < Slot.COUNT; i++) {
            DELTAS[i] = COUNTS.get(i) - PREV[i];
        }

        for (int i = 0; i < Slot.COUNT; i++) {
            int v = COUNTS.get(i);
            if (v == 0) continue;                 // never emitted, never fired
            int d = DELTAS[i];
            PREV[i] = v;
            sb.append("# DIAG ").append(Slot.name(i))
              .append(" total=").append(v)
              .append(" d=").append(d);
            if (sinceMs > 0 && d > 0) {
                sb.append(" rate=").append(String.format("%.1f/s", d * 1000.0 / sinceMs));
            }
            sb.append('\n');
        }

        synchronized (EV_LOCK) {
            if (evCount > 0) {
                int start = (evHead - evCount + EVENT_RING) % EVENT_RING;
                for (int k = 0; k < evCount; k++) {
                    int i = (start + k) % EVENT_RING;
                    sb.append("# DIAGEV ").append(evMs[i]).append(' ')
                      .append(Slot.name(evSlot[i])).append(' ')
                      .append(evText[i]).append('\n');
                }
                // Consume: an event reported once has done its job, and
                // repeating it every window buries the new ones.
                evCount = 0;
                evHead = 0;
            }
            if (evDropped > 0) {
                sb.append("# DIAGEV dropped=").append(evDropped)
                  .append(" (an event is on a hot path -- find it)\n");
                evDropped = 0;
            }
        }

        String alarms = Invariants.evaluate(sinceMs);
        if (!alarms.isEmpty()) sb.append(alarms);

        prevSnapshotMs = now;
        return sb.toString();
    }

    /** Test and explicit-operator use only. */
    public static synchronized void reset() {
        for (int i = 0; i < Slot.COUNT; i++) {
            COUNTS.set(i, 0);
            PREV[i] = 0;
        }
        synchronized (EV_LOCK) {
            evCount = 0;
            evHead = 0;
            evDropped = 0;
        }
        prevSnapshotMs = 0L;
    }
}
