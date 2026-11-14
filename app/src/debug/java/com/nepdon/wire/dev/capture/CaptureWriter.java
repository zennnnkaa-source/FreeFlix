package com.nepdon.wire.dev.capture;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The streaming capture writer: one dedicated thread draining a bounded,
 * allocation-free queue straight to disk.
 *
 * <p>Design points, each fixing a named defect from
 * {@code docs/DUMPER_REWRITE_BRIEF.md} §2:</p>
 *
 * <ul>
 *   <li><b>D4</b> — records are written continuously, not buffered until stop.
 *       The stream is flushed on an interval and on a byte threshold and the
 *       file descriptor is fsync'd periodically, so a capture killed mid-session
 *       (OOM kill, crash, phone reboot) leaves a valid file truncated at the
 *       last flush rather than nothing at all. Long sessions rotate into
 *       numbered parts instead of growing one unbounded file.</li>
 *   <li><b>D3</b> — the FULL payload is written. There is no hex limit on the
 *       write path; the only cap is the capture-time copy bound in
 *       {@link PacketCapture}, which is a whole datagram.</li>
 *   <li><b>D10</b> — no {@code StringBuilder} and no {@code String} per record.
 *       Every field is encoded straight into a reusable {@code byte[]} scratch
 *       buffer, including the hex, the decimals and the command name (cached as
 *       pre-encoded bytes per command id). Steady-state allocations per record
 *       on the writer thread: <b>0</b>.</li>
 *   <li><b>D9</b> — every discard path is counted: queue-full, pool-exhausted,
 *       mode-filtered, cap-exceeded and write-error, and all of them are
 *       reported in the footer.</li>
 * </ul>
 *
 * <p>The file format is the seam in brief §4.4 — fixed positional columns up to
 * {@code note}, which is free text and always last.</p>
 */
public final class CaptureWriter {

    // ---- tuning ----

    /**
     * Queue depth between the hot threads and the writer. At the measured peak
     * of ~1 100 pps this is ~3.7 s of buffering, and it is deliberately SMALLER
     * than {@link #POOL_SIZE} so the queue is the real limiter: a burst hits
     * "queue full", which is the honest, reportable drop path, instead of
     * silently running the event pool dry first.
     */
    public static final int QUEUE_CAPACITY = 4096;
    /** Pooled event ceiling. Beyond this an intake is counted, not served. */
    public static final int POOL_SIZE = 6144;
    /** Stream buffer; also the flush byte threshold. */
    private static final int STREAM_BUFFER = 1 << 16;
    /** Flush at least this often, so a kill loses at most this much. */
    private static final long FLUSH_INTERVAL_MS = 500L;
    /** fsync at least this often (cheap at this cadence, survives a reboot). */
    private static final long SYNC_INTERVAL_MS = 5000L;
    /** Rotate to a new part at this size. */
    private static final long ROTATE_BYTES = 32L << 20;
    /** Poll the lifecycle source at least this often. */
    private static final long TIMELINE_INTERVAL_MS = 250L;
    /** Re-sample the lifecycle phase at most this often (see process()). */
    private static final long PHASE_SAMPLE_MS = 20L;
    /** Highest command id whose name is cached as pre-encoded bytes. */
    private static final int CMD_NAME_CACHE_MAX = 4096;

    /** Phase names for {@code CaptureEvent.phaseId} (brief §4.3 ordering). */
    private static final String[] PHASE_NAMES = {
            "UNKNOWN", "LOBBY", "MATCHMAKING", "LOADING", "MATCH", "POSTMATCH"
    };

    /**
     * Writer-thread hooks supplied by {@link PacketCapture}: the mode gate (run
     * on the real command id, after annotation) and the legacy record mirror.
     */
    public interface Gate {
        /** True when this event belongs in the pressed capture. */
        boolean keep(CaptureEvent e);

        /** Called for every event actually written, on the writer thread. */
        void onWritten(CaptureEvent e);
    }

    /**
     * Bounded lock-free ring of event references. Multi-producer and
     * multi-consumer by CAS on the two cursors; neither {@code offer} nor
     * {@code poll} allocates. Used both for the packet queue and for the free
     * pool, so the hot path never touches a lock or the allocator.
     */
    static final class EventRing {
        private final AtomicReferenceArray<CaptureEvent> slots;
        private final AtomicLong head = new AtomicLong(0);
        private final AtomicLong tail = new AtomicLong(0);
        private final int mask;
        private final int capacity;

