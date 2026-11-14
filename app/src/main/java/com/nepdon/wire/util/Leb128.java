package com.nepdon.wire.util;

/**
 * LEB128 varint reading and writing.
 *
 * <p>Sixteen hand-rolled copies of this existed across the codebase, in six
 * different contract families that disagreed about what to return when the
 * buffer runs out: {@code 0} plus a flag, {@code -1}, {@code Long.MIN_VALUE},
 * a packed value-and-length, a {@code lastVal} side-channel, and one that
 * silently reported success. That disagreement is not cosmetic — a caller
 * written against one contract and pointed at another mis-reads truncated
 * input, which on this wire means a desynced record rather than an error.</p>
 *
 * <h3>The contract here, stated once</h3>
 *
 * <p>Reads are <b>cursor-based</b>: the caller owns a one-element {@code int[]}
 * holding the read position, and every call either advances it past exactly one
 * varint or parks it at {@code end}. <b>Truncation is always reported as
 * {@link #BAD}</b>, never as a value, and the cursor is parked so a loop
 * terminates rather than spinning.</p>
 *
 * <p>All methods are static, take primitives, and allocate nothing. They run on
 * the packet threads at thousands of calls per second, so the out-param and
 * index-return style of the originals is preserved deliberately — do not
 * "improve" this into something that returns an object.</p>
 *
 * <h3>Why 10 bytes</h3>
 *
 * <p>A 64-bit value needs at most ceil(64/7) = 10 groups. The originals
 * expressed this two ways — {@code ++n > 9} counting bytes, and
 * {@code shift > 63} counting bits — and the two accept exactly the same 1..10
 * byte range, so they were never in conflict. Written out here so nobody has
 * to re-derive it.</p>
 */
public final class Leb128 {

    private Leb128() {}

    /** Returned by every read when the input is truncated or overlong. */
    public static final long BAD = Long.MIN_VALUE;

    /** Maximum bytes a 64-bit LEB128 value can occupy. */
    public static final int MAX_BYTES = 10;

    /**
     * Reads one unsigned varint, advancing {@code pos[0]}.
     *
     * @return the value, or {@link #BAD} when truncated or longer than
     *         {@link #MAX_BYTES} (cursor parked at {@code end})
     */
    public static long read(byte[] b, int[] pos, int end) {
        int i = pos[0];
        long v = 0;
        int shift = 0;
        while (i < end) {
            int c = b[i++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) {
                pos[0] = i;
                return v;
            }
            shift += 7;
            if (shift > 63) break;      // overlong: 11th group cannot fit
        }
        pos[0] = end;
        return BAD;
    }

    /**
     * Index just past one varint, without decoding it.
     *
     * @return the next index, or -1 when truncated
     */
    public static int skip(byte[] b, int i, int end) {
        int n = 0;
        while (i < end) {
            int c = b[i++] & 0xFF;
            if ((c & 0x80) == 0) return i;
            if (++n >= MAX_BYTES) return -1;
        }
        return -1;
    }

    /**
     * Index just past {@code count} varints.
     *
     * @return the next index, or -1 when any of them is truncated
     */
    public static int skip(byte[] b, int i, int end, int count) {
        for (int k = 0; k < count; k++) {
            i = skip(b, i, end);
            if (i < 0) return -1;
        }
        return i;
    }

    /**
     * Writes {@code v} as a canonical (minimal-width) unsigned varint.
     *
     * @return the next write index
     */
    public static int write(byte[] b, int p, long v) {
        while (true) {
            int c = (int) (v & 0x7FL);
            v >>>= 7;
            if (v != 0) {
                b[p++] = (byte) (c | 0x80);
            } else {
                b[p++] = (byte) c;
                return p;
            }
        }
    }

    /** Bytes {@link #write} would emit for {@code v}. */
    public static int width(long v) {
        int n = 1;
        while ((v >>>= 7) != 0) n++;
        return n;
    }
}
