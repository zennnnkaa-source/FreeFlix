package com.nepdon.wire.tunnel;

import android.net.VpnService;
import android.os.Process;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.filter.FilterDecision;
import com.nepdon.wire.filter.FilterEngine;
import com.nepdon.wire.filter.PacketContext;
import com.nepdon.wire.protocol.PacketIntegrity;
import com.nepdon.wire.filter.PacketSender;
import com.nepdon.wire.filter.TelePacket;
import com.nepdon.wire.decode.frame.Direction;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.decode.frame.UdpPacket;

import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.mod.ModConfig;
import com.nepdon.wire.net.IpFirewall;

import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Owns the UDP session map (the old {@code SE}/{@code udpMap}/{@code getOrCreate}/
 * {@code RecvRunnable}) and the inbound recv threads. Also implements
 * {@link PacketSender} so the filter engine can replay buffered packets back
 * through the transport.
 */
public class UdpSessionManager implements PacketSender {

    private static final long IDLE = 0xafc8L;
    private static final int SO_TO = 0x7530;

    static final class SE {
        final DatagramSocket s;
        volatile long t;

        SE(DatagramSocket socket) {
            s = socket;
            t = System.currentTimeMillis();
        }

        void touch() {
            t = System.currentTimeMillis();
        }
    }

    private final VpnService vpnService;
    private final DevLogger logger;
    private final ExecutorService threadPool;
    private final ConcurrentHashMap<Long, SE> udpMap = new ConcurrentHashMap<>();

    private VpnEngine engine;
    private FilterEngine filterEngine;

    public UdpSessionManager(VpnService vpnService, DevLogger logger, ExecutorService pool) {
        this.vpnService = vpnService;
        this.logger = logger;
        this.threadPool = pool;
    }

    // Late-bound wiring (VpnEngine is constructed after this object).
    void setEngine(VpnEngine engine) {
        this.engine = engine;
    }

    void setFilterEngine(FilterEngine filterEngine) {
        this.filterEngine = filterEngine;
    }

    ExecutorService getThreadPool() {
        return threadPool;
    }

