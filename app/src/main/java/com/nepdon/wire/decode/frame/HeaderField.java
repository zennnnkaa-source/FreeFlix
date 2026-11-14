package com.nepdon.wire.decode.frame;

/**
 * The five fields of the Free Fire Max UDP header, and their widths.
 *
 * <p>{@code UDPParameters.HEAD_SECRET} is an {@code int[5]} <b>permutation</b>,
 * not a key: it reorders these five fields on the wire. It arrives as
 * configuration ({@code NetworkUtils.ParseUDPHeadSecret}), so it differs per
 * session; when it is null or not length 5 the game falls back to
 * {@link #defaultOrder()}.</p>
 *
 * <p><b>This is the real explanation of the long-standing "b2-b7 mask rotates
 * per session" observation.</b> The bytes are not rotating — the fields are
 * permuted, and a fixed offset therefore reads the wrong field in most
 * sessions.</p>
 *
 * <p>{@link #ORDER_ID} is present only on reliable packets, so the widths sum
 * to 6 (8-byte header) or 8 (10-byte header). The ordinal of each constant is
 * deliberately its {@code HEAD_SECRET} id, so {@code HEAD_SECRET[i]} indexes
 * straight into {@link #byId(int)}.</p>
 */
public enum HeaderField {

    /** Send option; drives {@code IsReliable}. 1 byte. */
    SEND_OPTION(1, false),
    /** Message command id. 2 bytes, little-endian. */
    CMD(2, false),
    /** Reliability sequence number. 2 bytes LE, present on reliable packets only. */
    ORDER_ID(2, true),
    /** Encryption / compression bits. 1 byte. */
    FLAGS(1, false),
    /** Payload byte count, excluding the header. 2 bytes LE. */
    DATA_LEN(2, false);

    /** Number of distinct header fields. */
    public static final int COUNT = 5;

    private static final HeaderField[] BY_ID = values();

    private static final HeaderField[] DEFAULT_ORDER = {
            SEND_OPTION, CMD, ORDER_ID, FLAGS, DATA_LEN
    };

    private final int width;
    private final boolean reliableOnly;

    HeaderField(int width, boolean reliableOnly) {
        this.width = width;
        this.reliableOnly = reliableOnly;
    }

    /** {@code HEAD_SECRET} id of this field (0..4). */
    public int id() {
        return ordinal();
    }

    /** Width on the wire in bytes. */
    public int width() {
        return width;
    }

    /** Width contributed for a packet of the given reliability (0 when absent). */
    public int widthFor(boolean reliable) {
        return (reliableOnly && !reliable) ? 0 : width;
    }

    /** True when this field appears on a packet of the given reliability. */
    public boolean isPresent(boolean reliable) {
        return reliable || !reliableOnly;
    }

    /** Field for a {@code HEAD_SECRET} id, or null when out of range. */
    public static HeaderField byId(int id) {
        return (id >= 0 && id < BY_ID.length) ? BY_ID[id] : null;
    }

    /** Internal, read-only view of {@link #defaultOrder()} — do not mutate. */
    static HeaderField[] defaultOrderRef() {
        return DEFAULT_ORDER;
    }
}
