package com.nepdon.wire.tunnel;

import android.net.VpnService;

import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.mod.ModConfig;
import com.nepdon.wire.net.IpFirewall;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Userspace TCP relay (tun2socks-lite). The VPN routes the whole Garena
 * server space into the tunnel, which includes services the game talks to
 * over TCP: login, lobby matchmaking, match-end handoff, and the in-game
 * voice-chat signaling.
 *
 * <p>Reliability model (the part that keeps long-lived lobby connections
 * alive across a whole match):</p>
 * <ul>
 *   <li><b>Client receive window is honored.</b> The relay tracks the
 *       client's advertised window and ACK progression. Remote data that
 *       would overflow the client's window is buffered and flushed as ACKs
 *       open the window — instead of being injected out-of-window and
 *       silently discarded by the client kernel (which permanently stalled
 *       the lobby connection after long matches: "can't exit team").</li>
 *   <li><b>Remote close is translated faithfully.</b> Clean EOF becomes
 *       FIN|ACK; a reset becomes RST (an app-level protocol sees the same
 *       semantics it would see without the VPN).</li>
 *   <li><b>Client payload is never written on the tun loop.</b> A slow or
 *       stalled remote must not block the packet path, so client data is
 *       queued and drained by a per-connection writer thread.</li>
 *   <li><b>Tear-down always notifies.</b> closeAll() sends RSTs so the game
 *       reconnects immediately instead of retransmitting into silence.</li>
 * </ul>
 */
public final class TcpProxy {

    private static final int MSS = 1240;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private static final int FLAG_FIN = 0x01;
    private static final int FLAG_SYN = 0x02;
    private static final int FLAG_RST = 0x04;
    private static final int FLAG_PSH = 0x08;
    private static final int FLAG_ACK = 0x10;

    private final VpnService vpnService;
    private final VpnEngine engine;
    private final ExecutorService pool;
    private final Random rng = new Random();
    private final ConcurrentHashMap<Integer, Conn> conns = new ConcurrentHashMap<>();

    private static final class Conn {
        final int clientPort;
        final int clientIp;
        final int dstIp;
        final int dstPort;
        final Socket remote = new Socket();
        final Object lock = new Object();
        /** Client -> remote payload queued by the tun loop, drained by the writer thread. */
        final ArrayDeque<byte[]> toRemote = new ArrayDeque<>();
        /** Remote -> client payload held while the client's receive window is closed. */
        final ArrayDeque<byte[]> toClient = new ArrayDeque<>();
        volatile boolean closed;
        volatile boolean relayStarted;
        volatile boolean writerStarted;
        volatile OutputStream remoteOut;
        long clientNext;     // next client seq we expect (our ACK value)
        long clientAck;      // last ACK the client sent us (<= ourNext)
        int clientWindow;    // client's advertised receive window (bytes)
        long ourNext;        // our next seq to send
        long ourIsn;         // our initial seq (for SYN-ACK retransmits)

        Conn(int clientPort, int clientIp, int dstIp, int dstPort) {
            this.clientPort = clientPort;
            this.clientIp = clientIp;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
        }
    }

    public TcpProxy(VpnService vpnService, VpnEngine engine, ExecutorService pool) {
        this.vpnService = vpnService;
        this.engine = engine;
        this.pool = pool;
    }

