package com.nepdon.wire.filter;

/**
 * Outcome of a {@link PacketFilter} decision for a single packet.
 *
 * <p>This is the policy result type shared by every filter in the engine. A
 * filter never mutates the tunnel directly; it only declares what should happen
 * to the packet, and the transport core acts on the decision.</p>
 */
public enum FilterDecision {
    /** Forward the packet normally. */
    PASS,

    /** Drop the packet (counted as a loss). */
    DROP,

    /** Hold the packet in a replay buffer; do not forward it now. */
    HOLD
}
