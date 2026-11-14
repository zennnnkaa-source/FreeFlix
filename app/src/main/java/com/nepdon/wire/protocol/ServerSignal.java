package com.nepdon.wire.protocol;

/**
 * Two server-side signals the auto features listen to. Written on tunnel
 * threads and read on the main looper, so both are volatile wall-clock stamps.
 */
public final class ServerSignal {

    private static volatile long ownCorrectionMs = 0L;
    private static volatile long retransmitAlarmMs = 0L;

    private ServerSignal() { }

    /** The server force-synced OUR OWN player: it rejected or rewound our state. */
    public static void noteOwnCorrection(long nowMs) {
        ownCorrectionMs = nowMs;
    }

    /** The server resent one reliable message several times: our ACKs are being withheld. */
    public static void noteRetransmitAlarm(long nowMs) {
        retransmitAlarmMs = nowMs;
    }

    public static long ownCorrectionMs() {
        return ownCorrectionMs;
    }

    public static long retransmitAlarmMs() {
        return retransmitAlarmMs;
    }
}
