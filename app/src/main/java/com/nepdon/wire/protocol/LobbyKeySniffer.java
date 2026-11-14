package com.nepdon.wire.protocol;

/**
 * Recovers the UDP match cipher key from the lobby TCP downlink.
 *
 * <p><b>The server sends us the key in the clear.</b> On match start the lobby
 * service pushes a {@code MatchSuccess} message whose protobuf body carries the
 * UDP endpoint, the 16-byte {@code SECRET_KEY} as ASCII hex, and the
 * {@code HEAD_SECRET} header-field permutation. The lobby downlink is NOT
 * encrypted (measured entropy 6.31 bits/byte, readable strings throughout) even
 * though the client&rarr;server direction is — an asymmetric design where the
 * useful direction happens to be the clear one. It also runs on a custom port to
 * a Garena IP, not the port-443 OpenSSL/HTTP stack, so no TLS is involved.</p>
 *
 * <p>This matters because it is the <b>only</b> way to get the key without root.
 * Reading it out of the game's memory needs a rooted device; this does not, and
 * the app already relays the bytes through {@code core.TcpProxy}.</p>
 *
 * <h3>Why this scans for a byte signature instead of parsing frames</h3>
 * The wire format is {@code [1-byte flag][4-byte BIG-ENDIAN length][protobuf]},
 * and the key lives in field 3 of field 5 of a flag-{@code 0x03} frame. Fully
 * reassembling that is possible but needs unbounded buffering — one observed
 * frame was 27 294 bytes, and frames straddle socket reads.
 *
 * <p>Instead we scan for the <b>adjacency</b> that identifies the key
 * unambiguously:</p>
 * <pre>
 *   0x12 &lt;len&gt; "&lt;ip&gt;:&lt;port&gt;"      protobuf field 2, the UDP server
 *   0x1A 0x20 &lt;32 ASCII hex&gt;       protobuf field 3, the SECRET_KEY
 * </pre>
 *
 * <p><b>Do not simplify this to "find a 32-character hex string".</b> The lobby
 * stream is full of them: CDN asset names like
 * {@code OB35/testing/Modes/6758d48cd0e5b4983ad71b5efb83b59a.png}. A naive scan
 * over one captured session returned <b>13 false positives</b> and one true key.
 * The preceding {@code ip:port} field is what makes the match certain.</p>
 *
 * <p>Verified end-to-end: a key recovered this way decrypted the matching UDP
 * stream at TEA 2896/2896 and CRC7 3462/3462, and independently on a second
 * match at 6263/6263 and 9869/9869.</p>
 *
 * <h3>The message now has a name, and it carries more than the key</h3>
 * The frame this signature lives in is <b>{@code tcp.MatchmakingSussNtf}</b>
 * ({@code EMatchmaking.Proto_MATCHMAKINGSUSS_NTF = 5}). Field 2 is
 * {@code server_addr}, field 3 is {@code secret} — exactly the adjacency scanned
 * for below, which is why the scan works. The same message also carries
 * {@code map_id} (6), <b>{@code game_mode} (7)</b>, <b>{@code match_mode} (8)</b>,
 * {@code group_mode} (21) and the {@code udp_mask} header permutation (42).
 * {@link SessionTimeline} reads the mode fields out of it; this class stays
 * focused on the key.
 *
 * <p>It also emits a lifecycle event through
 * {@link SessionTimeline#onSessionKey} whenever a key lands or rotates, so a
 * capture can say when the session was re-keyed and for which endpoint.</p>
 *
 * <p>Not thread-safe; call {@link #onServerBytes} from a single relay thread.
 * Allocation-free in the common case — the carry buffer is reused and nothing
 * is allocated unless a key is actually found.</p>
 */
public final class LobbyKeySniffer {

    /** protobuf tag for field 2, length-delimited: (2 &lt;&lt; 3) | 2. */
    private static final int TAG_ENDPOINT = 0x12;
    /** protobuf tag for field 3, length-delimited: (3 &lt;&lt; 3) | 2. */
    private static final int TAG_KEY = 0x1A;
    /** The key is always 32 ASCII hex characters = 16 bytes. */
    private static final int KEY_HEX_LEN = 32;
    /** Longest {@code ip:port} we will accept, e.g. "255.255.255.255:65535". */
    private static final int MAX_ENDPOINT_LEN = 24;