    /**
     * {@link PacketSender} impl: replay a buffered packet via session or
     * fallback socket.
     *
     * <p>This is the single choke point for every packet the app puts on the
     * wire that did not come straight off the tun: the ghost anchor replay and
     * the jitter/teleport replay both end here. It is therefore where the CRC-7
     * seal belongs — {@link PacketIntegrity#seal} verifies the checksum and
     * rewrites byte 1 only when a mutation upstream has invalidated it, so no
     * edited packet can reach the server with a stale checksum (the leading
     * suspect for the "server sets us back" behaviour in
     * {@code docs/TELEPORT.md}). Packets replayed verbatim, which is all of
     * them today, verify clean and are not written to at all.</p>
     */
    @Override
    public boolean send(TelePacket tp) {
        PacketIntegrity.seal(tp.data);
        // RECORD IT. This path used to reach the wire without touching either
        // the capture or TUNNEL_PKT_OUT, so every packet ghost and teleport
        // replayed was invisible twice over: absent from the dump, and missing
        // from the out/in ratio that docs/WHEN_NOTHING_WORKS.md says to read
        // first. An anchor hold emits roughly one every 500 ms for its whole
        // duration -- about 33 of them across the four holds in
        // capture_raw_20260917_054133.txt -- and not one was observable, which
        // is why "did the replay even run?" could not be answered from a
        // capture that recorded everything else about the same teleport.
        // Recorded as PASS because that is what it is -- the saved bytes go out
        // unmodified. The teleType column already distinguishes a replay from
        // an ordinary forward.
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.TUNNEL_PKT_OUT);
        if (engine != null && engine.capture() != null) {
            engine.capture().recordOutbound(tp.data.length, tp.dip, tp.sp, tp.dp,
                    tp.type, com.nepdon.wire.dev.capture.CaptureEvent.PASS,
                    tp.data, 0);
        }
        long key = ((long) (tp.sp & 0xffff) << 48) | ((long) (tp.dp & 0xffff) << 32) | (tp.dip & 0xFFFFFFFFL);
        SE se = udpMap.get(key);
        if (se != null) {
            try {
                DatagramPacket p = new DatagramPacket(tp.data, 0, tp.data.length,
                        InetAddress.getByAddress(PacketBuilder.tb(tp.dip)), tp.dp);
                se.s.send(p);
                se.touch();
                return true;
            } catch (Exception e) {
                // fall through to protected fallback socket
            }
        }
        DatagramSocket fallback = null;
        try {
            fallback = openRelaySocket(tp.sp);
            vpnService.protect(fallback);
            DatagramPacket p = new DatagramPacket(tp.data, 0, tp.data.length,
                    InetAddress.getByAddress(PacketBuilder.tb(tp.dip)), tp.dp);
            fallback.send(p);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fallback != null) {
                try {
                    fallback.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    /** Forward a PASS outbound packet through its UDP session. */
    void send(PacketContext ctx, FileOutputStream out) {
        if (IpFirewall.isBlocked(ctx.dip, ctx.dp)) {
            FileWatchLog.block("UDP", ctx.dip, ctx.dp, IpFirewall.reason(ctx.dip, ctx.dp));
            return;
        }
        long key = ((long) (ctx.sp & 0xffff) << 48) | ((long) (ctx.dp & 0xffff) << 32) | (ctx.dip & 0xFFFFFFFFL);
        SE se = getOrCreate(key, ctx.sp, ctx.dip, ctx.dp, out);
        if (se == null) return;
        try {
            // outLength(), not plen: a filter may have rewritten the body into
            // a different number of bytes (see PacketContext.outLength). This
            // is a userspace relay, so there is no IP or UDP checksum to fix —
            // the datagram just carries the new length.
            int sendLen = ctx.outLength();
            if (sendLen <= 0 || ctx.off + sendLen > ctx.buf.length) sendLen = ctx.plen;
            DatagramPacket pkt = new DatagramPacket(ctx.buf, ctx.off, sendLen,
                    InetAddress.getByAddress(PacketBuilder.tb(ctx.dip)), ctx.dp);
            se.s.send(pkt);
            se.touch();
            engine.pSent.incrementAndGet();
            engine.bOut.addAndGet(sendLen);
        } catch (Exception e) {
            // A packet that never left the device, with neither pSent nor bOut
            // advancing -- so it is invisible in the capture footer's out/in
            // ratio, which is the first thing WHEN_NOTHING_WORKS tells you to
            // check. Silence here makes the triage tool lie.
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.TUNNEL_SEND_FAILED);
        }
    }

    /**
     * Opens the protected UDP socket that carries one game flow to the server.
     *
     * <p>Binds to the game's own source port, so the server sees the same
     * 5-tuple it would see without the VPN. {@link ModConfig#RELAY_PORT}
     * overrides it with a fixed port. A busy port falls back to an ephemeral
     * one and is logged. SO_REUSEADDR is deliberately not set: on UDP it lets
     * two sockets share a port and split each other's replies.</p>
     */
    private DatagramSocket openRelaySocket(int sp) throws SocketException {
        int want = ModConfig.RELAY_PORT > 0 ? ModConfig.RELAY_PORT : (sp & 0xffff);
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.bind(new InetSocketAddress(want));
            return s;
        } catch (SocketException busy) {
            s.close();
            FileWatchLog.line("RELAY", "port " + want + " busy, using ephemeral");
            DatagramSocket e = new DatagramSocket(null);
            e.bind(null);
            return e;
        }
    }

