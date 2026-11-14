package com.nepdon.wire.decode.body;

import com.nepdon.wire.decode.frame.HeaderField;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.decode.frame.MsgKey;
import com.nepdon.wire.decode.frame.PacketCodec;

/**
 * Reads and rewrites the player's own position report — {@code Cmd 2001}
 * {@code UDP_PLAYER_STATE_SYNC}, the 10 Hz outbound keepalive that is the
 * <b>sole</b> authority on where the server thinks the player is.
 *
 * <h3>Wire layout</h3>
 * <pre>
 *   [8-byte header][varint objectHandle][23 body varints]
 * </pre>
 * The body indices this class cares about, all zigzag-encoded game units
 * ({@value com.nepdon.wire.esp.EspEngine#UNITS_PER_METRE} units per metre):
 * <pre>
 *   v0,v1,v2    position, MILLIMETRES (the binary's order is X, UP, Z — this
 *               class's field names say X, Z, Y, which is only a naming skew:
 *               every read and write uses the same index, so nothing crosses)
 *   v8,v9,v10   VELOCITY in cm/s. It reads as "displacement to the next tick"
 *               because field/displacement = 0.1/dt, and dt = 99 ms gives the
 *               measured ratio of 1.010 — the same 99 ms the v22 tick advances by
 *   v16         moving-platform id — NON-ZERO MEANS v0-v2 IS PLATFORM-RELATIVE
 *   v22         a wall-clock millisecond counter, left alone
 * </pre>
 *
 * <h3>Two rules that make a rewrite safe, both learned the hard way</h3>
 *
 * <p><b>1. A value can outgrow its field, so there are two write paths.</b>
 * Every scalar is a plain LEB128 varint, so changing a coordinate can change
 * its byte count. {@link #setSigned} writes in place, padding to the width the
 * field already had, which keeps the packet byte-identical in size and needs no
 * length repair at all. <b>But that padding is non-canonical and the client is
 * never observed to emit it</b> — see the retraction on {@link #V_PHYS_STATE};
 * the "the game emits it itself ({@code 8a 8a 00})" justification that used to
 * sit here was measured false (0 of 928,128 fields). Cheap, but it leaves a
 * signature whenever it actually pads. It also cannot
 * carry a teleport on its own: measured over 18 178 real reports, <b>58% of
 * velocity fields are a single byte</b>, holding only &plusmn;63 units =
 * &plusmn;0.066 m, which caps a ramp at <b>1.15 m/s</b>. So when a value does
 * not fit, {@link #rebuild} re-encodes the whole body canonically and repairs
 * the packet's own {@code Length} field. Position fields are 3 bytes in 96% of
 * reports (&plusmn;1104 m of headroom) and are almost never the constraint.</p>
 *
 * <p><b>2. The checksum must be resealed.</b> CRC-7 in byte 1 covers the packet
 * as transmitted. Rewriting any byte at offset &ge; 2 without calling
 * {@link #seal()} leaves a stale checksum and the server discards the packet as
 * corrupt — which looks exactly like a rejected teleport.</p>
 *
 * <p>Not thread-safe by design: one instance per tun loop, reused per packet to
 * keep the hot path allocation-free.</p>
 */
public final class PositionSync {

    /** Body varint count of a well-formed position report. */
    public static final int BODY_VARINTS = 23;

    /** Position component indices within the body. */
    public static final int V_POS_X = 0;
    public static final int V_POS_Z = 1;
    public static final int V_POS_Y = 2;

    /**
     * Velocity component indices. The triple matches the displacement to the
     * next tick with a median ratio of 1.010 across 24 894 real samples, and
     * in the 5-20 m/tick band that ratio collapses to 0.054 — "position moved,
     * declared velocity zero" is a shape that never occurs in genuine traffic.
     * A position rewrite that leaves these alone produces exactly that shape,
     * so they are always rewritten alongside the position.
     */
    /**
     * Movement input direction — the stick, as a 2D unit vector <b>scaled by
     * 100</b>. Measured in genuine traffic: values cluster hard at
     * {@code ±100, ±96..99} with a continuous spread between, and
     * {@code (0, 0)} means no input.
     *
     * <p>There is no Y component; it is horizontal input only.</p>
     *
     * <p><b>Rewriting position without this leaves a rare, cheap-to-detect
     * shape.</b> Conditioned on {@code physState == 0}, a report that declares
     * {@code moveDir == (0,0)} while the position moved &ge; 0.85 m occurs in
     * <b>2 of 20,021 genuine ticks (0.010%)</b> — and the first measured
     * teleport ramp emitted exactly that, three and four times consecutively,
     * because {@code writeState} rewrote position and velocity and left the
     * input untouched. It is the same "moved but declared nothing" incoherence
     * the velocity fields are already rewritten to avoid, in a field nobody had
     * thought about.</p>
     */
    public static final int V_MOVE_DIR_X = 6;
    /** Movement input, depth component. See {@link #V_MOVE_DIR_X}. */
    public static final int V_MOVE_DIR_Z = 7;

