package com.nepdon.wire.dev.capture;

/**
 * Records the lobby TCP channel — the transport that carries login, matchmaking,
 * the match assignment and the match-end handoff.
 *
 * <p>This is the fix for defect <b>D1</b> in {@code docs/DUMPER_REWRITE_BRIEF.md}
 * §2: {@code core/TcpProxy.java} relays every lobby byte and captures none of
 * them, so a capture is blind to everything that happens outside a match.</p>
 *
 * <h3>The wire format, read out of our own image</h3>
 * {@code GCommon.TCPMsgPacket::Unserialize} @{@code 0x082bf11c} does
 * {@code ReadByte()} then {@code ReadInt32()} then
 * {@code IPAddress.NetworkToHostOrder}, so a frame is:
 * <pre>
 *   [Cmd : 1 byte][Length : 4 bytes BIG-ENDIAN][Data : Length bytes]
 * </pre>
 * matching the class constants {@code TCPMESSAGE_CMD_HEADER_SIZE = 1},
 * {@code TCPMESSAGE_LENGTH_HEADER_SIZE = 4}, {@code TCPMESSAGE_HEADER_SIZE = 5}.
 *
 * <p>The {@code Cmd} byte is a frame class, not a message id, and the game tells
 * us the ranges itself:</p>
 * <ul>
 *   <li>{@code TCPMsgPacket::IsInitPackage} @{@code 0x082bf3b4} is
 *       {@code (cmd - 0x65) <u 0x32} &rarr; <b>101..150 = INIT</b>. The client
 *       picks one at random: {@code GetInitPackageCmd} @{@code 0x082bf3d4} is
 *       {@code Random.Range(0x65, 0x96)}.</li>
 *   <li>{@code TCPMsgPacket::IsHeartbeatPackage} @{@code 0x082bf3c4} is
 *       {@code (cmd - 0x97) <u 0x69} &rarr; <b>151..255 = HEARTBEAT</b>.</li>
 *   <li>Everything else is a <b>DATA</b> frame carrying a protobuf body.</li>
 * </ul>
 *
 * <h3>Only one direction is decodable, and that is a finding, not a gap</h3>
 * {@code COW.TCPSession2::Send} @{@code 0x06b52eb0} calls
 * {@code COW.TCPSession2::Encrypt} @{@code 0x06b55d3c} — verified to be its only
 * caller by a full {@code BL} scan of the decrypted image — behind a server
 * config flag, and {@code TCPSession2} has <b>no {@code Decrypt} method at
 * all</b>. So the server&rarr;client direction is plaintext protobuf (which is
 * why {@code net.LobbyKeySniffer} can read the session key straight out of it)
 * and the client&rarr;server direction is AES ciphertext whenever the server
 * turns the flag on. Rather than guess, this class attempts a protobuf decode on
 * every frame and <b>counts the ones that fail</b>, so the undecoded surface is a
 * number in the footer instead of a silence (brief §0).
 *
 * <h3>Observer discipline</h3>
 * Brief §3: the hot path copies bounded bytes into a lock-free bounded queue and
 * returns. Framing, protobuf decoding and file I/O all happen on this class's own
 * writer thread. Nothing here can drop, delay, reorder or mutate a packet; when
 * the queue is full the <b>capture</b> is dropped, never the traffic, and the
 * drop is counted. {@link #onLobbyBytes} never throws.
 *
 * <h3>Reassembly is per connection and per direction</h3>
 * Several lobby connections are open at once and each has its own relay thread,
 * so framing state is keyed by {@code (connKey, direction)}. A stream whose first
 * bytes do not frame is marked {@code RAW} and recorded as raw segments — it is
 * almost certainly not a {@code TCPMsgPacket} stream (voice signalling, CDN),
 * and inventing frames for it would be worse than saying so.
 */
public final class LobbyCapture {

    /** Frame header: {@code [cmd:1][len:4 BE]} — {@code TCPMESSAGE_HEADER_SIZE}. */
    private static final int HEADER_SIZE = 5;

    /**
     * Largest frame body we will accept. One observed lobby frame was 27 294 B;
     * 4 MiB is far above anything real and exists only to stop a mis-framed
     * stream from asking for an enormous buffer.
     */
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    /** Reassembly buffer cap per (connection, direction). */
    private static final int MAX_REASSEMBLY = 512 * 1024;

