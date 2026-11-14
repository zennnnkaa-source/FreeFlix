package com.nepdon.wire.decode.frame;

/** Which way a packet is travelling relative to this device. */
public enum Direction {

    /** Client to server (read off the tun device). */
    OUTBOUND,
    /** Server to client (read off a protected UDP socket). */
    INBOUND;

}
