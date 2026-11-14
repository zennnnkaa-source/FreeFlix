package com.nepdon.wire.util;

/**
 * Per-instance xorshift source for jittering fixed periods. Seeded from the
 * clock so two runs never share a timing sequence. Not thread-safe: each owner
 * keeps its own instance and calls it from a single thread.
 */
public final class Humanize {

    private long state;

    public Humanize() {
        long seed = System.nanoTime() ^ (System.currentTimeMillis() << 17) ^ 0x9E3779B97F4A7C15L;
        state = seed != 0L ? seed : 0x2545F4914F6CDD1DL;
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        long x = state;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        state = x;
        return (x >>> 11) * (1.0 / (1L << 53));
    }

    /** baseMs scaled by a uniform factor in [1 - frac, 1 + frac]. Never below 1 ms. */
    public long jitterMs(long baseMs, double frac) {
        double f = 1.0 + frac * (2.0 * nextDouble() - 1.0);
        long v = Math.round(baseMs * f);
        return v < 1L ? 1L : v;
    }

    /**
     * baseMs plus a uniform extra in [0, baseMs * frac). Use where baseMs is a
     * FLOOR the original code relies on (a minimum gap, a minimum cooldown):
     * the result is never shorter than baseMs.
     */
    public long upMs(long baseMs, double frac) {
        long v = baseMs + Math.round(baseMs * frac * nextDouble());
        return v < 1L ? 1L : v;
    }
}
