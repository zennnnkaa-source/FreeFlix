package com.nepdon.wire.decode.body;

/**
 * A cursor over a decrypted message body.
 *
 * <p>The encoding rules come from {@code GCommon.FastBinaryReader} and are not
 * uniform, which is the trap that desynchronises every naive parser:</p>
 *
 * <ul>
 *   <li>{@code U} unsigned / {@code Z} zigzag / floats — <b>one LEB128
 *       varint</b>. Type changes interpretation, never length. A float is a
 *       varint whose bits are reinterpreted.</li>
 *   <li>{@code B} — <b>one raw byte</b>, not a varint.</li>
 *   <li>{@code S} string — a <b>zigzag</b> varint byte-length, then that many
 *       raw UTF-8 bytes.</li>
 *   <li>{@code FixUInt16} — <b>two raw little-endian bytes</b>.</li>
 * </ul>
 *
 * <p>Every read is bounds-checked and sets {@link #bad} rather than throwing,
 * so a malformed or mis-keyed body degrades to "no data" instead of an
 * exception on a packet thread.</p>
 */
public final class WireReader {

    private final byte[] b;
    private int i;
    private final int end;
    /** Set once a read has run off the end or overflowed; stays set. */
    public boolean bad;

    public WireReader(byte[] buf, int off, int len) {
        this.b = buf;
        this.i = off;
        this.end = off + len;
        this.bad = buf == null || off < 0 || len < 0 || off + len > buf.length;
    }

    public boolean hasMore() {
        return !bad && i < end;
    }

    /** LEB128 varint — the encoding of every scalar except B, S and FixUInt16. */
    public long u() {
        if (bad) return 0;
        long v = 0;
        int shift = 0;
        while (i < end) {
            int c = b[i++] & 0xff;
            v |= (long) (c & 0x7f) << shift;
            if ((c & 0x80) == 0) return v;
            shift += 7;
            if (shift > 63) break;
        }
        bad = true;
        return 0;
    }

    /** Zigzag-decoded varint. */
    public long z() {
        long v = u();
        return com.nepdon.wire.util.ZigZag.decode(v);
    }

    /** One RAW byte. Reading this as a varint is the classic desync. */
    public int rawByte() {
        if (bad || i >= end) { bad = true; return 0; }
        return b[i++] & 0xff;
    }

    /**
     * A length-prefixed UTF-8 string: zigzag byte-length, then the bytes.
     *
     * @param maxLen refuse anything longer; a mis-parse yields an absurd
     *               length and would otherwise consume the whole body
     * @return the string, or null when the field is not a plausible one
     */
    public String str(int maxLen) {
        long n = z();
        if (bad || n < 0 || n > maxLen || i + n > end) { bad = true; return null; }
        if (n == 0) return "";
        try {
            String s = new String(b, i, (int) n, "UTF-8");
            i += (int) n;
            return s;
        } catch (Exception e) {
            bad = true;
            return null;
        }
    }

    /** Skips {@code n} varint fields. */
    public void skipU(int n) {
        for (int k = 0; k < n && !bad; k++) u();
    }
}