    /** Handles one tun-side TCP IP-packet (called from the tun read loop). */
    public void handlePacket(byte[] pkt, int len) {
        try {
            if (len < 40) return;
            int ihl = (pkt[0] & 0xf) * 4;
            if (len < ihl + 20) return;
            int srcIp = getInt(pkt, 12);
            int dstIp = getInt(pkt, 16);
            int sport = ((pkt[ihl] & 0xff) << 8) | (pkt[ihl + 1] & 0xff);
            int dport = ((pkt[ihl + 2] & 0xff) << 8) | (pkt[ihl + 3] & 0xff);
            long seq = getInt(pkt, ihl + 4) & 0xffffffffL;
            long ack = getInt(pkt, ihl + 8) & 0xffffffffL;
            int dataOff = ((pkt[ihl + 12] >> 4) & 0xf) * 4;
            int flags = pkt[ihl + 13] & 0xff;
            int window = ((pkt[ihl + 14] & 0xff) << 8) | (pkt[ihl + 15] & 0xff);
            int payloadOff = ihl + dataOff;
            int payloadLen = Math.max(0, len - payloadOff);

            if ((flags & FLAG_RST) != 0) {
                teardown(sport);
                return;
            }

            if ((flags & FLAG_SYN) != 0 && (flags & FLAG_ACK) == 0) {
                Conn existing = conns.get(sport);
                if (existing != null) {
                    if (!existing.closed) {
                        sendHeader(existing, FLAG_SYN | FLAG_ACK, existing.ourIsn);
                    }
                    return;
                }
                Conn c = new Conn(sport, srcIp, dstIp, dport);
                c.clientNext = (seq + 1) & 0xffffffffL;
                c.clientAck = c.clientNext;
                c.clientWindow = window;
                c.ourIsn = rng.nextInt() & 0xffffffffL;
                c.ourNext = (c.ourIsn + 1) & 0xffffffffL;

                if (IpFirewall.isBlocked(dstIp, dport)) {
                    String why = IpFirewall.reason(dstIp, dport);
                    FileWatchLog.block("TCP", dstIp, dport, why);
                    // no SYN-ACK, no outbound socket — client sees timeout/retry
                    return;
                }
                FileWatchLog.allow("TCP", dstIp, dport);

                conns.put(sport, c);
                sendSynAck(c);
                connectAsync(c);
                return;
            }

            Conn c = conns.get(sport);
            if (c == null || c.closed) return;
            if ((flags & FLAG_ACK) == 0) return;

            // Track the client's ACK progression and advertised window; an
            // ACK means the client consumed data, so try to flush whatever
            // the closed window made us hold back. A ZERO window is honored
            // as zero (clamping it would re-introduce the out-of-window
            // data loss that stalled lobby connections).
            synchronized (c.lock) {
                if (seqDelta(ack, c.clientAck) > 0) c.clientAck = ack;
                c.clientWindow = window;
            }
            flushToClient(c);

            if (payloadLen > 0) {
                long seqEnd = (seq + payloadLen) & 0xffffffffL;
                // Signed 32-bit distance handles sequence wraparound.
                int diff = (int) (seqEnd - c.clientNext);
                if (diff > 0 && diff <= payloadLen) {
                    // Deliver only the bytes we haven't seen (skip retransmits).
                    int skip = payloadLen - diff;
                    byte[] data = new byte[diff];
                    System.arraycopy(pkt, payloadOff + skip, data, 0, data.length);
                    queueToRemote(c, data);
                    c.clientNext = seqEnd;
                }
                sendHeader(c, FLAG_ACK, c.ourNext);
            }
            if ((flags & FLAG_FIN) != 0 && seq == c.clientNext) {
                c.clientNext = (c.clientNext + 1) & 0xffffffffL;
                sendHeader(c, FLAG_ACK, c.ourNext);
                try {
                    c.remote.shutdownOutput();
                } catch (Exception e) {
                    // not connected yet, or already closed
                }
            }
        } catch (Exception e) {
            // Never let a malformed packet kill the tun loop.
        }
    }

    /** Signed 32-bit sequence-space distance (b - a), wrap-safe. */
    private static int seqDelta(long b, long a) {
        return (int) ((b - a) & 0xffffffffL);
    }

    // ---- Client -> remote path (never blocks the tun loop) ----

