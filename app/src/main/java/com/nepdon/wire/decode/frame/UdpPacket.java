package com.nepdon.wire.decode.frame;

import com.nepdon.wire.decode.body.Cmd;
import com.nepdon.wire.decode.body.CmdCatalogue;

/**
 * One parsed Free Fire Max UDP packet. Immutable.
 *
 * <p>The backing array is <b>referenced, not copied</b>: parsing costs one
 * small object and no byte copying. The transport reuses its receive buffer, so
 * a {@code UdpPacket} is only valid for the duration of the callback that
 * received it — hold onto the decoded fields, never onto the instance.</p>
 *
 * <p>{@link #declaredLength()} and {@link #payloadLength()} are different
 * things and the difference matters. {@code declaredLength} is the header's
 * {@code Length} field; {@code payloadLength} is what actually arrived
 * ({@code len - headerSize}). They agree on every outbound packet — which is
 * exactly what makes the session solvable from traffic alone — but inbound
 * {@code Length} is the <i>pre-compression</i> size when {@code FLAG_ZIP} is
 * set, so they can differ.</p>
 */
public final class UdpPacket {

    private final byte[] buffer;
    private final int offset;
    private final int length;

    private final int msgKey;
    private final int checksum;
    private final boolean reliable;
    private final int headerSize;

    private final int sendOption;
    private final int cmd;
    private final int orderId;
    private final int flags;
    private final int declaredLength;

    private final int payloadOffset;
    private final int payloadLength;
    private final boolean crcValid;

    UdpPacket(byte[] buffer, int offset, int length,
              int msgKey, int checksum, boolean reliable, int headerSize,
              int sendOption, int cmd, int orderId, int flags, int declaredLength,
              boolean crcValid) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        this.msgKey = msgKey;
        this.checksum = checksum;
        this.reliable = reliable;
        this.headerSize = headerSize;
        this.sendOption = sendOption;
        this.cmd = cmd;
        this.orderId = orderId;
        this.flags = flags;
        this.declaredLength = declaredLength;
        this.payloadOffset = offset + headerSize;
        this.payloadLength = length - headerSize;
        this.crcValid = crcValid;
    }

    // ---- framing ----

    /** The backing array. Reused by the transport; do not retain. */
    public byte[] buffer() {
        return buffer;
    }

    /** Total packet length including the header. */
    public int length() {
        return length;
    }

    /** Byte 1, plaintext: the CRC-7 the sender computed over bytes 2.. */
    public int checksum() {
        return checksum;
    }

    /** True when this packet rides the reliable channel (and therefore has an OrderID). */
    public boolean isReliable() {
        return reliable;
    }

    /** 10 for reliable packets, 8 otherwise. */
    public int headerSize() {
        return headerSize;
    }

    /** True when byte 1 matches a recomputed CRC-7 over the rest of the packet. */
    public boolean isCrcValid() {
        return crcValid;
    }

    // ---- header fields ----

    /** Raw {@code Cmd} value. */
    public int cmd() {
        return cmd;
    }

    /** Reliability sequence number, or -1 on a non-reliable packet. */
    public int orderId() {
        return orderId;
    }

    /** Raw {@code Flags} byte. */
    public int flags() {
        return flags;
    }

    /** The header's {@code Length} field: payload bytes as the sender counted them. */
    public int declaredLength() {
        return declaredLength;
    }

    // ---- payload ----

    /** Offset of the first payload byte within {@link #buffer()}. */
    public int payloadOffset() {
        return payloadOffset;
    }

    /** Payload bytes actually present on the wire. */
    public int payloadLength() {
        return payloadLength;
    }

    /** True when the payload needs {@link Tea} and the session key. */
    public boolean isEncrypted() {
        return PacketFlags.isEncrypted(flags);
    }

    /** True when the payload is compressed; not readable without the session key. */
    public boolean isZipped() {
        return PacketFlags.isZipped(flags);
    }

    /** True when the payload can be read directly off the wire. */
    public boolean isPlaintext() {
        return PacketFlags.isPlaintext(flags);
    }

    /**
     * True when the declared {@code Length} matches the bytes that arrived.
     * Holds on 100% of outbound packets in every measured session; it is the
     * identity the solver is built on and the detector for a session change.
     */
    public boolean lengthMatches() {
        return declaredLength == payloadLength;
    }

    /** Diagnostic line for logs; allocates, so keep it off the packet path. */
    public String describe() {
        return (reliable ? "REL" : "NON")
                + " cmd=" + CmdCatalogue.describe(cmd)
                + " so=" + sendOption
                + (reliable ? (" order=" + orderId) : "")
                + " " + PacketFlags.describe(flags)
                + " len=" + declaredLength + "/" + payloadLength
                + (crcValid ? " crcOK" : " crcBAD");
    }

    @Override
    public String toString() {
        return describe();
    }
}