        EventRing(int minCapacity) {
            int cap = 2;
            while (cap < minCapacity) cap <<= 1;   // mask arithmetic needs 2^n
            this.capacity = cap;
            this.mask = cap - 1;
            this.slots = new AtomicReferenceArray<CaptureEvent>(cap);
        }

        boolean offer(CaptureEvent e) {
            for (;;) {
                long t = tail.get();
                if (t - head.get() >= capacity) return false;
                if (tail.compareAndSet(t, t + 1)) {
                    slots.set((int) (t & mask), e);
                    return true;
                }
            }
        }

        CaptureEvent poll() {
            for (;;) {
                long h = head.get();
                if (h >= tail.get()) return null;
                int i = (int) (h & mask);
                CaptureEvent e = slots.get(i);
                // Producer claimed the slot but has not stored yet: report
                // empty rather than spin. Callers treat that as "nothing now".
                if (e == null) return null;
                if (head.compareAndSet(h, h + 1)) {
                    slots.set(i, null);
                    return e;
                }
            }
        }

        int size() {
            long n = tail.get() - head.get();
            return n < 0 ? 0 : (int) n;
        }
    }

    // ---- state ----

    private final File dir;
    private final String modeName;
    private final Gate gate;
    private final int maxRecords;   // 0 = unlimited

    private final EventRing queue = new EventRing(QUEUE_CAPACITY);
    private final EventRing pool = new EventRing(POOL_SIZE + 64);
    private final AtomicInteger poolCreated = new AtomicInteger(0);

    private final AtomicBooleanFlag running = new AtomicBooleanFlag();
    private Thread thread;

    // counters (D9: every discard path is counted)
    private final AtomicLong seqGen = new AtomicLong(0);
    private final AtomicLong offered = new AtomicLong(0);
    private final AtomicLong dropQueueFull = new AtomicLong(0);
    private final AtomicLong dropPoolExhausted = new AtomicLong(0);
    private final AtomicLong dropMode = new AtomicLong(0);
    private final AtomicLong dropCap = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private final AtomicLong written = new AtomicLong(0);
    private final AtomicLong presolverCount = new AtomicLong(0);
    private final AtomicLong[] bySrc = {new AtomicLong(0), new AtomicLong(0), new AtomicLong(0)};
    private final AtomicLong[] byDir = {new AtomicLong(0), new AtomicLong(0)};

    private final List<File> parts = new ArrayList<File>();
    private volatile File primary;

    // writer-thread-only state
    private FileOutputStream fos;
    private BufferedOutputStream out;
    private long bytesInPart;
    private int partIndex;
    private String stamp;
    private long startedMs;
    private long lastFlushMs;
    private long lastSyncMs;
    private long lastTimelineMs;
    private long lastPhaseMs;
    private int phaseCache;
    private int matchCache;
    private long prevNanos;
    private boolean sawParsed;
    private String lastKeyHex;
    private byte[] scratch = new byte[1 << 16];
    private final byte[][] cmdNameCache = new byte[CMD_NAME_CACHE_MAX][];

    private static final byte[] HEX = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    /** Minimal volatile flag; avoids pulling in AtomicBoolean's extra surface. */
    private static final class AtomicBooleanFlag {
        volatile boolean value;
    }

    public CaptureWriter(File dir, String modeName, Gate gate, int maxRecords) {
        this.dir = dir;
        this.modeName = modeName == null ? "full" : modeName;
        this.gate = gate;
        this.maxRecords = maxRecords;
    }

    // ---- hot-path intake helpers (allocation-free once warm) ----

    /**
     * Borrows a pooled event, or creates one while the pool is still growing.
     * Returns null once {@link #POOL_SIZE} events are outstanding — the caller
     * must then count a drop rather than block or allocate without bound.
     */
    public CaptureEvent acquire() {
        CaptureEvent e = pool.poll();
        if (e != null) return e;
        if (poolCreated.get() >= POOL_SIZE) {
            dropPoolExhausted.incrementAndGet();
            return null;
        }
        poolCreated.incrementAndGet();
        return new CaptureEvent();
    }

    /** Returns an event to the pool without writing it. */
    public void recycle(CaptureEvent e) {
        if (e == null) return;
        // A buffer that ballooned for one jumbo packet is not worth keeping
        // pinned for the rest of the session.
        if (e.payload != null && e.payload.length > (1 << 14)) e.payload = null;
        e.reset();
        if (!pool.offer(e)) poolCreated.decrementAndGet();
    }