    /** Queued segments awaiting the writer thread. Bounded: capture drops, traffic never does. */
    private static final int QUEUE_CAP = 4096;

    /** Frames kept in memory for {@link #recentLines()} (dev panel). */
    private static final int RECENT = 64;

    /** Connections tracked before the oldest is evicted. */
    private static final int MAX_CONNS = 64;

    // ---- Frame classes, from TCPMsgPacket's own predicates ----
    private static final int INIT_LO = 0x65, INIT_HI = 0x96;          // 101..150
    private static final int HEARTBEAT_LO = 0x97, HEARTBEAT_HI = 0xFF; // 151..255

    private LobbyCapture() {
    }

    // ------------------------------------------------------------------
    // Hot path
    // ------------------------------------------------------------------

    private static final java.util.concurrent.ArrayBlockingQueue<Seg> queue =
            new java.util.concurrent.ArrayBlockingQueue<Seg>(QUEUE_CAP);

    private static final java.util.concurrent.atomic.AtomicLong segsIn =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong segsDropped =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong bytesUp =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong bytesDown =
            new java.util.concurrent.atomic.AtomicLong();

    private static volatile Thread worker;
    private static volatile boolean running;

    /** One captured TCP segment, copied off the caller's buffer. */
    private static final class Seg {
        int connKey;
        int peerIp;
        int peerPort;
        boolean outbound;
        long tsMs;
        byte[] data;
        boolean close;
    }

    /**
     * Feeds one lobby TCP segment. <b>Call this and nothing else from
     * {@code TcpProxy}</b> — it forwards to
     * {@link com.nepdon.wire.protocol.SessionTimeline#observeTcp} from the writer
     * thread, so the lifecycle stays tracked even when file capture is off and
     * the tun read loop never runs a parser.
     *
     * <p>Never throws. Copies the bytes (the inbound relay reuses its receive
     * buffer, so retaining the caller's array would corrupt the record) and
     * returns immediately.</p>
     *
     * @param connKey  a stable per-connection id; {@code Conn.clientPort} is one
     * @param peerIp   the real server IPv4 as a big-endian int ({@code Conn.dstIp})
     * @param peerPort the real server port ({@code Conn.dstPort})
     * @param outbound true for client&rarr;server
     * @param buf      source buffer; not retained
     * @param off      offset of the segment bytes
     * @param len      segment length
     */
    public static void onLobbyBytes(int connKey, int peerIp, int peerPort,
                                    boolean outbound, byte[] buf, int off, int len) {
        try {
            if (buf == null || len <= 0 || off < 0 || off + len > buf.length) return;
            ensureWorker();
            if (outbound) bytesUp.addAndGet(len); else bytesDown.addAndGet(len);
            Seg s = new Seg();
            s.connKey = connKey;
            s.peerIp = peerIp;
            s.peerPort = peerPort;
            s.outbound = outbound;
            s.tsMs = System.currentTimeMillis();
            s.data = new byte[len];
            System.arraycopy(buf, off, s.data, 0, len);
            if (!queue.offer(s)) {
                // Full: drop the RECORD. offer() never blocks, so the tun loop
                // and the relay threads are never stalled (brief §3 constraint 3).
                segsDropped.incrementAndGet();
            } else {
                segsIn.incrementAndGet();
            }
        } catch (Throwable t) {
            // Observer: a capture failure must never reach the packet path.
        }
    }

    /**
     * Marks a lobby connection closed so its reassembly state is released.
     * Optional — state is also evicted by {@link #MAX_CONNS} — but calling it
     * from {@code TcpProxy.teardown} keeps the file's connection log honest.
     */
    public static void onLobbyClose(int connKey) {
        try {
            ensureWorker();
            Seg s = new Seg();
            s.connKey = connKey;
            s.close = true;
            s.tsMs = System.currentTimeMillis();
            s.data = EMPTY;
            queue.offer(s);
        } catch (Throwable t) {
            // Observer.
        }
    }

    private static final byte[] EMPTY = new byte[0];

    // ------------------------------------------------------------------
    // File lifecycle
    // ------------------------------------------------------------------

