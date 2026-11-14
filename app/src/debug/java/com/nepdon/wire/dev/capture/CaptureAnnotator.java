package com.nepdon.wire.dev.capture;

import com.nepdon.wire.decode.body.BodyDecoderTable;
import com.nepdon.wire.decode.body.BodyDecoderText;
import com.nepdon.wire.decode.body.Cmd;
import com.nepdon.wire.decode.body.CmdCatalogue;
import com.nepdon.wire.decode.frame.HeaderField;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.protocol.LobbyKeySniffer;
import com.nepdon.wire.decode.frame.MsgKey;
import com.nepdon.wire.decode.frame.PacketCodec;
import com.nepdon.wire.decode.frame.PacketFlags;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.decode.crypt.Tea;
import com.nepdon.wire.decode.frame.UdpPacket;

/**
 * Fills the protocol half of a {@link CaptureEvent} on the writer thread.
 *
 * <p>Before this existed a captured packet was a length and a hex blob, and
 * every protocol fact {@code net/UdpPacket} had already computed — the command,
 * the order id, CRC validity, reliability, the encryption and compression bits,
 * whether the declared length agreed with the wire — was computed and then
 * thrown away (brief §2, <b>D6</b>). All of it is surfaced here, plus the
 * decoded body.</p>
 *
 * <p>Classification is by <b>command id</b>, never by byte shape (brief §2,
 * <b>D5</b>). The only shape input is {@code MsgKey}'s byte-0 range, which is
 * the protocol's own framing discriminator and the only thing readable before
 * the session is solved.</p>
 *
 * <h3>The pre-solver window is explicit, not guessed</h3>
 * <p>For the first few hundred packets of a session, and again after a key
 * rotation, the header layout is genuinely unknown and {@code cmd} does not
 * exist. Those records get {@link CaptureEvent#F_PRESOLVER} and
 * {@code cmd = -1}; nothing is inferred from length (brief §3, constraint 4 —
 * the raw-byte-window freeze policy that ate 88% of inbound is exactly the
 * mistake this avoids).</p>
 *
 * <h3>Contract</h3>
 * <p>{@link #annotate} <b>never throws</b>. It runs on the capture writer
 * thread, so one malformed packet must cost one annotation and not the capture;
 * failures are counted in {@link #coverageLine()} instead of propagating.</p>
 */
public final class CaptureAnnotator {

    /** Longest body the annotator will attempt to decrypt into scratch. */
    private static final int MAX_SCRATCH = 8192;

    private CaptureAnnotator() { }

    // ---- counters (writer thread; volatile so the dev panel can read them) ----

    private static volatile long annotated;
    private static volatile long presolver;
    private static volatile long notMatchTraffic;
    private static volatile long truncated;
    private static volatile long crcBad;
    private static volatile long decrypted;
    private static volatile long encryptedNoKey;
    private static volatile long zipped;
    private static volatile long errors;
    /** Bodies whose Flags said plaintext but which decrypted and then decoded. */
    private static volatile long hiddenCipher;

    /** Decryption scratch, grown at most a few times. Writer thread only. */
    private static byte[] scratch;
    /** Cached TEA schedule and the key hex it was built from. */
    private static int[] keySchedule;
    private static String keyScheduleHex;

    /** Zeroes every counter, including the decoder's. Call at capture start. */
    public static void resetCounters() {
        annotated = 0;
        presolver = 0;
        notMatchTraffic = 0;
        truncated = 0;
        crcBad = 0;
        decrypted = 0;
        encryptedNoKey = 0;
        zipped = 0;
        errors = 0;
        hiddenCipher = 0;
        BodyDecoderText.resetCounters();
    }

    // ================================================================
    // The seam
    // ================================================================

    /** Fills cmd/orderId/flags/note on the writer thread. Never throws. */
    public static void annotate(CaptureEvent e) {
        try {
            annotate0(e);
        } catch (Throwable t) {
            errors++;
        }
    }

