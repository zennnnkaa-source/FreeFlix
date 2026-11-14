package com.nepdon.wire.protocol;

import com.nepdon.wire.decode.frame.Direction;
import com.nepdon.wire.decode.frame.HeaderField;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.decode.frame.PacketCodec;
import com.nepdon.wire.decode.frame.UdpPacket;

/**
 * The packet-decoding front door: solve the session, parse the header, hand the
 * body to whoever registered for that command.
 *
 * <p>Two calls per packet, in this order:</p>
 * <pre>
 *   UdpPacket p = pipeline.observeAndParse(buf, off, len, dir);
 *   boolean consumed = p != null &amp;&amp; pipeline.dispatch(p, dir);
 * </pre>
 *
 * <p>They are separate so the caller can use the parsed header (the command, in
 * particular) for its own decisions even when no decoder wanted the body.</p>
 *
 * <p><b>Falling back is not a failure.</b> {@code observeAndParse} returns null
 * until the session is solved, and {@code dispatch} returns false for anything
 * it cannot read — a bad checksum, a zipped payload, an encrypted payload with
 * no key. Callers are expected to keep their previous heuristics behind this
 * and run them exactly when it declines, so behaviour before convergence is
 * unchanged and behaviour after it is exact.</p>
 *
 * <h3>The session key</h3>
 * <p>The 16-byte {@code SECRET_KEY} is <b>not on the wire</b>: it is provisioned
 * over the TCP login channel or negotiated by a handshake whose parser is
 * encrypted at rest in the APK. Header framing, classification and CRC need no
 * key, and that covers every plaintext family — which includes all of the
 * position data. Encrypted bodies stay closed unless a key is supplied through
 * {@link #setSessionKey(byte[])} (reading it live off a rooted device is the
 * only known way to get one).</p>
 *
 * <h3>Allocation</h3>
 * <p>Per packet: one {@link UdpPacket}. No arrays, no strings, no boxing. The
 * decryption scratch buffer is per-thread and grown at most a few times.</p>
 */
public final class PacketPipeline {

    /** Largest payload the decryption scratch buffer will grow to hold. */
    private static final int MAX_SCRATCH = 8192;

    /**
     * Consecutive parsed-but-checksum-failing packets that force a re-solve.
     *
     * <p>This is a <b>backstop</b>, not the primary session-change detector.
     * The primary one lives in {@link SessionKeySolver}: the {@code Length}
     * identity holds on 100% of outbound packets while the layout is right and
     * collapses the instant it is not, so
     * {@link SessionKeySolver#RESOLVE_FAILURE_RUN} outbound failures already
     * drop the solver back to voting. CRC-7 cannot see a session change at all
     * (it is computed over the transmitted bytes and stays valid across keys),
     * so a long run of CRC failures means the layout is putting us on the
     * wrong bytes entirely — a state worth abandoning even though the Length
     * check has not tripped. The threshold is deliberately high: a spurious
     * re-solve costs only a few seconds of falling back to the shape
     * heuristics, but a twitchy one would thrash.</p>
     */
    public static final int CRC_FAILURE_RUN = 256;

    private final SessionKeySolver solver = new SessionKeySolver();

    /** TEA key schedule, or null when no session key is known (the normal case). */
    private volatile int[] keySchedule;

    /**
     * Decryption scratch, per transport thread. Bodies are written at
     * {@link BodyDecoderRegistry#BODY_PREFIX} so decoders always have readable
     * bytes behind the body offset.
     */
    private final ThreadLocal<byte[]> scratch = new ThreadLocal<byte[]>();

    private volatile long parsed;
    private volatile long crcFailures;
    private volatile long undecodable;

    /** Run of consecutive CRC failures; drives the {@link #CRC_FAILURE_RUN} backstop. */
    private volatile int crcFailureRun;
    /** Times the backstop above forced the solver back to voting. */
    private volatile long forcedResolves;

    /** True once the header encoding is known and packets can be parsed exactly. */
    public boolean isReady() {
        return solver.isSolved();
    }

    /** The solved header encoding, or null. */
    public HeaderLayout layout() {
        return solver.layout();
    }

