package com.nepdon.wire.tunnel;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.filter.FilterDecision;
import com.nepdon.wire.filter.FilterEngine;
import com.nepdon.wire.protocol.GamePacketTaxonomy;
import com.nepdon.wire.filter.PacketClassifier;
import com.nepdon.wire.filter.PacketContext;
import com.nepdon.wire.decode.frame.Direction;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.decode.frame.UdpPacket;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * The tun read loop. Parses each packet into a {@link PacketContext}, classifies
 * it with {@link PacketClassifier}, answers ICMP echo requests, drops non-UDP /
 * non-IPv4 traffic, asks {@link FilterEngine#onOutbound} for a decision, and
 * forwards PASS packets to the {@link UdpSessionManager}.
 */
public class PacketLoop {

    private static final boolean ICMP_ECHO_REPLY_ENABLED = true;

    private final VpnEngine engine;
    private final FilterEngine filterEngine;
    private final UdpSessionManager sessions;
    private final DevLogger logger;

    public PacketLoop(VpnEngine engine, FilterEngine filterEngine, UdpSessionManager sessions, DevLogger logger) {
        this.engine = engine;
        this.filterEngine = filterEngine;
        this.sessions = sessions;
        this.logger = logger;
    }

    public void loop(FileInputStream in, FileOutputStream out) throws Exception {
        byte[] buf = new byte[0xffff];

        while (engine.isRun && engine.isActive) {
            int len = in.read(buf);
            if (len < 0) break;   // tun EOF: exit instead of hot-spinning
            if (len < 20) continue;
            engine.bIn.addAndGet(len);

            int version = (buf[0] >> 4) & 0xf;
            if (version != 4) {
                logger.logProtocolDrop("DROP_NON_IPV4", buf, 0, len, 0, 0, 0,
                        "version=" + version + " ip_len=" + len + " route=" + engine.activeRouteModeName());
                continue;
            }
            int proto = buf[9] & 0xff;
            int ihl = (buf[0] & 0xf) * 4;

            if (len < ihl + 8) continue;

            int dip = PacketBuilder.ri(buf, 16);
            int sp = PacketBuilder.rs(buf, ihl);
            int dp = PacketBuilder.rs(buf, ihl + 2);

            if (proto == 1 && ICMP_ECHO_REPLY_ENABLED
                    && PacketBuilder.replyIcmpEchoRequest(buf, len, ihl, dip, sp, dp, out, logger,
                    engine.activeRouteModeName())) {
                continue;
            }

            if (proto == 6) {
                // TCP: login, lobby matchmaking, match-end handoff, and the
                // in-game voice signaling. Routed into the VPN by the wide
                // Garena routes; relayed through the userspace TCP proxy so
                // these connections stay alive instead of being dropped.
                engine.tcpProxy().handlePacket(buf, len);
                continue;
            }

            if (proto != 17) {
                logger.logProtocolDrop("DROP_NON_UDP", buf, ihl, len - ihl, sp, dp, dip,
                        "proto=" + PacketBuilder.protoName(proto)
                                + " ip_len=" + len
                                + " route=" + engine.activeRouteModeName());
                continue;
            }

            int plen = PacketBuilder.rs(buf, ihl + 4) - 8;
            int off = ihl + 8;

            if (plen < 0 || off + plen > len) continue;
            engine.outUdpSeen.incrementAndGet();
            // The single most diagnostic pair in the system: a healthy match is
            // near 1:1, and out=1 against in=6247 is the documented signature
            // of the game's traffic never entering the tunnel at all.
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.TUNNEL_PKT_OUT);

            int teleType = PacketClassifier.classify(buf, off, plen);

            // Feed the adaptive learners (mask-proof R detection + the
            // ghost movement-stream signature).
            GamePacketTaxonomy.observeOutbound(buf, off, plen, System.currentTimeMillis(), dip, dp);

            // ESP feed: the 24B input stream is the mask oracle; the 50-63B
            // keepalive carries own-player state (23 LEB128 varints).
            if (plen == 24) {
                engine.esp().observeInputStream(buf, off);
            } else if (plen >= 32 && plen <= 96
                    && off + 14 <= buf.length
                    && (buf[off + 9] & 0xff) == 0x80 && (buf[off + 10] & 0xff) == 0x80
                    && (buf[off + 8] & 0x80) != 0) {
                // b8..b11 is NOT a connection token — it is the first varint
                // of the message, an object handle (type<<24)|id, LEB128
                // encoded as (0x80|id) 0x80 0x80 (type<<5).
                //
                // DO NOT TEST THE TYPE BYTE b11. It was pinned to 0x08 ("own
                // team") and that silently made the whole ESP session- and
                // mode-specific: b11 is 0x08 in some matches, 0x10 in others
                // and 0x40 in others again, in Clash Squad as well as in
                // survival. Whenever it differed, ZERO keepalives matched,
                // ownLastUpdateMs never advanced, and the renderer bailed out
                // at its staleness check — so boxes AND radar went completely
                // dark while ghost and freeze (which do not read this) carried
                // on working. Measured recall of the old gate across six
                // sessions: 100/100/100/100/0/0 %.
                //
                // What is actually invariant is the SHAPE — an id below 128
                // zero-padded to a 4-byte varint, so b9 and b10 are 0x80 —
                // plus the body being exactly 23 varints, which
                // observeKeepalive already enforces. That pair scored 100%
                // recall AND 100% precision on all six sessions, both modes,
                // every observed type byte.
                engine.esp().observeKeepalive(buf, off, plen);
            }

            // Exact parse, strictly behind the ESP feeds above: they keep the
            // bytes they have always been given, and this only adds an exact
            // reading of the header on top.
            //
            // Outbound is the direction the solver needs. It is the ONLY one
            // whose Length field equals the bytes that arrived -- inbound
            // Length is the pre-compression size when FLAG_ZIP is set -- so it
            // is the only direction that can drive the vote, and the same
            // identity is the session-change detector: it holds on 100% of
            // outbound packets while the layout is right and collapses the
            // moment a match/room transition rotates the key, dropping the
            // solver back to voting on its own.
            //
            // Until it converges (a few hundred outbound packets), cmd stays
            // unresolved and every filter runs exactly the heuristics it ran
            // before.
            int cmd = PacketContext.CMD_UNRESOLVED;
            PacketPipeline pipeline = engine.pipeline();
            UdpPacket parsed = pipeline.observeAndParse(buf, off, plen, Direction.OUTBOUND);
            if (parsed != null) {
                // ONLY trust the command id when the CRC-7 checks out.
                //
                // observeAndParse returns a packet whether or not the checksum
                // is valid (it feeds the solver's own failure backstop), and
                // foreign UDP lands in the MsgKey range about 8% of the time --
                // so without this gate ctx.cmd can carry a command id read out
                // of somebody else's packet. Filters treat hasCmd() as "this is
                // exactly identified" and act on it: ghost drops on it and
                // teleport rewrites on it. A garbage id that happened to read
                // as 2001 would put an unrelated datagram straight onto the
                // withhold path. A genuine game packet always has a valid CRC,
                // so gating here costs nothing and makes hasCmd() mean what
                // every filter already assumes it means.
                if (parsed.isCrcValid()) {
                    cmd = parsed.cmd();
                    // SCOPE STATE. Cmd 137/138 RUDP_START/STOP_SNIPER are
                    // outbound-only, empty-bodied signals -- the command id IS
                    // the state, so this is the whole scope detector: no screen
                    // capture, no recognition model, two integer compares on
                    // the hot path. Measured 242/242 reliable and perfectly
                    // paired across six captures. CRC-gated for the same reason
                    // everything below is: foreign UDP lands in the MsgKey
                    // range about 8% of the time and would flip the latch from
                    // noise. See protocol/ScopeState.
                    com.nepdon.wire.protocol.ScopeState.observeOutbound(cmd);
                    com.nepdon.wire.protocol.FireState.observeOutbound(cmd);
                    // OWN STATE WHILE MOUNTED.
                    //
                    // Cmd 2001 stops the instant the player mounts anything, so
                    // these are the only first-hand source of our own position
                    // and (on 2069) our camera while riding. Both are
                    // unreliable, therefore plaintext -- no session key needed.
                    // Fed straight from here, the outbound analogue of the
                    // inbound command switch in UdpSessionManager; routing it
                    // through BodyDecoderRegistry instead would risk the
                    // double-feed this file's own comments warn about.
                    if (com.nepdon.wire.decode.body.VehicleSync
                            .isVehicleCarrier(cmd)) {
                        engine.esp().observeVehicleState(cmd, parsed.buffer(),
                                parsed.payloadOffset(), parsed.payloadLength());
                    }
                }
                // Session/lifecycle tracking: which phase we are in, which
                // match, which mode. Cheap and never throws; see
                // net/SessionTimeline. Match liveness is keyed on command
                // identity, not on the endpoint, because a UdpPacket carries
                // no peer address.
                com.nepdon.wire.protocol.SessionTimeline.observeUdp(parsed, true);
            }
            // Tell the capture whether the header layout is resolved, so
            // F_PRESOLVER is exact and re-arms after a key rotation. NOTE:
            // the pipeline's accessor is isReady(), not isSolved().
            engine.capture().setLayoutSolved(pipeline.isReady());

            // AIM BIAS REMOVED -- DISPROVEN, do not reintroduce.
            //
            // Rotating the aim vector we report in Cmd 2001 was the only lever
            // the client had on hit resolution, since nothing outbound carries
            // damage, a body part or a target. It does nothing.
            //
            // Test: bias set to 30 degrees, aim held on the torso, SMG, full
            // match. At 30 deg the server would have to raycast ~2.9 m above a
            // chest at 5 m -- no torso hit is possible if it used our vector.
            // Hits landed completely normally and not one headshot resulted.
            // The A/B harness agreed beforehand: control avg 19 HP (n=81) vs
            // biased avg 17 HP (n=46) -- if anything slightly LOWER. The
            // rewrite itself was verified working (1 291 packets, 0 skipped).
            //
            // Conclusion: the server resolves the body part itself, almost
            // certainly via its own aim assist locking to the target's chest.
            // That also explains why Cmd 104/105 START_FIRE carry no geometry
            // at all -- the client was never trusted with aim.
            //
            // AimBias/AimBiasExperiment are kept as the record of this result.

            PacketContext ctx = new PacketContext(buf, len, ihl, proto, dip, sp, dp, plen, off,
                    teleType, cmd);

            FilterDecision d = filterEngine.onOutbound(ctx);
            // A filter that rewrote the body in place (e.g. the aim hit-zone
            // rewrite of Cmd 106) still forwards it like PASS, but the capture
            // must show it as REWRITE so the edit is visible in the dev dump.
            //
            // RECORD ctx.outLength(), NOT plen. A rewrite that takes the
            // canonical rebuild path CHANGES the length -- teleport's ramp grew
            // packets by 1-3 bytes in every measured burst. Recording the
            // original plen made the dumper copy too few bytes, so 11 of the 20
            // rewritten packets in capture_raw_20260917_054133.txt were
            // truncated mid-varint: they show flags=1 instead of 43, fail
            // LEN_MATCH, fail a CRC check over the dumped bytes, and print the
            // fallback "~ used=NN/NN" note instead of decoded fields.
            //
            // Nothing was wrong on the wire -- the 9 complete dumps all verify
            // CRC-7 -- but the capture is the only evidence we have about a
            // rewrite, and it was corrupting exactly the packets we most need
            // to read. It also cost an investigation cycle: "used=48/48" was
            // read as "the body was padded to fill 48 bytes" when it only ever
            // meant "the dumper printed the 48 bytes it was told to copy".
            final int recLen = ctx.outLength();
            if (d == FilterDecision.PASS && ctx.wasRewritten()) {
                engine.capture().recordOutbound(recLen, dip, sp, dp, teleType,
                        com.nepdon.wire.dev.capture.CaptureEvent.REWRITE, buf, off);
            } else {
                engine.capture().recordOutbound(recLen, dip, sp, dp, teleType,
                        toCaptureDecision(d), buf, off);
            }
            if (d == FilterDecision.PASS) {
                sessions.send(ctx, out);
            } else if (d == FilterDecision.DROP) {
                engine.pDrop.incrementAndGet();
            }
            // HOLD: the filter buffered the packet for replay.
        }
    }

    private static com.nepdon.wire.dev.capture.CaptureRecord.Decision toCaptureDecision(FilterDecision d) {
        if (d == FilterDecision.DROP) return com.nepdon.wire.dev.capture.CaptureRecord.Decision.DROP;
        if (d == FilterDecision.HOLD) return com.nepdon.wire.dev.capture.CaptureRecord.Decision.HOLD;
        return com.nepdon.wire.dev.capture.CaptureRecord.Decision.PASS;
    }
}