    /**
     * Hands a filled event to the writer. Never blocks. Returns false when the
     * queue is full, in which case the event has already been recycled and the
     * drop counted.
     */
    public boolean submit(CaptureEvent e) {
        if (e == null) return false;
        e.seq = seqGen.getAndIncrement();
        offered.incrementAndGet();
        if (!queue.offer(e)) {
            dropQueueFull.incrementAndGet();
            recycle(e);
            return false;
        }
        return true;
    }

    // ---- lifecycle ----

    public boolean isRunning() {
        return running.value;
    }

    /** Opens the first part and starts the writer thread. */
    public boolean start() {
        if (running.value) return true;
        running.value = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "CaptureWriter");
        thread.setPriority(Thread.MIN_PRIORITY);
        // Daemon: the writer must never be the reason a process stays alive. It
        // exits on stop(), and on Android the process is killed outright anyway.
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /**
     * Stops the writer: the thread finishes the queue, writes the footer,
     * flushes and closes. Joined with a bounded wait so a wedged file system
     * cannot hang the service.
     */
    public void stop() {
        if (!running.value) return;
        running.value = false;
        Thread t = thread;
        if (t != null) {
            try {
                // 3 s, matching the join the old implementation used: start()
                // can be reached from the service's main thread, and a longer
                // wait there would be an ANR. The writer drains at ~259 000
                // records/s (measured), so this is never actually hit.
                t.join(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /** The first part file, i.e. the one to hand to the UI / Prefs. */
    public File primaryFile() {
        return primary;
    }

    // ---- counters ----

    public long writtenCount() { return written.get(); }

    public long droppedTotal() {
        return dropQueueFull.get() + dropPoolExhausted.get()
                + dropMode.get() + dropCap.get() + writeErrors.get();
    }

    // ---- writer thread ----

    private void loop() {
        startedMs = System.currentTimeMillis();
        stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startedMs));
        lastFlushMs = startedMs;
        lastSyncMs = startedMs;
        lastTimelineMs = startedMs;
        if (!openPart()) {
            // Nothing to write to; still drain so the hot path never backs up.
            drainAndDiscard();
            return;
        }
        while (running.value || queue.size() > 0) {
            CaptureEvent e = queue.poll();
            long now = System.currentTimeMillis();
            if (e == null) {
                pollTimeline(now);
                // Health block, on the writer thread and only when the queue is
                // momentarily empty -- so it never delays a real record.
                if (diagRequested) {
                    diagRequested = false;
                    writeDiag();
                }
                maybeFlush(now, true);
                if (!running.value) break;
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                process(e, now);
            } catch (Throwable t) {
                // A single bad record must never kill the capture.
                writeErrors.incrementAndGet();
            } finally {
                recycle(e);
            }
            if ((written.get() & 63L) == 0L) pollTimeline(now);
            maybeFlush(now, false);
        }
        writeFooter();
        closePart();
    }

    /** Last-resort path when the file could not be opened at all. */
    private void drainAndDiscard() {
        while (running.value || queue.size() > 0) {
            CaptureEvent e = queue.poll();
            if (e == null) {
                if (!running.value) return;
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            writeErrors.incrementAndGet();
            recycle(e);
        }
    }

    private void process(CaptureEvent e, long now) {
        // 1. Annotation (AGENT 2): fills cmd / orderId / flags / note.
        try {
            CaptureAnnotator.annotate(e);
        } catch (Throwable t) {
            // Contract says it never throws; if it does, the record still gets
            // written with whatever the hot path knew.
        }

        // 2. Pre-solver marking (brief constraint 5). Two independent sources,
        //    both honest: the hot path stamps F_PRESOLVER when the session
        //    layout was known-unsolved, and here we mark every UDP record that
        //    arrives before the first successfully parsed one. After the first
        //    parse an unparsed packet is "unparsed", not "pre-solver", and is
        //    left alone.
        if (e.src == CaptureEvent.SRC_UDP) {
            if ((e.flags & CaptureEvent.F_PARSED) != 0) {
                sawParsed = true;
                // A parsed record is by definition not a pre-solver one, even
                // if the hot path stamped it before the solver committed.
                e.flags &= ~CaptureEvent.F_PRESOLVER;
            } else if (!sawParsed) {
                e.flags |= CaptureEvent.F_PRESOLVER;
            }
        }
        if ((e.flags & CaptureEvent.F_PRESOLVER) != 0) presolverCount.incrementAndGet();

        // 3. Lifecycle context (AGENT 3). Sampled on a short interval rather
        //    than per record: SessionTimeline.phaseId() runs a tick() on every
        //    call, and phase transitions happen on the scale of seconds, so a
        //    20 ms sample is ~50x finer than anything it can report while
        //    keeping the writer's per-record cost flat.
        if (now - lastPhaseMs >= PHASE_SAMPLE_MS) {
            lastPhaseMs = now;
            try {
                phaseCache = com.nepdon.wire.protocol.SessionTimeline.phaseId();
                matchCache = com.nepdon.wire.protocol.SessionTimeline.matchSeq();
            } catch (Throwable t) {
                phaseCache = 0;
                matchCache = 0;
            }
        }
        e.phaseId = phaseCache;
        e.matchSeq = matchCache;

        // 4. Mode filter, on the REAL command id (D5's capture half) — off the
        //    hot path entirely, and counted when it discards (D9).
        if (gate != null && !gate.keep(e)) {
            dropMode.incrementAndGet();
            return;
        }

        if (maxRecords > 0 && written.get() >= maxRecords) {
            dropCap.incrementAndGet();
            return;
        }

        // 5. Rotation before the record, so a record never straddles parts.
        if (bytesInPart >= ROTATE_BYTES) {
            closePart();
            partIndex++;
            if (!openPart()) {
                writeErrors.incrementAndGet();
                return;
            }
        }

        int n = encode(e);
        try {
            out.write(scratch, 0, n);
            bytesInPart += n;
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
            return;
        }
        written.incrementAndGet();
        if (e.src >= 0 && e.src < 3) bySrc[e.src].incrementAndGet();
        if (e.dir >= 0 && e.dir < 2) byDir[e.dir].incrementAndGet();
        if (gate != null) {
            try {
                gate.onWritten(e);
            } catch (Throwable t) {
                // the mirror is best-effort; never let it break the stream
            }
        }
    }

    // ---- line encoding (D10: bytes, never Strings) ----

    /**
     * Encodes one record into {@link #scratch} and returns its length.
     *
     * <p>Columns, positional and fixed up to {@code note} (brief §4.4):</p>
     * <pre>
     * seq ts_ms dt_us src dir decision phase match cmd cmdname flags peer sp dp len hexlen payload_hex note
     * </pre>
     *
     * <p>{@code payload_hex} and {@code note} are written as {@code -} when
     * empty, so the column count never varies. {@code phase} carries the
     * lifecycle phase and, when any effect was live, a {@code /}-separated
     * effect list ({@code MATCH/FREEZE|GHOST}) — one whitespace-free token, so
     * positional parsing is unaffected.</p>
     */
    private int encode(CaptureEvent e) {
        // 512 bytes of slack covers every fixed column plus the longest
        // command name in the catalogue with room to spare.
        int need = 512 + (e.copyLen * 2) + (e.note == null ? 0 : e.note.length())
                + (e.ctrlKind == null ? 0 : e.ctrlKind.length());
        if (scratch.length < need) {
            int cap = scratch.length;
            while (cap < need) cap <<= 1;
            scratch = new byte[cap];
        }
        byte[] b = scratch;
        int p = 0;

        p = putLong(b, p, e.seq);              b[p++] = ' ';
        p = putLong(b, p, e.tsMs);             b[p++] = ' ';
        long dtUs = 0L;
        if (prevNanos != 0L && e.tsNanos != 0L) {
            long d = e.tsNanos - prevNanos;
            dtUs = d < 0 ? 0 : d / 1000L;
        }
        if (e.tsNanos != 0L) prevNanos = e.tsNanos;
        p = putLong(b, p, dtUs);               b[p++] = ' ';
        p = putAscii(b, p, CaptureEvent.srcName(e.src));      b[p++] = ' ';
        p = putAscii(b, p, CaptureEvent.dirName(e.dir));      b[p++] = ' ';
        p = putAscii(b, p, CaptureEvent.decisionName(e.decision)); b[p++] = ' ';
        p = putPhase(b, p, e);                 b[p++] = ' ';
        p = putLong(b, p, e.matchSeq);         b[p++] = ' ';
        p = putLong(b, p, e.cmd);              b[p++] = ' ';
        p = putCmdName(b, p, e.cmd);           b[p++] = ' ';
        p = putHexInt(b, p, e.flags);          b[p++] = ' ';
        p = putIp(b, p, e.peerIp);             b[p++] = ' ';
        p = putLong(b, p, e.sp);               b[p++] = ' ';
        p = putLong(b, p, e.dp);               b[p++] = ' ';
        p = putLong(b, p, e.len);              b[p++] = ' ';
        p = putLong(b, p, e.copyLen);          b[p++] = ' ';

        if (e.copyLen <= 0 || e.payload == null) {
            b[p++] = '-';
        } else {
            int max = Math.min(e.copyLen, e.payload.length);
            for (int i = 0; i < max; i++) {
                int v = e.payload[i] & 0xff;
                b[p++] = HEX[v >>> 4];
                b[p++] = HEX[v & 0x0f];
            }
        }
        b[p++] = ' ';

        int noteStart = p;
        if (e.ctrlKind != null && e.ctrlKind.length() > 0) {
            p = putAscii(b, p, "kind=");
            p = putNote(b, p, e.ctrlKind);
        }
        if (e.teleType != 0) {
            if (p > noteStart) b[p++] = ' ';
            p = putAscii(b, p, "tele=");
            p = putAscii(b, p, com.nepdon.wire.filter.TelePacket.typeName(e.teleType));
        }
        if (e.orderId >= 0) {
            if (p > noteStart) b[p++] = ' ';
            p = putAscii(b, p, "ord=");
            p = putLong(b, p, e.orderId);
        }
        if (e.note != null && e.note.length() > 0) {
            if (p > noteStart) b[p++] = ' ';
            p = putNote(b, p, e.note);
        }
        if (p == noteStart) b[p++] = '-';
        b[p++] = '\n';
        return p;
    }

    private int putPhase(byte[] b, int p, CaptureEvent e) {
        int id = e.phaseId;
        p = putAscii(b, p, (id >= 0 && id < PHASE_NAMES.length) ? PHASE_NAMES[id] : "UNKNOWN");
        int m = e.filterMask;
        if (m != 0) {
            b[p++] = '/';
            boolean any = false;
            if ((m & CaptureEvent.FX_FREEZE) != 0) { p = putAscii(b, p, "FREEZE"); any = true; }
            if ((m & CaptureEvent.FX_GHOST) != 0) {
                if (any) b[p++] = '|';
                p = putAscii(b, p, "GHOST");
                any = true;
            }
            if ((m & CaptureEvent.FX_JITTER) != 0) {
                if (any) b[p++] = '|';
                p = putAscii(b, p, "JITTER");
                any = true;
            }
            if (!any) p = putHexInt(b, p, m);
        }
        return p;
    }

    /**
     * Writes {@code CmdCatalogue.nameOr(cmd)} as bytes. The encoded name is
     * cached per id so the steady-state path does no map lookup, no boxing and
     * no string building.
     */
    private int putCmdName(byte[] b, int p, int cmd) {
        if (cmd < 0) {
            b[p++] = '-';
            return p;
        }
        byte[] cached = null;
        if (cmd < CMD_NAME_CACHE_MAX) cached = cmdNameCache[cmd];
        if (cached == null) {
            String n;
            try {
                n = com.nepdon.wire.decode.body.CmdCatalogue.nameOr(cmd);
            } catch (Throwable t) {
                n = "CMD_" + cmd;
            }
            cached = asciiBytes(n);
            if (cmd < CMD_NAME_CACHE_MAX) cmdNameCache[cmd] = cached;
        }
        System.arraycopy(cached, 0, b, p, cached.length);
        return p + cached.length;
    }

    private static byte[] asciiBytes(String s) {
        int n = s.length();
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            out[i] = (c < 0x20 || c > 0x7e || c == ' ') ? (byte) '_' : (byte) c;
        }
        return out;
    }

    private static int putAscii(byte[] b, int p, String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) b[p++] = (byte) s.charAt(i);
        return p;
    }

    /** Note text: spaces survive (note is last), control chars do not. */
    private static int putNote(byte[] b, int p, String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b[p++] = (c < 0x20 || c > 0x7e) ? (byte) '.' : (byte) c;
        }
        return p;
    }