    public static final int V_VEL_X = 8;
    public static final int V_VEL_Z = 9;
    public static final int V_VEL_Y = 10;

    /**
     * Moving-platform id. **When this is non-zero the position at v0-v2 is NOT
     * a world position** -- the sender substitutes
     * {@code Player::get_OffsetPositionToMovePlatform()}, a small offset
     * relative to the platform. Anything treating v0-v2 as world coordinates
     * must check this first or it reads a few centimetres as a map coordinate.
     */
    public static final int V_PLATFORM_ID = 16;

    /**
     * Movement state — {@code EPHYSXSTATE}, <b>remapped, not the raw enum</b>.
     *
     * <p>The client encodes it through {@code KANJBNIANHC::MBOHNCMOJDE}
     * (table at RVA {@code 0x1927e64}), so <b>wire == enum for 1..16</b> but
     * {@code FORCEMOVING(17)->18, SPRINT(18)->19, SlideRunning(19)->20,
     * SlideFalling(20)->21}, and {@code WALKING(0)}, {@code ONFERRISWHEEL(13)}
     * and {@code PASSIVEMOVING(21)} all encode as <b>wire 0</b>.</p>
     *
     * <p>Useful wire values: {@code 0} walking/idle, {@code 1} falling,
     * {@code 2} parachuting, {@code 3} onboard (aircraft), {@code 4} skydiving,
     * {@code 7} on-strop (zipline), {@code 15} faith-jumping (launch pad).</p>
     *
     * <p>The server keeps its own copy of this byte and pushes it back in
     * {@code Cmd 144}, so it is <b>not</b> a free field to fake.</p>
     */
    /**
     * <b>RETRACTED 2026-09-17: the client NEVER emits a non-minimal varint in
     * Cmd 2001.</b>
     *
     * <p>This class used to claim that {@code setSigned}'s in-place padding was
     * safe because "LEB128 permits non-canonical padding and the game emits it
     * itself ({@code 8a 8a 00} = 1290)". Measured: <b>0 non-minimal varints in
     * 928,128 fields across 38,672 genuine outbound Cmd 2001 packets</b>, 9
     * sessions, 7 peers, 6 capture files. Every field is encoded in the
     * minimum width for its value, always.</p>
     *
     * <p>The {@code 8a 8a 00} datum was real but came from somewhere else
     * entirely, and the document it came from says the opposite:
     * {@code docs/PROTOCOL_MASTER.md} §4.5 records those bytes as part of an
     * <b>inbound remote-player entity record</b>, where bytes 7/8/9 are
     * <b>fixed width, not varints at all</b> — they only look like padded
     * varints when mis-decoded. That same section states that the outbound
     * keepalive contains zero non-canonical varints. The claim was inverted on
     * its way into this file.</p>
     *
     * <p><b>So the in-place path is a 1-in-928,128 signature whenever it
     * actually pads.</b> It did not fire in the measured teleport bursts —
     * those all took {@link #rebuild} and came out canonical — but any rewrite
     * that lands on it emits a byte pattern the client has never been observed
     * to produce. The same false premise underpins
     * {@code AimFilter}'s Cmd 106 in-place path; prefer {@code rebuild} over
     * padding wherever a length change is tolerable.</p>
     */
    public static final int V_PHYS_STATE = 11;

    /** Pose — {@code EPHYSXPOSE}, raw (no remap). 0 standing, 1 crouching, 2 dashing. */
    public static final int V_POSE = 12;

    private final int[] starts = new int[BODY_VARINTS];
    private final int[] widths = new int[BODY_VARINTS];
    private final long[] raw = new long[BODY_VARINTS];

    private final long[] pending = new long[BODY_VARINTS];
    private final byte[] scratch = new byte[256];

    private byte[] buf;
    private int off;
    private int plen;
    private int hdrSize;
    private int handleStart;
    private boolean valid;
    private boolean dirty;

