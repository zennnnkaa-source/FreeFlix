package com.nepdon.wire.protocol;

import com.nepdon.wire.decode.body.Cmd;
import com.nepdon.wire.decode.body.CmdCatalogue;
import com.nepdon.wire.decode.frame.UdpPacket;

/**
 * Where we are in the game's session lifecycle — lobby, matchmaking, loading,
 * match, post-match — and which match this is.
 *
 * <p>This is the fix for defect <b>D7</b> in {@code docs/DUMPER_REWRITE_BRIEF.md}
 * §2: before this class the capture could not say which mode was joined, when the
 * match started, when the key rotated or when the server endpoint changed. Every
 * capture record is stamped with {@link #phaseId()} and {@link #matchSeq()}, so an
 * offline analyst can slice one capture file into per-match segments and know what
 * each segment was.</p>
 *
 * <h3>This class is an OBSERVER</h3>
 * Brief §3 constraint 1 is absolute: nothing here may drop, delay, reorder or
 * mutate a packet. {@link #observeTcp} and {@link #observeUdp} are called from the
 * packet path, take no reference to the caller's buffers past the call, and
 * <b>never throw</b> — every public entry point has a {@code catch (Throwable)}.
 * The uplink path does no parsing and takes no lock at all (see
 * {@link #observeTcp}).
 *
 * <h3>Where the game mode comes from — the evidence</h3>
 *
 * <p>The mode arrives <b>in plaintext on the lobby TCP downlink</b>, in the very
 * same message that carries the UDP session key that
 * {@link LobbyKeySniffer} already recovers.</p>
 *
 * <p>The message is <b>{@code tcp.MatchmakingSussNtf}</b> ("Suss" = Success),
 * dispatched as {@code EMatchmaking.Proto_MATCHMAKINGSUSS_NTF = 5}. Its protobuf
 * field numbers, from the {@code [ProtoMember(n)]} attributes:</p>
 *
 * <pre>
 *   1  uint64 match_id
 *   2  string server_addr           "ip:port"      &lt;- LobbyKeySniffer's anchor
 *   3  string secret                32 ASCII hex   &lt;- the UDP SECRET_KEY
 *   4  string prepare_token
 *   5  uint32 sleep_ms
 *   6  uint32 map_id
 *   7  uint32 game_mode             EMatch.GameMode      &lt;-- THE MODE
 *   8  uint32 match_mode            EMatch.MatchMode     &lt;-- casual / ranked / ...
 *   9  bool   use_cache
 *  11  uint32 level_visual_style
 *  12  uint32 difficulty
 *  21  uint32 group_mode            EMatch.GroupMode (solo/duo/squad)
 *  28  bool   is_reconnection
 *  29  bool   is_halfway_join
 *  42  string udp_mask              the HEAD_SECRET header permutation
 * </pre>
 *
 * <p>Three independent confirmations that this is the right class:</p>
 * <ol>
 *   <li>{@code LobbyKeySniffer} already matches "field 2 = an {@code ip:port}
 *       string immediately followed by field 3 = a 32-byte lowercase-hex string"
 *       on <b>live traffic from this build</b>, and the key it extracts decrypted
 *       the matching UDP stream at TEA 2896/2896 and CRC7 3462/3462. That is a
 *       field-number-level match against the real wire, not a guess.</li>
 *   <li>Our own image's {@code dump_names.txt} lists
 *       {@code tcp.MatchmakingSussNtf} with <b>52 fields in exactly the order
 *       above</b> — byte-for-byte the same field list and order as the reference
 *       dump the {@code [ProtoMember]} numbers came from.</li>
 *   <li>The two mode enums agree numerically across two completely separate
 *       protocol namespaces: the lobby protobuf {@code EMatch.GameMode} and the
 *       RUDP {@code message.NIKKALKPIBO} ({@code EGAMEMODE_*}) assign the same
 *       value to every member they share (1 BattleRoyale, 15 CS, 24 TDM,
 *       36 StrikeOut, 61 PDM, 82 FPPCS, ...).</li>
 * </ol>
 *
 * <h3>Confidently detected vs inferred</h3>
 * Marked per transition in {@link #applyUdpCmd} and {@link #tick}:
 * <ul>
 *   <li><b>CONFIRMED</b> — driven by a message we identified by name/field number:
 *       the match assignment (mode, map, server, key), and the in-match
 *       transitions driven by command <i>identity</i> (Cmd 100/110/130 to MATCH,
 *       Cmd 103/407 to POSTMATCH, the round-result ids).</li>
 *   <li><b>INFERRED</b> — a timing or traffic-shape heuristic with no message to
 *       back it: MATCHMAKING (the uplink is AES-encrypted, see below, so we
 *       cannot read the request), the MATCH fallback when no join message is
 *       seen, and every "went quiet" transition.</li>
 * </ul>
 *
 * <h3>Why the client&rarr;server lobby direction is not parsed</h3>
 * {@code COW.TCPSession2::Send} @{@code 0x06b52eb0} calls
 * {@code COW.TCPSession2::Encrypt} @{@code 0x06b55d3c} (its <b>only</b> caller,
 * verified by a full {@code BL} scan of the decrypted image) gated on a server
 * config bool. {@code TCPSession2} has <b>no</b> {@code Decrypt} method, which is
 * the code-level counterpart of the measured 6.31 bits/byte entropy on the
 * downlink: <b>the uplink may be AES ciphertext and the downlink is plaintext.</b>
 * So the uplink is counted, captured raw by {@code dev.capture.LobbyCapture}, and
 * not parsed here.
 *
 * <p>All state is static because exactly one tunnel is ever active, and because
 * the capture writer must reach the phase without holding an engine reference —
 * the same reasoning as {@link LobbyKeySniffer#lastKeyHex()}.</p>
 */