    private static int putLong(byte[] b, int p, long v) {
        if (v < 0) {
            b[p++] = '-';
            v = -v;
        }
        if (v == 0) {
            b[p++] = '0';
            return p;
        }
        int start = p;
        while (v > 0) {
            b[p++] = (byte) ('0' + (int) (v % 10));
            v /= 10;
        }
        for (int i = start, j = p - 1; i < j; i++, j--) {
            byte t = b[i];
            b[i] = b[j];
            b[j] = t;
        }
        return p;
    }

    private static int putHexInt(byte[] b, int p, int v) {
        if (v == 0) {
            b[p++] = '0';
            return p;
        }
        int shift = 28;
        while (shift > 0 && ((v >>> shift) & 0xf) == 0) shift -= 4;
        while (shift >= 0) {
            b[p++] = HEX[(v >>> shift) & 0xf];
            shift -= 4;
        }
        return p;
    }

    private static int putIp(byte[] b, int p, int ip) {
        p = putLong(b, p, (ip >> 24) & 0xff);
        b[p++] = '.';
        p = putLong(b, p, (ip >> 16) & 0xff);
        b[p++] = '.';
        p = putLong(b, p, (ip >> 8) & 0xff);
        b[p++] = '.';
        p = putLong(b, p, ip & 0xff);
        return p;
    }

