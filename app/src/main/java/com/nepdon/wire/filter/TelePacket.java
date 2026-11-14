package com.nepdon.wire.filter;

/**
 * A captured outbound packet held for delayed replay. Immutable value type that
 * replaces the old {@code _TP} inner class.
 */
public final class TelePacket {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_MOVE = 1;
    public static final int TYPE_FIRE = 2;

    public final byte[] data;
    public final int dip;
    public final int dp;
    public final int sp;
    public final int type;

    public TelePacket(byte[] data, int dip, int dp, int sp, int type) {
        this.data = data;
        this.dip = dip;
        this.dp = dp;
        this.sp = sp;
        this.type = type;
    }

    public static String typeName(int type) {
        return type == TYPE_MOVE ? "MOVE" : "FIRE";
    }
}