    /**
     * Parses one outbound packet. Returns false — leaving the packet untouched
     * — for anything that is not a well-formed 23-varint position report.
     */
    public boolean parse(byte[] b, int packetOff, int packetLen) {
        valid = false;
        dirty = false;
        if (b == null || packetOff < 0 || packetLen < 12) return false;
        if (packetOff + packetLen > b.length) return false;

        int hdr = MsgKey.headerSizeOf(b, packetOff, packetLen);
        if (hdr != MsgKey.HEADER_NON_RELIABLE) return false;

        int end = packetOff + packetLen;
        int i = skipVarint(b, packetOff + hdr, end);   // the object handle
        if (i < 0) return false;

        for (int k = 0; k < BODY_VARINTS; k++) {
            if (i >= end) return false;
            int start = i;
            long v = 0;
            int shift = 0;
            while (true) {
                if (i >= end) return false;
                int c = b[i++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) return false;
            }
            starts[k] = start;
            widths[k] = i - start;
            raw[k] = v;
        }
        if (i != end) return false;   // must consume the body exactly

        this.buf = b;
        this.off = packetOff;
        this.plen = packetLen;
        this.hdrSize = hdr;
        this.handleStart = packetOff + hdr;
        this.valid = true;
        System.arraycopy(raw, 0, pending, 0, BODY_VARINTS);
        return true;
    }

    public boolean isValid() {
        return valid;
    }

    /** The packet's total length as it now stands (rebuild can change it). */
    public int length() {
        return plen;
    }

    /** The signed (zigzag-decoded) value at a body index. */
    public long signedAt(int index) {
        return zigzagDecode(raw[index]);
    }

    /** The raw varint at a body index, undecoded. */
    public long rawAt(int index) {
        return raw[index];
    }

    /**
     * True when {@code value} would still fit the byte width this field already
     * occupies. Check the whole triple before writing any of it, so a packet is
     * never left half-rewritten.
     */
    public boolean fitsSigned(int index, long value) {
        return varintWidth(zigzagEncode(value)) <= widths[index];
    }

    /**
     * Writes a zigzag value in place, padded to its existing byte width.
     *
     * @return false when the value needs more bytes than the field has; the
     *         packet is left untouched and the caller must withhold it rather
     *         than transmit a real position
     */
    public boolean setSigned(int index, long value) {
        if (!valid) return false;
        long u = zigzagEncode(value);
        int w = widths[index];
        if (varintWidth(u) > w) return false;
        int p = starts[index];
        for (int k = 0; k < w - 1; k++) {
            buf[p + k] = (byte) ((u & 0x7f) | 0x80);
            u >>>= 7;
        }
        buf[p + w - 1] = (byte) (u & 0x7f);
        raw[index] = zigzagEncode(value);
        pending[index] = raw[index];
        dirty = true;
        return true;
    }

    /**
     * Recomputes the CRC-7 over the packet as it now stands. Must be called
     * after any {@link #setSigned} or the server drops the packet.
     */
    public boolean seal() {
        if (!valid) return false;
        return PacketCodec.reserialize(buf, off, plen);
    }

    // ---- canonical rebuild: the path taken when a value outgrows its field ----

    /**
     * Stages a value for {@link #rebuild}. Unlike {@link #setSigned} this has
     * no width limit, because rebuild re-encodes the whole body.
     */
    public void stageSigned(int index, long value) {
        pending[index] = zigzagEncode(value);
    }

    /**
     * Stages a raw unsigned varint value for {@link #rebuild}, without
     * zigzag-encoding.
     *
     * <p>Used for {@code v13/v14/v15} (the float32 camera direction fields),
     * which are stored as raw IEEE-754 uint32 bit patterns, not zigzag
     * integers. Pass {@code Float.floatToRawIntBits(f) & 0xFFFFFFFFL} here.
     * {@link #rebuild} writes every {@code pending[k]} as a plain unsigned
     * LEB128 varint, so the raw bits land on the wire exactly as-is — which
     * is what those fields require.</p>
     *
     * <p>Do NOT call this for position, velocity, or moveDir fields — those
     * are zigzag-encoded; use {@link #stageSigned} for them.</p>
     *
     * @param index  body varint index (e.g. {@link C2S_UDP_Sync_AimRotation#V_CAM_X})
     * @param rawBits unsigned uint32 bit pattern (upper 32 bits ignored)
     */
    public void stageRaw(int index, long rawBits) {
        pending[index] = rawBits & 0xFFFFFFFFL;
    }

