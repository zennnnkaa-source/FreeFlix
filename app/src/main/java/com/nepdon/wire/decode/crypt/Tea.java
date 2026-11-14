package com.nepdon.wire.decode.crypt;

/**
 * TEA as Free Fire Max uses it: 16 rounds, little-endian words, wrapped in
 * Tencent's QQ-TEA outer framing.
 *
 * <p>Constants come from {@code NetworkCryptologyUtil}'s statics at
 * {@code .rodata:0x18BC950} — {@code SALT_LEN=2 ZERO_LEN=7 DELTA=0x9E3779B9
 * ROUNDS=16}. <b>DELTA is a static field, not an instruction immediate</b>, so
 * scanning the image for {@code 0x9E3779B9} finds only {@code
 * boost::hash_combine} and misses the cipher entirely.</p>
 *
 * <p>Outer framing, CBC-chained over 8-byte blocks:</p>
 * <pre>
 *   [padCount byte][padCount zeros][2 salt zeros][plaintext][7 zeros]
 *   encLen = align8(plainLen + SALT_LEN + ZERO_LEN + 1) = align8(plainLen + 10)
 * </pre>
 *
 * <p>{@code rand()} is stubbed to {@code return 0} in the shipped binary, so
 * the salt bytes are always zero and encryption is fully deterministic. The
 * seven-zero tail is therefore a free decryption oracle and is verified here:
 * a decrypt whose tail is not seven zeros is rejected rather than returned as
 * garbage. Measured 138/138 and 339/339 on live captures with the session
 * key.</p>
 *
 * <p><b>The 16-byte session key is not on the wire.</b> Header framing,
 * classification and CRC need no key at all; only {@code Flags &amp; 1}
 * payloads do. Everything here is inert until a key is supplied.</p>
 *
 * <p>Allocation: the primary entry points write into a caller-supplied buffer
 * and allocate nothing, so they are safe on the per-packet path.</p>
 */
public final class Tea {

    public static final int ROUNDS = 16;
    public static final int DELTA = 0x9E3779B9;
    public static final int SALT_LEN = 2;
    public static final int ZERO_LEN = 7;
    public static final int BLOCK_BYTES = 8;
    public static final int KEY_BYTES = 16;

    /** Framing overhead: pad-count byte + salt + zero tail. */
    public static final int FRAME_OVERHEAD = 1 + SALT_LEN + ZERO_LEN;

    /** Shortest legal ciphertext (two blocks). */
    public static final int MIN_CIPHERTEXT = 16;

    private Tea() { }

    // ---- key schedule ----

    /**
     * The four little-endian 32-bit key words, or {@code null} when the key is
     * missing or short. Compute this once per session, never per packet.
     */
    public static int[] schedule(byte[] key) {
        if (key == null || key.length < KEY_BYTES) return null;
        int[] k = new int[4];
        for (int i = 0; i < 4; i++) {
            k[i] = le32(key, i * 4);
        }
        return k;
    }

    // ---- block primitives ----

    /** Raw TEA decrypt of a word pair; returns {@code v0} high, {@code v1} low. */
    private static long decryptWords(int v0, int v1, int[] k) {
        int k0 = k[0], k1 = k[1], k2 = k[2], k3 = k[3];
        int sum = DELTA * ROUNDS;
        for (int i = 0; i < ROUNDS; i++) {
            v1 -= ((v0 << 4) + k2) ^ (v0 + sum) ^ ((v0 >>> 5) + k3);
            v0 -= ((v1 << 4) + k0) ^ (v1 + sum) ^ ((v1 >>> 5) + k1);
            sum -= DELTA;
        }
        return pack(v0, v1);
    }

    /** Raw TEA encrypt of a word pair; returns {@code v0} high, {@code v1} low. */
    private static long encryptWords(int v0, int v1, int[] k) {
        int k0 = k[0], k1 = k[1], k2 = k[2], k3 = k[3];
        int sum = 0;
        for (int i = 0; i < ROUNDS; i++) {
            sum += DELTA;
            v0 += ((v1 << 4) + k0) ^ (v1 + sum) ^ ((v1 >>> 5) + k1);
            v1 += ((v0 << 4) + k2) ^ (v0 + sum) ^ ((v0 >>> 5) + k3);
        }
        return pack(v0, v1);
    }

    // ---- QQ-TEA framing ----

    /** Ciphertext length for a given plaintext length: {@code align8(plainLen + 10)}. */
    public static int qqTeaEncryptLen(int plainLen) {
        int n = plainLen + FRAME_OVERHEAD;
        int r = n & 7;
        return r == 0 ? n : n + (8 - r);
    }