    private static void annotate0(CaptureEvent e) {
        if (e == null) return;
        if (e.src != CaptureEvent.SRC_UDP) return;   // lobby TCP belongs to AGENT 3
        byte[] buf = e.payload;
        int have = e.copyLen;
        if (buf == null || have <= 0) return;
        if (have > buf.length) have = buf.length;

        annotated++;

        // Byte 0 is the protocol's own framing discriminator and is plaintext.
        int hdr = MsgKey.headerSizeOf(buf, 0, have);
        if (hdr == MsgKey.NOT_MATCH_TRAFFIC) {
            notMatchTraffic++;
            return;
        }
        boolean reliable = (hdr == MsgKey.HEADER_RELIABLE);
        if (reliable) e.flags |= CaptureEvent.F_RELIABLE;

        PacketPipeline pipe = PacketPipeline.current();
        HeaderLayout layout = (pipe == null) ? null : pipe.layout();
        if (layout == null) {
            // Genuinely unknowable: every header byte from index 2 is masked and
            // the five fields are permuted per session.
            e.flags |= CaptureEvent.F_PRESOLVER;
            presolver++;
            return;
        }

        boolean whole = (have >= e.len);
        if (!whole) truncated++;

        int cmd;
        int orderId;
        int rawFlags;
        int declared;
        int payloadLen;

        if (whole) {
            // The solved path: one call, and every fact UdpPacket knows.
            UdpPacket p = PacketCodec.parse(buf, 0, e.len, layout);
            if (p == null) {
                notMatchTraffic++;
                return;
            }
            cmd = p.cmd();
            orderId = p.orderId();
            rawFlags = p.flags();
            declared = p.declaredLength();
            payloadLen = p.payloadLength();
            if (p.isCrcValid()) e.flags |= CaptureEvent.F_CRC_OK; else crcBad++;
            if (p.lengthMatches()) e.flags |= CaptureEvent.F_LEN_MATCH;
        } else {
            // The capture clipped the tail, so CRC-7 and the Length identity are
            // both unanswerable — but the header itself is intact and is read
            // with the same HeaderLayout accessors PacketCodec uses.
            cmd = layout.readShort(buf, 0, HeaderField.CMD, reliable);
            orderId = reliable ? layout.readShort(buf, 0, HeaderField.ORDER_ID, reliable) : -1;
            rawFlags = layout.readByte(buf, 0, HeaderField.FLAGS, reliable);
            declared = layout.readShort(buf, 0, HeaderField.DATA_LEN, reliable);
            payloadLen = e.len - hdr;
        }

        e.flags |= CaptureEvent.F_PARSED;
        e.cmd = cmd;
        e.orderId = orderId;
        if (PacketFlags.isEncrypted(rawFlags)) e.flags |= CaptureEvent.F_ENCRYPTED;
        if (PacketFlags.isZipped(rawFlags)) e.flags |= CaptureEvent.F_ZIPPED;

        if (payloadLen <= 0) return;
        boolean outbound = (e.dir == CaptureEvent.OUT);

        // ---- body ----
        if (PacketFlags.isZipped(rawFlags)) {
            // Unzip is obfuscated in the image and its gzip header is XOR'd with
            // SECRET_KEY. There is nothing honest to do without the key.
            zipped++;
            e.note = "# zipped len=" + declared + "/" + payloadLen;
            return;
        }

        int bodyOff = hdr;
        int bodyLen = Math.min(payloadLen, have - hdr);
        byte[] body = buf;

        if (PacketFlags.isEncrypted(rawFlags)) {
            int[] sched = schedule();
            if (sched == null) {
                encryptedNoKey++;
                e.note = "# enc-nokey len=" + declared + "/" + payloadLen;
                return;
            }
            if (!whole || bodyLen <= 0 || bodyLen > MAX_SCRATCH) {
                encryptedNoKey++;
                e.note = "# enc-clipped len=" + declared + "/" + payloadLen;
                return;
            }
            byte[] out = scratchFor(bodyLen);
            if (out == null) {
                encryptedNoKey++;
                return;
            }
            int plainLen = Tea.qqTeaDecrypt(buf, bodyOff, bodyLen, sched, out, 0);
            if (plainLen < 0) {
                encryptedNoKey++;
                e.note = "# enc-badkey len=" + declared + "/" + payloadLen;
                return;
            }
            e.flags |= CaptureEvent.F_DECRYPTED;
            decrypted++;
            body = out;
            bodyOff = 0;
            bodyLen = plainLen;
        }

        if (bodyLen <= 0) return;

        // FLAGS IS NOT THE WHOLE ENCRYPTION STORY. Measured on a keyed capture:
        // 183 bodies with Flags == 0 that failed their modelled layout all
        // QQ-TEA-decrypted cleanly (the seven-zero tail check passes by chance
        // at 2^-56), and 147 of those then decoded BYTE-EXACTLY against the same
        // layout. So a body that claims plaintext and does not fit its own
        // layout is worth one speculative decrypt before it is written off.
        // This is observation only — nothing here changes what the tunnel sends.
        if (!PacketFlags.isEncrypted(rawFlags) && whole
                && !BodyDecoderText.isExactlyDecodable(cmd, outbound, body, bodyOff, bodyLen)
                && bodyLen >= Tea.MIN_CIPHERTEXT && (bodyLen & 7) == 0
                && bodyLen <= MAX_SCRATCH) {
            int[] sched = schedule();
            if (sched != null) {
                byte[] out = scratchFor(bodyLen);
                if (out != null) {
                    int plainLen = Tea.qqTeaDecrypt(body, bodyOff, bodyLen, sched, out, 0);
                    if (plainLen > 0
                            && BodyDecoderText.isExactlyDecodable(cmd, outbound, out, 0, plainLen)) {
                        e.flags |= CaptureEvent.F_DECRYPTED;
                        decrypted++;
                        hiddenCipher++;
                        body = out;
                        bodyOff = 0;
                        bodyLen = plainLen;
                    }
                }
            }
        }

        String dump = BodyDecoderText.decode(cmd, outbound, body, bodyOff, bodyLen);
        if (dump == null) return;
        if (!whole) {
            e.note = "clipped " + dump;
        } else {
            e.note = dump;
        }
    }