public final class SessionTimeline {

    public static final int P_UNKNOWN = 0, P_LOBBY = 1, P_MATCHMAKING = 2,
                            P_LOADING = 3, P_MATCH = 4, P_POSTMATCH = 5;

    // ---- Timings. All are "how long before we BELIEVE something ended", never
    // ---- a gate on any packet: nothing in this class can withhold traffic.

    /**
     * Match UDP silence after which we assume the match is over.
     * {@code GamePacketTaxonomy} measured routine mid-match movement pauses of
     * 7.3 s (standing still / aiming), and it uses 10 s for its own staleness
     * gate. We sit above that so a pause can never be mistaken for a match end,
     * because the CONFIRMED path (Cmd 103 / 407) normally fires first anyway.
     */
    private static final long MATCH_QUIET_MS = 15000L;

    /** Post-match dwell before falling back to LOBBY. */
    private static final long POSTMATCH_MS = 8000L;

    /**
     * Loading grace. If the match endpoint is streaming for this long and no
     * join/battle-start command has been seen, assume the match is running.
     * Needed because the join burst happens in the pre-solver window (brief §3
     * constraint 5), where {@code cmd} does not exist yet.
     */
    private static final long LOADING_TO_MATCH_MS = 20000L;

    /** Lobby uplink idle time after which a MATCHMAKING guess decays. */
    private static final long MM_IDLE_MS = 6000L;

    /**
     * Uplink bytes inside {@link #MM_WINDOW_MS} that suggest a matchmaking
     * request rather than lobby chatter. This is the one heuristic in the class
     * and it only ever changes a LABEL — brief §3 constraint 4 forbids gating
     * traffic on a byte window, and nothing here gates anything.
     */
    private static final int MM_BURST_BYTES = 1024;
    private static final long MM_WINDOW_MS = 2500L;

    /** Bound on the queued lifecycle events, so a never-drained queue cannot grow. */
    private static final int MAX_EVENTS = 512;

    // ---- Phase state ----

    private static volatile int phase = P_UNKNOWN;
    private static volatile long phaseSinceMs;
    private static volatile int matchSeq;

    /** True once the phase we are in was reached by a CONFIRMED signal. */
    private static volatile boolean phaseConfirmed;

    // ---- Match identity, as announced by tcp.MatchmakingSussNtf ----

    private static volatile int gameMode = -1;      // EMatch.GameMode / EGAMEMODE_*
    private static volatile int matchMode = -1;     // EMatch.MatchMode / EMATCHMODE_*
    private static volatile int groupMode = -1;     // EMatch.GroupMode
    private static volatile int mapId = -1;
    private static volatile int difficulty = -1;
    private static volatile boolean reconnection;
    private static volatile String serverAddr;      // "ip:port" as announced
    private static volatile long matchStartMs;
    private static volatile int round;

    // ---- Observed transport state ----

    private static volatile int matchIp;
    private static volatile int matchPort;
    /** Last inbound world-state packet: the liveness signal for the match. */
    private static volatile long lastMatchPktMs;
    /** Last UDP packet of any kind, for the LOADING fallback and telemetry. */
    private static volatile long lastUdpAnyMs;
    private static volatile long lastLobbyDownMs;
    private static volatile long lastLobbyUpMs;
    private static volatile long lobbyUpWindowStartMs;
    private static volatile int lobbyUpWindowBytes;
    private static volatile String keyHex;
    private static volatile int keyRotations;

    private static final java.util.concurrent.atomic.AtomicLong tcpUpBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong tcpDownBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong assignments =
            new java.util.concurrent.atomic.AtomicLong();

    /** Drained by the main capture writer via {@link #drainEvents()} (brief §4.3). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> events =
            new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private static final java.util.concurrent.atomic.AtomicInteger eventCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A second, independent copy of the same events, drained by
     * {@code dev.capture.LobbyCapture} for its own file. Two queues rather than
     * one because {@link #drainEvents()} is destructive by contract, and two
     * consumers sharing one destructive drain would each get half the log.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> mirror =
            new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private static final java.util.concurrent.atomic.AtomicInteger mirrorCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private SessionTimeline() {
    }

    // ------------------------------------------------------------------
    // Seam: brief §4.3
    // ------------------------------------------------------------------

    /**
     * Feeds one lobby TCP segment, either direction. Never throws.
     *
     * <p>Inbound (server&rarr;client) is scanned for the
     * {@code tcp.MatchmakingSussNtf} signature under a private lock; that call
     * arrives on a per-connection relay thread, never on the tun read loop.
     * Outbound only bumps two atomics — no parse, no lock — both because the tun
     * read loop is latency-critical (brief §3 constraint 2) and because the
     * uplink is AES ciphertext (see the class javadoc), so there is nothing to
     * parse.</p>
     *
     * @param buf      source buffer; not retained
     * @param off      offset of the segment bytes
     * @param len      segment length
     * @param outbound true for client&rarr;server
     * @param peerIp   remote IPv4 as a big-endian int
     * @param port     remote port
     */
    public static void observeTcp(byte[] buf, int off, int len,
                                  boolean outbound, int peerIp, int port) {
        try {
            if (buf == null || len <= 0 || off < 0 || off + len > buf.length) return;
            long now = System.currentTimeMillis();
            if (outbound) {
                tcpUpBytes.addAndGet(len);
                lastLobbyUpMs = now;
                noteUplinkBytes(len, now);
            } else {
                tcpDownBytes.addAndGet(len);
                lastLobbyDownMs = now;
                scanDownlink(buf, off, len, peerIp, port, now);
            }
            if (phase == P_UNKNOWN) {
                enter(P_LOBBY, now, false, "lobby TCP traffic seen");
            }
            tick(now);
        } catch (Throwable t) {
            // Observer. A parse slip must never reach the packet path.
        }
    }