    private static volatile java.io.Writer out;
    private static volatile java.io.File file;
    private static volatile long startedMs;

    /**
     * Starts writing frames to {@code lobby_<stamp>.txt} in {@code dir}. Safe to
     * call when already started (no-op) and safe to never call at all — the
     * lifecycle feed to {@code SessionTimeline} runs regardless.
     */
    public static synchronized void start(java.io.File dir) {
        try {
            if (out != null) return;
            if (dir != null && !dir.exists()) {
                // Result deliberately ignored: if the directory cannot be made,
                // the FileWriter below throws and capture stays off.
                dir.mkdirs();
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            java.io.File f = new java.io.File(dir, "lobby_" + stamp + ".txt");
            java.io.Writer w = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
            startedMs = System.currentTimeMillis();
            writeHeader(w);
            file = f;
            out = w;
            ensureWorker();
        } catch (Throwable t) {
            out = null;
            file = null;
        }
    }

    /** Flushes and closes the file, appending the coverage footer. */
    public static synchronized void stop() {
        java.io.Writer w = out;
        out = null;
        if (w == null) return;
        try {
            w.write("# " + coverageLine() + "\n");
            w.flush();
            w.close();
        } catch (Throwable t) {
            // Nothing useful to do at close time.
        }
    }

    private static void writeHeader(java.io.Writer w) throws java.io.IOException {
        w.write("# fakelag lobby TCP capture\n");
        w.write("# started=" + new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date(startedMs)) + "\n");
        w.write("# framing=[cmd:1][len:4 BE][protobuf]  (GCommon.TCPMsgPacket,"
                + " TCPMESSAGE_HEADER_SIZE=5)\n");
        w.write("# cmd_class: 101-150=INIT  151-255=HEARTBEAT  else=DATA"
                + "  (TCPMsgPacket::IsInitPackage / ::IsHeartbeatPackage)\n");
        w.write("# NOTE client->server frames are AES ciphertext when the server"
                + " enables it (COW.TCPSession2::Send -> ::Encrypt);"
                + " server->client is plaintext.\n");
        w.write("# columns: seq ts_ms conn dir peer cmd class phase match len"
                + " decode fields payload_hex\n");
        String[] hdr = com.nepdon.wire.protocol.SessionTimeline.headerLines();
        for (int i = 0; i < hdr.length; i++) w.write("# " + hdr[i] + "\n");
        String key = com.nepdon.wire.protocol.LobbyKeySniffer.lastKeyHex();
        if (key != null) {
            w.write("# session_key=" + key + " endpoint="
                    + com.nepdon.wire.protocol.LobbyKeySniffer.lastEndpoint() + "\n");
        }
    }

    // ------------------------------------------------------------------
    // Writer thread
    // ------------------------------------------------------------------