    /**
     * Re-encodes the entire body canonically with the staged values, fixes the
     * packet's own {@code Length} field, and reseals the checksum.
     *
     * <p>This is needed because the velocity fields are genuinely narrow: over
     * 18 178 real reports, <b>58% of them occupy a single byte</b>, which holds
     * only &plusmn;63 units = &plusmn;0.066 m. Padding within the existing width
     * therefore caps a teleport at a measured <b>1.15 m/s</b> — slower than
     * walking. Growing the packet by the two or three bytes a real step needs
     * removes that ceiling entirely, and a 60 &rarr; 63 byte report is
     * unremarkable in a channel whose real lengths already span 48-67 B.</p>
     *
     * <p>Canonical encoding is also the <i>more</i> natural output: it is byte
     * for byte what the game itself would emit for those values.</p>
     *
     * @param layout the solved header layout, needed to write {@code Length}
     * @return the new total packet length, or -1 when the packet was left
     *         untouched (no layout, or no room in the buffer)
     */
    public int rebuild(HeaderLayout layout) {
        if (!valid || layout == null) return -1;

        boolean reliable = (hdrSize == MsgKey.HEADER_RELIABLE);

        // PROVE THE LAYOUT DECODES *THIS* PACKET BEFORE TRUSTING IT TO ENCODE.
        //
        // The layout carries a per-session XOR mask and a field permutation,
        // and it is fetched from a process-wide static. If it is stale -- a key
        // rotation at a match or room transition, a solver that has not
        // reconverged -- then writeShort masks Length with the wrong byte and
        // the server reads a body length that does not match the datagram.
        // That is a malformed packet, and a run of them is a protocol error
        // that ends the session: it surfaces to the player as a network error
        // that drops them back to the lobby, with no ban and no explanation.
        //
        // The check is free and total: Length on an OUTBOUND packet must equal
        // the payload bytes that are actually here. If that identity holds, the
        // mask and the offset are both right, so the write will be right. If it
        // does not, refuse -- the caller withholds the packet, which costs one
        // position report and nothing else.
        int declared = layout.readShort(buf, off, HeaderField.DATA_LEN, reliable);
        if (declared != plen - hdrSize) return -1;

        int p = 0;
        // The object handle is copied through unchanged, exactly as received.
        int bodyStart = starts[0];
        int handleBytes = bodyStart - handleStart;
        if (handleBytes < 0 || handleBytes > scratch.length) return -1;
        System.arraycopy(buf, handleStart, scratch, 0, handleBytes);
        p = handleBytes;

        for (int k = 0; k < BODY_VARINTS; k++) {
            long u = pending[k];
            while (true) {
                if (p >= scratch.length) return -1;
                int c = (int) (u & 0x7f);
                u >>>= 7;
                if (u != 0) {
                    scratch[p++] = (byte) (c | 0x80);
                } else {
                    scratch[p++] = (byte) c;
                    break;
                }
            }
        }

        int newPayload = p;
        int newLen = hdrSize + newPayload;
        if (off + newLen > buf.length) return -1;   // no room: caller withholds

        System.arraycopy(scratch, 0, buf, off + hdrSize, newPayload);
        layout.writeShort(buf, off, HeaderField.DATA_LEN, reliable, newPayload);

        plen = newLen;
        dirty = true;
        if (!PacketCodec.reserialize(buf, off, newLen)) return -1;
        // Keep the parsed view consistent with what is now on the wire.
        System.arraycopy(pending, 0, raw, 0, BODY_VARINTS);
        return newLen;
    }

    // ---- LEB128 / zigzag ----

    /** Index just past one varint, or -1 when truncated. */
    private static int skipVarint(byte[] b, int i, int end) {
        int shift = 0;
        while (i < end) {
            int c = b[i++] & 0xff;
            if ((c & 0x80) == 0) return i;
            shift += 7;
            if (shift > 63) return -1;
        }
        return -1;
    }

    /** Minimum bytes needed to encode an unsigned varint. */
    public static int varintWidth(long u) {
        int n = 1;
        while ((u >>>= 7) != 0) n++;
        return n;
    }

    public static long zigzagEncode(long v) {
        return (v << 1) ^ (v >> 63);
    }

    public static long zigzagDecode(long u) {
        return com.nepdon.wire.util.ZigZag.decode(u);
    }
}