    // ---- field 42: the header permutation, in ASCII ----
    //
    // THE HEADER LAYOUT NEVER HAD TO BE SOLVED. The same lobby message that
    // carries the cipher key also carries the header field permutation, in
    // plaintext, as five ASCII digits -- and the XOR mask is simply the key's
    // first byte. The solver spends 1.1-2.6 s and ~440 packets electing by
    // vote what arrives here for free, 231-274 ms BEFORE the first UDP packet
    // of the session. It must arrive first: field 2 of this very message is
    // the match server's address, so the client cannot address the server
    // before reading the message that names it.
    //
    // Cost of not reading it: every packet of that window decodes with an
    // unknown layout. Cmd 101 RUDP_PLAYER_JOIN survives at 20.4%, which is
    // why the roster is missing names and why authoritative team binds are
    // thin at match start.
    //
    // Verified 4/4 against permutations the solver independently elected, and
    // the signature matched 7 times in 2.1 MB of real lobby traffic with zero
    // false positives -- unlike the "32 hex chars anywhere" trap recorded
    // above, this constraint is ~64 bits.
    //
    /** protobuf tag for field 42, length-delimited: (42 &lt;&lt; 3) | 2 = 0x152, varint-encoded. */
    private static final int TAG_MASK_B0 = 0xD2;
    private static final int TAG_MASK_B1 = 0x02;
    /** The permutation is always five characters, one per header field. */
    private static final int PERM_LEN = 5;

    /** Last permutation seen, as HeaderField ids in wire order; null until seen. */
    private volatile int[] headOrder;

    /**
     * Bytes carried between reads so a signature split across a socket read is
     * still found. Must exceed the longest signature:
     * 1 + 1 + MAX_ENDPOINT_LEN + 2 + KEY_HEX_LEN.
     */
    private static final int CARRY = 96;

    private final byte[] carry = new byte[CARRY];
    private int carryLen;

    private volatile byte[] secretKey;      // 16 bytes, decoded from hex
    private volatile String udpEndpoint;    // "ip:port"
    private volatile long foundAtMs;

    /**
     * Last key recovered by ANY instance, as hex, for the capture dump header.
     *
     * <p>Static because {@code PacketCapture} has no reference to the engine and
     * only one tunnel is ever active. Without this the phone can capture
     * encrypted traffic it can never decrypt, which defeats the whole point of
     * recovering the key without root.</p>
     */
    private static volatile String lastKeyHex;
    private static volatile String lastEndpoint;
    private static volatile long lastFoundAtMs;
    private static final java.util.concurrent.atomic.AtomicInteger keyEvents =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger keyRotations =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Last recovered key as lowercase hex, or null. */
    public static String lastKeyHex() {
        return lastKeyHex;
    }

    /** UDP endpoint that key belongs to, or null. */
    public static String lastEndpoint() {
        return lastEndpoint;
    }

