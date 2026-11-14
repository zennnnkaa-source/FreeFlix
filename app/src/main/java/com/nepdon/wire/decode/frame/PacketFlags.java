package com.nepdon.wire.decode.frame;

/**
 * The {@code Flags} header byte.
 *
 * <p>{@code BitArray.HasFlag(v, f)} is {@code (f &amp; v) != 0}, so these are
 * plain bit tests. Receive handling:</p>
 *
 * <pre>
 *   0  payload verbatim
 *   1  TeaDecrypt the whole payload
 *   2  Unzip the whole payload
 *   3  Unzip, then TeaDecrypt
 * </pre>
 *
 * <p><b>Flags is the authoritative encryption indicator — not {@code len % 8}.</b>
 * 744 measured plaintext packets happen to satisfy {@code len % 8 == 0} and
 * must not be decrypted. The {@code 8+8k} / {@code 10+8k} "ECB families" that
 * earlier analyses chased are just header size plus an 8-aligned ciphertext.</p>
 *
 * <p>No bit outside {@link #KNOWN_MASK} has ever been observed, which makes
 * {@link #isPlausible(int)} a strong per-packet test of whether a candidate
 * header layout put {@code Flags} at the right offset — a wrong offset yields
 * an essentially uniform byte and fails it ~98.4% of the time.</p>
 */
public enum PacketFlags {

    /** Payload is TEA-encrypted with the 16-byte session key. */
    ENCRYPTED(0x01),
    /**
     * Payload is zipped.
     *
     * <p><b>Nothing on the match wire ever sets this.</b> Measured across a
     * full match: 0 of 88 413 packets, and Flags only ever holds 0 or 1. The
     * game does ship a zip path ({@code GCommon.NetworkZipUtil::ZipXORHeader}
     * XORs the first min(len,16) payload bytes with SECRET_KEY before a gzip
     * stream) but it is never exercised here.</p>
     *
     * <p>Historically this bit appeared set on ~49% of packets, and those were
     * all discarded unread — 14 911 packets across ~40 commands in one capture.
     * They were not compressed. {@link SessionKeySolver} was electing the wrong
     * HEAD_SECRET permutation, so this byte was actually {@code orderId >> 8}.
     * See the {@code orderScore} comment there for the fix.</p>
     */
    ZIPPED(0x02);

    /** Every bit the protocol is known to define. */
    public static final int KNOWN_MASK = 0x03;

    private final int bit;

    PacketFlags(int bit) {
        this.bit = bit;
    }

    /** True when the payload needs {@link Tea} before it can be read. */
    public static boolean isEncrypted(int flags) {
        return (flags & ENCRYPTED.bit) != 0;
    }

    /**
     * True when the payload needs decompression first. The client's
     * {@code Unzip} is obfuscated in the image and has no analysable body, so
     * zipped payloads would not be readable here — though in practice the
     * match wire never sets the bit; they are skipped, never
     * guessed at.
     */
    public static boolean isZipped(int flags) {
        return (flags & ZIPPED.bit) != 0;
    }

    /** True when the payload is readable as-is. */
    public static boolean isPlaintext(int flags) {
        return (flags & KNOWN_MASK) == 0;
    }

    /** True when no unknown bit is set — see the class note on layout scoring. */
    public static boolean isPlausible(int flags) {
        return (flags & ~KNOWN_MASK) == 0;
    }

    /** Short label for logs; allocates nothing (returns interned literals). */
    public static String describe(int flags) {
        if (isZipped(flags)) return isEncrypted(flags) ? "zip+enc" : "zip";
        return isEncrypted(flags) ? "enc" : "plain";
    }
}