    /**
     * Decrypts a QQ-TEA payload into {@code out}, verifying the seven-zero
     * tail. Returns the plaintext length (written at {@code outOff}), or -1
     * when the input is malformed or the tail check fails.
     *
     * <p>{@code out} must have at least {@code len} bytes free at
     * {@code outOff}: the chained stream is produced there first and the
     * plaintext is then compacted to the front of that window.</p>
     */
    public static int qqTeaDecrypt(byte[] in, int off, int len, int[] k, byte[] out, int outOff) {
        if (k == null || k.length < 4) return -1;
        if (in == null || out == null) return -1;
        if (len < MIN_CIPHERTEXT || (len & 7) != 0) return -1;
        if (off < 0 || off + len > in.length) return -1;
        if (outOff < 0 || out.length - outOff < len) return -1;

        // Block 0 decrypts straight through and seeds both chain registers.
        int c0 = le32(in, off);
        int c1 = le32(in, off + 4);
        long d = decryptWords(c0, c1, k);
        int prevPlain0 = high(d), prevPlain1 = low(d);
        putLe32(out, outOff, prevPlain0);
        putLe32(out, outOff + 4, prevPlain1);
        int prevCipher0 = c0, prevCipher1 = c1;

        for (int i = BLOCK_BYTES; i < len; i += BLOCK_BYTES) {
            int ci0 = le32(in, off + i);
            int ci1 = le32(in, off + i + 4);
            long dd = decryptWords(ci0 ^ prevPlain0, ci1 ^ prevPlain1, k);
            int d0 = high(dd), d1 = low(dd);
            putLe32(out, outOff + i, d0 ^ prevCipher0);
            putLe32(out, outOff + i + 4, d1 ^ prevCipher1);
            prevPlain0 = d0;
            prevPlain1 = d1;
            prevCipher0 = ci0;
            prevCipher1 = ci1;
        }

        int pad = out[outOff] & 7;
        int plainLen = len - pad - FRAME_OVERHEAD;
        if (plainLen < 0) return -1;
        int start = outOff + 1 + pad + SALT_LEN;
        for (int i = 0; i < ZERO_LEN; i++) {
            if (out[start + plainLen + i] != 0) return -1;   // not our key / not QQ-TEA
        }
        if (plainLen > 0) {
            System.arraycopy(out, start, out, outOff, plainLen);
        }
        return plainLen;
    }

    /** Convenience wrapper for offline tools; allocates. Returns null on failure. */
    public static byte[] qqTeaDecrypt(byte[] payload, byte[] key) {
        int[] k = schedule(key);
        if (k == null || payload == null) return null;
        byte[] scratch = new byte[payload.length];
        int n = qqTeaDecrypt(payload, 0, payload.length, k, scratch, 0);
        if (n < 0) return null;
        byte[] plain = new byte[n];
        System.arraycopy(scratch, 0, plain, 0, n);
        return plain;
    }

    /**
     * Encrypts {@code plain} into {@code out} with the QQ-TEA framing, returning
     * the ciphertext length ({@link #qqTeaEncryptLen}) or -1 when {@code out} is
     * too small. The chain is the exact inverse of {@link #qqTeaDecrypt}:
     * <pre>
     *   d0 = S0            C0 = E(d0)
     *   di = Si ^ C(i-1)   Ci = E(di) ^ d(i-1)
     * </pre>
     */
    public static int qqTeaEncrypt(byte[] plain, int pOff, int pLen, int[] k, byte[] out, int outOff) {
        if (k == null || k.length < 4 || plain == null || out == null) return -1;
        if (pOff < 0 || pLen < 0 || pOff + pLen > plain.length) return -1;
        int encLen = qqTeaEncryptLen(pLen);
        if (outOff < 0 || out.length - outOff < encLen) return -1;

        int pad = encLen - pLen - FRAME_OVERHEAD;
        java.util.Arrays.fill(out, outOff, outOff + encLen, (byte) 0);
        out[outOff] = (byte) pad;                       // rand() is stubbed to 0
        System.arraycopy(plain, pOff, out, outOff + 1 + pad + SALT_LEN, pLen);

        int prevD0 = le32(out, outOff);
        int prevD1 = le32(out, outOff + 4);
        long e = encryptWords(prevD0, prevD1, k);
        int prevC0 = high(e), prevC1 = low(e);
        putLe32(out, outOff, prevC0);
        putLe32(out, outOff + 4, prevC1);

        for (int i = BLOCK_BYTES; i < encLen; i += BLOCK_BYTES) {
            int s0 = le32(out, outOff + i);
            int s1 = le32(out, outOff + i + 4);
            int d0 = s0 ^ prevC0;
            int d1 = s1 ^ prevC1;
            long ee = encryptWords(d0, d1, k);
            int c0 = high(ee) ^ prevD0;
            int c1 = low(ee) ^ prevD1;
            putLe32(out, outOff + i, c0);
            putLe32(out, outOff + i + 4, c1);
            prevD0 = d0;
            prevD1 = d1;
            prevC0 = c0;
            prevC1 = c1;
        }
        return encLen;
    }

    // ---- little-endian helpers ----

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF)
                | ((b[i + 1] & 0xFF) << 8)
                | ((b[i + 2] & 0xFF) << 16)
                | ((b[i + 3] & 0xFF) << 24);
    }

    private static void putLe32(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
        b[i + 2] = (byte) (v >>> 16);
        b[i + 3] = (byte) (v >>> 24);
    }

    private static long pack(int v0, int v1) {
        return ((long) v0 << 32) | (v1 & 0xFFFFFFFFL);
    }

    private static int high(long p) {
        return (int) (p >>> 32);
    }

    private static int low(long p) {
        return (int) p;
    }
}
