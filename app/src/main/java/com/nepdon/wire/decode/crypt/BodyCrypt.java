package com.nepdon.wire.decode.crypt;

import com.nepdon.wire.protocol.LobbyKeySniffer;

/**
 * Decrypts a reliable message body with the captured session key.
 *
 * <p>The capture annotator has always done this, but privately — so the live
 * ESP could only ever read the plaintext channels (the entity chain, the small
 * handle events) and was blind to everything reliable. Health lives in
 * Cmd 1900, which is reliable and therefore encrypted, which is the mechanical
 * reason the ESP invented health instead of reading it.</p>
 *
 * <p>The key arrives in the clear over lobby TCP and is already captured by
 * {@link LobbyKeySniffer}; the schedule is cached and rebuilt only when the key
 * rotates. Scratch buffers are thread-local because this is called from the
 * per-session receive threads.</p>
 */
public final class BodyCrypt {

    private BodyCrypt() {}

    /** Biggest body we will decrypt. Comfortably above any observed message. */
    public static final int MAX_BODY = 4096;

    private static volatile int[] keySchedule;
    private static volatile String keyScheduleHex;

    private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[MAX_BODY];
        }
    };

    /** The TEA key schedule for the current session key, or null if none yet. */
    public static int[] schedule() {
        String hex = LobbyKeySniffer.lastKeyHex();
        if (hex == null || hex.length() != 32) return null;
        String cached = keyScheduleHex;
        if (hex.equals(cached)) return keySchedule;
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            key[i] = (byte) ((hi << 4) | lo);
        }
        int[] s = Tea.schedule(key);
        keySchedule = s;
        keyScheduleHex = hex;
        return s;
    }

    /** True once a session key has been captured. */
    public static boolean hasKey() {
        return schedule() != null;
    }

    /**
     * Decrypts {@code len} bytes at {@code off} into a thread-local scratch
     * buffer.
     *
     * @return the plaintext length, or -1 when there is no key, the length is
     *         not a whole number of TEA blocks, or the trailing-zero check
     *         fails (i.e. the key is wrong for this body)
     */
    public static int decrypt(byte[] buf, int off, int len) {
        if (buf == null || off < 0 || len <= 0 || off + len > buf.length) return -1;
        if (len < Tea.MIN_CIPHERTEXT || (len & 7) != 0 || len > MAX_BODY) return -1;
        int[] sched = schedule();
        if (sched == null) return -1;
        try {
            return Tea.qqTeaDecrypt(buf, off, len, sched, SCRATCH.get(), 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The buffer a successful {@link #decrypt} wrote into. Valid until the
     *  next decrypt on this thread. */
    public static byte[] plain() {
        return SCRATCH.get();
    }
}
