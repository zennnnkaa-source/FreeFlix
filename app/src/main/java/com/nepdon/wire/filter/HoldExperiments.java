package com.nepdon.wire.filter;

/**
 * Hold / anti-hack switches. Defaults match measured safer path from watch.txt:
 * - do NOT drop 431/453 outside ghost (was causing multi-second loot lag)
 * - advance clientMs in-place during hold
 */
public final class HoldExperiments {

    public static final boolean REPLAY_FAST = false;
    public static final boolean RELEASE_ON_OWN_CORRECTION = false;

    /** Rewrite clientMs on ghost keepalive replay (in-place, fallback rebuild). */
    public static final boolean ADVANCE_CLIENT_MS = true;

    /** Small jitter on pingMs index 18 while advancing clientMs. */
    public static final boolean NUDGE_PING_MS = true;

    /**
     * Soft anti-hack ONLY while ghost is holding: drop live 431/453 and
     * slow-replay a cached pre-hold sample. Outside ghost everything passes.
     */
    public static final boolean ANTIHACK_SOFT = true;

    /** Drop gin-check / report cmds only while ghost is active. */
    public static final boolean ANTIHACK_DROP_ON_GHOST = true;

    private HoldExperiments() { }
}