    /**
     * Feeds one parsed UDP packet. Never throws.
     *
     * <p>A non-null {@link UdpPacket} means {@code PacketPipeline} had a solved
     * header layout when it parsed — {@code observeAndParse} returns null in the
     * pre-solver window — so {@link UdpPacket#cmd()} is meaningful here. We still
     * require {@link UdpPacket#isCrcValid()} before acting on a command id, since
     * a stale layout after a key rotation shows up first as a CRC failure run.</p>
     */
    public static void observeUdp(UdpPacket p, boolean outbound) {
        try {
            if (p == null) return;
            long now = System.currentTimeMillis();
            lastUdpAnyMs = now;
            if (p.isCrcValid()) {
                int cmd = p.cmd();
                // "The match is alive" is keyed on the identity of the world-state
                // stream, NOT on "a UDP packet arrived". At match end the server
                // does not go silent — GamePacketTaxonomy recorded the client
                // seamlessly re-binding the same endpoint for the lobby/squad
                // session — so packet arrival alone would never detect the end.
                // 2001/2012 and friends only flow while a match is running.
                if (!outbound && Cmd.isWorldStateStream(cmd)) lastMatchPktMs = now;
                applyUdpCmd(cmd, outbound, now);
            }
            tick(now);
        } catch (Throwable t) {
            // Observer.
        }
    }