    /** One line for the capture header / dev panel. */
    public static String statusLine() {
        String k = lastKeyHex;
        return "lobbykey=" + (k == null ? "-" : k)
                + " endpoint=" + (lastEndpoint == null ? "-" : lastEndpoint)
                + " seen=" + keyEvents.get() + " rotations=" + keyRotations.get();
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    public boolean hasKey() {
        return secretKey != null;
    }

    /** Forget the current key — call on tunnel restart. */
    public void reset() {
        secretKey = null;
        udpEndpoint = null;
        foundAtMs = 0;
        carryLen = 0;
        // Per-session like the key: the permutation rotates with it, and a
        // stale one would seed the next match with the previous match's layout.
        headOrder = null;
    }

    /**
     * Feeds server&rarr;client bytes. Returns true if a NEW key was recovered.
     *
     * @param buf source buffer
     * @param off offset of the freshly read bytes
     * @param len number of bytes read
     */
    public boolean onServerBytes(byte[] buf, int off, int len) {
        if (buf == null || len <= 0 || off < 0 || off + len > buf.length) return false;

        // Scan the carry-over tail joined to the head of the new data, so a
        // signature straddling the read boundary is not missed.
        boolean found = false;
        if (carryLen > 0) {
            int headLen = Math.min(len, CARRY);
            byte[] joined = new byte[carryLen + headLen];
            System.arraycopy(carry, 0, joined, 0, carryLen);
            System.arraycopy(buf, off, joined, carryLen, headLen);
            found = scan(joined, 0, joined.length);
        }
        if (scan(buf, off, len)) found = true;
        if (carryLen > 0) {
            int headLen = Math.min(len, CARRY);
            byte[] joined = new byte[carryLen + headLen];
            System.arraycopy(carry, 0, joined, 0, carryLen);
            System.arraycopy(buf, off, joined, carryLen, headLen);
            scanPermutation(joined, 0, joined.length);
        }
        scanPermutation(buf, off, len);

        // Keep the tail for next time.
        int keep = Math.min(len, CARRY);
        System.arraycopy(buf, off + len - keep, carry, 0, keep);
        carryLen = keep;
        return found;
    }

    /**
     * Independent scan for field 42, the header permutation.
     *
     * <p>Deliberately NOT an adjacency check against the key like the scan
     * below: field 42 sits <b>2 125-5 060 bytes</b> after the key field in the
     * same frame, and this class does not reassemble frames, so no carry
     * window could ever span it. The two facts are attached to the same
     * session by arriving in the same connection, not by being adjacent.</p>
     */
    private void scanPermutation(byte[] b, int off, int len) {
        int end = off + len;
        for (int i = off; i + 2 + PERM_LEN < end + 1; i++) {
            if ((b[i] & 0xff) != TAG_MASK_B0) continue;
            if ((b[i + 1] & 0xff) != TAG_MASK_B1) continue;
            if ((b[i + 2] & 0xff) != PERM_LEN) continue;
            int at = i + 3;
            if (at + PERM_LEN > end) continue;
            int[] ord = new int[PERM_LEN];
            boolean ok = true;
            int seen = 0;
            for (int k = 0; k < PERM_LEN; k++) {
                int c = b[at + k] & 0xff;
                if (c < '0' || c > '4') { ok = false; break; }
                int id = c - '0';
                if ((seen & (1 << id)) != 0) { ok = false; break; }  // must be a permutation
                seen |= (1 << id);
                ord[k] = id;
            }
            if (!ok || seen != 0x1F) continue;
            headOrder = ord;
        }
    }

    /**
     * The header field permutation declared by the lobby, or null.
     *
     * <p>Five {@link HeaderField} ids in wire order. Pair with
     * {@link #headMask()} to build a {@link HeaderLayout} with no solving.</p>
     */
    public int[] headOrder() {
        int[] o = headOrder;
        return (o == null) ? null : o.clone();
    }

    /**
     * The header XOR mask: simply the first byte of the session key.
     *
     * <p>Documented in {@code docs/PROTOCOL_CIPHER.md} §4 and confirmed 6/6,
     * and never wired up until now.</p>
     *
     * @return 0-255, or -1 when no key has been recovered
     */
    public int headMask() {
        byte[] k = secretKey;
        return (k == null || k.length == 0) ? -1 : (k[0] & 0xFF);
    }

    /** Looks for {@code field2(ip:port)} immediately followed by {@code field3(32 hex)}. */
    private boolean scan(byte[] b, int off, int len) {
        int end = off + len;
        boolean found = false;
        for (int i = off; i + 4 < end; i++) {
            if ((b[i] & 0xff) != TAG_ENDPOINT) continue;
            int epLen = b[i + 1] & 0xff;
            if (epLen < 9 || epLen > MAX_ENDPOINT_LEN) continue;

            int afterEp = i + 2 + epLen;
            // need: tag, len, 32 hex bytes
            if (afterEp + 2 + KEY_HEX_LEN > end) continue;
            if (!isEndpoint(b, i + 2, epLen)) continue;
            if ((b[afterEp] & 0xff) != TAG_KEY) continue;
            if ((b[afterEp + 1] & 0xff) != KEY_HEX_LEN) continue;

            int hexAt = afterEp + 2;
            if (!isLowerHex(b, hexAt, KEY_HEX_LEN)) continue;

            byte[] key = decodeHex(b, hexAt, KEY_HEX_LEN);
            if (key == null) continue;

            String ep = new String(b, i + 2, epLen, java.nio.charset.StandardCharsets.US_ASCII);
            byte[] prev = secretKey;
            boolean isNew = (prev == null) || !java.util.Arrays.equals(prev, key);
            secretKey = key;
            udpEndpoint = ep;
            foundAtMs = System.currentTimeMillis();
            lastKeyHex = toHex(key);
            lastEndpoint = ep;
            lastFoundAtMs = foundAtMs;
            // Log the endpoint only — NEVER the key. Routing a 16-byte session
            // secret through a logger (even one R8 strips in release) is a latent
            // leak resting entirely on that strip staying intact (2026-09-18 audit L2).
            android.util.Log.i("FakelagKey", "lobby key recovered for " + ep + " (" + key.length + "B)");

            // Lifecycle: the key arriving is the strongest "a match is being set
            // up" signal on the whole lobby channel — this signature IS the
            // tcp.MatchmakingSussNtf field2/field3 pair. Recording it here (and
            // not only in SessionTimeline) means the event is emitted even when
            // dev.capture.LobbyCapture is not running.
            //
            // Key recovery above is deliberately unchanged: brief §3 constraint 6
            // says an encrypted capture is undecodable without it and a non-rooted
            // phone has no other source, so nothing may be reordered around it.
            keyEvents.incrementAndGet();
            boolean rotated = isNew && prev != null;
            if (rotated) keyRotations.incrementAndGet();
            try {
                SessionTimeline.onSessionKey(lastKeyHex, ep, rotated);
            } catch (Throwable t) {
                // The sniffer's own job must survive any observer failure.
            }

            if (isNew) found = true;
            i = hexAt + KEY_HEX_LEN - 1;
        }
        return found;
    }

    /** True for an ASCII {@code a.b.c.d:port} — digits, dots and exactly one colon. */
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

    /**
     * Lower-case hex test. Package-private and shared: {@code SessionTimeline}
     * carried a byte-identical private copy until 2026-09-20. Both live in
     * {@code protocol}, so one implementation is reachable from both.
     */
    static boolean isLowerHex(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    private static byte[] decodeHex(byte[] b, int off, int len) {
        if ((len & 1) != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = hexVal(b[off + 2 * i] & 0xff);
            int lo = hexVal(b[off + 2 * i + 1] & 0xff);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}