    /** Queues client payload for the per-connection writer thread. */
    private void queueToRemote(Conn c, byte[] data) {
        // Lobby capture, client -> server. This direction had never been
        // observed by anything: the key sniffer only ever saw the downlink, so
        // the request side (matchmaking, mode selection) was invisible. Pure
        // observer -- copies bytes, never mutates or delays them.
        com.nepdon.wire.dev.capture.LobbyCapture.onLobbyBytes(
                c.clientPort, c.dstIp, c.dstPort, true, data, 0, data.length);
        synchronized (c.lock) {
            c.toRemote.add(data);
            c.lock.notifyAll();
        }
        startWriter(c);
    }

    /** Drains the client -> remote queue; blocks while waiting for data. */
    private void startWriter(final Conn c) {
        synchronized (c.lock) {
            if (c.writerStarted || c.closed) return;
            c.writerStarted = true;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!c.closed) {
                        byte[] data;
                        OutputStream out;
                        synchronized (c.lock) {
                            while (!c.closed && (c.toRemote.isEmpty() || c.remoteOut == null)) {
                                c.lock.wait(1000);
                            }
                            if (c.closed) return;
                            data = c.toRemote.poll();
                            if (data == null) continue;
                            out = c.remoteOut;
                        }
                        if (out != null) {
                            out.write(data);
                            out.flush();
                        }
                    }
                } catch (Exception e) {
                    FileWatchLog.conn("TCP", "FAIL", c.dstIp, c.dstPort,
                            e.getClass().getSimpleName(),
                            e.getMessage() != null ? e.getMessage() : "");
                    sendRst(c);
                    teardown(c.clientPort);
                }
            }
        });
    }

    // ---- Remote -> client path (window-aware) ----

    /** Opens the protected connection to the real destination (async). */
    private void connectAsync(final Conn c) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    vpnService.protect(c.remote);
                    c.remote.connect(new InetSocketAddress(
                            InetAddress.getByAddress(PacketBuilder.tb(c.dstIp)), c.dstPort),
                            CONNECT_TIMEOUT_MS);
                    c.remote.setTcpNoDelay(true);
                    OutputStream out = c.remote.getOutputStream();
                    synchronized (c.lock) {
                        c.remoteOut = out;
                        c.lock.notifyAll();
                    }
                    startRelay(c);
                } catch (Exception e) {
                    sendRst(c);
                    teardown(c.clientPort);
                }
            }
        });
    }

    /** Pumps remote responses back into the tun as synthesized TCP segments. */
    private void startRelay(final Conn c) {
        synchronized (c.lock) {
            if (c.relayStarted || c.closed) return;
            c.relayStarted = true;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                boolean cleanClose = false;
                try {
                    InputStream in = c.remote.getInputStream();
                    byte[] buf = new byte[MSS];
                    int n;
                    while (!c.closed && engine.isRun) {
                        n = in.read(buf);
                        if (n < 0) break;
                        // The lobby downlink carries the UDP cipher key in the
                        // clear. This is the ONLY way to get it without root,
                        // and these bytes already pass through here.
                        engine.lobbyKey().onServerBytes(buf, 0, n);
                        // The SAME message carries the header layout the solver
                        // would otherwise spend 1.1-2.6 s and ~440 packets
                        // voting on -- the mask is SECRET_KEY[0] and field 42
                        // is the field permutation in ASCII. It necessarily
                        // arrives before the session's first UDP packet, since
                        // field 2 of this message is the match server address.
                        // Seeding here reads the match-start burst from packet
                        // 1 instead of losing ~80% of RUDP_PLAYER_JOIN to an
                        // unsolved layout.
                        int headMask = engine.lobbyKey().headMask();
                        int[] headOrder = engine.lobbyKey().headOrder();
                        if (headMask >= 0 && headOrder != null) {
                            engine.pipeline().seedLayout(headMask, headOrder);
                        }
                        // Lobby capture, server -> client. Same bytes the key
                        // sniffer reads; this is where MatchmakingSussNtf
                        // (match id, server addr, secret, game/match/group
                        // mode) arrives in the clear.
                        com.nepdon.wire.dev.capture.LobbyCapture.onLobbyBytes(
                                c.clientPort, c.dstIp, c.dstPort, false, buf, 0, n);
                        synchronized (c.lock) {
                            c.toClient.add(copyOf(buf, n));
                        }
                        flushToClient(c);
                    }
                    cleanClose = true;   // normal loop exit = graceful EOF
                } catch (Exception e) {
                    // Read failure: remote reset or tunnel down. RST below.
                } finally {
                    if (c.closed || !engine.isRun) return;   // torn down already
                    if (cleanClose) {
                        // Wait for window-held data to drain first. If the
                        // client's window stays shut, RST instead of FIN —
                        // a FIN beyond undelivered data would strand the
                        // client retransmitting into a dead conn.
                        for (int i = 0; i < 100 && queuedToClient(c) > 0 && !c.closed; i++) {
                            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                        }
                        if (queuedToClient(c) > 0) {
                            sendRst(c);
                        } else {
                            sendHeader(c, FLAG_FIN | FLAG_ACK, c.ourNext);
                            c.ourNext = (c.ourNext + 1) & 0xffffffffL;
                        }
                    } else {
                        sendRst(c);
                    }
                    teardown(c.clientPort);
                }
            }
        });
    }

    private static int queuedToClient(Conn c) {
        synchronized (c.lock) {
            return c.toClient.size();
        }
    }

    /**
     * Sends as much window-held data as the client's advertised window
     * allows. Called when new remote data arrives and whenever the client
     * ACKs (opening the window). The sequence number is reserved atomically
     * INSIDE the lock — flushes from the tun loop and the relay thread can
     * race, and reserving outside would emit overlapping sequence numbers.
     */
    private void flushToClient(Conn c) {
        while (true) {
            byte[] data;
            long seq;
            int ack;
            synchronized (c.lock) {
                data = c.toClient.peek();
                if (data == null) return;
                int inFlight = seqDelta(c.ourNext, c.clientAck);
                if (inFlight < 0) inFlight += 0x100000000L;
                if (inFlight + data.length > c.clientWindow) return;   // window closed
                c.toClient.poll();
                seq = c.ourNext;
                c.ourNext = (c.ourNext + data.length) & 0xffffffffL;
                ack = (int) c.clientNext;
            }
            sendSegment(c, seq, ack, data, data.length);
        }
    }

    /** Sends a data segment from the remote side to the client. */
    private void sendSegment(Conn c, long seq, int ack, byte[] data, int len) {
        byte[] pkt = buildTcpIp(c, seq, ack, FLAG_ACK | FLAG_PSH, false, data, len);
        engine.writeToTun(pkt);
    }

    /** Sends a header-only segment (ACK / SYN-ACK retransmit / FIN). */
    private void sendHeader(Conn c, int flags, long seq) {
        engine.writeToTun(buildTcpIp(c, seq, c.clientNext, flags, false, null, 0));
    }

    /** Sends the handshake answer with the MSS option. */
    private void sendSynAck(Conn c) {
        engine.writeToTun(buildTcpIp(c, c.ourIsn, c.clientNext, FLAG_SYN | FLAG_ACK, true, null, 0));
    }

    /** Sends a connection reset. */
    private void sendRst(Conn c) {
        engine.writeToTun(buildTcpIp(c, c.ourNext, c.clientNext, FLAG_RST | FLAG_ACK, false, null, 0));
    }

    /** Tears down one connection by client port. */
    private void teardown(int clientPort) {
        Conn c = conns.remove(clientPort);
        if (c != null) {
            // Release this connection's reassembly state (keyed by clientPort).
            com.nepdon.wire.dev.capture.LobbyCapture.onLobbyClose(clientPort);
            c.closed = true;
            synchronized (c.lock) {
                c.lock.notifyAll();
            }
            try {
                c.remote.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Tears down every connection (tunnel restart / shutdown). Sends RSTs
     * first so the game's TCP stack fails fast and reconnects, instead of
     * retransmitting into silence for minutes.
     */
    public void closeAll() {
        for (Integer port : conns.keySet()) {
            Conn c = conns.get(port);
            if (c != null) sendRst(c);
            teardown(port);
        }
    }

    // ---- Packet building ----

    /**
     * Builds a full IPv4/TCP packet from the "server" back to the client.
     * Source = the original destination (the real server), dest = the client
     * tun IP, with correct IP and TCP checksums.
     */
    private byte[] buildTcpIp(Conn c, long seq, long ack, int flags,
                              boolean withMss, byte[] data, int dataLen) {
        int tcpHdr = withMss ? 24 : 20;
        int total = 20 + tcpHdr + dataLen;
        byte[] pkt = new byte[total];

        // IP header.
        pkt[0] = 0x45;
        pkt[2] = (byte) (total >> 8);
        pkt[3] = (byte) total;
        int id = rng.nextInt(0xffff);
        pkt[4] = (byte) (id >> 8);
        pkt[5] = (byte) id;
        pkt[6] = 0x40;
        pkt[7] = 0x00;
        pkt[8] = 64;
        pkt[9] = 6;
        putInt(pkt, 12, c.dstIp);      // src = the server the client dialed
        putInt(pkt, 16, c.clientIp);   // dst = the client's tun address
        int ipCk = checksum(pkt, 0, 20);
        pkt[10] = (byte) (ipCk >> 8);
        pkt[11] = (byte) ipCk;

        // TCP header.
        int t = 20;
        pkt[t] = (byte) (c.dstPort >> 8);
        pkt[t + 1] = (byte) c.dstPort;
        pkt[t + 2] = (byte) (c.clientPort >> 8);
        pkt[t + 3] = (byte) c.clientPort;
        putInt(pkt, t + 4, (int) seq);
        putInt(pkt, t + 8, (int) ack);
        pkt[t + 12] = (byte) ((tcpHdr / 4) << 4);
        pkt[t + 13] = (byte) flags;
        pkt[t + 14] = (byte) 0xFF;
        pkt[t + 15] = (byte) 0xFF;    // window 65535
        if (withMss) {
            pkt[t + 20] = 2;
            pkt[t + 21] = 4;
            pkt[t + 22] = (byte) (MSS >> 8);
            pkt[t + 23] = (byte) MSS;
        }
        if (dataLen > 0) {
            System.arraycopy(data, 0, pkt, 20 + tcpHdr, dataLen);
        }
        int tcpCk = tcpChecksum(pkt, 20, total - 20, c.dstIp, c.clientIp);
        pkt[t + 16] = (byte) (tcpCk >> 8);
        pkt[t + 17] = (byte) tcpCk;
        return pkt;
    }

    private static byte[] copyOf(byte[] src, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }

    /** Standard ones-complement checksum over a buffer region. */
    private static int checksum(byte[] buf, int off, int len) {
        long sum = 0;
        int i = off;
        int remaining = len;
        while (remaining > 1) {
            sum += ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
            i += 2;
            remaining -= 2;
        }
        if (remaining == 1) {
            sum += (buf[i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum & 0xffff);
    }

    /** TCP checksum over the IPv4 pseudo-header + segment. */
    private static int tcpChecksum(byte[] pkt, int off, int segLen, int srcIp, int dstIp) {
        long sum = 0;
        sum += (srcIp >> 16) & 0xffff;
        sum += srcIp & 0xffff;
        sum += (dstIp >> 16) & 0xffff;
        sum += dstIp & 0xffff;
        sum += 6;
        sum += segLen;
        int i = off;
        int remaining = segLen;
        while (remaining > 1) {
            sum += ((pkt[i] & 0xff) << 8) | (pkt[i + 1] & 0xff);
            i += 2;
            remaining -= 2;
        }
        if (remaining == 1) {
            sum += (pkt[i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum & 0xffff);
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }
}
