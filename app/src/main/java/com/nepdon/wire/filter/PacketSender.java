package com.nepdon.wire.filter;

/**
 * Sends a buffered {@link TelePacket} back out to its destination. Implemented by
 * the transport core (which owns the UDP sessions and the {@code protect()}
 * capability); the filter engine depends on this abstraction instead of reaching
 * into the tunnel internals.
 */
public interface PacketSender {

    /**
     * Send a buffered packet. Implementations should prefer the existing UDP
     * session for the packet's 4-tuple and fall back to a fresh protected socket.
     *
     * @return true if the packet was sent, false otherwise.
     */
    boolean send(TelePacket tp);
}
