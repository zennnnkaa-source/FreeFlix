package com.nepdon.wire.protocol;

import com.nepdon.wire.filter.FreezeFilter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-level classification of Free Fire Max UDP payloads, derived from
 * packet captures (see dev/capture). Three protocol families ride the game's
 * UDP stream, and each shape is a distinct event kind. The game XORs each
 * session's payloads with a rotating byte mask, so classification relies on
 * mask-independent structure (byte equality and XOR-1 offsets), never on
 * hardcoded signature bytes:
 *
 * <h3>Family "R" — reliability layer (heartbeat / ACK / timer ticks)</h3>
 * Payload bytes 2..3 are EQUAL (XOR preserves equality; observed as
 * {@code DF DF} in one session, {@code 70 70} in another). Cadence is
 * metronome-steady (~100-140 ms). OUTBOUND R packets (plen 55..61) are the
 * client's movement keepalive at ~10 Hz; inbound R packets are the server's
 * ACK/timer stream. Freeze must NEVER touch these — dropping them starves the
 * game's reliability timers and triggers disconnect / reconnect flags.
 *
 * <h3>Family "S" — state sync (enemy position / fire / world updates)</h3>
 * Byte 2 equals the R-marker XOR 0x01 (byte 3 is a rolling sequence counter,
 * so b2 != b3). This is the dense bursty stream the freeze effect targets:
 * enemies stop updating on screen precisely when this family is dropped
 * inbound.
 *
 * <h3>Family "C" — control / session (login, matchmaking, big blobs)</h3>
 * Everything else: large state blobs (&gt; 450 bytes), the legacy fire
 * heuristics, and unclassified traffic.
 *
 * <h2>SUPERSEDED — this file is now the pre-solver fallback only</h2>
 *
 * <p><b>Every shape heuristic below is a guess at a fact the header already
 * states.</b> {@code net/SessionKeySolver} recovers the per-session XOR mask and
 * the {@code HEAD_SECRET} field permutation from traffic alone, after which
 * {@code net/PacketCodec.parse} reads the real {@code Cmd}, {@code SendOption},
 * {@code OrderID}, {@code Flags} and {@code Length} out of every packet, and
 * {@code net/CmdCatalogue} names all 1974 ids. Classifying by byte shape when
 * the command id is available is defect <b>D5</b> of
 * {@code docs/DUMPER_REWRITE_BRIEF.md} §2.</p>
 *
 * <p><b>What replaces what</b> — each heuristic, and the command id (or solved
 * header field) that now answers the same question exactly:</p>
 *
 * <table border="1">
 * <caption>Shape heuristic to command id</caption>
 * <tr><th>heuristic</th><th>replaced by</th><th>keep for pre-solver?</th></tr>
 * <tr><td>{@link #FAMILY_R} ({@code b2==b3}, learned tuples)</td>
 *     <td>{@code Cmd.isTransportLayer(cmd)} — ids 1..99, the reserved transport
 *         range: HELLO 1, ACK 2, PING 3, BYEBYE 4, LONGTIMENOSEE 5,
 *         MUDP_CONNECT_ACK 6, SPLITTED_DATAGRAM 99</td><td><b>YES</b></td></tr>
 * <tr><td>{@link #FAMILY_S} ({@code b2 == marker^1})</td>
 *     <td>{@code Cmd.isWorldStateStream(cmd)} — 2001, 2002, 2003, 2009, 2012,
 *         2014, 2016</td><td><b>YES</b></td></tr>
 * <tr><td>{@link #FAMILY_C} (everything else)</td>
 *     <td>the complement of the two above; {@code CmdCatalogue.isKnown(cmd)}
 *         says whether the id is catalogued at all</td><td><b>YES</b></td></tr>
 * <tr><td>{@link #KIND_ACK_SMALL} ({@code plen <= 20})</td>
 *     <td>{@code cmd == 2} {@code UDP_ACK}</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_HEARTBEAT} (inbound R)</td>
 *     <td>{@code cmd == 3} {@code UDP_PING}</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_ACK} (outbound R, off-window length)</td>
 *     <td>{@code cmd == 2} / {@code cmd == 3}</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_KEEPALIVE_MOVE} (outbound R, 55..61 B)</td>
 *     <td>{@code cmd == 2001} outbound {@code UDP_PLAYER_STATE_SYNC}, and its
 *         mounted variants 2002 / 2006 / 2031 which the length window never
 *         saw — {@code Cmd.isOwnPositionCarrier(cmd)}</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_PLAYER_STATE} ({@code plen == 24 || plen == 32})</td>
 *     <td>{@code cmd == 2001} outbound</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_ENEMY_POSITION} ({@code plen == 26 || plen == 34})</td>
 *     <td>{@code cmd == 2001} / {@code 2012} inbound</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_WORLD_STATE} (inbound S, other lengths)</td>
 *     <td>{@code Cmd.isWorldStateStream(cmd)}</td><td>yes</td></tr>
 * <tr><td>{@link #KIND_HIT_CONFIRM} / {@link #isHitConfirmation}
 *         (20..40 B, handle shape, exactly 8 varints)</td>
 *     <td>{@code cmd == 2010} {@code UDP_PLAYER_HURT}. The "exactly 8 varints"
 *         test is exactly this message's field count, so the heuristic was a
 *         re-derivation of the layout: {@code UUZZUUBB}</td><td>yes</td></tr>
 * <tr><td>{@link #hitDamage} (varint v5)</td>
 *     <td>{@code 2010.v5}, {@code hpDamage}. Confirmed.</td><td>yes</td></tr>
 * <tr><td>{@link #hitSource} (varint v2)</td>
 *     <td>{@code 2010.v2}, {@code weaponId} — <b>zigzag</b>, which the raw
 *         varint read here gets wrong for every value</td><td>no</td></tr>
 * <tr><td>{@link #hitWeaponTag} (raw {@code b16})</td>
 *     <td>nothing — {@code b16} is a byte inside a varint. Superseded by
 *         {@code 2010.v2}</td><td><b>no — it is wrong</b></td></tr>
 * <tr><td>{@link #hitZone} and {@link #ZONE_HEAD} and friends</td>
 *     <td>{@code 106.v7} / {@code 168.v3} {@code HitBodyPart}
 *         ({@code Head=1 Body=2 Limb=3}). Already {@code @Deprecated} and
 *         returning 0; the zone constants {@code 1/2/3/9/0x21} never matched
 *         the game's enum</td><td><b>no — it is wrong</b></td></tr>
 * <tr><td>{@link #isPositionKeepalive} (handle shape + 23 varints)</td>
 *     <td>{@code cmd == 2001} outbound. The 23-varint test is that message's
 *         field count; the solved program is
 *         {@code UZZZZZZZZZZZBBFFFUBUBUBU}</td><td>yes</td></tr>
 * <tr><td>{@link #isMovementStream} / {@link #learnMovement}
 *         (length + b2..b7 signature)</td>
 *     <td>{@code Cmd.isOwnPositionCarrier(cmd)}. <b>b2..b7 are masked AND
 *         permuted per session</b>, so the signature is a session-local
 *         accident; the solver reads those same bytes exactly</td><td>yes</td></tr>
 * <tr><td>{@link #isFromMatchServer} / {@link #isMatchStreamLive} /
 *         {@link #matchServerIp}</td>
 *     <td><b>NOT superseded.</b> Endpoint identity and match liveness are not
 *         in any packet body; {@code net/SessionTimeline} (AGENT 3) is the
 *         intended replacement</td><td><b>keep</b></td></tr>
 * <tr><td>{@link #family} learned R-marker / tuple learner</td>
 *     <td>{@code SessionKeySolver} recovers the same session constants exactly
 *         and in a form that survives the permutation</td><td>yes</td></tr>
 * </table>
 *
 * <p><b>Why none of it is deleted.</b> Brief §3 constraint 5: for the first few
 * hundred packets of a session, and again after a key rotation, the header
 * layout is genuinely unknown and {@code cmd} does not exist. In that window
 * shape is all there is, and {@code PacketPipeline.observeAndParse} returns null
 * precisely so callers fall back here. <b>Everything marked "no" above is wrong
 * even as a fallback</b> and must not be reintroduced.</p>
 *
 * <p><b>The rule.</b> Constraint 4: never gate on a raw byte window or a
 * roster-sized constant. A previous freeze policy dropped everything in a raw
 * 21-450 B window and ate 88% of inbound including the player's own damage feed.
 * Every length constant in this file — {@link #KEEPALIVE_MIN},
 * {@link #KEEPALIVE_MAX}, {@link #PLAYER_STATE_LEN_A}, {@link #ENEMY_POS_LEN_A},
 * {@link #SMALL_ACK_MAX}, {@link #MOVE_MIN_LEN}, {@link #MOVE_MAX_LEN} — is one
 * of those. They are tolerable only while {@code cmd} is unavailable.</p>
 *
 * @see com.nepdon.wire.decode.body.Cmd
 * @see com.nepdon.wire.decode.body.CmdCatalogue
 * @see com.nepdon.wire.decode.body.BodyDecoderTable
 * @see com.nepdon.wire.dev.capture.CaptureAnnotator
 */
public final class GamePacketTaxonomy {

    private GamePacketTaxonomy() { }

    // ---- Families ----

    /** Reliability layer: heartbeat, ACK, timer ticks, movement keepalive. */
    public static final int FAMILY_R = 0;
    /** State sync: enemy positions, fire events, world updates. */
    public static final int FAMILY_S = 1;
    /** Control / session / unclassified. */
    public static final int FAMILY_C = 2;

    // ---- Event kinds (what the packet means to the game) ----

    /** Unclassified packet (no rule matched). */
    public static final int KIND_UNKNOWN        = 0;
    /** Reliability: client movement keepalive (OUT, plen 55..61, R-family). */
    public static final int KIND_KEEPALIVE_MOVE = 1;
    /** Reliability: client input/ACK (OUT, R-family, larger than keepalive). */
    public static final int KIND_ACK            = 2;
    /** Reliability: server heartbeat/timer tick (IN, R-family). */
    public static final int KIND_HEARTBEAT      = 3;
    /** State sync: player state feed (OUT, S-family, plen 24/32). */
    public static final int KIND_PLAYER_STATE   = 4;
    /** State sync: enemy position update (IN, S-family, plen 26/34). */
    public static final int KIND_ENEMY_POSITION = 5;
    /** State sync: fire/shoot event (matches the legacy fire heuristics). */
    public static final int KIND_FIRE           = 6;
    /** State sync: enemy/world state blob (IN, S-family, other lengths). */
    public static final int KIND_WORLD_STATE    = 7;
    /** Control: large state blob, session, matchmaking. */
    public static final int KIND_CONTROL        = 8;
    /** Reliability: small ACK fragment (IN or OUT, plen &lt; 21). */
    public static final int KIND_ACK_SMALL      = 9;
    /** Combat: server hit confirmation (your bullet landed; carries damage + zone). */
    public static final int KIND_HIT_CONFIRM    = 10;
    /** Outbound: the learned movement/input stream (ghost target). */
    public static final int KIND_MOVEMENT       = 11;

    // ---- Shape constants (from capture data) ----

    /** Outbound R-family keepalive length window. */
    public static final int KEEPALIVE_MIN = 55;
    /** Outbound R-family keepalive length window. */
    public static final int KEEPALIVE_MAX = 61;
    /** Outbound S-family player-state feed lengths. */
    public static final int PLAYER_STATE_LEN_A = 24;
    /** Outbound S-family player-state feed lengths. */
    public static final int PLAYER_STATE_LEN_B = 32;
    /** Inbound S-family enemy-position update lengths. */
    public static final int ENEMY_POS_LEN_A = 26;
    /** Inbound S-family enemy-position update lengths. */
    public static final int ENEMY_POS_LEN_B = 34;
    /** Lengths at or below this are treated as small ACK fragments. */
    public static final int SMALL_ACK_MAX = 20;

    // ---- Family detection ----

    /**
     * Session XOR-mask marker learned from observed R-family packets
     * ({@code b2 == b3} reveals the marker directly, since the mask cancels
     * out in the equality). {@code -1} until the first R packet is seen.
     */
    private static volatile int learnedRMarker = -1;

    // ---- Adaptive channel learner (mask-proof R detection) ----

    /**
     * Some sessions obfuscate payloads with a positional keystream, so even
     * the b2==b3 equality invariant disappears. What survives every encoding
     * so far: the movement-keepalive channel (outbound, ~10 Hz metronome)
     * and its inbound ACK responses share a constant (b2, b4, b5) header
     * tuple. The learner confirms a tuple as "reliability" after
     * {@link #KEEPALIVE_CONFIRM_COUNT} consecutive gaps in the 10 Hz window,
     * then {@link #family} protects inbound packets carrying that tuple.
     */
    private static final int KEEPALIVE_GAP_MIN_MS = 50;
    private static final int KEEPALIVE_GAP_MAX_MS = 150;
    private static final int KEEPALIVE_CONFIRM_COUNT = 20;
    /** Movement-keepalive length window, stable across all observed sessions. */
    private static final int KEEPALIVE_LEN_MIN = 45;
    private static final int KEEPALIVE_LEN_MAX = 64;
    /** The keepalive channel always spans multiple lengths; a single-length
     *  stream with steady gaps is something else (e.g. the player state feed). */
    private static final int KEEPALIVE_DISTINCT_LENS = 2;
    private static final int MAX_OUT_TUPLES = 256;
    private static final int MAX_LEARNED_TUPLES = 32;

    private static final ConcurrentHashMap<Integer, TupleTrack> outTuples = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> reliabilityTuples = new ConcurrentHashMap<>();

    private static final class TupleTrack {
        long lastTs;
        int steadyCount;
        long lenMask;
    }

    private static int tupleKey(int b2, int b4, int b5) {
        return (b2 << 16) | (b4 << 8) | b5;
    }

    /**
     * Observes one outbound packet for keepalive-channel learning. Called on
     * the tun loop for every outbound packet in the keepalive length window;
     * cost is one hash lookup plus a timestamp compare. A tuple is confirmed
     * as the reliability channel after {@link #KEEPALIVE_CONFIRM_COUNT}
     * consecutive gaps in the 10 Hz window AND at least
     * {@link #KEEPALIVE_DISTINCT_LENS} distinct packet lengths (the
     * movement-keepalive channel always spans 55-61-ish bytes; bursty
     * single-length state feeds never qualify).
     */
    public static void observeOutbound(byte[] data, int off, int plen, long nowMs,
                                       int dip, int dp) {
        // The movement learner also sets the match endpoint on confirmation
        // (the only trustworthy source — see learnMovement).
        learnMovement(data, off, plen, nowMs, dip, dp);
        // Single-writer R-marker learning (keeps family() a pure query).
        learnRMarker(data, off, plen);
        if (plen < KEEPALIVE_LEN_MIN || plen > KEEPALIVE_LEN_MAX) return;
        if (off < 0 || off + plen > data.length) return;
        int key = tupleKey(data[off + 2] & 0xff, data[off + 4] & 0xff, data[off + 5] & 0xff);
        if (outTuples.size() > MAX_OUT_TUPLES) {
            outTuples.clear();
        }
        TupleTrack t = outTuples.get(key);
        if (t == null) {
            TupleTrack fresh = new TupleTrack();
            t = outTuples.putIfAbsent(key, fresh);
            if (t == null) t = fresh;
        }
        synchronized (t) {
            if (t.lastTs != 0L) {
                long gap = nowMs - t.lastTs;
                if (gap >= KEEPALIVE_GAP_MIN_MS && gap <= KEEPALIVE_GAP_MAX_MS) {
                    t.steadyCount++;
                    t.lenMask |= (1L << (plen & 63));
                    if (t.steadyCount >= KEEPALIVE_CONFIRM_COUNT
                            && Long.bitCount(t.lenMask) >= KEEPALIVE_DISTINCT_LENS
                            && reliabilityTuples.size() < MAX_LEARNED_TUPLES) {
                        reliabilityTuples.put(key, Boolean.TRUE);
                    }
                } else {
                    t.steadyCount = 0;
                }
            }
            t.lastTs = nowMs;
        }
    }

    /** Clears all learned channel state (call when the tunnel restarts). */
    public static void resetLearned() {
        outTuples.clear();
        reliabilityTuples.clear();
        learnedRMarker = -1;
        matchServerIp = 0;
        matchServerPort = 0;
        resetMovementChannel();
    }

    // ---- Movement-stream learner + match-endpoint tracking (ghost/freeze scope) ----

    /**
     * The player's movement/input channel. From capture analysis across every
     * observed session, this stream is:
     * <ul>
     *   <li>outbound UDP to the match server, at a steady high rate
     *       (median gap 15-35 ms, i.e. ~20-60 packets/sec), sustained for
     *       the whole match;</li>
     *   <li>a single fixed payload length per session (always 24 bytes in
     *       every observed layout) — nothing else ever rides that length;</li>
     *   <li>payload bytes 2..7 are a session-constant header (unique per
     *       session under the rotating XOR mask); bytes 8+ are live input
     *       data (95-99% of consecutive packets change them);</li>
     *   <li>crucially, fire events NEVER share this length AND header —
     *       they ride separate shapes (isolated ~26-byte packets with
     *       per-shot varying bytes 3-5), so a length+header-matched drop
     *       cannot block fire.</li>
     * </ul>
     *
     * <p><b>This learner is also the match-endpoint tracker.</b> The old
     * endpoint tracker learned from ANY outbound packet, which let the
     * voice server (outbound voice keepalives and mic speech) steal the
     * "match server" identity — the freeze then dropped teammate voice
     * while passing enemy state, and the ghost never learned the movement
     * stream at all. Now the endpoint is set ONLY when a movement stream
     * CONFIRMS on it. Voice can never confirm: voice frames carry an
     * incrementing sequence byte at position 3 (header changes every
     * packet), so each voice packet lands in a different candidate bucket
     * and never accumulates. Match-loading bursts (40-byte flushes with
     * 0 ms gaps) also cannot confirm (gaps must be 5-400 ms).</p>
     */
    private static final int MOVE_MIN_LEN = 21;
    /**
     * DO NOT RAISE THIS PAST ~55 WITHOUT READING THIS NOTE.
     *
     * <p>Despite the name, the stream this learner locks onto is <b>not</b> the
     * player's movement channel — it is the outbound 24-byte {@code Cmd 2}
     * {@code UDP_ACK} stream. Measured in
     * {@code captures/dev_20260916/capture_raw_20260916_214646.txt}: outbound
     * Cmd 2001 is <b>56-62 bytes</b>, so it fails this ceiling and can never be
     * a candidate; the only 21-40 B outbound stream with a constant bytes-2..7
     * header is the ACK stream (27,608 packets). {@code GhostFilter} already
     * says so at its own call site; this class's javadoc above still calls it
     * "the movement channel" and is stale.</p>
     *
     * <p><b>That accident is load-bearing.</b> It is what keeps the endpoint
     * identity alive while mounted. Max observed gap on the tracked 24 B
     * stream: <b>3,092 ms</b>. Max observed gap on Cmd 2001: <b>34,585 ms</b>,
     * because outbound 2001 stops entirely in a vehicle. Seven 2001 gaps
     * exceeded {@code MATCH_STALE_MS} (10,000); the ACK stream never came
     * close.</p>
     *
     * <p>So if a future 2001 shrinks below this ceiling, or someone widens it,
     * the learner locks onto 2001 instead and <b>every mount longer than 10 s
     * stales the match identity</b> — opening it to theft by another endpoint
     * and taking freeze's and ghost's liveness gates open with it. That is up
     * to ~24 s of a lit button doing nothing, with no error anywhere.</p>
     */
    private static final int MOVE_MAX_LEN = 40;
    /** Upper bound for a steady gap (bursts below 5 ms count as steady too). */
    private static final int MOVE_GAP_MAX_MS = 400;
    /**
     * Confirm the stream after this many packets with the same header.
     * Capture analysis showed the movement stream is unmistakable by ~100
     * packets (nothing else sends 100+ same-length same-header packets at
     * steady gaps); the old 250/6s bar took ~27s at real-world rates, during
     * which freeze taps silently did nothing — the "randomly doesn't work"
     * symptom. 100 packets at ~19-30 Hz arms the freeze in ~4-5s.
     */
    private static final int MOVE_CONFIRM_COUNT = 100;
    /** ...and at least this much sustained time (excludes loading bursts). */
    private static final long MOVE_CONFIRM_SPAN_MS = 4000L;
    /** A gap longer than this resets the candidate (speech has pauses;
     *  loading bursts end; the movement stream runs for minutes). */
    private static final long MOVE_CAND_GAP_RESET_MS = 1500L;
    private static final int MAX_MOVE_ENDPOINTS = 16;
    /**
     * Per-endpoint candidate cap. Fire bursts each carry a unique header, so
     * combat generates a new candidate per burst; the old 32-entry cap made
     * the whole map clear every ~33 bursts, repeatedly delaying movement
     * re-confirmation by 8-40s mid-match. 128 absorbs a full engagement.
     */
    private static final int MAX_MOVE_CANDS = 128;

    /** Learned movement stream: payload length, or -1 until confirmed. */
    private static volatile int moveLen = -1;
    /** Learned movement stream: payload bytes 2..7 (six bytes). */
    private static volatile byte[] moveHeader = null;
    /** Last time a packet matching the confirmed signature was seen. */
    private static volatile long lastTrackedMoveMs = 0L;
    /** Rate-limits mid-match header re-adoption (mask-rotation recovery). */
    private static volatile long lastHeaderAdoptMs = 0L;

    private static final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, MoveCand>> moveCands =
            new ConcurrentHashMap<>();

    private static final class MoveCand {
        long firstTs;
        long lastTs;
        int count;
        int steadyRun;
    }

    /** Clears learned movement-channel state. */
    public static void resetMovementChannel() {
        moveLen = -1;
        moveHeader = null;
        lastTrackedMoveMs = 0L;
        moveCands.clear();
    }

    /** True once the movement stream signature has been learned this session. */
    public static boolean movementLearned() {
        return moveLen >= 0;
    }

    /** Full-precision endpoint key: the whole IP plus the port (no truncation). */
    private static long endpointKey(int dip, int dp) {
        return ((long) dip << 16) | (dp & 0xffffL);
    }

    /**
     * True when this outbound payload is the learned movement/input stream.
     * Call only for outbound packets; returns false until learned.
     */
    public static boolean isMovementStream(byte[] data, int off, int plen) {
        int len = moveLen;
        byte[] hdr = moveHeader;
        if (len < 0 || hdr == null) return false;
        if (plen != len || off < 0 || off + len > data.length) return false;
        for (int i = 0; i < hdr.length; i++) {
            if ((data[off + 2 + i] & 0xff) != (hdr[i] & 0xff)) return false;
        }
        return true;
    }

    /**
     * Learns the movement stream and, on confirmation, the match endpoint.
     * Candidates are keyed by (endpoint, length, bytes-2..7-header); only a
     * candidate with a constant header, steady 5-400 ms gaps, 250+ packets,
     * and 6+ seconds of continuity can confirm — which only the movement
     * stream satisfies (see class comment).
     */
    private static void learnMovement(byte[] data, int off, int plen, long nowMs,
                                      int dip, int dp) {
        if (plen < MOVE_MIN_LEN || plen > MOVE_MAX_LEN) return;
        if (off < 0 || off + plen > data.length) return;
        long ep = endpointKey(dip, dp);
        // Already-confirmed signature on the tracked endpoint: refresh liveness.
        if (isMovementStream(data, off, plen) && ep == endpointKey(matchServerIp, matchServerPort)) {
            lastTrackedMoveMs = nowMs;
            return;
        }
        // Header-rotation recovery: same endpoint + same length + steady
        // cadence IS the movement stream even when the session's XOR mask
        // rotated the bytes 2..7 header mid-match. Without this, a rotation
        // permanently killed liveness (isMovementStream never matched again)
        // and re-learning had to survive candidate-map churn.
        if (ep == endpointKey(matchServerIp, matchServerPort) && plen == moveLen) {
            lastTrackedMoveMs = nowMs;
            long lastAdopt = lastHeaderAdoptMs;
            if (nowMs - lastAdopt > 2000L) {
                byte[] hdr = new byte[6];
                System.arraycopy(data, off + 2, hdr, 0, 6);
                moveHeader = hdr;
                lastHeaderAdoptMs = nowMs;
            }
            return;
        }
        int key = plen;
        for (int i = 2; i <= 7; i++) key = key * 31 + (data[off + i] & 0xff);
        ConcurrentHashMap<Integer, MoveCand> cands = moveCands.get(ep);
        if (cands == null) {
            if (moveCands.size() >= MAX_MOVE_ENDPOINTS) moveCands.clear();
            cands = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, MoveCand> prev = moveCands.putIfAbsent(ep, cands);
            if (prev != null) cands = prev;
        }
        if (cands.size() > MAX_MOVE_CANDS) cands.clear();
        MoveCand c = cands.get(key);
        if (c == null) {
            MoveCand fresh = new MoveCand();
            c = cands.putIfAbsent(key, fresh);
            if (c == null) c = fresh;
        }
        synchronized (c) {
            if (c.lastTs != 0L) {
                long gap = nowMs - c.lastTs;
                if (gap > MOVE_CAND_GAP_RESET_MS) {
                    // Stream went silent: start over (speech pause / loading end).
                    c.count = 0;
                    c.steadyRun = 0;
                    c.firstTs = nowMs;
                } else if (gap <= MOVE_GAP_MAX_MS) {
                    // Steady, INCLUDING intra-burst gaps below 5 ms — the
                    // game sends input packets in back-to-back bursts
                    // (capture-verified: p50 gap 1 ms, ~50% of gaps < 5 ms).
                    // Treating those as "unsteady" reset the run counter and
                    // kept confirmation forever out of reach (longest run
                    // 15-18 vs the required 20) — freeze/ghost silently
                    // never armed.
                    c.steadyRun++;
                } else {
                    c.steadyRun = 0;
                }
            } else {
                c.firstTs = nowMs;
            }
            c.lastTs = nowMs;
            c.count++;
            if (c.count >= MOVE_CONFIRM_COUNT && c.steadyRun >= 12
                    && nowMs - c.firstTs >= MOVE_CONFIRM_SPAN_MS) {
                confirmMovement(data, off, plen, nowMs, dip, dp);
            }
        }
    }

    /**
     * A movement candidate confirmed: adopt its signature and endpoint.
     * A different endpoint only takes over when the current one has gone
     * stale for MATCH_STALE_MS (aligned with {@link #isMatchStreamLive}),
     * so a stale server never steals the identity back mid-match — and a
     * trickling old signature can't block a live re-learn forever.
     */
    private static void confirmMovement(byte[] data, int off, int plen, long nowMs,
                                        int dip, int dp) {
        if (matchServerIp != 0 && (dip != matchServerIp || dp != matchServerPort)
                && nowMs - lastTrackedMoveMs < MATCH_STALE_MS) {
            return; // current match endpoint still live; ignore stranger
        }
        byte[] hdr = new byte[6];
        System.arraycopy(data, off + 2, hdr, 0, 6);
        moveHeader = hdr;
        moveLen = plen;
        lastTrackedMoveMs = nowMs;
        matchServerIp = dip;
        matchServerPort = dp;
        // Signature adopted; drop all candidate tracking state.
        moveCands.clear();
    }

    // ---- Match-server tracking (freeze scope) ----

    /**
     * The UDP endpoint of the current match server. Set ONLY when a movement
     * stream confirms on it (see {@link #learnMovement}) — never from raw
     * packet observation, which let the voice server steal the identity and
     * caused the freeze to drop teammate voice while passing enemy state.
     */
    private static volatile int matchServerIp;
    private static volatile int matchServerPort;

    /** True when this inbound packet's source is the tracked match server. */
    public static boolean isFromMatchServer(int srcIp, int srcPort) {
        return matchServerIp != 0 && srcIp == matchServerIp && srcPort == matchServerPort;
    }

    /** The tracked match server's IP (0 when unset; for telemetry). */
    public static int matchServerIp() {
        return matchServerIp;
    }

    /** The tracked match server's port (for telemetry). */
    public static int matchServerPort() {
        return matchServerPort;
    }

    /** Voice frames exempted by the freeze since the last telemetry drain. */
    private static final java.util.concurrent.atomic.AtomicLong voiceFrames =
            new java.util.concurrent.atomic.AtomicLong();

    /** Drains the voice-frame counter (telemetry poller). */
    public static long drainVoiceFrames() {
        return voiceFrames.getAndSet(0);
    }

    /**
     * A tracked endpoint counts as a LIVE match only while its movement
     * stream keeps flowing. At match end the server does NOT go silent —
     * capture evidence shows it seamlessly becomes the lobby/squad session
     * (client re-binds ports on the same server) — so endpoint identity
     * alone can't distinguish match from lobby. Stream liveness can: the
     * movement stream stops for good the moment the match ends.
     *
     * <p>Capture-measured: routine mid-match movement pauses reach 7.3 s
     * (player standing still / aiming / talking), which falsely staled the
     * old 3 s gate and made the freeze silently pass everything — the
     * "randomly stops working" symptom. 10 s clears every observed mid-match
     * pause while still detecting match end within seconds.</p>
     */
    private static final long MATCH_STALE_MS = 10000L;

    /** True while the tracked endpoint carries a live match movement stream. */
    public static boolean isMatchStreamLive(long nowMs) {
        long last = lastTrackedMoveMs;
        return matchServerIp != 0 && last != 0L && (nowMs - last) < MATCH_STALE_MS;
    }

    /**
     * Detects the protocol family from mask-independent payload structure.
     * The game obfuscates each session's payloads (constant XOR mask in some
     * sessions, positional keystream in others), so hardcoded signature bytes
     * break whenever the encoding rotates. What survives:
     *
     * <ul>
     *   <li><b>Family R</b> (reliability: heartbeat, ACK, timer ticks):
     *       bytes 2 and 3 are EQUAL under a constant XOR mask (observed
     *       {@code DF DF}, {@code 70 70}); under a keystream, the packet
     *       instead carries the keepalive channel's learned (b2, b4, b5)
     *       tuple. Steady ~100-200 ms cadence either way. <b>Length-capped
     *       at {@link #KEEPALIVE_LEN_MAX}</b>: real heartbeat/ACK responses
     *       are always small (&le; 64 bytes across every observed session),
     *       while 147+ byte packets riding the same channel are world-state
     *       snapshot blobs (enemy positions) that freeze must drop —
     *       protecting them lets enemies keep moving on screen.</li>
     *   <li><b>Family S</b> (state sync: enemy position / fire / world):
     *       byte 2 equals the R-marker XOR 0x01 (constant-mask sessions);
     *       byte 3 is a rolling sequence counter, so b2 != b3. Bursty cadence.</li>
     *   <li><b>Family C</b>: everything else (control / session / big
     *       blobs).</li>
     * </ul>
     */
    public static int family(byte[] data, int off, int plen) {
        if (plen < 5 || off < 0 || off + plen > data.length) return FAMILY_C;
        int b2 = data[off + 2] & 0xff;
        int b3 = data[off + 3] & 0xff;
        if (plen <= KEEPALIVE_LEN_MAX) {
            if (b2 == b3) {
                return FAMILY_R;
            }
            if (plen >= 6) {
                int key = tupleKey(b2, data[off + 4] & 0xff, data[off + 5] & 0xff);
                if (reliabilityTuples.containsKey(key)) {
                    return FAMILY_R;
                }
            }
        }
        int marker = learnedRMarker;
        if ((marker >= 0 && b2 == (marker ^ 0x01)) || b2 == (b3 ^ 0x01)) {
            return FAMILY_S;
        }
        return FAMILY_C;
    }

    /**
     * Learns the R marker from an outbound packet (mask cancels in the
     * b2==b3 equality). Called from the tun loop only, keeping the marker
     * single-writer; {@link #family()} stays a pure query.
     */
    private static void learnRMarker(byte[] data, int off, int plen) {
        if (plen < 5 || plen > KEEPALIVE_LEN_MAX) return;
        if (off < 0 || off + plen > data.length) return;
        if ((data[off + 2] & 0xff) == (data[off + 3] & 0xff)) {
            learnedRMarker = data[off + 2] & 0xff;
        }
    }

    // ---- Event kind detection ----

    /** Hit-zone codes observed in the hit-confirmation packet (see PROTOCOL_MAP). */
    public static final int ZONE_TORSO  = 1;
    public static final int ZONE_LIMB   = 2;
    public static final int ZONE_EXTRA  = 3;
    public static final int ZONE_HEAD   = 9;
    public static final int ZONE_MARKER = 0x21;


    /**
     * True for a genuine damage event.
     *
     * <p><b>Rewritten 2026-09-10; the previous version accepted only 22.7% of
     * real damage events</b>, silently discarding 77% of hits. Two reasons,
     * both measured over 2 668 real events:</p>
     * <ul>
     *   <li>It required byte 17 to be a "plausible hit zone". There is no hit
     *       zone in this packet: byte 17 falls inside the server-tick varint
     *       in <b>76.5%</b> of packets and inside the weapon varint in the
     *       other 23.5%. It is never a body part. See {@link #hitZone}.</li>
     *   <li>It then locked onto masked bytes b2/b4/b5 as a signature, and
     *       b2-b7 are XOR-masked AND cyclically rotated per session — the
     *       trap documented in PROTOCOL_MASTER.md section 1.2.</li>
     * </ul>
     *
     * <p>The structural test instead: the body from b8 must parse to exactly
     * eight varints consuming the packet with no padding, with the leading
     * object handle in its canonical 4-byte shape.</p>
     *
     * @deprecated <b>PRE-SOLVER FALLBACK ONLY.</b> This is
     *     {@code cmd == 2010} {@code UDP_PLAYER_HURT}, whose solved layout is
     *     {@code UUZZUUBB} — so "exactly eight varints" was this message's field
     *     count rediscovered from bytes. Note the layout also says v2 and v3 are
     *     <b>zigzag</b>, which {@link #hitSource} reads as a plain varint and
     *     therefore reports wrong for every value.
     */
    @Deprecated
    public static boolean isHitConfirmation(byte[] data, int off, int plen) {
        if (plen < 20 || plen > 40) return false;
        if (off < 0 || off + plen > data.length || off + 12 > data.length) return false;
        if ((data[off + 8] & 0x80) == 0) return false;
        if ((data[off + 9] & 0xff) != 0x80 || (data[off + 10] & 0xff) != 0x80) return false;
        int tt = data[off + 11] & 0xff;
        if (tt == 0 || (tt & 0x07) != 0) return false;   // team << 3
        return countVarints(data, off + 8, off + plen, 16) == 8;
    }

    /**
     * Exact varint count for data[from..end), or -1 if it does not align.
     *
     * <p><b>{@code max} is a parameter because sharing one cap broke a
     * caller.</b> The ceiling was hard-coded at 16, which is right for the
     * 8-varint team-assignment gate and silently fatal for the 23-varint
     * keepalive gate: {@code isPositionKeepalive} bailed at the 17th varint
     * and returned -1, so its {@code == 23} test could never pass and the
     * method <b>always returned false</b>.</p>
     *
     * <p>Both callers are the pre-solver fallback, so the damage landed in the
     * first few hundred packets of every session and again after every key
     * rotation: {@code GhostFilter} held nothing in that window, and
     * {@code TeleportFilter} was inert in it. Ghost's own log printed
     * {@code shape=false}, which reads as "the test failed" rather than "the
     * test is unreachable" -- the silent-skip shape again.</p>
     */
    private static int countVarints(byte[] data, int from, int end, int max) {
        int n = 0, i = from;
        while (i < end) {
            int sh = 0;
            boolean done = false;
            while (i < end) {
                int c = data[i++] & 0xff;
                if ((c & 0x80) == 0) { done = true; break; }
                sh += 7;
                if (sh > 63) return -1;
            }
            if (!done) return -1;
            n++;
            if (n > max) return -1;
        }
        return n;
    }

    /** Reads the n-th varint of the body starting at b8; -1 if absent. */
    private static long bodyVarint(byte[] data, int off, int plen, int index) {
        int i = off + 8, end = off + plen;
        for (int k = 0; ; k++) {
            long v = 0; int sh = 0; boolean done = false;
            while (i < end) {
                int c = data[i++] & 0xff;
                v |= (long) (c & 0x7f) << sh;
                if ((c & 0x80) == 0) { done = true; break; }
                sh += 7;
                if (sh > 63) return -1;
            }
            if (!done) return -1;
            if (k == index) return v;
        }
    }

    /**
     * True for the outbound position keepalive — by SHAPE, not by length.
     *
     * <p>A length window is the wrong test and was measurably losing packets.
     * Real keepalives span <b>48-67 bytes</b>, not the 45-64 that ghost
     * assumed: in one session 431 of 3 966 (<b>10.9%</b>) were 65-67 B and
     * fell outside the window, so ghost leaked our true position on one
     * packet in nine while claiming to hold it. Another session had 22.6% of
     * keepalives at 48-54 B.</p>
     *
     * <p>The shape is what is actually invariant: an id below 128 zero-padded
     * into a 4-byte varint handle (so b9 and b10 are 0x80, and b8 carries its
     * continuation bit), plus a body of exactly 23 varints. Measured 100%
     * recall AND 100% precision across six sessions, both game modes, every
     * observed team byte — see PROTOCOL_MASTER section 4.2.</p>
     *
     * <p>Do NOT add a test on b11: it is the squad index and varies per match.
     * Do NOT test b2-b7: masked and rotated per session.</p>
     *
     * @deprecated <b>PRE-SOLVER FALLBACK ONLY.</b> This is {@code cmd == 2001}
     *     outbound, solved program {@code UZZZZZZZZZZZBBFFFUBUBUBU} — the
     *     "exactly 23 varints" test is that message's field count. It also
     *     cannot see the mounted variants: the sender dispatches on
     *     {@code IsInVehicle()} first, so a driver sends 2002, a firing
     *     passenger 2006 and a cannon 2031, and this returns false for all of
     *     them. Use {@code Cmd.isOwnPositionCarrier(cmd)}.
     */
    @Deprecated
    public static boolean isPositionKeepalive(byte[] data, int off, int plen) {
        if (plen < 32 || plen > 96) return false;
        if (off < 0 || off + 14 > data.length || off + plen > data.length) return false;
        if ((data[off + 9] & 0xff) != 0x80 || (data[off + 10] & 0xff) != 0x80) return false;
        if ((data[off + 8] & 0x80) == 0) return false;
        int body = skipOneVarint(data, off + 8, off + plen);
        if (body < 0) return false;
        return countVarints(data, body, off + plen, 23) == 23;
    }

    /** Index just past one LEB128 varint, or -1 if truncated. */
    private static int skipOneVarint(byte[] data, int i, int end) {
        int sh = 0;
        while (i < end) {
            int c = data[i++] & 0xff;
            if ((c & 0x80) == 0) return i;
            sh += 7;
            if (sh > 63) return -1;
        }
        return -1;
    }

    /** Damage value carried by a hit confirmation (call after {@link #isHitConfirmation}). */
    public static int hitDamage(byte[] data, int off, int plen) {
        // Varint v5, decoded properly. The old data[plen-3] agreed 97.90% of
        // the time and failed for every hit of 128 or more, where it returned
        // the varint's high byte instead of the value.
        long d = bodyVarint(data, off, plen, 5);
        return (d < 0 || d > 255) ? 0 : (int) d;
    }

    /** Hit-zone code (1=torso, 2=limb, 3=extra, 9=head; call after {@link #isHitConfirmation}). */
    /**
     * @deprecated <b>There is no hit zone in this packet.</b> Measured over
     *     2 668 real damage events, the byte this used to read lies inside
     *     the server-tick varint 76.5% of the time and inside the weapon
     *     varint the rest. The packet parses as exactly eight varints
     *     consuming every byte with no padding, so no hidden fixed-width
     *     field exists; and conditioned on (attacker, weapon) the damage is
     *     continuous rather than bimodal, so there is no headshot multiplier
     *     to expose a zone either. Body-part data lives in the encrypted
     *     hit-effect messages. Always returns 0.
     */
    @Deprecated
    public static int hitZone(byte[] data, int off, int plen) {
        return 0;
    }

    /** Human-readable zone name. */
    public static String zoneName(int zone) {
        switch (zone) {
            case ZONE_TORSO:  return "TORSO";
            case ZONE_LIMB:   return "LIMB";
            case ZONE_EXTRA:  return "EXTRA";
            case ZONE_HEAD:   return "HEAD";
            case ZONE_MARKER: return "MARKER";
            default:          return "Z" + zone;
        }
    }

    /**
     * Classifies a payload into an event kind. Direction-aware because the
     * same byte shapes mean different things client→server vs server→client
     * (R-family outbound is movement keepalive; inbound is server heartbeat).
     *
     * @deprecated <b>PRE-SOLVER FALLBACK ONLY (brief §2, D5).</b> Once
     *     {@code PacketPipeline.isReady()} the packet's real {@code Cmd} is
     *     available and {@code CmdCatalogue.nameOr(cmd)} names it exactly; every
     *     kind this returns is a guess at that id from a length window. See the
     *     class-level heuristic-to-id table. Callers that have a parsed
     *     {@code cmd} must use it and call this only when they do not.
     */
    @Deprecated
    public static int kind(byte[] data, int off, int plen, boolean outbound) {
        if (plen < 1) return KIND_UNKNOWN;
        if (plen <= SMALL_ACK_MAX) return KIND_ACK_SMALL;
        if (outbound && isMovementStream(data, off, plen)) return KIND_MOVEMENT;

        int f = family(data, off, plen);
        if (f == FAMILY_R) {
            if (outbound) {
                return (plen >= KEEPALIVE_MIN && plen <= KEEPALIVE_MAX)
                        ? KIND_KEEPALIVE_MOVE : KIND_ACK;
            }
            return KIND_HEARTBEAT;
        }
        if (f == FAMILY_S) {
            if (outbound) {
                if (plen == PLAYER_STATE_LEN_A || plen == PLAYER_STATE_LEN_B) {
                    return KIND_PLAYER_STATE;
                }
                return KIND_UNKNOWN;
            }
            if (isHitConfirmation(data, off, plen)) return KIND_HIT_CONFIRM;
            if (plen == ENEMY_POS_LEN_A || plen == ENEMY_POS_LEN_B) {
                return KIND_ENEMY_POSITION;
            }
            return KIND_WORLD_STATE;
        }
        // Family C.
        if (!outbound && isHitConfirmation(data, off, plen)) return KIND_HIT_CONFIRM;
        if (plen > FreezeFilter.SHAPE_MAX) return KIND_CONTROL;
        return KIND_UNKNOWN;
    }

    // ---- Names ----

    /** Short family name for logs and reports. */
    public static String familyName(int f) {
        switch (f) {
            case FAMILY_R: return "R";
            case FAMILY_S: return "S";
            default:       return "C";
        }
    }

    /** Human-readable event kind name for logs and reports. */
    public static String kindName(int k) {
        switch (k) {
            case KIND_KEEPALIVE_MOVE:  return "KEEPALIVE_MOVE";
            case KIND_ACK:             return "ACK";
            case KIND_HEARTBEAT:       return "HEARTBEAT";
            case KIND_PLAYER_STATE:    return "PLAYER_STATE";
            case KIND_ENEMY_POSITION:  return "ENEMY_POSITION";
            case KIND_FIRE:            return "FIRE";
            case KIND_WORLD_STATE:     return "WORLD_STATE";
            case KIND_CONTROL:         return "CONTROL";
            case KIND_ACK_SMALL:       return "ACK_SMALL";
            case KIND_HIT_CONFIRM:     return "HIT_CONFIRM";
            case KIND_MOVEMENT:        return "MOVEMENT";
            default:                   return "UNKNOWN";
        }
    }
}
