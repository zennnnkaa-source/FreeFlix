package com.nepdon.wire.filter;

/**
 * A single fake-lag policy. Each effect (freeze, ghost, jitter, jitter-v2,
 * delay-ping, tele-speed) is one implementation of this interface, following
 * policy-based design: the engine composes independent policies instead of
 * branching on boolean flags.
 *
 * <p>Implementations must be safe to call from the tun read thread and from the
 * replay threads. They own their own buffers and replay state.</p>
 */
public interface PacketFilter {

    /** Stable short name used in dev logs (e.g. "freeze", "ghost", "tele"). */
    String name();

    /** True while this filter is actively applying its effect. */
    boolean isActive();

    /** Called for each outbound packet. */
    FilterDecision onOutbound(PacketContext ctx);

    /** Called for each inbound packet (before it is written back to the tun). */
    FilterDecision onInbound(PacketContext ctx);

    /** Activate the effect. */
    void start();

    /** Deactivate the effect and flush any pending replay. */
    void stop();

    /** Release all buffers and threads; called on service shutdown. */
    void shutdown();
}
