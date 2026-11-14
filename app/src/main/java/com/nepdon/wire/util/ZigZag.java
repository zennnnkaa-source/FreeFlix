package com.nepdon.wire.util;

/**
 * ZigZag signed-integer encoding, as used by protobuf and by this game's wire
 * format for every signed field (positions, velocities, aim components).
 *
 * <p>Maps signed values onto unsigned ones so that small magnitudes of either
 * sign stay short: {@code 0,-1,1,-2,2 -> 0,1,2,3,4}.</p>
 *
 * <p><b>The decode shift must be {@code >>>}.</b> Six copies of this existed
 * and one used {@code >>}, transliterated from a Python reference where
 * integers are unbounded and the distinction does not exist. In Java an
 * arithmetic shift drags the sign bit down, so any value with bit 63 set
 * decodes negative when it must decode positive. That copy has been corrected;
 * this is the only implementation that should survive.</p>
 */
public final class ZigZag {

    private ZigZag() {}

    /** Unsigned wire value -> signed value. */
    public static long decode(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    /** Signed value -> unsigned wire value. */
    public static long encode(long v) {
        return (v << 1) ^ (v >> 63);
    }

}
