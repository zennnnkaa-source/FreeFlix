package com.nepdon.wire.dev;

/**
 * Optional developer-mode packet logger. Both the transport core and the filter
 * engine log through this abstraction; the concrete implementation buffers
 * events and dumps a report file when dev mode is disabled.
 */
public interface DevLogger {

    boolean isEnabled();

    /** Log a packet event (only recorded when {@link #isEnabled()}). */
    void logPacket(String kind, String direction, byte[] data, int off, int len,
                   int sourcePort, int destPort, int peerIp, String note);

    /** Log a control/state event. */
    void logControl(String kind, String note);

    /** Log a protocol-level drop (non-IPv4 / non-UDP). */
    void logProtocolDrop(String kind, byte[] data, int off, int len,
                         int sourcePort, int destPort, int peerIp, String note);
}
