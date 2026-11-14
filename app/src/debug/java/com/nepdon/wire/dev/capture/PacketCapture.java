package com.nepdon.wire.dev.capture;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records everything the VPN sees — UDP both directions, lobby TCP, and
 * control/lifecycle events — and streams it to disk.
 *
 * <h3>Hot-path policy (brief §3 constraints 1 and 2)</h3>
 * The tun read loop and the UDP recv threads do exactly this per packet:
 * append to the retro ring (two atomic adds + one array copy), borrow a pooled
 * {@link CaptureEvent}, copy the payload into its reusable buffer, stamp
 * primitive fields, and hand it to a lock-free bounded queue. No locks, no
 * formatting, no classification, no file I/O, and — once the pool has warmed —
 * <b>zero allocations per record</b>. Everything else runs on
 * {@link CaptureWriter}'s single writer thread.
 *
 * <h3>What changed and why</h3>
 * <ul>
 *   <li><b>D2</b> — {@code setFreezePhase()} is gone. It had zero callers, so it
 *       was dead weight pretending to be a second source of truth. Phase now
 *       comes solely from {@link #setFilterMask(int)}, which <i>is</i> wired:
 *       {@code VpnEngine.startCapture}, {@code VpnEngine.noteFilterToggled} and
 *       {@code VpnEngine.disableFilters} all push the live effect mask, so the
 *       field genuinely varies with reality. The capture file additionally
 *       carries the {@code SessionTimeline} phase per record.</li>
 *   <li><b>D3</b> — the payload copy bound is a whole datagram
 *       ({@value #MAX_CAPTURE_PAYLOAD_BYTES}), not 2 048, and the write path has
 *       no hex limit at all.</li>
 *   <li><b>D4</b> — records stream to disk continuously instead of piling up in
 *       a 220 000-entry list until stop.</li>
 *   <li><b>D5 (capture half)</b> — mode filtering runs on the writer thread
 *       against the real command id from {@link CaptureAnnotator}, not against
 *       {@code GamePacketTaxonomy} byte-shape heuristics on the hot path.</li>
 *   <li><b>D9</b> — queue-full, pool-exhausted, mode-filtered, cap-exceeded,
 *       mirror-full and write-error discards are each counted and reported.</li>
 * </ul>
 *
 * <h3>The legacy in-memory mirror</h3>
 * {@code VpnEngine.stopCapture} still feeds {@code FreezeDiffAnalyzer} and
 * {@code DecodedDumper} from {@link #snapshot()}. That mirror is retained but is
 * now <b>bounded by bytes as well as by count</b> and is no longer the system of
 * record — the streamed file is. Set {@link #setMirrorEnabled(boolean)} to false
 * once those two analyzers move to reading the file.
 */
public final class PacketCapture {

    /**
     * Legacy constant: the default "hex limit" the UI passes in. Truncation is
     * gone from the write path (D3), so this now only survives as the intent
     * default that {@code PacketVpnService} references.
     */
    public static final int MAX_PAYLOAD_BYTES = 2048;

    /** Real capture-time copy bound: one whole datagram, nothing clipped. */
    public static final int MAX_CAPTURE_PAYLOAD_BYTES = 65535;

    /** Records kept in the in-memory mirror for the legacy analyzers. */
    public static final int MAX_RECORDS = 120000;

    /** Payload bytes kept by the in-memory mirror before it stops growing. */
    public static final int MIRROR_PAYLOAD_BUDGET = 24 << 20;

    /** Retained for callers; the streamed file has no line cap (it rotates). */
    public static final int MAX_RAW_LINES = 220000;

    // ---- Capture modes (what a pressed capture keeps) ----
    public static final int MODE_FULL = 0;    // everything
    public static final int MODE_MATCH = 1;   // only tracked-match-endpoint traffic
    public static final int MODE_COMBAT = 2;  // fire/hit/movement + ±2 s windows
    public static final int MODE_EVENTS = 3;  // only identified packets

    public static final String MODE_FULL_NAME = "full";
    public static final String MODE_MATCH_NAME = "match";
    public static final String MODE_COMBAT_NAME = "combat";
    public static final String MODE_EVENTS_NAME = "events";

    public static int modeFromName(String name, int fallback) {
        if (MODE_MATCH_NAME.equals(name)) return MODE_MATCH;
        if (MODE_COMBAT_NAME.equals(name)) return MODE_COMBAT;
        if (MODE_EVENTS_NAME.equals(name)) return MODE_EVENTS;
        if (MODE_FULL_NAME.equals(name)) return MODE_FULL;
        return fallback;
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_MATCH: return MODE_MATCH_NAME;
            case MODE_COMBAT: return MODE_COMBAT_NAME;
            case MODE_EVENTS: return MODE_EVENTS_NAME;
            default: return MODE_FULL_NAME;
        }
    }

    private final Context context;

    /** Always-rolling retro ring, fed on every record() call, capture on or off. */
    private final CaptureRing ring = new CaptureRing();

    private final AtomicInteger filterMask = new AtomicInteger(0);
    private final AtomicLong mirrorDropped = new AtomicLong(0);

    private volatile boolean running = false;
    private volatile CaptureWriter writer;
    private volatile File lastPrimaryFile;
    private volatile int hexLimit = MAX_PAYLOAD_BYTES;
    private volatile int mode = MODE_FULL;
    private volatile boolean mirrorEnabled = true;
    /**
     * Whether the UDP header layout is currently solved. Fed from the transport
     * (see the report's call-site list); until something calls it, the writer's
     * own "nothing has parsed yet" rule still marks the pre-solver window.
     */
    private volatile boolean layoutSolved = false;
    /**
     * True once anything has actually reported the layout state. Until then the
     * hot path stamps nothing and the writer's "nothing has parsed yet" rule is
     * the only pre-solver marker — stamping every record would be a lie, not a
     * conservative default.
     */
    private volatile boolean layoutStateKnown = false;

    /** Timestamp of the last combat-classified packet (COMBAT window gate). */
    private volatile long lastCombatTs = 0L;

    private final ArrayList<CaptureRecord> records = new ArrayList<CaptureRecord>();
    private int mirrorBytes = 0;

    /**
     * Writer-thread hooks: the real-cmd mode gate and the legacy mirror. Both
     * run on {@link CaptureWriter}'s thread only.
     */
    private final CaptureWriter.Gate gate = new CaptureWriter.Gate() {
        @Override
        public boolean keep(CaptureEvent e) {
            return modeKeeps(e);
        }

        @Override
        public void onWritten(CaptureEvent e) {
            mirror(e);
        }
    };

    public PacketCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    // ---- Lifecycle ----

    /**
     * Starts capturing (idempotent-safe: a running capture is stopped and
     * restarted with the new settings, pinning the retro slice).
     *
     * @param hexLimit historical; ignored for truncation (D3), recorded only
     * @param mode     what to keep (see the MODE_ constants)
     * @param retroSec seconds of pre-press history pulled from the ring
     */
    public void start(int hexLimit, int mode, int retroSec) {
        stop();
        this.hexLimit = hexLimit;
        this.mode = mode;
        this.lastCombatTs = 0L;
        this.mirrorDropped.set(0);
        synchronized (records) {
            records.clear();
            mirrorBytes = 0;
        }
        CaptureWriter w = new CaptureWriter(captureDir(), modeName(mode), gate, 0);
        w.start();
        writer = w;   // replaces the previous session's writer (already stopped)
        lastPrimaryFile = null;
        running = true;
        if (retroSec > 0) replayRetro(w, System.currentTimeMillis() - retroSec * 1000L);
    }

    /**
     * Stops capturing; the writer drains, writes its footer, flushes and closes.
     *
     * <p>The writer reference is deliberately KEPT after the stop so the drop
     * counters and the file list stay readable — {@code VpnEngine.stopCapture}
     * reads {@link #droppedByCapacity()} after calling this. A new
     * {@link #start} replaces it.</p>
     */
    public void stop() {
        running = false;
        CaptureWriter w = writer;
        if (w != null && w.isRunning()) {
            w.stop();
            lastPrimaryFile = w.primaryFile();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Records the full effect mask (freeze/ghost/jitter bits) so every captured
     * packet carries exactly which effects were live when it was seen. This is
     * the ONLY source of phase — see D2 in the class doc.
     */
    public void setFilterMask(int mask) {
        filterMask.set(mask);
    }

    /**
     * Tells the capture whether the UDP header layout is currently solved, so
     * records taken in the pre-solver window (and after a key rotation) are
     * marked {@code F_PRESOLVER} instead of being guessed at (constraint 5).
     */
    public void setLayoutSolved(boolean solved) {
        layoutSolved = solved;
        layoutStateKnown = true;
    }

    // ---- Hot-path capture API (called from the tun loop / recv threads) ----

    /** Records one outbound UDP packet + the engine's decision for it. */
    public void recordOutbound(int plen, int dip, int sp, int dp, int teleType,
                               CaptureRecord.Decision decision, byte[] buf, int off) {
        record(CaptureEvent.SRC_UDP, CaptureEvent.OUT, plen, dip, sp, dp, teleType,
                toDecision(decision), buf, off);
    }

    /** Records one outbound UDP packet with an explicit decision code. */
    public void recordOutbound(int plen, int dip, int sp, int dp, int teleType,
                               int decision, byte[] buf, int off) {
        record(CaptureEvent.SRC_UDP, CaptureEvent.OUT, plen, dip, sp, dp, teleType,
                decision, buf, off);
    }

    /** Records one inbound UDP packet + the engine's decision for it. */
    public void recordInbound(int plen, int dip, int sp, int dp,
                              CaptureRecord.Decision decision, byte[] buf, int off) {
        record(CaptureEvent.SRC_UDP, CaptureEvent.IN, plen, dip, sp, dp, 0,
                toDecision(decision), buf, off);
    }

    /** Records one inbound UDP packet with an explicit decision code. */
    public void recordInbound(int plen, int dip, int sp, int dp,
                              int decision, byte[] buf, int off) {
        record(CaptureEvent.SRC_UDP, CaptureEvent.IN, plen, dip, sp, dp, 0,
                decision, buf, off);
    }

    /** Records a control/lifecycle event (no payload). */
    public void recordControl(String kind, String note) {
        recordControl(kind, note, null, 0, 0, 0, 0, 0, true);
    }

    /**
     * Records a control event, optionally carrying the payload that triggered
     * it. This is the intake {@code DevLoggerImpl} uses so it no longer needs a
     * parallel logging system of its own (D8).
     */
    public void recordControl(String kind, String note, byte[] buf, int off, int len,
                              int peerIp, int sp, int dp, boolean outbound) {
        if (!running) return;
        CaptureWriter w = writer;
        if (w == null) return;
        CaptureEvent e = w.acquire();
        if (e == null) return;
        fill(e, CaptureEvent.SRC_CTRL, outbound ? CaptureEvent.OUT : CaptureEvent.IN,
                len, peerIp, sp, dp, 0, CaptureEvent.PASS, buf, off,
                System.currentTimeMillis());
        e.ctrlKind = kind;
        e.note = note;
        w.submit(e);
    }

    /**
     * The one hot-path intake. Ring first (it is the retro buffer and records
     * with the capture off), then, if capturing, one pooled event into the
     * queue. Allocation-free in the steady state.
     */
    /**
     * Asks the writer to emit a module-health block now.
     *
     * <p>Periodic rather than per-record: the health of a subsystem changes on
     * the scale of seconds, and a per-packet emission would bury the packets
     * the file exists for.</p>
     */
    public void writeDiagBlock() {
        CaptureWriter w = writer;
        if (w == null || !running) return;
        w.requestDiagBlock();
    }

    private void record(int src, int dir, int len, int peerIp, int sp, int dp,
                        int teleType, int decision, byte[] buf, int off) {
        if (len <= 0) return;
        long now = System.currentTimeMillis();
        int mask = filterMask.get();
        // The ring fed BEFORE this check, so it ran on every packet even with
        // capture switched off -- a full payload copy per packet into a
        // permanently-resident ~10.6 MB buffer, for a retro-dump feature that
        // is only ever read after the user asks for one.
        //
        // Behaviour note: a dump taken immediately after enabling capture no
        // longer reaches back before the enable. That is the intended trade --
        // the retro window is worth nothing against a per-packet cost paid for
        // the whole session.
        if (!running) return;
        ring.add(now, src, dir, len, peerIp, sp, dp, decision, mask, buf, off);
        CaptureWriter w = writer;
        if (w == null) return;
        CaptureEvent e = w.acquire();
        if (e == null) return;   // pool exhausted; already counted by the writer
        fill(e, src, dir, len, peerIp, sp, dp, teleType, decision, buf, off, now);
        w.submit(e);
    }

    /** Fills a pooled event. No allocation once its payload buffer is big enough. */
    private void fill(CaptureEvent e, int src, int dir, int len, int peerIp, int sp, int dp,
                      int teleType, int decision, byte[] buf, int off, long now) {
        int copyLen = 0;
        if (buf != null && len > 0) {
            copyLen = len > MAX_CAPTURE_PAYLOAD_BYTES ? MAX_CAPTURE_PAYLOAD_BYTES : len;
            if (off + copyLen > buf.length) copyLen = Math.max(0, buf.length - off);
        }
        e.tsMs = now;
        e.tsNanos = System.nanoTime();
        e.src = src;
        e.dir = dir;
        e.peerIp = peerIp;
        e.sp = sp;
        e.dp = dp;
        e.len = len;
        e.teleType = teleType;
        e.decision = decision;
        e.filterMask = filterMask.get();
        e.cmd = -1;
        e.orderId = -1;
        e.flags = (layoutStateKnown && !layoutSolved) ? CaptureEvent.F_PRESOLVER : 0;
        e.note = null;
        e.ctrlKind = null;
        if (copyLen > 0) {
            e.ensureCapacity(copyLen);
            System.arraycopy(buf, off, e.payload, 0, copyLen);
        }
        e.copyLen = copyLen;
    }

    private static int toDecision(CaptureRecord.Decision d) {
        if (d == CaptureRecord.Decision.DROP) return CaptureEvent.DROP;
        if (d == CaptureRecord.Decision.HOLD) return CaptureEvent.HOLD;
        return CaptureEvent.PASS;
    }

    private static CaptureRecord.Decision toRecordDecision(int d) {
        if (d == CaptureEvent.DROP) return CaptureRecord.Decision.DROP;
        if (d == CaptureEvent.HOLD) return CaptureRecord.Decision.HOLD;
        return CaptureRecord.Decision.PASS;
    }

    // ---- Writer-thread mode gate (D5's capture half) ----

    /**
     * Mode predicate, run on the WRITER thread after annotation, so it can use
     * the real command id instead of byte-shape heuristics — and so none of this
     * work happens on a latency-critical thread.
     *
     * <p>Falls back to {@code GamePacketTaxonomy} only when the command id is
     * unavailable (pre-solver window), which is exactly the fallback brief §3
     * constraint 4 permits.</p>
     */
    @SuppressWarnings("deprecation")   // byte-shape fallback, pre-solver only
    private boolean modeKeeps(CaptureEvent e) {
        int m = mode;
        if (m == MODE_FULL) return true;
        // Control and lobby events are lifecycle context; every mode keeps them.
        if (e.src != CaptureEvent.SRC_UDP) return true;
        switch (m) {
            case MODE_MATCH:
                return com.nepdon.wire.protocol.GamePacketTaxonomy.isFromMatchServer(e.peerIp, e.sp)
                        || com.nepdon.wire.protocol.GamePacketTaxonomy.isFromMatchServer(e.peerIp, e.dp);
            case MODE_COMBAT: {
                int cmd = e.cmd;
                if (cmd >= 0) {
                    if (com.nepdon.wire.decode.body.Cmd.isCombatFeedback(cmd)
                            || com.nepdon.wire.decode.body.Cmd.isOwnPositionCarrier(cmd)
                            || com.nepdon.wire.decode.body.Cmd.isMountedPositionCarrier(cmd)) {
                        lastCombatTs = e.tsMs;
                        return true;
                    }
                    if (com.nepdon.wire.decode.body.Cmd.isWorldStateStream(cmd)
                            || com.nepdon.wire.decode.body.Cmd.isEntityStream(cmd)) {
                        return (e.tsMs - lastCombatTs) <= 2000L;
                    }
                    return false;
                }
                return combatByShape(e);
            }
            case MODE_EVENTS:
                if (e.cmd >= 0) return true;
                return com.nepdon.wire.protocol.GamePacketTaxonomy.kind(
                        e.payload, 0, e.copyLen, e.dir == CaptureEvent.OUT)
                        != com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_UNKNOWN;
            default:
                return true;
        }
    }

    /** Pre-solver fallback for COMBAT mode: byte shape, and only then. */
    @SuppressWarnings("deprecation")   // byte-shape fallback, pre-solver only
    private boolean combatByShape(CaptureEvent e) {
        int kind = com.nepdon.wire.protocol.GamePacketTaxonomy.kind(
                e.payload, 0, e.copyLen, e.dir == CaptureEvent.OUT);
        if (kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_FIRE
                || kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_HIT_CONFIRM
                || kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_MOVEMENT) {
            lastCombatTs = e.tsMs;
            return true;
        }
        if (kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_ENEMY_POSITION
                || kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_PLAYER_STATE
                || kind == com.nepdon.wire.protocol.GamePacketTaxonomy.KIND_WORLD_STATE) {
            return (e.tsMs - lastCombatTs) <= 2000L;
        }
        return false;
    }

    // ---- Legacy in-memory mirror (writer thread only) ----

    @SuppressWarnings("deprecation")   // CaptureRecord's legacy taxonomy fields
    private void mirror(CaptureEvent e) {
        if (!mirrorEnabled) return;
        if (e.src == CaptureEvent.SRC_CTRL) return;
        synchronized (records) {
            if (records.size() >= MAX_RECORDS || mirrorBytes >= MIRROR_PAYLOAD_BUDGET) {
                mirrorDropped.incrementAndGet();
                return;
            }
            mirrorBytes += e.copyLen;
        }
        byte[] payload = new byte[e.copyLen];
        if (e.copyLen > 0) System.arraycopy(e.payload, 0, payload, 0, e.copyLen);
        boolean outbound = e.dir == CaptureEvent.OUT;
        CaptureRecord rec = new CaptureRecord(
                e.tsMs,
                outbound ? CaptureRecord.Dir.OUT : CaptureRecord.Dir.IN,
                (e.filterMask & CaptureRecord.F_FREEZE) != 0
                        ? CaptureRecord.Phase.FREEZE : CaptureRecord.Phase.BEFORE,
                toRecordDecision(e.decision),
                e.len, e.peerIp, e.sp, e.dp, e.teleType,
                com.nepdon.wire.protocol.GamePacketTaxonomy.family(payload, 0, e.copyLen),
                com.nepdon.wire.protocol.GamePacketTaxonomy.kind(
                        payload, 0, e.copyLen, outbound),
                payload, e.filterMask);
        synchronized (records) {
            records.add(rec);
        }
    }

    /** Replays the retro slice into the freshly started writer and the mirror. */
    private void replayRetro(final CaptureWriter w, long cutoff) {
        ring.replayFrom(cutoff, new CaptureRing.Sink() {
            @Override
            public void onRetro(long tsMs, int src, int dir, int len, int copyLen,
                                int peerIp, int sp, int dp, int decision, int mask,
                                byte[] buf, int off) {
                CaptureEvent e = w.acquire();
                if (e == null) return;
                fill(e, src, dir, len, peerIp, sp, dp, 0, decision, buf, off, tsMs);
                // Retro records predate the press; their nanos are unknown, so
                // leave the inter-packet delta out rather than inventing one.
                e.tsNanos = 0L;
                e.filterMask = mask;
                w.submit(e);
            }
        });
    }

    // ---- Snapshot / stats ----

    /** Snapshot of the in-memory mirror (valid after stop()). */
    public List<CaptureRecord> snapshot() {
        synchronized (records) {
            return new ArrayList<CaptureRecord>(records);
        }
    }

    /**
     * Total discarded records across every path: queue-full, pool-exhausted,
     * mode-filtered, cap-exceeded, write errors and mirror overflow (D9).
     */
    public long droppedByCapacity() {
        CaptureWriter w = writer;
        long writerDrops = w != null ? w.droppedTotal() : 0L;
        return writerDrops + mirrorDropped.get();
    }

    /** Records written to disk this session. */
    public long writtenCount() {
        CaptureWriter w = writer;
        return w != null ? w.writtenCount() : 0L;
    }

    // ---- File output ----

    /**
     * The streamed capture file. The capture is written continuously while it
     * runs (D4), so this no longer writes anything — it returns the part the UI
     * should point at. {@code dir} is honoured only when it differs from the
     * directory the session streamed into, in which case there is nothing to
     * return because nothing was written there.
     */
    public File writeRawCapture(File dir) {
        File f = lastPrimaryFile;
        if (f == null) {
            CaptureWriter w = writer;
            if (w != null) f = w.primaryFile();
        }
        return f;
    }

    private File captureDir() {
        File dir = context.getExternalFilesDir("dev_logs");
        if (dir == null) dir = new File(context.getFilesDir(), "dev_logs");
        return dir;
    }
}