    private static void ensureWorker() {
        if (running) return;
        synchronized (LobbyCapture.class) {
            if (running) return;
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "lobby-capture");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            worker = t;
            t.start();
        }
    }

    /** Stops the writer thread and clears all state. Call on tunnel shutdown. */
    public static synchronized void shutdown() {
        stop();
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        queue.clear();
        synchronized (conns) {
            conns.clear();
        }
        synchronized (recent) {
            recent.clear();
        }
    }

    private static void pump() {
        while (running) {
            Seg s;
            try {
                s = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (s == null) {
                flushQuietly();
                continue;
            }
            try {
                handle(s);
            } catch (Throwable t) {
                undecoded.incrementAndGet();
            }
        }
    }

    private static void flushQuietly() {
        java.io.Writer w = out;
        if (w == null) return;
        try {
            w.flush();
        } catch (Throwable t) {
            // Ignored: a flush failure must not kill the writer thread.
        }
    }

    // ------------------------------------------------------------------
    // Reassembly
    // ------------------------------------------------------------------

    /** Per (connection, direction) framing state. */
    private static final class Stream {
        byte[] buf = new byte[8192];
        int len;
        /** Null until the first frame decides; TRUE = framed, FALSE = raw. */
        Boolean framed;
        long frames;
        long rawSegments;
    }

    private static final class ConnState {
        final Stream up = new Stream();
        final Stream down = new Stream();
        int peerIp;
        int peerPort;
        long lastMs;
    }

    private static final java.util.LinkedHashMap<Integer, ConnState> conns =
            new java.util.LinkedHashMap<Integer, ConnState>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, ConnState> e) {
                    return size() > MAX_CONNS;
                }
            };

    private static final java.util.concurrent.atomic.AtomicLong seq =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong framesOut =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong decoded =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong undecoded =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.ArrayDeque<String> recent =
            new java.util.ArrayDeque<String>();

    private static void handle(Seg s) {
        ConnState c;
        synchronized (conns) {
            c = conns.get(Integer.valueOf(s.connKey));
            if (s.close) {
                if (c != null) conns.remove(Integer.valueOf(s.connKey));
            } else if (c == null) {
                c = new ConnState();
                c.peerIp = s.peerIp;
                c.peerPort = s.peerPort;
                conns.put(Integer.valueOf(s.connKey), c);
                line("# CONN_OPEN conn=" + s.connKey + " peer="
                        + com.nepdon.wire.protocol.SessionTimeline.ipv4(s.peerIp)
                        + ":" + s.peerPort);
            }
        }
        if (s.close) {
            if (c != null) {
                line("# CONN_CLOSE conn=" + s.connKey + " frames="
                        + (c.up.frames + c.down.frames)
                        + " raw=" + (c.up.rawSegments + c.down.rawSegments));
            }
            return;
        }
        c.lastMs = s.tsMs;

        // Feed the lifecycle tracker first: it must see the bytes even if the
        // framing below decides this stream is not a TCPMsgPacket stream.
        com.nepdon.wire.protocol.SessionTimeline.observeTcp(
                s.data, 0, s.data.length, s.outbound, s.peerIp, s.peerPort);
        drainTimelineEvents();

        Stream st = s.outbound ? c.up : c.down;

        // A stream already known not to be TCPMsgPacket-framed is recorded
        // segment by segment and never buffered: a CDN or voice stream would
        // otherwise accumulate megabytes we have no way to frame.
        if (st.framed != null && !st.framed.booleanValue()) {
            st.rawSegments++;
            emitRaw(c, s, s.data, 0, s.data.length, 0);
            return;
        }

        append(st, s.data);
        if (st.framed == null) {
            if (st.len < HEADER_SIZE) return;         // still undecided
            st.framed = Boolean.valueOf(headerLooksValid(st.buf, 0));
        }
        if (st.framed.booleanValue()) {
            drainFrames(st, c, s);
        } else {
            st.rawSegments++;
            emitRawBuffered(c, s, st);
        }
    }

    private static void append(Stream st, byte[] data) {
        int need = st.len + data.length;
        if (need > MAX_REASSEMBLY) {
            // A stream this far behind is mis-framed; restart rather than grow.
            st.len = 0;
            st.framed = Boolean.FALSE;
            need = data.length;
        }
        if (st.buf.length < need) {
            int cap = st.buf.length;
            while (cap < need) cap <<= 1;
            byte[] nb = new byte[cap];
            System.arraycopy(st.buf, 0, nb, 0, st.len);
            st.buf = nb;
        }
        System.arraycopy(data, 0, st.buf, st.len, data.length);
        st.len += data.length;
    }

    /**
     * A {@code TCPMsgPacket} header is valid when its big-endian length is
     * positive and inside {@link #MAX_FRAME}. The {@code Cmd} byte is not
     * constrained — every value 0..255 is a legal frame class.
     */
    private static boolean headerLooksValid(byte[] b, int off) {
        int length = be32(b, off + 1);
        return length > 0 && length <= MAX_FRAME;
    }

    private static void drainFrames(Stream st, ConnState c, Seg s) {
        int p = 0;
        while (st.len - p >= HEADER_SIZE) {
            int cmd = st.buf[p] & 0xff;
            int length = be32(st.buf, p + 1);
            if (length <= 0 || length > MAX_FRAME) {
                // Lost framing (ciphertext, or a stream we misidentified).
                // Record what is left and stop guessing rather than invent frames.
                if (p > 0) {
                    System.arraycopy(st.buf, p, st.buf, 0, st.len - p);
                    st.len -= p;
                }
                st.framed = Boolean.FALSE;
                st.rawSegments++;
                emitRawBuffered(c, s, st);
                return;
            }
            if (st.len - p < HEADER_SIZE + length) break;     // frame incomplete
            emitFrame(c, s, cmd, st.buf, p + HEADER_SIZE, length);
            st.frames++;
            p += HEADER_SIZE + length;
        }
        if (p > 0) {
            System.arraycopy(st.buf, p, st.buf, 0, st.len - p);
            st.len -= p;
        }
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static String cmdClass(int cmd) {
        if (cmd >= INIT_LO && cmd <= INIT_HI) return "INIT";
        if (cmd >= HEARTBEAT_LO && cmd <= HEARTBEAT_HI) return "HEARTBEAT";
        return "DATA";
    }

    // ------------------------------------------------------------------
    // Record emission
    // ------------------------------------------------------------------

    private static void emitFrame(ConnState c, Seg s, int cmd,
                                  byte[] body, int off, int len) {
        framesOut.incrementAndGet();
        String fields = null;
        if (cmdClass(cmd).equals("DATA")) {
            fields = dumpProtobuf(body, off, len);
        }
        boolean ok = fields != null;
        if (ok) decoded.incrementAndGet(); else undecoded.incrementAndGet();

        StringBuilder sb = new StringBuilder(128 + len * 2);
        sb.append(seq.getAndIncrement()).append(' ')
          .append(s.tsMs).append(' ')
          .append(s.connKey).append(' ')
          .append(s.outbound ? "OUT" : "IN").append(' ')
          .append(com.nepdon.wire.protocol.SessionTimeline.ipv4(c.peerIp))
          .append(':').append(c.peerPort).append(' ')
          .append(cmd).append(' ')
          .append(cmdClass(cmd)).append(' ')
          .append(com.nepdon.wire.protocol.SessionTimeline.phaseName()).append(' ')
          .append(com.nepdon.wire.protocol.SessionTimeline.matchSeq()).append(' ')
          .append(len).append(' ')
          .append(ok ? "PB" : (s.outbound ? "ENC?" : "OPAQUE")).append(' ')
          .append(ok ? fields : "-").append(' ');
        hex(sb, body, off, len);
        line(sb.toString());
    }

    /** Flushes a stream's reassembly buffer as one unframed record and empties it. */
    private static void emitRawBuffered(ConnState c, Seg s, Stream st) {
        int n = st.len;
        st.len = 0;
        if (n > 0) emitRaw(c, s, st.buf, 0, n, 0);
    }

    /**
     * One record for bytes we could not frame.
     *
     * <p>The hex is capped at {@link #MAX_RAW_HEX} and the record says exactly
     * how many bytes were left out, so an unframed stream cannot blow the file
     * up and cannot silently lose data either. Real frames are never capped
     * (defect D3).</p>
     */
    private static void emitRaw(ConnState c, Seg s, byte[] b, int off, int len, int already) {
        undecoded.incrementAndGet();
        int show = Math.min(len, MAX_RAW_HEX);
        StringBuilder sb = new StringBuilder(96 + show * 2);
        sb.append(seq.getAndIncrement()).append(' ')
          .append(s.tsMs).append(' ')
          .append(s.connKey).append(' ')
          .append(s.outbound ? "OUT" : "IN").append(' ')
          .append(com.nepdon.wire.protocol.SessionTimeline.ipv4(c.peerIp))
          .append(':').append(c.peerPort).append(' ')
          .append("-1 RAW ")
          .append(com.nepdon.wire.protocol.SessionTimeline.phaseName()).append(' ')
          .append(com.nepdon.wire.protocol.SessionTimeline.matchSeq()).append(' ')
          .append(len).append(' ')
          .append("UNFRAMED ")
          .append(show == len ? "-" : ("trunc=" + (len - show))).append(' ');
        hex(sb, b, off + already, show);
        line(sb.toString());
    }

    /** Hex cap for unframed bytes only; frames are written in full. */
    private static final int MAX_RAW_HEX = 65536;

    private static void drainTimelineEvents() {
        java.util.List<String> evs =
                com.nepdon.wire.protocol.SessionTimeline.drainMirrorEvents();
        for (int i = 0; i < evs.size(); i++) line("# " + evs.get(i));
    }

    private static void line(String s) {
        java.io.Writer w = out;
        if (w != null) {
            try {
                w.write(s);
                w.write('\n');
            } catch (Throwable t) {
                // A write failure must not kill the writer thread.
            }
        }
        synchronized (recent) {
            recent.addLast(s.length() > 240 ? s.substring(0, 240) : s);
            while (recent.size() > RECENT) recent.removeFirst();
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static void hex(StringBuilder sb, byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xff;
            sb.append(HEX[v >> 4]).append(HEX[v & 0xf]);
        }
    }

    // ------------------------------------------------------------------
    // Generic protobuf dump
    // ------------------------------------------------------------------

    /**
     * Dumps a protobuf body as {@code f<n>:<what>} pairs. Protobuf is
     * self-describing — every field carries its number and wire type — so a
     * message whose class we cannot name is still readable field by field. That
     * is far more useful than a hex blob, and it is what makes the undecoded
     * count below meaningful: a body that does not parse as protobuf really is
     * not protobuf (it is ciphertext, or a stream we misframed).
     *
     * @return the dump, or {@code null} when the body is not valid protobuf
     */
    static String dumpProtobuf(byte[] b, int off, int len) {
        if (len == 0) return "";
        int end = off + len;
        int p = off;
        int fields = 0;
        StringBuilder sb = new StringBuilder(64);
        while (p < end) {
            long tag = varint(b, p, end);
            if (tag == BAD) return null;
            p += varLen(b, p, end);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field <= 0) return null;
            if (fields > 0) sb.append(',');
            sb.append('f').append(field).append(':');
            if (wire == 0) {
                long v = varint(b, p, end);
                if (v == BAD) return null;
                p += varLen(b, p, end);
                sb.append('v').append(v);
            } else if (wire == 1) {
                if (p + 8 > end) return null;
                sb.append("d64");
                p += 8;
            } else if (wire == 2) {
                long n = varint(b, p, end);
                if (n == BAD) return null;
                p += varLen(b, p, end);
                if (n < 0 || p + n > end) return null;
                int ln = (int) n;
                if (isPrintable(b, p, ln)) {
                    sb.append('"').append(ascii(b, p, Math.min(ln, 48)));
                    if (ln > 48) sb.append("...");
                    sb.append('"');
                } else {
                    sb.append('b').append(ln);
                }
                p += ln;
            } else if (wire == 5) {
                if (p + 4 > end) return null;
                sb.append("d32");
                p += 4;
            } else {
                return null;        // groups (3/4) and 6/7 are not used here
            }
            fields++;
            if (fields > 256) break;    // a dump, not a parser
        }
        return sb.toString();
    }

    private static final long BAD = Long.MIN_VALUE;

    private static long varint(byte[] b, int off, int end) {
        long v = 0L;
        int shift = 0;
        for (int i = off; i < end && shift <= 63; i++) {
            int c = b[i] & 0xff;
            v |= ((long) (c & 0x7f)) << shift;
            if ((c & 0x80) == 0) return v;
            shift += 7;
        }
        return BAD;
    }

    private static int varLen(byte[] b, int off, int end) {
        int n = 0;
        for (int i = off; i < end && n < 10; i++) {
            n++;
            if ((b[i] & 0x80) == 0) break;
        }
        return n;
    }

    private static boolean isPrintable(byte[] b, int off, int len) {
        if (len == 0) return false;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int c = b[off + i] & 0xff;
            sb.append(c == '"' ? '\'' : (char) c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** Coverage as {@code n/total}, per brief §3 constraint 8. */
    public static String coverageLine() {
        long f = framesOut.get();
        long d = decoded.get();
        long u = undecoded.get();
        return "lobby frames=" + f + " decoded=" + d + "/" + (d + u)
                + " undecoded=" + u
                + " segs=" + segsIn.get() + " dropped=" + segsDropped.get()
                + " bytes_up=" + bytesUp.get() + " bytes_down=" + bytesDown.get();
    }

    /** One line for the dev panel. */
    public static String statusLine() {
        return (out == null ? "lobby:off " : "lobby:on ") + coverageLine();
    }

}