    private SE getOrCreate(long key, int sp, int dip, int dp, FileOutputStream out) {
        SE existing = udpMap.get(key);
        if (existing != null) return existing;
        try {
            DatagramSocket sock = openRelaySocket(sp);
            vpnService.protect(sock);
            try {
                sock.setSoTimeout(SO_TO);
            } catch (Exception e) {
                // ignore
            }
            SE se = new SE(sock);
            SE prev = udpMap.putIfAbsent(key, se);
            if (prev != null) {
                sock.close();
                return prev;
            }
            // A REJECTED EXECUTE LEAVES A READER-LESS SOCKET IN THE MAP.
            //
            // putIfAbsent has already succeeded by this point, so if the pool
            // refuses the reader (shutdown, or the unbounded pool finally
            // failing to spawn) the entry stays and every later packet for this
            // flow finds it and sends happily — while NOTHING ever reads the
            // replies. Inbound for that 3-tuple is lost for the rest of the
            // match, silently, with outbound still working: the hardest kind of
            // failure to diagnose, because the connection looks alive.
            try {
                threadPool.execute(new RecvRunnable(se, sp, dip, dp, out, key));
            } catch (Throwable t) {
                udpMap.remove(key, se);
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
                com.nepdon.wire.diag.Diag.event(
                        com.nepdon.wire.diag.Slot.TUNNEL_SESSION_NO_READER,
                        "recv thread rejected: " + t.getClass().getSimpleName());
                return null;
            }
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.TUNNEL_SESSION_CREATED);
            return se;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes an inbound {@code Cmd 144 RUDP_FORCE_SYNC_PLAYER_STATE} and, when
     * it names our own entity, hands the forced position to the filter engine.
     *
     * <p>Body layout, decoded from real packets:
     * {@code v0 entityHandle, v1, v2 pos.X, v3 pos.Z, v4 pos.Y, v5..v7 aim,
     * v8..v10 velocity, v11 physState, v12 pose, v13 platformId, ... v18 tick}
     * — the same component order and millimetre units as the outbound report,
     * which is unsurprising: the server is speaking the client's own message
     * vocabulary back at it.</p>
     *
     * <p>Ours is identified by the low byte of the handle, the established
     * {@code entityId == (handle & 0xFF)} rule. A force-sync naming another
     * player is ignored.</p>
     *
     * <p><b>Read-only.</b> This never drops or edits the packet — the client
     * must receive and acknowledge it. If it does not, it latches
     * {@code Player.WaitForForceSync}, stops sending every kind of position
     * report, and re-requests on a 2 s reliable loop until the session's
     * retransmit budget is exhausted.</p>
     */
    private void observeForceSync(byte[] b, int off, int len) {
        if (filterEngine == null) return;
        com.nepdon.wire.esp.EspEngine esp = engine.esp();
        int own = esp == null ? -1 : esp.ownEntityId();
        if (own < 0) return;                      // identity not learned yet

        int p = off;
        int end = off + len;
        long[] v = new long[5];
        for (int i = 0; i < 5; i++) {
            long val = 0;
            int shift = 0;
            boolean ok = false;
            while (p < end && shift <= 63) {
                int c = b[p++] & 0xff;
                val |= ((long) (c & 0x7f)) << shift;
                if ((c & 0x80) == 0) { ok = true; break; }
                shift += 7;
            }
            if (!ok) return;                      // truncated body; say nothing
            v[i] = val;
        }
        if ((int) (v[0] & 0xFF) != own) return;   // someone else's correction

        // v2/v3/v4 are zigzag-encoded signed millimetres.
        filterEngine.onOwnForceSync(zigzag(v[2]), zigzag(v[3]), zigzag(v[4]));
    }

    private static long zigzag(long n) {
        return com.nepdon.wire.util.ZigZag.decode(n);
    }

    private class RecvRunnable implements Runnable {
        private final SE fe;
        private final int sip, dp, sp;
        private final FileOutputStream out;
        private final long key;

        RecvRunnable(SE fe, int origSip, int origDip, int origDp, FileOutputStream out, long key) {
            this.fe = fe;
            this.sip = origDip; // server IP (outbound destination)
            this.dp = origDp;   // server port (outbound destination port)
            this.sp = origSip;  // client port (outbound source port)
            this.out = out;
            this.key = key;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] rbuf = new byte[0x8000];
            while (engine.isRun && engine.isActive) {
                try {
                    DatagramPacket pkt = new DatagramPacket(rbuf, rbuf.length);
                    long t0 = System.nanoTime();
                    fe.s.receive(pkt);
                    engine.rtt = (engine.rtt * 3 + (System.nanoTime() - t0) / 1000000L) / 4;
                    int rlen = pkt.getLength();
                    engine.bIn.addAndGet(rlen);
                    com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.TUNNEL_PKT_IN);
                    fe.touch();

                    // Exact parse. Inbound deliberately does NOT drive the
                    // vote -- inbound Length is the pre-compression size when
                    // FLAG_ZIP is set, so it would poison the tally -- but the
                    // solved layout reads both directions, and the solver's
                    // own tie-break pass wants inbound reliable packets, which
                    // are plentiful here and scarce outbound. Until it
                    // converges the command is unresolved and every filter
                    // below falls back to the shape heuristics it always used.
                    int cmd = PacketContext.CMD_UNRESOLVED;
                    PacketPipeline pipeline = engine.pipeline();
                    UdpPacket parsed = pipeline.observeAndParse(
                            pkt.getData(), pkt.getOffset(), rlen, Direction.INBOUND);
                    if (parsed != null) {
                        // ONLY trust the id when the CRC-7 checks out, exactly
                        // as the outbound loop does. observeAndParse returns a
                        // packet whether or not the checksum validates (it
                        // feeds the solver's own failure backstop), and foreign
                        // UDP lands in the MsgKey range about 8% of the time.
                        // Freeze now DROPS on this id, so a command read out of
                        // an unrelated datagram would withhold real traffic.
                        if (parsed.isCrcValid()) {
                            cmd = parsed.cmd();
                            // Cmd 135: the server's own statement of which
                            // players it considers relevant to us. Free
                            // measurement of our effective range -- see
                            // net/VisibilityMask.
                            com.nepdon.wire.protocol.VisibilityMask.observe(parsed);
                            // Cmd 1900 RUDP_VAR_SYNC_PRI_DATA: the ONLY source
                            // of real player health. It is reliable, therefore
                            // TEA-encrypted, which is the mechanical reason the
                            // ESP never read it and invented health instead --
                            // every other ESP feed is a plaintext channel.
                            // Every reliable channel the ESP needs. All of
                            // these are TEA-encrypted, which is why none of
                            // them was ever read: health (1900), the
                            // owner->entity binding (118 and, as fallback, the
                            // equipment join via 121) and the player names
                            // (101 join, 107/139 kills, 102 quit).
                            // Cmd 144 RUDP_FORCE_SYNC_PLAYER_STATE: the
                            // server's position correction, and the ONLY
                            // inbound message that carries a position for our
                            // own entity. It is reliable + TEA-encrypted, which
                            // is why a plaintext scan never found it and
                            // docs/TELEPORT.md long recorded the correction
                            // carrier as unidentified.
                            //
                            // Teleport needs it: it is a direct "the server
                            // refused your rewrite" signal, ~45 ms before the
                            // client physically obeys it. Never withheld --
                            // freeze already spares reliable messages, and if
                            // this one is ever lost the client latches
                            // Player.WaitForForceSync, stops sending ANY
                            // position report, and re-sends a reliable request
                            // every 2 s until the retransmit budget disconnects
                            // the session.
                            if (cmd == 144 || cmd == 1900 || cmd == 121 || cmd == 118
                                    || cmd == 101 || cmd == 107 || cmd == 139
                                    || cmd == 102) {
                                int bOff = parsed.payloadOffset();
                                int bLen = parsed.payloadLength();
                                byte[] body = parsed.buffer();
                                if (parsed.isEncrypted()) {
                                    int plain = com.nepdon.wire.decode.crypt.BodyCrypt
                                            .decrypt(body, bOff, bLen);
                                    if (plain > 0) {
                                        com.nepdon.wire.diag.Diag.hit(
                                                com.nepdon.wire.diag.Slot.PROTO_DECRYPT_OK);
                                        body = com.nepdon.wire.decode.crypt.BodyCrypt.plain();
                                        bOff = 0;
                                        bLen = plain;
                                    } else {
                                        // WIRE THE ALARM. Invariants.DECRYPT_FAILING has always
                                        // read PROTO_DECRYPT_OK/FAILED and NOTHING has ever
                                        // written them, so the check was reading two permanent
                                        // zeroes. That is why the 2026-09-17/18 server re-key
                                        // (bodies after RUDP_JOIN_MATCH switched to a key that
                                        // is not on the lobby wire) went unnoticed for two days
                                        // while every reliable channel -- health 1900, binds
                                        // 118, equipment 121, damage 106 -- read as empty and
                                        // ESP silently drew nothing.
                                        //
                                        // Separate the two causes: no key yet is the ordinary
                                        // pre-match state, a failure WITH a key is the alarm.
                                        if (com.nepdon.wire.decode.crypt.BodyCrypt.schedule() == null) {
                                            com.nepdon.wire.diag.Diag.hit(
                                                    com.nepdon.wire.diag.Slot.PROTO_NO_KEY);
                                        } else {
                                            com.nepdon.wire.diag.Diag.hit(
                                                    com.nepdon.wire.diag.Slot.PROTO_DECRYPT_FAILED);
                                        }
                                        bLen = 0;
                                    }
                                }
                                if (bLen > 0) {
                                    com.nepdon.wire.esp.EspEngine esp = engine.esp();
                                    switch (cmd) {
                                        case 144:  observeForceSync(body, bOff, bLen); break;
                                        case 1900: esp.observePriSync(body, bOff, bLen); break;
                                        case 121:  esp.observeEquipment(body, bOff, bLen); break;
                                        case 118:  esp.observeBindPri(body, bOff, bLen); break;
                                        case 101:  esp.roster().observeJoin(body, bOff, bLen); break;
                                        case 107:
                                        case 139:  esp.roster().observeKill(body, bOff, bLen); break;
                                        case 102:  esp.roster().observeQuit(body, bOff, bLen); break;
                                        // Mount / dismount, broadcast for EVERY
                                        // player. The authoritative answer to
                                        // "am I in a vehicle" -- which used to
                                        // be guessed from Cmd 2001 going quiet
                                        // for 400 ms -- and the only statement
                                        // anywhere of which player is riding
                                        // which vehicle.
                                        case 125:  esp.observeMount(body, bOff, bLen, true); break;
                                        case 126:  esp.observeMount(body, bOff, bLen, false); break;
                                        // INBOUND 2002 is the batched list of
                                        // every visible vehicle -- a different
                                        // class entirely from the outbound 2002
                                        // that reports our own. It carries a
                                        // mounted enemy at the full tick while
                                        // the entity chain only carries them
                                        // about half the time.
                                        case 2002: esp.observeVehicleList(body, bOff, bLen); break;
                                        default: break;
                                    }
                                }
                            }
                        }
                        // Session/lifecycle tracking. The inbound side is what
                        // actually drives the phase machine: match liveness is
                        // keyed on inbound world-state (Cmd.isWorldStateStream)
                        // rather than the outbound move stream, because the
                        // outbound stream stops entirely while mounted.
                        com.nepdon.wire.protocol.SessionTimeline.observeUdp(parsed, false);
                    }

                    PacketContext ctx = new PacketContext(
                            pkt.getData(), rlen, 0, 17, sip, dp, sp, rlen, pkt.getOffset(),
                            TelePacket.TYPE_NONE, cmd);
                    // Reliability travels with the context so a filter can
                    // refuse to withhold a reliable message. Set from the same
                    // CRC-validated parse that produced `cmd`, so it is exactly
                    // as trustworthy as the command id itself and no more.
                    if (parsed != null && parsed.isCrcValid()) {
                        ctx.setReliable(parsed.isReliable());
                    }
                    // v3: a repeated inbound reliable OrderID means the server is resending
                    // because our ACKs are withheld. Counted before any filter can drop it.
                    if (parsed != null && parsed.isCrcValid() && parsed.isReliable()
                            && com.nepdon.wire.protocol.RetransmitWatch.SHARED
                                    .observe(parsed.orderId(), System.currentTimeMillis())) {
                        com.nepdon.wire.protocol.ServerSignal.noteRetransmitAlarm(
                                System.currentTimeMillis());
                    }
                    FilterDecision d = filterEngine.onInbound(ctx);
                    // Telemetry hook: the only hot-path event — a two-comparison
                    // length gate rejects ~all traffic; survivors cost ~10 reads.
                    if (rlen >= 22 && rlen <= 26
                            && com.nepdon.wire.protocol.GamePacketTaxonomy.isHitConfirmation(
                                    pkt.getData(), pkt.getOffset(), rlen)) {
                        engine.telemetry().event(
                                com.nepdon.wire.dev.capture.TelemetryCollector.EV_HIT_CONFIRM,
                                com.nepdon.wire.protocol.GamePacketTaxonomy.hitDamage(
                                        pkt.getData(), pkt.getOffset(), rlen),
                                com.nepdon.wire.protocol.GamePacketTaxonomy.hitZone(
                                        pkt.getData(), pkt.getOffset(), rlen));
                    }
                    // ESP feed: the 38-170B CHAIN0001 packets carry the
                    // entity-record chains (family + record-count oracle
                    // validated inside observeChainPacket; entity 01 rides
                    // the 38-46B singles). Small IN/11-13 packets validate
                    // the session mask.
                    // The 170 B ceiling was measured on Clash Squad and made
                    // the ESP mode-specific by accident: survival/BR chains
                    // carry many more records and run to ~630 B, so in those
                    // modes the packets never even reached the parser. The
                    // real validation (family, record-count oracle, record
                    // byte-fit) happens inside observeChainPacket; this gate
                    // only needs to be cheap and not exclude whole game modes.
                    // FLOOR WAS 38 AND IT STARVED THE ESP. Measured against
                    // ground truth (Cmd 2012 = UDP_PLAYER_STATE_SYNC_V2,
                    // identified exactly via the recovered header format):
                    // a chain carrying ONE entity is 32-36 B and a chain
                    // carrying TWO is 54-56 B, so the 38 B floor silently
                    // dropped every single-entity update -- 318 of 353 (90%)
                    // in one BR match, and across 40 sessions 2685/3131
                    // (85.8%) with a MEDIAN PER-SESSION LOSS OF 100%.
                    // That is exactly "boxes do not follow": a lone tracked
                    // enemy never updated and the renderer extrapolated off a
                    // stale position until a second entity happened to appear.
                    // observeChainPacket itself only needs plen >= 20 and
                    // validates b8==0, b9 even, the declared record count and
                    // the byte budget, so the floor was pure caller-side loss.
                    if (rlen >= 20 && rlen <= 1024) {
                        engine.esp().observeChainPacket(pkt.getData(), pkt.getOffset(), rlen);
                    } else if (rlen >= 11 && rlen <= 13) {
                        engine.esp().observeSmallInbound(pkt.getData(), pkt.getOffset(), rlen);
                    }
                    // DAMAGE events are 23-27 B, i.e. BELOW the chain window —
                    // they must be offered separately or they are never seen.
                    if (rlen >= 20 && rlen <= 40) {
                        engine.esp().observeDamage(pkt.getData(), pkt.getOffset(), rlen);
                    }
                    // Small handle events: a free teammate label that arrives
                    // without anyone having to shoot (99.7% own-team).
                    if (rlen >= 14 && rlen <= 20) {
                        engine.esp().observeHandleEvent(pkt.getData(), pkt.getOffset(), rlen);
                    }
                    engine.capture().recordInbound(rlen, sip, dp, sp,
                            d == FilterDecision.DROP
                                    ? com.nepdon.wire.dev.capture.CaptureRecord.Decision.DROP
                                    : com.nepdon.wire.dev.capture.CaptureRecord.Decision.PASS,
                            pkt.getData(), pkt.getOffset());
                    if (d == FilterDecision.DROP) {
                        engine.pDrop.incrementAndGet();
                        continue;
                    }

                    byte[] ip = PacketBuilder.buildPkt(pkt, sip, dp, sp, engine.tunIP);
                    synchronized (out) {
                        out.write(ip);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    if (!engine.isRun || !engine.isActive) break;
                    if (System.currentTimeMillis() - fe.t > IDLE) break;
                } catch (RuntimeException e) {
                    // A BUG DOWNSTREAM MUST NOT COST THE SOCKET.
                    //
                    // This block wraps the entire inbound pipeline -- six ESP
                    // decoders, BodyCrypt, the header solver -- and every one
                    // of them can throw on a malformed or unexpected body. The
                    // old `catch (Exception) { break; }` treated that exactly
                    // like a dead socket: it closed the session, so the next
                    // outbound packet re-NATted onto a NEW SOURCE PORT
                    // mid-match. That is the documented kick fingerprint
                    // (ff-ack-starvation-kicks) -- one decoder bug spending a
                    // player's match.
                    //
                    // Drop the packet, keep the session, and say so. A
                    // RuntimeException here is always our defect, never the
                    // network's; genuine transport failure is an IOException
                    // and still breaks below.
                    engine.pDrop.incrementAndGet();
                    com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.TUNNEL_DECODE_THREW,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (logger != null) {
                        logger.logControl("UDP_DECODE_THREW",
                                "cls=" + e.getClass().getSimpleName()
                                        + " msg=" + e.getMessage()
                                        + " -- packet dropped, session kept");
                    }
                } catch (Exception e) {
                    // Real I/O failure: the socket is gone, end the session.
                    //
                    // COUNTED. Invariants raises SESSION_CHURN on more than 5
                    // of these in a window, and SESSION_CHURN is the documented
                    // mid-match KICK FINGERPRINT -- but this slot had no writer
                    // anywhere in the tree, so that alarm could never fire. The
                    // one diagnostic built to recognise a kick was silent.
                    com.nepdon.wire.diag.Diag.hit(
                            com.nepdon.wire.diag.Slot.TUNNEL_SESSION_CLOSED_IO);
                    break;
                }
            }
            udpMap.remove(key, fe);
            try {
                fe.s.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /** Close every session socket and clear the map (tunnel teardown). */
    void closeAll() {
        for (SE se : udpMap.values()) {
            try {
                se.s.close();
            } catch (Exception e) {
                // ignore
            }
        }
        udpMap.clear();
    }
}
