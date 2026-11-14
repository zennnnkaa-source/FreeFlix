package com.nepdon.wire.filter;

/**
 * Orchestrates the set of active {@link PacketFilter} policies. This is the
 * boundary the transport core depends on: it parses packets and asks the engine
 * for a {@link FilterDecision}, never touching individual filters or buffers.
 *
 * <p>The concrete implementation owns the filter instances, their mutual
 * exclusion, and the replay threads. It is constructed with a
 * {@link PacketSender} so replay packets flow back through the transport.</p>
 */
public interface FilterEngine {

    /** Decide what to do with an outbound packet. */
    FilterDecision onOutbound(PacketContext ctx);

    /** Decide what to do with an inbound packet. */
    FilterDecision onInbound(PacketContext ctx);

    /** Toggle the given effect on/off (enforcing mutual exclusion). */
    void toggle(FilterKind kind);

    /** True while the given effect is currently active (used for capture markers). */
    boolean isKindActive(FilterKind kind);

    /** Deactivate every effect and flush pending replays. */
    void disableAll();

    /**
     * Tunnel restart boundary: drop every filter-held buffer and abort any
     * in-flight replay. The game's encoding rotates per session, so
     * pre-restart packets replayed into the new session would be protocol
     * garbage. Active states survive (the user's toggles stay on) — only
     * stale DATA is discarded.
     */
    void onTunnelRestart();

    /**
     * The server force-synced our own position (inbound {@code Cmd 144}).
     *
     * <p>Coordinates are in millimetres, in the same X/Z/Y order as the
     * outbound position report. Teleport uses this as a direct "your rewrite
     * was refused" signal.</p>
     */
    void onOwnForceSync(long x, long z, long y);

    /**
     * True when the kind is still editing packets even though its toggle reads
     * off — today only teleport's RAMP.
     *
     * <p>Separate from {@link #isKindActive}, which is what the UI paints: the
     * button must read OFF during a release the user already asked to end. The
     * capture wants the opposite answer, so it asks a different question.</p>
     */
    boolean isKindRewriting(FilterKind kind);

    /** True if any effect is currently active. */
    boolean hasAnyActive();

    /** Release all buffers and threads. */
    void shutdown();
}