    /** Per-run coverage stats for the capture footer. */
    public static String coverageLine() {
        StringBuilder sb = new StringBuilder(320);
        sb.append("annotated=").append(annotated)
          .append(" presolver=").append(presolver)
          .append(" foreign=").append(notMatchTraffic)
          .append(" clipped=").append(truncated)
          .append(" crcBad=").append(crcBad)
          .append(" decrypted=").append(decrypted)
          .append(" encNoKey=").append(encryptedNoKey)
          .append(" zipped=").append(zipped)
          .append(" hiddenCipher=").append(hiddenCipher);
        if (errors != 0) sb.append(" annotateErrors=").append(errors);
        sb.append(" | ").append(BodyDecoderText.coverage());
        sb.append(" | catalogue=").append(CmdCatalogue.COUNT)
          .append('/').append(CmdCatalogue.INBOUND_COUNT)
          .append(" wireLayouts=").append(BodyDecoderTable.COUNT);
        return sb.toString();
    }

    /**
     * One line naming a packet, for the dev panel: the command's real name plus
     * the flag letters. Allocates; not for the packet path.
     */
    public static String describe(CaptureEvent e) {
        if (e == null) return "-";
        StringBuilder sb = new StringBuilder(96);
        sb.append(CaptureEvent.dirName(e.dir)).append(' ');
        if (e.cmd < 0) {
            sb.append((e.flags & CaptureEvent.F_PRESOLVER) != 0 ? "presolver" : "unparsed");
        } else {
            sb.append(CmdCatalogue.describe(e.cmd));
        }
        sb.append(' ').append(flagLetters(e.flags));
        return sb.toString();
    }

    /** Compact letters for the {@code F_*} bits: {@code PCRedZLp}. */
    public static String flagLetters(int flags) {
        StringBuilder sb = new StringBuilder(8);
        if ((flags & CaptureEvent.F_PARSED) != 0) sb.append('P');
        if ((flags & CaptureEvent.F_CRC_OK) != 0) sb.append('C');
        if ((flags & CaptureEvent.F_RELIABLE) != 0) sb.append('R');
        if ((flags & CaptureEvent.F_ENCRYPTED) != 0) sb.append('e');
        if ((flags & CaptureEvent.F_DECRYPTED) != 0) sb.append('d');
        if ((flags & CaptureEvent.F_ZIPPED) != 0) sb.append('Z');
        if ((flags & CaptureEvent.F_LEN_MATCH) != 0) sb.append('L');
        if ((flags & CaptureEvent.F_PRESOLVER) != 0) sb.append('p');
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ================================================================
    // Session key
    // ================================================================

    /**
     * The TEA key schedule for the current session, or null.
     *
     * <p>The key arrives in plaintext on the lobby TCP channel and
     * {@link LobbyKeySniffer} already recovers it, so this needs no root and no
     * extra plumbing. The schedule is rebuilt only when the key hex changes,
     * i.e. once per session.</p>
     */
    private static int[] schedule() {
        String hex = LobbyKeySniffer.lastKeyHex();
        if (hex == null || hex.length() != 32) return null;
        if (hex.equals(keyScheduleHex)) return keySchedule;
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            key[i] = (byte) ((hi << 4) | lo);
        }
        keySchedule = Tea.schedule(key);
        keyScheduleHex = hex;
        return keySchedule;
    }

    private static byte[] scratchFor(int n) {
        if (n > MAX_SCRATCH) return null;
        byte[] b = scratch;
        if (b == null || b.length < n) {
            b = new byte[Math.max(n, 1024)];
            scratch = b;
        }
        return b;
    }

    // ================================================================
    // Small helpers the writer and the dev panel can use
    // ================================================================

    /** True when this id must never be withheld — the reliability layer. */
    public static boolean isTransport(int cmd) {
        return cmd >= 0 && Cmd.isTransportLayer(cmd);
    }

}
