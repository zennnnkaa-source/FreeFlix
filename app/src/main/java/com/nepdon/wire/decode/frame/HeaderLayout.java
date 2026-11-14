package com.nepdon.wire.decode.frame;

/**
 * A solved header encoding: the one-byte XOR mask plus the {@code HEAD_SECRET}
 * field permutation, with every byte offset precomputed.
 *
 * <p>Immutable and safe to publish across threads. {@link SessionKeySolver}
 * produces one of these per session and swaps it in atomically; parsers read it
 * without locking.</p>
 *
 * <p>The mask is a single byte replicated over both bytes of the 16-bit fields
 * ({@code v43 = v30 | (v30 << 8)} in {@code Unserialize}), and it is
 * {@code SECRET_KEY[0]} — the first byte of the TEA key. It changes per session
 * only because {@code SECRET_KEY} does.</p>
 */
public final class HeaderLayout {

    /** The encoding the game falls back to when {@code HEAD_SECRET} is absent, with no mask. */
    public static final HeaderLayout DEFAULT = new HeaderLayout(0, HeaderField.defaultOrderRef());

    private final int mask;
    private final HeaderField[] order;

    /** Byte offsets indexed by {@link HeaderField#id()}; -1 when the field is absent. */
    private final int[] reliableOffsets;
    private final int[] nonReliableOffsets;

    /**
     * @param mask  the per-session XOR byte applied to every header byte from index 2 on
     * @param order a permutation of all five {@link HeaderField}s
     * @throws IllegalArgumentException when {@code order} is not a permutation
     */
    public HeaderLayout(int mask, HeaderField[] order) {
        if (order == null || order.length != HeaderField.COUNT) {
            throw new IllegalArgumentException("HEAD_SECRET must name all five fields");
        }
        this.mask = mask & 0xFF;
        this.order = order.clone();

        this.reliableOffsets = layOut(this.order, true);
        this.nonReliableOffsets = layOut(this.order, false);

        if (reliableOffsets == null || nonReliableOffsets == null) {
            throw new IllegalArgumentException("HEAD_SECRET must be a permutation of the five fields");
        }
    }

    /** Computes per-field offsets, or null when {@code order} is not a permutation. */
    private static int[] layOut(HeaderField[] order, boolean reliable) {
        int[] offsets = new int[HeaderField.COUNT];
        java.util.Arrays.fill(offsets, -1);
        int off = Crc7.COVERED_FROM;
        for (int i = 0; i < order.length; i++) {
            HeaderField f = order[i];
            if (f == null || offsets[f.id()] != -1) return null;   // missing or duplicated
            if (!f.isPresent(reliable)) {
                offsets[f.id()] = -1;
                continue;
            }
            offsets[f.id()] = off;
            off += f.width();
        }
        int expected = reliable ? MsgKey.HEADER_RELIABLE : MsgKey.HEADER_NON_RELIABLE;
        return off == expected ? offsets : null;
    }

    /** The per-session XOR byte, i.e. {@code SECRET_KEY[0]}. */
    public int mask() {
        return mask;
    }

    /** The field order; a defensive copy. */
    public HeaderField[] order() {
        return order.clone();
    }

    /** Byte offset of a field within a packet of the given reliability, or -1 when absent. */
    public int offsetOf(HeaderField field, boolean reliable) {
        return (reliable ? reliableOffsets : nonReliableOffsets)[field.id()];
    }

    /** Header size for a packet of the given reliability. */
    public int headerSize(boolean reliable) {
        return reliable ? MsgKey.HEADER_RELIABLE : MsgKey.HEADER_NON_RELIABLE;
    }

    /** Reads a 1-byte field, unmasking it. Caller must have bounds-checked the header. */
    public int readByte(byte[] pkt, int off, HeaderField field, boolean reliable) {
        int o = offsetOf(field, reliable);
        return o < 0 ? -1 : ((pkt[off + o] & 0xFF) ^ mask);
    }

    /** Reads a 2-byte little-endian field, unmasking both bytes. */
    public int readShort(byte[] pkt, int off, HeaderField field, boolean reliable) {
        int o = offsetOf(field, reliable);
        if (o < 0) return -1;
        int lo = (pkt[off + o] & 0xFF) ^ mask;
        int hi = (pkt[off + o + 1] & 0xFF) ^ mask;
        return lo | (hi << 8);
    }

    /** Writes a 1-byte field, applying the mask. */
    public void writeByte(byte[] pkt, int off, HeaderField field, boolean reliable, int value) {
        int o = offsetOf(field, reliable);
        if (o < 0) return;
        pkt[off + o] = (byte) ((value & 0xFF) ^ mask);
    }

    /** Writes a 2-byte little-endian field, applying the mask. */
    public void writeShort(byte[] pkt, int off, HeaderField field, boolean reliable, int value) {
        int o = offsetOf(field, reliable);
        if (o < 0) return;
        pkt[off + o] = (byte) ((value & 0xFF) ^ mask);
        pkt[off + o + 1] = (byte) (((value >>> 8) & 0xFF) ^ mask);
    }

    /** Human-readable form for logs and the dev overlay. */
    public String describe() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("mask=0x");
        String hex = Integer.toHexString(mask).toUpperCase(java.util.Locale.US);
        if (hex.length() < 2) sb.append('0');
        sb.append(hex).append(" HEAD_SECRET=[");
        for (int i = 0; i < order.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(order[i].name());
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
