package com.nepdon.wire.decode.frame;

/**
 * The {@code SendOption} header field.
 *
 * <p>It is the client-side source of truth for reliability:</p>
 * <pre>
 *   IsReliable(cmd, sendOption) = sendOption in {HELLO, RELIABLE, VAR_RELIABLE}
 *                                 and not (cmd == UDP_ACK)
 * </pre>
 *
 * <p>Because MsgKey independently encodes the same fact (90..99 = reliable),
 * this field is the lever that breaks the one ambiguity the header solver
 * cannot resolve on its own: {@code SendOption} and {@code Flags} are both one
 * byte wide, so swapping them in {@code HEAD_SECRET} leaves every byte offset
 * unchanged and the Length identity cannot tell them apart.
 * {@link SessionKeySolver} scores candidate layouts on
 * {@link #impliesReliable(int)} agreeing with the MsgKey range.</p>
 */
public enum SendOption {

    /** Fire-and-forget; the common case for position and state traffic. */
    UNRELIABLE(0),
    /** Session hello. */
    HELLO(1),
    /** Reliable delivery with an OrderID. */
    RELIABLE(2),
    /** Variable-rate reliable delivery. */
    VAR_RELIABLE(5),
    /** Anything not named above. */
    UNKNOWN(-1);

    private final int code;

    SendOption(int code) {
        this.code = code;
    }

    /** The wire value, or -1 for {@link #UNKNOWN}. */
    public int code() {
        return code;
    }

    /** True when this option asks the transport for reliable delivery. */
    public boolean isReliable() {
        return this == HELLO || this == RELIABLE || this == VAR_RELIABLE;
    }

    /** Maps a wire value to a constant; never returns null. */
    public static SendOption of(int code) {
        switch (code) {
            case 0:  return UNRELIABLE;
            case 1:  return HELLO;
            case 2:  return RELIABLE;
            case 5:  return VAR_RELIABLE;
            default: return UNKNOWN;
        }
    }

    /**
     * True when a raw wire value belongs to the reliable set. Allocation-free
     * and branch-cheap: used on the solver's scoring path.
     */
    public static boolean impliesReliable(int code) {
        return code == 1 || code == 2 || code == 5;
    }
}
