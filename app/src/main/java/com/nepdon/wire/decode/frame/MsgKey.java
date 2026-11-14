package com.nepdon.wire.decode.frame;

/**
 * Packet byte 0 — the only plaintext framing discriminator in the Free Fire
 * Max UDP protocol.
 *
 * <p>{@code GenMsgKey} re-randomises it per packet, so the <b>value</b> carries
 * no identity at all; only its <b>range</b> is meaningful:</p>
 *
 * <pre>
 *   90 .. 99    reliable      header 10 bytes
 *  100 ..109    non-reliable  header  8 bytes
 * </pre>
 *
 * <p>MIN/MID/MAX were established three independent ways — measured from
 * captures, recovered from {@code .rodata:0x18BB4E0}, and read live out of the
 * running process. Every one of 16 078 packets with MsgKey 90..99 satisfies
 * {@code len % 8 == 2}, i.e. a 10-byte header plus an 8-aligned body.</p>
 *
 * <p>This is also the <b>only</b> thing that tells a receiver the header size:
 * every other header byte is XOR-masked and the five fields are permuted per
 * session, so nothing else in the header can be read before the session key is
 * solved. Identify match traffic by this range and never by port — 10013 and
 * 10014 are both observed.</p>
 *
 * @see PacketCodec
 */
public final class MsgKey {

    /** Lowest valid MsgKey (inclusive). */
    public static final int MIN = 90;
    /** Boundary between the reliable and non-reliable ranges (exclusive lower bound of non-reliable). */
    public static final int MID = 100;
    /** Highest valid MsgKey (inclusive). */
    public static final int MAX = 110;

    /** Header size in bytes for a reliable packet (carries OrderID). */
    public static final int HEADER_RELIABLE = 10;
    /** Header size in bytes for a non-reliable packet (no OrderID). */
    public static final int HEADER_NON_RELIABLE = 8;

    /** Returned by {@link #headerSize(int)} when the key is not match traffic. */
    public static final int NOT_MATCH_TRAFFIC = -1;

    private MsgKey() { }

    /** True when this byte-0 value marks the packet as match-server traffic. */
    public static boolean isValid(int msgKey) {
        return msgKey >= MIN && msgKey <= MAX;
    }

    /** True when this MsgKey marks a reliable (RUDP) packet, i.e. one with an OrderID. */
    public static boolean isReliable(int msgKey) {
        return msgKey >= MIN && msgKey < MID;
    }

    /**
     * Header size implied by byte 0, or {@link #NOT_MATCH_TRAFFIC} when the
     * value is outside the match range.
     */
    public static int headerSize(int msgKey) {
        if (msgKey >= MIN && msgKey < MID) return HEADER_RELIABLE;
        if (msgKey >= MID && msgKey <= MAX) return HEADER_NON_RELIABLE;
        return NOT_MATCH_TRAFFIC;
    }

    /** Header size for a packet buffer, or {@link #NOT_MATCH_TRAFFIC}. */
    public static int headerSizeOf(byte[] pkt, int off, int len) {
        if (pkt == null || off < 0 || len <= 0 || off + len > pkt.length) {
            return NOT_MATCH_TRAFFIC;
        }
        int hdr = headerSize(pkt[off] & 0xFF);
        return (hdr > 0 && len >= hdr) ? hdr : NOT_MATCH_TRAFFIC;
    }
}
