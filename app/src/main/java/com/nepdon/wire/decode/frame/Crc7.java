package com.nepdon.wire.decode.frame;

/**
 * CRC-7/MMC (polynomial 0x09) — the checksum Free Fire Max carries in UDP
 * packet byte 1.
 *
 * <pre>
 *   packet[1] == crc7(packet[2:], seed = 0)
 * </pre>
 *
 * <p>Measured <b>25 596 / 25 596 = 100.0%</b> in one session and 100% in every
 * other session tested, across both directions, encrypted and plaintext.
 * Rejected alternatives: {@code b0 + b2..end} 0.0%, header only 0.9%, payload
 * only 57.8%.</p>
 *
 * <p>The table is the Linux {@code crc7_syndrome_table} shifted right by one
 * (the kernel stores the value pre-shifted into the high 7 bits). It is
 * generated here rather than transcribed so there is nothing to mistype.</p>
 *
 * <p><b>The CRC covers the bytes as transmitted</b> — masked and encrypted, in
 * wire order — and spans the header fields plus the whole payload. MsgKey and
 * the CRC byte itself are excluded. Consequently <b>any</b> rewrite of a byte
 * at offset &gt;= 2 invalidates it, and the server then sees a corrupt packet.
 * Route every mutation through {@link PacketCodec#reserialize}.</p>
 */
public final class Crc7 {

    /** Index of the checksum byte inside a packet. */
    public static final int CHECKSUM_INDEX = 1;
    /** First byte covered by the checksum. */
    public static final int COVERED_FROM = 2;

    /** CRC-7/MMC table, 7-bit values (poly 0x09, reflected into 0x12 while shifting left). */
    private static final byte[] TABLE = buildTable();

    private Crc7() { }

    private static byte[] buildTable() {
        byte[] t = new byte[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int bit = 0; bit < 8; bit++) {
                c = ((c & 0x80) != 0) ? (((c << 1) ^ 0x12) & 0xFF) : ((c << 1) & 0xFF);
            }
            t[i] = (byte) (c >> 1);
        }
        return t;
    }

    /** CRC-7 over {@code buf[off, off+len)} with the protocol's seed of 0. */
    public static int compute(byte[] buf, int off, int len) {
        return compute(buf, off, len, 0);
    }

    /** CRC-7 over {@code buf[off, off+len)} starting from an explicit seed. */
    public static int compute(byte[] buf, int off, int len, int seed) {
        int crc = seed & 0x7F;
        int end = off + len;
        for (int i = off; i < end; i++) {
            crc = TABLE[(buf[i] ^ (crc << 1)) & 0xFF] & 0xFF;
        }
        return crc;
    }

    /**
     * The checksum a packet <i>should</i> carry: CRC-7 over everything from
     * byte 2 to the end. Callers must have validated {@code len >= 2}.
     */
    public static int ofPacket(byte[] pkt, int off, int len) {
        return compute(pkt, off + COVERED_FROM, len - COVERED_FROM);
    }

    /** True when a packet's byte 1 matches the checksum of its remaining bytes. */
    public static boolean verify(byte[] pkt, int off, int len) {
        if (pkt == null || off < 0 || len < 3 || off + len > pkt.length) return false;
        return (pkt[off + CHECKSUM_INDEX] & 0xFF) == ofPacket(pkt, off, len);
    }
}
