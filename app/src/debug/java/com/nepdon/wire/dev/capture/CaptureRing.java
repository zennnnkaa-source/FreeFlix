package com.nepdon.wire.dev.capture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-rolling payload ring fed by every capture call site (zero new hot-path
 * hooks). Holds the last ~60 s of packets so a capture started mid-incident can
 * reach back in time ("capture this section") instead of only recording from the
 * press.
 *
 * <p><b>Storage changed from fixed per-slot buffers to a shared byte arena.</b>
 * The previous layout pre-allocated {@code 66 000 × 2 048 B = 135 MB} of live
 * byte arrays for a retro buffer that measurement says needs a few megabytes:
 * at the observed peak of ~1 100 pps and ~50 B average payload, 60 s is about
 * 3.3 MB. 135 MB resident inside a {@code VpnService} is itself a reason for
 * Android to kill the process — the very failure mode D4 is about. The arena
 * holds {@value #ARENA_BYTES} bytes plus ~3 MB of primitive metadata, and the
 * per-record cap rises from 2 048 to {@value #PAYLOAD_CAP} bytes, so large
 * entity chains stop being clipped.</p>
 *
 * <p>Lock-free: one atomic slot index and one atomic byte cursor, both claimed
 * with {@code getAndAdd}. Lapped records are overwritten (ring semantics) and a
 * reader detects that by comparing the record's absolute byte offset against the
 * live cursor, so a lapped record is skipped rather than read as garbage.</p>
 */
public final class CaptureRing {

    /** Ring depth in seconds (retro-capture budget). */
    public static final int RING_SECONDS = 60;

    /**
     * Bytes of payload retained per record.
     *
     * <p>Was 256, which clipped 28-582 packets per session — and the ones it
     * clipped were the large entity chains, exactly the packets worth keeping.
     * Then 2 048, which still cut the largest survival chains. The arena layout
     * makes the per-record cap nearly free, so it is a full datagram's worth.</p>
     */
    public static final int PAYLOAD_CAP = 8192;

    /**
     * Retro-buffer slots. Sized for the MEASURED peak, not the design
     * assumption: the original 24 000 was documented as "60 s at 400 pps", but
     * measured peaks are 193-1 007 pps, so at peak it actually held ~24 s.
     * 66 000 is roughly a minute at the observed 1 100 pps ceiling.
     */
    private static final int SLOTS = 66000;

    /** Shared payload arena: 8 MiB, ~2.5 minutes of payload at peak rates. */
    private static final int ARENA_BYTES = 1 << 23;

    /** Callback form of {@link #sliceFrom}, used to replay without allocating records. */
    public interface Sink {
        /**
         * @param buf  scratch buffer owned by the ring, valid only for this call
         */
        void onRetro(long tsMs, int src, int dir, int len, int copyLen,
                     int peerIp, int sp, int dp, int decision, int filterMask,
                     byte[] buf, int off);
    }

    private final byte[] arena = new byte[ARENA_BYTES];
    private final AtomicLong byteCursor = new AtomicLong(0);
    private final AtomicLong writeIdx = new AtomicLong(0);

    // Parallel primitive metadata — no per-record object, no per-record array.
    private final long[] mTs = new long[SLOTS];
    private final long[] mAbs = new long[SLOTS];
    private final int[] mCopyLen = new int[SLOTS];
    private final int[] mLen = new int[SLOTS];
    private final int[] mPeer = new int[SLOTS];
    private final int[] mSp = new int[SLOTS];
    private final int[] mDp = new int[SLOTS];
    /** src | dir<<4 | decision<<8 | filterMask<<16 */
    private final int[] mMeta = new int[SLOTS];

    /** Legacy entry point, kept so existing call sites need no edit. */
    public void add(long ts, int plen, int dip, int sp, int dp,
                    CaptureRecord.Dir dir, CaptureRecord.Decision decision,
                    byte[] buf, int off) {
        add(ts, CaptureEvent.SRC_UDP,
                dir == CaptureRecord.Dir.OUT ? CaptureEvent.OUT : CaptureEvent.IN,
                plen, dip, sp, dp,
                decision == CaptureRecord.Decision.DROP ? CaptureEvent.DROP
                        : decision == CaptureRecord.Decision.HOLD ? CaptureEvent.HOLD
                        : CaptureEvent.PASS,
                0, buf, off);
    }

    /**
     * Records one packet into the ring. Called from the hot path: no locks, no
     * allocation, two atomic adds and one (occasionally two) array copy.
     */
    public void add(long ts, int src, int dir, int len, int peerIp, int sp, int dp,
                    int decision, int filterMask, byte[] buf, int off) {
        if (len < 0) return;
        int copy = len;
        if (copy > PAYLOAD_CAP) copy = PAYLOAD_CAP;
        if (buf == null) copy = 0;
        if (copy > 0 && off + copy > buf.length) copy = Math.max(0, buf.length - off);

        long idx = writeIdx.getAndIncrement();
        int slot = (int) (idx % SLOTS);
        long abs = byteCursor.getAndAdd(copy);
        if (copy > 0) {
            int start = (int) (abs % ARENA_BYTES);
            int first = ARENA_BYTES - start;
            if (first > copy) first = copy;
            System.arraycopy(buf, off, arena, start, first);
            if (first < copy) System.arraycopy(buf, off + first, arena, 0, copy - first);
        }
        mAbs[slot] = abs;
        mCopyLen[slot] = copy;
        mLen[slot] = len;
        mPeer[slot] = peerIp;
        mSp[slot] = sp;
        mDp[slot] = dp;
        mMeta[slot] = (src & 0xf) | ((dir & 0xf) << 4) | ((decision & 0xff) << 8)
                | ((filterMask & 0xffff) << 16);
        mTs[slot] = ts;   // published last: a reader keys validity off this
    }

    /**
     * Replays every record at or after {@code tsMs} into {@code sink}, oldest
     * first. Records whose arena region has since been overwritten are skipped
     * rather than returned as garbage.
     *
     * @return the number of records replayed
     */
    public int replayFrom(long tsMs, Sink sink) {
        byte[] scratch = new byte[PAYLOAD_CAP];
        long total = writeIdx.get();
        long start = Math.max(0, total - SLOTS);
        int n = 0;
        for (long i = start; i < total; i++) {
            int slot = (int) (i % SLOTS);
            long ts = mTs[slot];
            if (ts == 0L || ts < tsMs) continue;
            int copy = mCopyLen[slot];
            long abs = mAbs[slot];
            long cursor = byteCursor.get();
            // Region lapped by later writers -> the bytes are somebody else's.
            if (cursor - abs + copy > ARENA_BYTES) continue;
            if (copy > 0) {
                int off = (int) (abs % ARENA_BYTES);
                int first = ARENA_BYTES - off;
                if (first > copy) first = copy;
                System.arraycopy(arena, off, scratch, 0, first);
                if (first < copy) System.arraycopy(arena, 0, scratch, first, copy - first);
            }
            int meta = mMeta[slot];
            sink.onRetro(ts, meta & 0xf, (meta >> 4) & 0xf, mLen[slot], copy,
                    mPeer[slot], mSp[slot], mDp[slot], (meta >> 8) & 0xff,
                    (meta >> 16) & 0xffff, scratch, 0);
            n++;
        }
        return n;
    }

}
