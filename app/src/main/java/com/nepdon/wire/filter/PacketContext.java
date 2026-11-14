package com.nepdon.wire.filter;

/**
 * A parsed packet handed to the filter engine. The transport core parses the
 * raw IP/UDP header once and passes this immutable snapshot to every filter,
 * so filters never re-parse bytes.
 *
 * <h3>The address fields mean different things in each direction</h3>
 *
 * <p>This class was written for the outbound path and its field names still
 * read that way, but {@code UdpSessionManager} reuses it for inbound with the
 * addresses <b>deliberately swapped</b>, so that the remote peer is always
 * reachable without a direction flag:</p>
 *
 * <table>
 *   <tr><th></th><th>outbound</th><th>inbound</th></tr>
 *   <tr><td>{@link #dip}</td><td>server IP (destination)</td>
 *       <td><b>server IP (source/peer)</b></td></tr>
 *   <tr><td>{@link #sp}</td><td>client port (source)</td>
 *       <td><b>server port</b></td></tr>
 *   <tr><td>{@link #dp}</td><td>server port (destination)</td>
 *       <td><b>client port</b></td></tr>
 * </table>
 *
 * <p>So the match-server test is {@code isFromMatchServer(dip, dp)} outbound
 * (GhostFilter, TeleportFilter) and {@code isFromMatchServer(dip, sp)} inbound
 * (FreezeFilter). The asymmetry is intentional and both are correct — but it
 * is exactly the "narrow what a field means, then grep every reader" hazard
 * this project has already paid for four times, so check the direction before
 * copying either line.</p>
 */
public final class PacketContext {

    /** Raw buffer (shared, read-only). */
    public final byte[] buf;
    /** Total bytes read from the tun device. */
    public final int len;
    /** IP header length in bytes. */
    public final int ihl;
    /** IP protocol number (17 = UDP). */
    public final int proto;
    /** Destination IP outbound; <b>peer (server) IP inbound</b> — see class doc. */
    public final int dip;
    /** Source port outbound; <b>server port inbound</b> — see class doc. */
    public final int sp;
    /** Destination port outbound; <b>client port inbound</b> — see class doc. */
    public final int dp;
    /** UDP payload length in bytes. */
    public final int plen;
    /** Offset of the UDP payload within {@link #buf}. */
    public final int off;
    /** Classified tele packet type (see {@link TelePacket}). */
    public final int teleType;

    /**
     * The packet's exact {@code Cmd}, or {@link #CMD_UNRESOLVED} when the
     * header could not be read.
     *
     * <p>It is unresolved for the first few hundred packets of every session
     * (the header encoding is solved from traffic, which takes a moment), for
     * every non-Free-Fire packet, and for a short window after a match/room
     * transition rotates the key. Filters must therefore treat this as a
     * <b>bonus</b> — an exact answer when one is available — and keep their
     * shape heuristics as the path that always works.</p>
     */
    public final int cmd;

    /** Value of {@link #cmd} when the header has not been parsed. */
    public static final int CMD_UNRESOLVED = -1;

    /**
     * Payload length to actually transmit, which differs from {@link #plen}
     * only when a filter rewrote the body into a different number of bytes.
     *
     * <p>This is the one mutable field on an otherwise immutable snapshot, and
     * it exists because a varint body genuinely changes size when its values
     * do — {@link com.nepdon.wire.filter.TeleportFilter} rewrites a
     * position whose new coordinates may need an extra byte or two. Egress is a
     * userspace relay, so there is no IP or UDP checksum to repair; the
     * transport simply has to send this many bytes instead of {@code plen}.
     * The filter that changes the body is responsible for repairing the
     * packet's own {@code Length} field and its CRC-7.</p>
     */
    private int outLength = -1;

    /** Bytes the transport should send: the rewritten length, or {@link #plen}. */
    public int outLength() {
        return outLength > 0 ? outLength : plen;
    }

    /** Called by a filter that rewrote the body into a different length. */
    public void setOutLength(int newLength) {
        this.outLength = newLength;
    }

    /**
     * Set by a filter that rewrote the packet in place (e.g.
     * {@link com.nepdon.wire.filter.AimFilter} flipping a Cmd 106 hit zone).
     * The packet is still forwarded like any {@code PASS}; this only tells the
     * capture to record it as {@code REWRITE} instead of {@code PASS} so the edit
     * is visible in the dev dump. Like {@link #outLength} it is a deliberate
     * mutable exception on an otherwise immutable snapshot.
     */
    private boolean rewritten;

    /** True when a filter rewrote this packet's body in place. */
    public boolean wasRewritten() {
        return rewritten;
    }

    /** Marks this packet as rewritten in place (see {@link #wasRewritten()}). */
    public void markRewritten() {
        this.rewritten = true;
    }

    /**
     * True when the header says this is a RELIABLE message.
     *
     * <p><b>A reliable message must never be withheld.</b> The client never
     * acknowledges what it is not given, so the server retransmits — and a
     * filter that dropped the original drops every retransmission too. That is
     * the ack-starvation shape this project already identifies as the likely
     * mid-match kick mechanism.</p>
     *
     * <p>This was added because {@code FreezeFilter}'s javadoc asserted that
     * every command in {@code Cmd.isWorldStateStream} is non-reliable, and
     * measurement disagreed: {@code Cmd 2003 UDP_AIRDROP_STATE_SYNC} arrives
     * reliable-and-encrypted ({@code flags=0x5F}) 7 times in
     * {@code captures/dev_20260916/capture_raw_20260916_214646.txt}, against
     * 609 unreliable. The {@code UDP_} name prefix is not a guarantee.</p>
     *
     * <p>Only meaningful when {@link #hasCmd()} is true — it is set from the
     * same parse. It defaults to {@code false}, so any path that forgets to
     * set it behaves exactly as the code did before this field existed.</p>
     */
    private boolean reliable;

    /** True when the header marked this a reliable message (see the field doc). */
    public boolean isReliable() {
        return reliable;
    }

    /** Set from {@code UdpPacket.isReliable()} by whoever parsed the header. */
    public void setReliable(boolean r) {
        this.reliable = r;
    }

    public PacketContext(byte[] buf, int len, int ihl, int proto, int dip,
                         int sp, int dp, int plen, int off, int teleType) {
        this(buf, len, ihl, proto, dip, sp, dp, plen, off, teleType, CMD_UNRESOLVED);
    }

    public PacketContext(byte[] buf, int len, int ihl, int proto, int dip,
                         int sp, int dp, int plen, int off, int teleType, int cmd) {
        this.buf = buf;
        this.len = len;
        this.ihl = ihl;
        this.proto = proto;
        this.dip = dip;
        this.sp = sp;
        this.dp = dp;
        this.plen = plen;
        this.off = off;
        this.teleType = teleType;
        this.cmd = cmd;
    }

    /** True when {@link #cmd} carries an exactly-parsed command id. */
    public boolean hasCmd() {
        return cmd != CMD_UNRESOLVED;
    }

    /** Copies the UDP payload into a fresh array (for buffering). */
    public byte[] copyPayload() {
        byte[] copy = new byte[plen];
        System.arraycopy(buf, off, copy, 0, plen);
        return copy;
    }

}