    /** True when encrypted bodies can be read. */
    /**
     * Seeds the header layout from the lobby announcement.
     *
     * <p>Pass the mask and the five {@link HeaderField} ids in wire order, as
     * recovered by {@link LobbyKeySniffer}. The layout is installed
     * provisionally and confirmed on the wire; see
     * {@link SessionKeySolver#seedLayout}. Safe to call repeatedly -- it is
     * ignored once a layout is confirmed.</p>
     *
     * @return true when the seed was installed
     */
    public boolean seedLayout(int mask, int[] orderIds) {
        if (orderIds == null || orderIds.length != HeaderField.COUNT) return false;
        if (mask < 0 || mask > 0xFF) return false;
        HeaderField[] order = new HeaderField[HeaderField.COUNT];
        for (int i = 0; i < orderIds.length; i++) {
            int id = orderIds[i];
            if (id < 0 || id >= HeaderField.COUNT) return false;
            order[i] = HeaderField.byId(id);
        }
        return solver.seedLayout(mask, order);
    }

    /**
     * One line on how the header layout was obtained. Loud by design: a seed
     * silently failing looks exactly like a seed working, since the solver
     * converges either way -- just seconds later, having lost the match-start
     * burst.
     */
    public String layoutSourceLine() {
        if (solver.seedsAccepted() == 0) {
            return "LAYOUT solved (no lobby seed) acc=0 rej=" + solver.seedsRejected();
        }
        return "LAYOUT " + (solver.isProvisional() ? "seeded(confirming)" : "seeded+confirmed")
                + " acc=" + solver.seedsAccepted()
                + " rej=" + solver.seedsRejected();
    }

    /** Forgets the session. Call on tunnel restart. */
    public void reset() {
        solver.reset();
        parsed = 0;
        crcFailures = 0;
        undecodable = 0;
        crcFailureRun = 0;
        forcedResolves = 0;
    }

    /**
     * One line for the dev overlay: solver phase, and what the parse is doing.
     * Allocates, so keep it off the packet path.
     */
    /**
     * The pipeline belonging to the live tunnel, for the dev panel. There is
     * exactly one engine at a time; this saves threading a reference through
     * the overlay.
     */
    private static volatile PacketPipeline last;

    /** Current pipeline, or null before the tunnel starts. */
    public static PacketPipeline current() {
        return last;
    }

    /** Marks this instance as the live one. */
    public void markCurrent() {
        last = this;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(solver.describe());
        sb.append(" parsed=").append(parsed);
        if (crcFailures > 0) sb.append(" crcBad=").append(crcFailures);
        if (undecodable > 0) sb.append(" opaque=").append(undecodable);
        if (forcedResolves > 0) sb.append(" forced=").append(forcedResolves);
        return sb.toString();
    }

    /**
     * Feeds the solver and, once it has converged, parses the packet.
     *
     * @return the parsed packet, or null while the session is unsolved or the
     *         buffer is not match traffic
     */
    public UdpPacket observeAndParse(byte[] buf, int off, int len, Direction direction) {
        solver.observe(buf, off, len, direction);
        HeaderLayout l = solver.layout();
        if (l == null) return null;
        UdpPacket p = PacketCodec.parse(buf, off, len, l);
        if (p != null) {
            parsed++;
            noteChecksum(p.isCrcValid());
        }
        return p;
    }

    /**
     * Runs the {@link #CRC_FAILURE_RUN} backstop. A single failure means
     * nothing — foreign UDP lands in the MsgKey range about 8% of the time and
     * will fail here — so only a sustained run counts, and any success clears
     * it.
     */
    private void noteChecksum(boolean ok) {
        if (ok) {
            if (crcFailureRun != 0) crcFailureRun = 0;
            return;
        }
        int run = crcFailureRun + 1;
        if (run >= CRC_FAILURE_RUN) {
            crcFailureRun = 0;
            forcedResolves++;
            solver.reset();
            return;
        }
        crcFailureRun = run;
    }

    // THE BODY-DECODER REGISTRY PATH WAS REMOVED, NOT DISABLED.
    //
    // dispatch() was called on every parsed packet in both directions and
    // could never do anything: nothing ever registered a decoder (the only
    // registrar, EspBodyDecoders, had zero callers) and setSessionKey had
    // zero callers too, so the key schedule was permanently null and every
    // encrypted body bailed at the first check.
    //
    // What actually decodes, and still does, is the hand-written command
    // switch in UdpSessionManager (inbound, with BodyCrypt.decrypt inline)
    // and the shape gates in PacketLoop (outbound). Moving those into
    // decoders would be a net-zero line change on a field-tested hot path
    // with a documented double-feed hazard, so this is a deletion rather
    // than a migration. The design is in docs/ if the seam is ever wanted.
}