    // ---- file handling ----

    private boolean openPart() {
        try {
            if (!dir.exists()) dir.mkdirs();
            String name = partIndex == 0
                    ? ("capture_raw_" + stamp + ".txt")
                    : ("capture_raw_" + stamp + "_" + pad3(partIndex) + ".txt");
            File f = new File(dir, name);
            fos = new FileOutputStream(f);
            out = new BufferedOutputStream(fos, STREAM_BUFFER);
            bytesInPart = 0L;
            synchronized (parts) {
                parts.add(f);
            }
            if (primary == null) primary = f;
            writeHeader();
            return true;
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
            out = null;
            fos = null;
            return false;
        }
    }

    private static String pad3(int n) {
        if (n < 10) return "00" + n;
        if (n < 100) return "0" + n;
        return String.valueOf(n);
    }

    private void writeHeader() {
        comment("SunnyMod packet capture v2 (streaming)");
        comment("started=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(startedMs)));
        comment("part=" + partIndex + " mode=" + modeName);
        // The UDP cipher key, recovered from the lobby TCP downlink. Without it
        // an encrypted capture is undecodable, and on a non-rooted phone there
        // is no other way to obtain it. Repeated in every part so each part is
        // independently decodable (brief constraint 6).
        lastKeyHex = safeKeyHex();
        comment("secret_key=" + (lastKeyHex == null ? "UNKNOWN" : lastKeyHex));
        comment("key_endpoint=" + safeKeyEndpoint());
        comment("columns: seq ts_ms dt_us src dir decision phase match cmd cmdname flags"
                + " peer sp dp len hexlen payload_hex note");
        comment("phase column is TIMELINE_PHASE[/EFFECT|EFFECT], effects are FREEZE GHOST JITTER");
        comment("payload_hex and note are '-' when empty; note is free text and always last");
        comment("flags: 01=PARSED 02=CRC_OK 04=RELIABLE 08=ENCRYPTED 10=DECRYPTED"
                + " 20=ZIPPED 40=LEN_MATCH 80=PRESOLVER");
    }

    private void emitKeyLineIfChanged() {
        String k = safeKeyHex();
        if (k == null) return;
        if (lastKeyHex != null && lastKeyHex.equals(k)) return;
        lastKeyHex = k;
        comment("secret_key=" + k);
        comment("key_endpoint=" + safeKeyEndpoint());
    }

    private static String safeKeyHex() {
        try {
            return com.nepdon.wire.protocol.LobbyKeySniffer.lastKeyHex();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeKeyEndpoint() {
        try {
            return String.valueOf(com.nepdon.wire.protocol.LobbyKeySniffer.lastEndpoint());
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }

    /**
     * Emits the module-health block.
     *
     * <p>This is the half of the dump the packet records cannot provide. A
     * capture says what arrived; these lines say what the code then DECIDED
     * about it -- which guard bailed, which classifier rung fired, which
     * precondition refused. Every long-running bug in this project was a
     * decision, not a packet, and each one cost an evening of replaying logic
     * offline against a capture to recover exactly this information.</p>
     *
     * <p>Written periodically AND in the footer, so a dump that ends abruptly
     * still carries the last window rather than nothing.</p>
     */
    /**
     * Requests a health block on the next writer-thread wakeup.
     *
     * <p>A flag, not a direct write: this file has exactly one writer thread
     * and keeping it that way is why the capture has never interleaved a
     * half-written record. The stats thread asks; the writer decides when.</p>
     */
    public void requestDiagBlock() {
        diagRequested = true;
    }

    private volatile boolean diagRequested = false;

    private void writeDiag() {
        try {
            String block = com.nepdon.wire.diag.Diag.snapshot();
            if (block == null || block.isEmpty()) return;
            // The block is already '#'-prefixed per line; write it raw rather
            // than through comment(), which would add a second '#'.
            int start = 0;
            while (start < block.length()) {
                int nl = block.indexOf(10, start);
                if (nl < 0) nl = block.length();
                rawLine(block.substring(start, nl));
                start = nl + 1;
            }
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
        }
    }

    /** Writes one already-formatted line verbatim, plus a newline. */
    private void rawLine(String s) {
        if (out == null || s.isEmpty()) return;
        try {
            int n = s.length();
            if (scratch.length < n + 4) scratch = new byte[n + 64];
            int p = putNote(scratch, 0, s);
            scratch[p++] = (byte) 10;
            out.write(scratch, 0, p);
            bytesInPart += p;
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
        }
    }

    private void writeFooter() {
        comment("---- footer ----");
        writeDiag();
        comment("records_written=" + written.get() + " offered=" + offered.get());
        comment("by_src udp=" + bySrc[0].get() + " tcp=" + bySrc[1].get()
                + " ctrl=" + bySrc[2].get());
        comment("by_dir out=" + byDir[0].get() + " in=" + byDir[1].get());
        comment("drops queue_full=" + dropQueueFull.get()
                + " pool_exhausted=" + dropPoolExhausted.get()
                + " mode_filtered=" + dropMode.get()
                + " cap_exceeded=" + dropCap.get()
                + " write_error=" + writeErrors.get()
                + " total=" + droppedTotal());
        comment("presolver_records=" + presolverCount.get());
        String cov;
        try {
            cov = CaptureAnnotator.coverageLine();
        } catch (Throwable t) {
            cov = "coverage=UNAVAILABLE";
        }
        comment("coverage " + (cov == null ? "UNAVAILABLE" : cov));
        comment("parts=" + parts.size());
        comment("ended=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(System.currentTimeMillis())));
    }

    /** Writes one {@code #} line. Only used off the per-record path. */
    private void comment(String s) {
        if (out == null) return;
        try {
            int n = s.length();
            if (scratch.length < n + 4) scratch = new byte[n + 64];
            int p = 0;
            scratch[p++] = '#';
            scratch[p++] = ' ';
            p = putNote(scratch, p, s);
            scratch[p++] = '\n';
            out.write(scratch, 0, p);
            bytesInPart += p;
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
        }
    }

    private void pollTimeline(long now) {
        if (now - lastTimelineMs < TIMELINE_INTERVAL_MS) return;
        lastTimelineMs = now;
        emitKeyLineIfChanged();
        List<String> events;
        try {
            events = com.nepdon.wire.protocol.SessionTimeline.drainEvents();
        } catch (Throwable t) {
            return;
        }
        if (events == null || events.isEmpty()) return;
        for (int i = 0; i < events.size(); i++) {
            String s = events.get(i);
            if (s != null) comment("EV " + s);
        }
    }

    private void maybeFlush(long now, boolean idle) {
        if (out == null) return;
        boolean due = idle || (now - lastFlushMs) >= FLUSH_INTERVAL_MS;
        if (!due) return;
        lastFlushMs = now;
        try {
            out.flush();
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
        }
        if ((now - lastSyncMs) >= SYNC_INTERVAL_MS) {
            lastSyncMs = now;
            try {
                if (fos != null) fos.getFD().sync();
            } catch (Throwable t) {
                // sync is best-effort; a flushed stream already survives a
                // process kill, sync only adds reboot durability
            }
        }
    }

    private void closePart() {
        if (out == null) return;
        try {
            out.flush();
        } catch (Throwable t) {
            writeErrors.incrementAndGet();
        }
        try {
            fos.getFD().sync();
        } catch (Throwable t) {
            // best effort
        }
        try {
            out.close();
        } catch (Throwable t) {
            // best effort
        }
        out = null;
        fos = null;
    }
}