    /** Current phase id. Cheap; also advances the time-driven transitions. */
    public static int phaseId() {
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable t) {
            // Observer.
        }
        return phase;
    }

    /** Current phase as a short uppercase token. */
    public static String phaseName() {
        return phaseName(phaseId());
    }

    /** Phase token for an arbitrary id (for the capture writer's column). */
    public static String phaseName(int id) {
        switch (id) {
            case P_LOBBY: return "LOBBY";
            case P_MATCHMAKING: return "MATCHMAKING";
            case P_LOADING: return "LOADING";
            case P_MATCH: return "MATCH";
            case P_POSTMATCH: return "POSTMATCH";
            default: return "UNKNOWN";
        }
    }

    /** Increments once per match joined; 0 before the first match. */
    public static int matchSeq() {
        return matchSeq;
    }

    /** One line for the dev panel, e.g. {@code MATCH mode=CS/CSRANKING srv=1.2.3.4:9001 round=3 m#2 t+184s}. */
    public static String describe() {
        try {
            long now = System.currentTimeMillis();
            tick(now);
            StringBuilder sb = new StringBuilder(96);
            sb.append(phaseName(phase));
            if (!phaseConfirmed) sb.append('?');
            sb.append(" mode=").append(modeLabel());
            if (mapId >= 0) sb.append(" map=").append(mapId);
            String srv = serverAddr;
            if (srv == null && matchIp != 0) srv = ipv4(matchIp) + ":" + matchPort;
            if (srv != null) sb.append(" srv=").append(srv);
            if (round > 0) sb.append(" round=").append(round);
            sb.append(" m#").append(matchSeq);
            long t0 = matchStartMs;
            if (t0 != 0L) sb.append(" t+").append((now - t0) / 1000L).append('s');
            return sb.toString();
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }

    /**
     * Lifecycle events since the last drain, oldest first. Each is a bare line
     * {@code "<tsMs> <EVENT> k=v k=v"}; the capture writer prefixes {@code '#'}
     * per brief §4.4. Draining is destructive and safe from any thread.
     */
    public static java.util.List<String> drainEvents() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        try {
            tick(System.currentTimeMillis());
            String s;
            while ((s = events.poll()) != null) {
                eventCount.decrementAndGet();
                out.add(s);
            }
        } catch (Throwable t) {
            // Observer.
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Extra read-only accessors (dev panel, capture header, LobbyCapture)
    // ------------------------------------------------------------------

    /** Current round number in round-based modes; 0 otherwise. */
    public static int round() {
        return round;
    }

    /** {@code GAMEMODE/MATCHMODE} label, e.g. {@code CS/CSRANKING}, or {@code ?}. */
    public static String modeLabel() {
        int g = gameMode, m = matchMode;
        if (g < 0 && m < 0) return "?";
        return gameModeName(g) + "/" + matchModeName(m);
    }

    /** Comment lines for the capture file header. */
    public static String[] headerLines() {
        return new String[]{
                "timeline_phase=" + phaseName(phase) + (phaseConfirmed ? "" : " (inferred)"),
                "timeline_match=" + matchSeq + " mode=" + modeLabel()
                        + " game_mode=" + gameMode + " match_mode=" + matchMode
                        + " group_mode=" + groupMode + " map=" + mapId
                        + " difficulty=" + difficulty,
                "timeline_server=" + (serverAddr == null ? "-" : serverAddr)
                        + " observed=" + (matchIp == 0 ? "-" : (ipv4(matchIp) + ":" + matchPort)),
                "timeline_key=" + (keyHex == null ? "-" : keyHex) + " rotations=" + keyRotations,
                "timeline_tcp_bytes=up:" + tcpUpBytes.get() + " down:" + tcpDownBytes.get()
                        + " assignments:" + assignments.get(),
                "timeline_mode_source=tcp.MatchmakingSussNtf field7=game_mode field8=match_mode"
                        + " (EMatch.GameMode / EMatch.MatchMode)"
        };
    }

    /** Forgets everything. Call on tunnel start/stop. */
    public static void reset() {
        phase = P_UNKNOWN;
        phaseSinceMs = 0L;
        phaseConfirmed = false;
        matchSeq = 0;
        gameMode = -1;
        matchMode = -1;
        groupMode = -1;
        mapId = -1;
        difficulty = -1;
        reconnection = false;
        serverAddr = null;
        matchStartMs = 0L;
        round = 0;
        matchIp = 0;
        matchPort = 0;
        lastMatchPktMs = 0L;
        lastUdpAnyMs = 0L;
        lastLobbyDownMs = 0L;
        lastLobbyUpMs = 0L;
        lobbyUpWindowStartMs = 0L;
        lobbyUpWindowBytes = 0;
        keyHex = null;
        keyRotations = 0;
        tcpUpBytes.set(0L);
        tcpDownBytes.set(0L);
        assignments.set(0L);
        events.clear();
        eventCount.set(0);
        mirror.clear();
        mirrorCount.set(0);
        synchronized (SCAN) {
            carryLen = 0;
            pendingLen = 0;
        }
    }

    // ------------------------------------------------------------------
    // Called by LobbyKeySniffer when a key lands or rotates
    // ------------------------------------------------------------------

    /**
     * Records that a UDP session key arrived on the lobby downlink.
     *
     * <p>Called by {@link LobbyKeySniffer#scan}. Key recovery itself is untouched
     * (brief §3 constraint 6) — this only notes the event and the endpoint it was
     * announced for, so the capture can say when the key rotated.</p>
     *
     * @param hex      the key as lowercase hex
     * @param endpoint the {@code "ip:port"} field-2 value it was adjacent to
     * @param rotated  true when this key differs from the previous one
     */
    public static void onSessionKey(String hex, String endpoint, boolean rotated) {
        try {
            long now = System.currentTimeMillis();
            String prev = keyHex;
            keyHex = hex;
            if (rotated && prev != null) keyRotations++;
            emit(now, rotated ? "KEY_ROTATED" : "KEY_SEEN",
                    "srv=" + (endpoint == null ? "-" : endpoint)
                            + " key=" + (hex == null ? "-" : hex));
        } catch (Throwable t) {
            // Observer.
        }
    }

    // ------------------------------------------------------------------
    // UDP side
    // ------------------------------------------------------------------

    /**
     * Phase transitions driven by <b>command identity</b> — brief §3
     * constraint 4: never a raw byte window, never a roster-sized constant.
     * Every id below is a real name from {@code CmdCatalogue}.
     */
    private static void applyUdpCmd(int cmd, boolean outbound, long now) {
        switch (cmd) {
            // CONFIRMED: the client has joined and the match is running.
            case 100:   // RUDP_JOIN_MATCH
            case 110:   // RUDP_BATTLE_START
            case 130:   // RUDP_JOIN_MATCH_FINISHED
                if (phase != P_MATCH) {
                    if (matchSeq == 0 || phase == P_POSTMATCH || phase == P_LOBBY) beginMatch(now);
                    enter(P_MATCH, now, true, "Cmd " + cmd + " " + CmdCatalogue.nameOr(cmd));
                }
                return;

            // CONFIRMED: the server said the match is over.
            case 103:   // RUDP_MATCH_END
            case 407:   // RUDP_SHOW_MATCH_RESULT
                if (phase == P_MATCH || phase == P_LOADING) {
                    enter(P_POSTMATCH, now, true, "Cmd " + cmd + " " + CmdCatalogue.nameOr(cmd));
                }
                return;

            // CONFIRMED: a round boundary in a round-based mode.
            case 415:   // RUDP_CS_CURROUND_RESULT
            case 416:   // RUDP_CS_CURROUND_SPECTATOR_RESULT
            case 463:   // RUDP_SYNC_CSSO_ROUND_RESULT
            case 719:   // RUDP_S2C_LWSO_CURROUND_RESULT
            case 389:   // RUDP_PVE_ROUND_NOTIFY
                if (!outbound) {
                    round++;
                    emit(now, "ROUND", "round=" + round + " by=" + CmdCatalogue.nameOr(cmd));
                }
                return;

            // CONFIRMED: the match itself is finished being scored.
            case 464:   // RUDP_SYNC_CSSO_BATTLE_RESULT
                if (!outbound && (phase == P_MATCH || phase == P_LOADING)) {
                    enter(P_POSTMATCH, now, true, "Cmd 464 RUDP_SYNC_CSSO_BATTLE_RESULT");
                }
                return;

            default:
                break;
        }

        // INFERRED: any non-transport application traffic on the match endpoint
        // means the match is up, even if we missed the join burst (which usually
        // lands inside the pre-solver window, brief §3 constraint 5).
        if (phase == P_LOADING && !CmdCatalogue.isTransport(cmd)
                && now - phaseSinceMs > 1500L) {
            enter(P_MATCH, now, false, "application traffic on the match endpoint");
        }
    }

    /**
     * Notes the match endpoint. Called by {@link #scanDownlink} from the
     * announcement, so the endpoint is known before the first UDP packet.
     */
    private static void setEndpoint(String addr) {
        serverAddr = addr;
        int colon = addr == null ? -1 : addr.lastIndexOf(':');
        if (colon <= 0) return;
        try {
            matchIp = parseIpv4(addr.substring(0, colon));
            matchPort = Integer.parseInt(addr.substring(colon + 1));
        } catch (Throwable t) {
            matchIp = 0;
            matchPort = 0;
        }
    }

    private static void beginMatch(long now) {
        matchSeq++;
        matchStartMs = now;
        round = 0;
    }

    // ------------------------------------------------------------------
    // Time-driven transitions. All INFERRED, all label-only.
    // ------------------------------------------------------------------

    private static void tick(long now) {
        int p = phase;
        if (p == P_MATCH) {
            long last = lastMatchPktMs;
            if (last != 0L && now - last > MATCH_QUIET_MS) {
                enter(P_POSTMATCH, now, false, "match stream quiet for "
                        + ((now - last) / 1000L) + "s");
            }
            return;
        }
        if (p == P_LOADING) {
            long last = lastMatchPktMs;
            if (last != 0L && now - phaseSinceMs > LOADING_TO_MATCH_MS
                    && now - last < MATCH_QUIET_MS) {
                enter(P_MATCH, now, false, "world-state stream running, no join message seen");
            } else if (last == 0L && now - phaseSinceMs > MATCH_QUIET_MS * 2
                    && now - lastUdpAnyMs > MATCH_QUIET_MS) {
                // Assignment announced but no UDP stream ever appeared.
                enter(P_LOBBY, now, false, "no UDP stream after the match assignment");
            }
            return;
        }
        if (p == P_POSTMATCH) {
            if (now - phaseSinceMs > POSTMATCH_MS) {
                enter(P_LOBBY, now, false, "post-match dwell elapsed");
            }
            return;
        }
        if (p == P_MATCHMAKING) {
            if (now - lastLobbyUpMs > MM_IDLE_MS) {
                enter(P_LOBBY, now, false, "lobby uplink went idle");
            }
        }
    }

    /**
     * The one traffic-shape heuristic: a burst of lobby uplink while no match is
     * live looks like a matchmaking request. We cannot read the request itself
     * because it is AES-encrypted, so this transition is always INFERRED and is
     * never allowed to overwrite a CONFIRMED phase.
     */
    private static void noteUplinkBytes(int len, long now) {
        if (now - lobbyUpWindowStartMs > MM_WINDOW_MS) {
            lobbyUpWindowStartMs = now;
            lobbyUpWindowBytes = 0;
        }
        lobbyUpWindowBytes += len;
        if (phase == P_LOBBY && lobbyUpWindowBytes >= MM_BURST_BYTES) {
            enter(P_MATCHMAKING, now, false,
                    "lobby uplink burst " + lobbyUpWindowBytes + " B / "
                            + MM_WINDOW_MS + " ms (request body is encrypted)");
        }
    }

    private static void enter(int next, long now, boolean confirmed, String why) {
        int prev = phase;
        if (prev == next) {
            if (confirmed) phaseConfirmed = true;
            return;
        }
        phase = next;
        phaseSinceMs = now;
        phaseConfirmed = confirmed;
        emit(now, "PHASE", phaseName(prev) + "->" + phaseName(next)
                + " m#" + matchSeq
                + " conf=" + (confirmed ? "CONFIRMED" : "INFERRED")
                + " why=\"" + why + "\"");
    }

    private static void emit(long tsMs, String name, String detail) {
        String line = tsMs + " " + name + " " + detail;
        // Drop the oldest rather than grow without bound; a queue that is never
        // drained (capture off) must not turn into a leak.
        if (eventCount.get() >= MAX_EVENTS && events.poll() != null) {
            eventCount.decrementAndGet();
        }
        events.add(line);
        eventCount.incrementAndGet();
        if (mirrorCount.get() >= MAX_EVENTS && mirror.poll() != null) {
            mirrorCount.decrementAndGet();
        }
        mirror.add(line);
        mirrorCount.incrementAndGet();
    }

    /**
     * Second, independent drain used by {@code dev.capture.LobbyCapture} for its
     * own file. Does <b>not</b> consume {@link #drainEvents()}.
     */
    public static java.util.List<String> drainMirrorEvents() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        try {
            String s;
            while ((s = mirror.poll()) != null) {
                mirrorCount.decrementAndGet();
                out.add(s);
            }
        } catch (Throwable t) {
            // Observer.
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Lobby downlink: find tcp.MatchmakingSussNtf and read the mode out of it
    // ------------------------------------------------------------------

    /** protobuf tag for field 2, length-delimited: {@code (2 << 3) | 2}. */
    private static final int TAG_SERVER_ADDR = 0x12;
    /** protobuf tag for field 3, length-delimited: {@code (3 << 3) | 2}. */
    private static final int TAG_SECRET = 0x1A;
    /** {@code secret} is always 32 ASCII hex characters. */
    private static final int SECRET_LEN = 32;
    /** Longest {@code ip:port} we accept, e.g. "255.255.255.255:65535". */
    private static final int MAX_ADDR_LEN = 24;

    /** Guards the reassembly scratch below; only the lobby relay threads take it. */
    private static final Object SCAN = new Object();
    /** Tail carried between reads so a signature split across reads is still found. */
    private static final byte[] carry = new byte[96];
    private static int carryLen;
    /**
     * Bytes that followed a located {@code secret} but ran out before the mode
     * fields. The walk resumes on the next downlink chunk. {@code field 7}
     * normally lands within ~80 bytes of the key, but the frame can be tens of
     * kilobytes and straddles socket reads, so resuming matters.
     */
    private static final byte[] pending = new byte[1024];
    private static int pendingLen;

    private static void scanDownlink(byte[] buf, int off, int len,
                                     int peerIp, int port, long now) {
        synchronized (SCAN) {
            // 1. Resume a mode walk that ran out of bytes last time.
            if (pendingLen > 0) {
                int take = Math.min(len, pending.length - pendingLen);
                System.arraycopy(buf, off, pending, pendingLen, take);
                int total = pendingLen + take;
                int consumed = walkModeFields(pending, 0, total, now);
                if (consumed >= 0 || total >= pending.length) {
                    pendingLen = 0;       // finished, or gave up on a full buffer
                } else {
                    pendingLen = total;   // still waiting for more
                }
            }

            // 2. Scan carry+head so a signature across the read boundary is found.
            if (carryLen > 0) {
                int headLen = Math.min(len, carry.length);
                byte[] joined = new byte[carryLen + headLen];
                System.arraycopy(carry, 0, joined, 0, carryLen);
                System.arraycopy(buf, off, joined, carryLen, headLen);
                findAssignment(joined, 0, joined.length, peerIp, port, now);
            }
            findAssignment(buf, off, len, peerIp, port, now);

            int keep = Math.min(len, carry.length);
            System.arraycopy(buf, off + len - keep, carry, 0, keep);
            carryLen = keep;
        }
    }

    /**
     * Looks for {@code field2(ip:port)} immediately followed by
     * {@code field3(32 hex)} — the {@code tcp.MatchmakingSussNtf} signature.
     *
     * <p>Deliberately NOT "find a 32-character hex string": the lobby stream is
     * full of those (CDN asset names). {@link LobbyKeySniffer} measured 13 false
     * positives and one true key with the naive scan; the preceding {@code ip:port}
     * field is what makes the match certain.</p>
     */
    private static void findAssignment(byte[] b, int off, int len,
                                       int peerIp, int port, long now) {
        int end = off + len;
        for (int i = off; i + 4 < end; i++) {
            if ((b[i] & 0xff) != TAG_SERVER_ADDR) continue;
            int addrLen = b[i + 1] & 0xff;
            if (addrLen < 9 || addrLen > MAX_ADDR_LEN) continue;
            int afterAddr = i + 2 + addrLen;
            if (afterAddr + 2 + SECRET_LEN > end) continue;
            if (!isEndpoint(b, i + 2, addrLen)) continue;
            if ((b[afterAddr] & 0xff) != TAG_SECRET) continue;
            if ((b[afterAddr + 1] & 0xff) != SECRET_LEN) continue;
            int hexAt = afterAddr + 2;
            if (!LobbyKeySniffer.isLowerHex(b, hexAt, SECRET_LEN)) continue;

            String addr = new String(b, i + 2, addrLen,
                    java.nio.charset.StandardCharsets.US_ASCII);
            boolean isNew = onAssignment(addr, peerIp, port, now);

            // Walk forward for map_id / game_mode / match_mode / group_mode.
            // Skipped when this is the same signature seen twice in one call
            // (the carry-join window overlaps the raw window by design).
            int after = hexAt + SECRET_LEN;
            if (isNew || gameMode < 0) {
                if (walkModeFields(b, after, end, now) < 0) {
                    int spill = Math.min(end - after, pending.length);
                    System.arraycopy(b, after, pending, 0, spill);
                    pendingLen = spill;
                } else {
                    pendingLen = 0;
                }
            }
            i = after - 1;
        }
    }

    /**
     * A new match has been assigned to us. This is the CONFIRMED entry to
     * LOADING.
     *
     * @return true when this announcement is for a match we had not already
     *         recorded (the same signature can be seen twice in one call because
     *         the carry-join window overlaps the raw window by design)
     */
    private static boolean onAssignment(String addr, int peerIp, int port, long now) {
        String prev = serverAddr;
        boolean isNew = (prev == null) || !prev.equals(addr) || phase == P_LOBBY
                || phase == P_MATCHMAKING || phase == P_POSTMATCH || phase == P_UNKNOWN;
        setEndpoint(addr);
        if (!isNew) return false;
        assignments.incrementAndGet();
        // Clear the previous match's identity: the fields below are re-read from
        // this message, and a stale mode is worse than an unknown one.
        gameMode = -1;
        matchMode = -1;
        groupMode = -1;
        mapId = -1;
        difficulty = -1;
        reconnection = false;
        beginMatch(now);
        emit(now, "MATCH_ASSIGNED", "m#" + matchSeq + " srv=" + addr
                + " via=" + ipv4(peerIp) + ":" + port
                + " src=tcp.MatchmakingSussNtf");
        enter(P_LOADING, now, true, "tcp.MatchmakingSussNtf announced " + addr);
        return true;
    }

    /**
     * Walks the protobuf field stream that follows {@code secret}, reading the
     * fields we care about. Protobuf is self-describing, so this needs no fixed
     * offsets and tolerates the server omitting default-valued fields.
     *
     * @return the offset just past the last field read, or {@code -1} when the
     *         buffer ran out before {@code match_mode} — the caller then stashes
     *         the tail and resumes on the next chunk.
     */
    private static int walkModeFields(byte[] b, int off, int end, long now) {
        int p = off;
        boolean sawGame = false, sawMatch = false;
        int localMap = -1, localGame = -1, localMatch = -1, localGroup = -1, localDiff = -1;
        boolean localReconn = false;
        while (p < end) {
            long tag = readVarint(b, p, end);
            if (tag == BAD_VARINT) return -1;
            p += varintLen(b, p, end);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            // Fields 1..3 are behind us; a low or absurd field number means we
            // have walked out of this message into unrelated bytes.
            if (field <= 3 || field > 60) break;
            if (wire == 0) {
                long v = readVarint(b, p, end);
                if (v == BAD_VARINT) return -1;
                p += varintLen(b, p, end);
                switch (field) {
                    case 6:  localMap = (int) v; break;
                    case 7:  localGame = (int) v; sawGame = true; break;
                    case 8:  localMatch = (int) v; sawMatch = true; break;
                    case 12: localDiff = (int) v; break;
                    case 21: localGroup = (int) v; break;
                    case 28: localReconn = v != 0L; break;
                    default: break;
                }
            } else if (wire == 2) {
                long n = readVarint(b, p, end);
                if (n == BAD_VARINT) return -1;
                p += varintLen(b, p, end);
                if (n < 0 || p + n > end) return -1;
                p += (int) n;
            } else if (wire == 5) {
                if (p + 4 > end) return -1;
                p += 4;
            } else if (wire == 1) {
                if (p + 8 > end) return -1;
                p += 8;
            } else {
                break;      // groups: not used by this schema
            }
            if (field >= 29) break;     // everything we want is at or before 28
        }

        if (localMap >= 0) mapId = localMap;
        if (localDiff >= 0) difficulty = localDiff;
        if (localGroup >= 0) groupMode = localGroup;
        if (localReconn) reconnection = true;
        if (sawGame) gameMode = localGame;
        if (sawMatch) matchMode = localMatch;
        if (sawGame || sawMatch) {
            emit(now, "MATCH_MODE", "m#" + matchSeq
                    + " game_mode=" + gameMode + "(" + gameModeName(gameMode) + ")"
                    + " match_mode=" + matchMode + "(" + matchModeName(matchMode) + ")"
                    + " group_mode=" + groupMode + "(" + groupModeName(groupMode) + ")"
                    + " map=" + mapId + " difficulty=" + difficulty
                    + (reconnection ? " reconnect=1" : ""));
            return p;
        }
        // Ran out before game_mode/match_mode: tell the caller to stash and resume.
        return -1;
    }

    private static final long BAD_VARINT = Long.MIN_VALUE;

    private static long readVarint(byte[] b, int off, int end) {
        long v = 0L;
        int shift = 0;
        for (int i = off; i < end && shift <= 63; i++) {
            int c = b[i] & 0xff;
            v |= ((long) (c & 0x7f)) << shift;
            if ((c & 0x80) == 0) return v;
            shift += 7;
        }
        return BAD_VARINT;
    }

    private static int varintLen(byte[] b, int off, int end) {
        int n = 0;
        for (int i = off; i < end && n < 10; i++) {
            n++;
            if ((b[i] & 0x80) == 0) break;
        }
        return n;
    }

    /** True for an ASCII {@code a.b.c.d:port}. */
    private static boolean isEndpoint(byte[] b, int off, int len) {
        int dots = 0, colons = 0, digits = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c >= '0' && c <= '9') digits++;
            else if (c == '.') dots++;
            else if (c == ':') colons++;
            else return false;
        }
        return dots == 3 && colons == 1 && digits >= 5;
    }

    private static int parseIpv4(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) return 0;
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int o = Integer.parseInt(parts[i]);
            if (o < 0 || o > 255) return 0;
            v = (v << 8) | o;
        }
        return v;
    }

    /** Big-endian int to dotted quad. */
    public static String ipv4(int v) {
        return ((v >> 24) & 0xff) + "." + ((v >> 16) & 0xff) + "."
                + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    // ------------------------------------------------------------------
    // Mode names
    // ------------------------------------------------------------------

    /**
     * {@code EMatch.GameMode} / {@code message.NIKKALKPIBO} ({@code EGAMEMODE_*}).
     * The two enums are separate types in separate namespaces — one on the lobby
     * protobuf, one on the RUDP wire — and they agree on every value they share,
     * which is what licenses using one table for both.
     */
    public static String gameModeName(int v) {
        switch (v) {
            case 0: return "NONE";
            case 1: return "BATTLE_ROYALE";
            case 2: return "SPEED_ROYALE";
            case 3: return "FATE_ROYALE";
            case 4: return "SPEED_ROYALE_ZOMBIE";
            case 5: return "FATE_XMAS";
            case 7: return "BATTLE_ROYALE_ZOMBIE";
            case 9: return "JANKENPON";
            case 11: return "WINNER_TAKES_ALL";
            case 12: return "PURGE";
            case 13: return "REBORN";
            case 14: return "HOOK_BRICK";
            case 15: return "CS";                 // Clash Squad
            case 16: return "BIG_HEAD";
            case 17: return "SPRAY_AND_PRAY";
            case 18: return "EXPLOSIVE_JUMP";
            case 19: return "NIGHT_HUNTER";
            case 20: return "SHARPSHOOTER";
            case 21: return "DGG";
            case 22: return "THROWING_KNIFE";
            case 23: return "TRAINING";
            case 24: return "TDM";
            case 25: return "SNOWMAN";
            case 26: return "ARMS_RACE";
            case 27: return "BOMB";
            case 28: return "TOKEN";
            case 29: return "GIANT";
            case 30: return "REAPER";
            case 31: return "ATTACK_ON_HEAD";
            case 32: return "CONTROL";
            case 33: return "BANKNOTE";
            case 34: return "TEAM_ARMS_RACE";
            case 35: return "AD_SPACE";
            case 36: return "CS_STRIKE_OUT";
            case 37: return "WEREWOLVES";
            case 38: return "PARTY";
            case 39: return "FOUR_SYMBOLS";
            case 40: return "TUTORIAL_BR";
            case 41: return "TUTORIAL_CS";
            case 42: return "GUILD_WAR";
            case 43: return "FIGHT_CLUB";
            case 44: return "RUSHING_PETS";
            case 45: return "UGC_PARTY";
            case 46: return "UGC";
            case 47: return "DMM";
            case 48: return "FATE_ROYALE_COMBINE_WEAPON";
            case 49: return "LWSO_1V1";
            case 50: return "LUDO";
            case 51: return "HAPPY_FRIDAY";
            case 52: return "CS_HARDCORE";
            case 53: return "UGC_COMMON";
            case 54: return "UGC_COMMON_DEV1";
            case 55: return "BATTLE_ROYALE_ZOMBIE_PLUS";
            case 56: return "SINGERS_B";
            case 57: return "FLAG_BATTLE";
            case 58: return "UGC_INFECTION";
            case 59: return "FORCE_TUTORIAL";
            case 60: return "FATE_RANDOM";
            case 61: return "PDM";
            case 62: return "SOCIAL";
            case 63: return "HUNTING_GROUND";
            case 64: return "FOOTBALL";
            case 65: return "PDM_HEADSHOT";
            case 66: return "PDM_SNIPER";
            case 67: return "PDM_SHOTGUN";
            case 68: return "PDM_AKIMBO";
            case 69: return "PDM_PISTOL";
            case 70: return "PDM_COLD_STEEL";
            case 71: return "PDM_EJ";
            case 72: return "HUNTING_GROUND_SNOW";
            case 73: return "LONE_WOLF_MULTI";
            case 74: return "LONE_WOLF_SPECIAL";
            case 77: return "VERTICLE_VIEW";
            case 78: return "CS_DUAL_ACTIVE_SKILL";
            case 79: return "METROPOLIS";
            case 80: return "PVE_INFINITE";
            case 81: return "PVE_UGC_ALTERNATE";
            case 82: return "FPP_CS";
            case 83: return "LOBBY_SOCIAL_AREA";
            case 84: return "COLLECTION_TRIAL";
            case 88: return "HIPPO_CRISIS";
            case 89: return "HIPPO_CRISIS_TUTORIAL";
            case 90: return "PVP_MAX";
            case 99: return "BR_CABIN_WAITING_TEST";
            case 100: return "PVE_MIN";
            case 101: return "PVE_HYAKKI";
            case 102: return "PVE_SVZOMBIE";
            case 103: return "PVE_MAX";
            case 201: return "FATE_ROYALE_FAST";
            case 301: return "OFFLINE_TRAINING";
            default: return v < 0 ? "?" : ("MODE_" + v);
        }
    }

    /** {@code EMatch.MatchMode} / {@code message.DLBMPCCFKKM} ({@code EMATCHMODE_*}). */
    public static String matchModeName(int v) {
        switch (v) {
            case 0: return "NONE";
            case 1: return "CASUAL";
            case 2: return "RANKING";
            case 3: return "ROOM";
            case 4: return "CHAMPIONSHIP";
            case 5: return "TRAINING";
            case 6: return "CSRANKING";
            case 7: return "CUP";
            case 8: return "UGC";
            case 9: return "PERIODIC";
            case 10: return "UGC_PLUS";
            case 11: return "PVE";
            case 12: return "ROOM_MATCHMAKING";
            case 13: return "GUILD_WAR";
            case 14: return "HIPPOCRISIS_RANKING";
            case 15: return "GUILD_WAR_TOURNAMENT";
            case 16: return "CS_PEAK";
            case 17: return "ESPORTS";
            case 999: return "COMMON";
            default: return v < 0 ? "?" : ("MATCHMODE_" + v);
        }
    }

    /** {@code EMatch.GroupMode} / {@code message.CMDBIPLGLGA} ({@code EGROUPMODE_*}). */
    public static String groupModeName(int v) {
        switch (v) {
            case 0: return "SOLO";
            case 1: return "DUO";
            case 2: return "TRIO";
            case 3: return "SQUAD";
            case 4: return "PENTA";
            case 5: return "HEXA";
            default: return v < 0 ? "?" : ("GROUP_" + (v + 1));
        }
    }
}
