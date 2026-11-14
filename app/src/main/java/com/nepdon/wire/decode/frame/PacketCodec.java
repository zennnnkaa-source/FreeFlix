package com.nepdon.wire.decode.frame;

/**
 * Reads and writes the Free Fire Max UDP header.
 *
 * <p>Decode procedure, verbatim from the spec:</p>
 * <pre>
 *   1. match traffic: 90 &lt;= b0 &lt;= 110
 *   2. hdr = (90 &lt;= b0 &lt;= 99) ? 10 : 8
 *   3. solve (mask, HEAD_SECRET) from traffic          -- SessionKeySolver
 *   4. unmask b2..hdr-1 and read the five fields in HEAD_SECRET order
 *   5. verify crc7(pkt[2:]) == pkt[1]
 *   6. payload = pkt[hdr : hdr + Length]
 * </pre>
 *
 * <p><b>Every mutation of a packet must come back through
 * {@link #reserialize}.</b> CRC-7 covers the bytes as transmitted, so rewriting
 * any byte at offset &gt;= 2 — a position, a flag, a length — leaves byte 1
 * stale and the server treats the packet as corrupt. That is the leading
 * suspect for teleport's "server sets us back" behaviour and for the
 * distance-budget rejections in {@code docs/TELEPORT.md}: the rewrite looked
 * plausible on the wire but failed the checksum.</p>
 */
public final class PacketCodec {

    private PacketCodec() { }

    // ---- decode ----

    /**
     * Parses a packet with a solved layout.
     *
     * @return the parsed packet, or null when the buffer is not match traffic
     *         or is too short for its declared header
     */
    public static UdpPacket parse(byte[] pkt, int off, int len, HeaderLayout layout) {
        if (layout == null || pkt == null) return null;
        if (off < 0 || len < 0 || off + len > pkt.length) return null;

        int msgKey = len > 0 ? (pkt[off] & 0xFF) : -1;
        int hdr = MsgKey.headerSize(msgKey);
        if (hdr == MsgKey.NOT_MATCH_TRAFFIC || len < hdr) return null;

        boolean reliable = (hdr == MsgKey.HEADER_RELIABLE);
        int checksum = pkt[off + Crc7.CHECKSUM_INDEX] & 0xFF;

        int sendOption = layout.readByte(pkt, off, HeaderField.SEND_OPTION, reliable);
        int cmd = layout.readShort(pkt, off, HeaderField.CMD, reliable);
        int orderId = reliable ? layout.readShort(pkt, off, HeaderField.ORDER_ID, reliable) : -1;
        int flags = layout.readByte(pkt, off, HeaderField.FLAGS, reliable);
        int declared = layout.readShort(pkt, off, HeaderField.DATA_LEN, reliable);

        boolean crcOk = checksum == Crc7.ofPacket(pkt, off, len);

        return new UdpPacket(pkt, off, len, msgKey, checksum, reliable, hdr,
                sendOption, cmd, orderId, flags, declared, crcOk);
    }

    // ---- checksum maintenance ----

    /**
     * Recomputes byte 1 over the packet as it now stands. This is the whole of
     * the "reserialize" contract when only the body changed.
     *
     * @return false when the buffer is too short to carry a checksum
     */
    public static boolean reserialize(byte[] pkt, int off, int len) {
        if (pkt == null || off < 0 || len < 3 || off + len > pkt.length) return false;
        pkt[off + Crc7.CHECKSUM_INDEX] = (byte) Crc7.ofPacket(pkt, off, len);
        return true;
    }

    /**
     * Rewrites the five header fields with {@code layout} and then recomputes
     * the checksum. Pass -1 for any field that should keep its current value;
     * {@code orderId} is ignored on non-reliable packets.
     *
     * @return false when the buffer is not match traffic or is too short
     */
    public static boolean reserialize(byte[] pkt, int off, int len, HeaderLayout layout,
                                      int sendOption, int cmd, int orderId, int flags, int dataLen) {
        if (layout == null || pkt == null) return false;
        if (off < 0 || len < 3 || off + len > pkt.length) return false;

        int hdr = MsgKey.headerSize(pkt[off] & 0xFF);
        if (hdr == MsgKey.NOT_MATCH_TRAFFIC || len < hdr) return false;
        boolean reliable = (hdr == MsgKey.HEADER_RELIABLE);

        if (sendOption >= 0) layout.writeByte(pkt, off, HeaderField.SEND_OPTION, reliable, sendOption);
        if (cmd >= 0) layout.writeShort(pkt, off, HeaderField.CMD, reliable, cmd);
        if (orderId >= 0 && reliable) layout.writeShort(pkt, off, HeaderField.ORDER_ID, reliable, orderId);
        if (flags >= 0) layout.writeByte(pkt, off, HeaderField.FLAGS, reliable, flags);
        if (dataLen >= 0) layout.writeShort(pkt, off, HeaderField.DATA_LEN, reliable, dataLen);

        return reserialize(pkt, off, len);
    }

    /**
     * Repairs the checksum of a buffer that is match traffic, and leaves
     * anything else untouched.
     *
     * <p>This is the guard on the transport's egress: every replayed, held or
     * rewritten packet passes through it, so a body edit anywhere upstream can
     * no longer ship a stale CRC. Packets that were never modified are simply
     * rewritten with the value they already had.</p>
     *
     * @return true when the buffer was recognised as match traffic and its
     *         checksum is now correct
     */
    public static boolean refreshCrcIfMatchTraffic(byte[] pkt, int off, int len) {
        if (MsgKey.headerSizeOf(pkt, off, len) == MsgKey.NOT_MATCH_TRAFFIC) return false;
        return reserialize(pkt, off, len);
    }

    /** True when a buffer is match traffic whose checksum already agrees. */
    public static boolean isIntactMatchTraffic(byte[] pkt, int off, int len) {
        if (MsgKey.headerSizeOf(pkt, off, len) == MsgKey.NOT_MATCH_TRAFFIC) return false;
        return Crc7.verify(pkt, off, len);
    }
}
