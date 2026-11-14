package com.nepdon.wire.dev;

import android.content.Context;
import android.util.Log;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.dev.capture.PacketCapture;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concrete {@link DevLogger}.
 *
 * <h3>D8 — the hot-path contention bug is gone</h3>
 * The previous implementation built a {@code String} per packet <b>inside a
 * global {@code synchronized (devLock)} block</b>, on the tun read loop and on
 * every UDP recv thread, while {@link PacketCapture} did the same job lock-free.
 * Two parallel logging systems, one of them serialising every packet thread
 * through a single monitor.
 *
 * <p>What runs per packet now: one volatile read, one
 * {@link ConcurrentHashMap#get} on an already-present key, two atomic
 * increments, and — when a capture is running — a hand-off into the capture
 * pipeline as a {@code SRC_CTRL} event. <b>No lock, no {@code String}, no
 * allocation.</b> The only per-packet text that survives anywhere is the
 * {@code note} the caller already built before calling in.</p>
 *
 * <p>Packet <i>text</i> is no longer buffered here at all: the capture file is
 * the packet record. What this class still owns is the dev report — session
 * timing, per-kind and per-size histograms, protocol-drop accounting, and the
 * (rare) control events, which are cheap to format because there are hundreds
 * of them per session rather than hundreds of thousands.</p>
 */
public class DevLoggerImpl implements DevLogger {

    private static final int DEV_MAX_CONTROL_EVENTS = 20000;
    private static final int DEV_UNKNOWN_ACTIVE_MAX = 512;
    private static final int DEV_PROTOCOL_DROP_MAX = 1024;
    private static final boolean ICMP_ECHO_REPLY_ENABLED = true;
    private static final int TELE_BUF_MAX = 10000;
    /** Size histogram buckets: 0..2047 exact, 2048 = "2048 or more". */
    private static final int SIZE_BUCKETS = 2049;

    private final Context context;
    /** Held only by session start/stop and the report dump. Never per packet. */
    private final Object devLock = new Object();
    private volatile boolean devMode = false;
    private final AtomicBoolean devSessionActive = new AtomicBoolean(false);
    private volatile long devSessionStartMs = 0L;
    private volatile long devSessionEndMs = 0L;

    private final AtomicLong devEventSeq = new AtomicLong(0);
    private final AtomicLong devPacketEvents = new AtomicLong(0);
    private final AtomicLong devControlDropped = new AtomicLong(0);
    private final AtomicLong devUnknownActiveEvents = new AtomicLong(0);
    private final AtomicLong devProtocolDropEvents = new AtomicLong(0);
    private final AtomicLong devNonIpv4Drops = new AtomicLong(0);
    private final AtomicLong devNonUdpDrops = new AtomicLong(0);

    private final ConcurrentHashMap<String, AtomicLong> devKindCounts =
            new ConcurrentHashMap<String, AtomicLong>();
    private final AtomicIntegerArray devSizeCounts = new AtomicIntegerArray(SIZE_BUCKETS);
    private final ConcurrentLinkedQueue<String> devControlEvents =
            new ConcurrentLinkedQueue<String>();
    private final AtomicLong devControlCount = new AtomicLong(0);

    /**
     * Where packet events go now. Set by the service so the dev logger feeds the
     * one capture pipeline instead of running a second one. Null is fine: the
     * counters still work, nothing is buffered.
     */
    private volatile PacketCapture captureSink;

    public DevLoggerImpl(Context context) {
        this.context = context;
    }

    /** Routes dev packet events into the capture pipeline (see the class doc). */
    public void setCaptureSink(PacketCapture sink) {
        this.captureSink = sink;
    }

    @Override
    public boolean isEnabled() {
        return devMode;
    }

    /** Start (true) or stop+dump (false) a dev session. */
    public void setEnabled(boolean enabled) {
        synchronized (devLock) {
            devMode = enabled;
            if (enabled) {
                if (devSessionActive.compareAndSet(false, true)) {
                    devSessionStartMs = System.currentTimeMillis();
                    devSessionEndMs = 0L;
                    resetCounters();
                }
            } else if (devSessionActive.get()) {
                dumpDevReportLocked();
            }
        }
    }

    private void resetCounters() {
        devEventSeq.set(0);
        devPacketEvents.set(0);
        devControlDropped.set(0);
        devUnknownActiveEvents.set(0);
        devProtocolDropEvents.set(0);
        devNonIpv4Drops.set(0);
        devNonUdpDrops.set(0);
        devKindCounts.clear();
        for (int i = 0; i < SIZE_BUCKETS; i++) devSizeCounts.set(i, 0);
        devControlEvents.clear();
        devControlCount.set(0);
    }

    /**
     * Packet event. Lock-free and allocation-free: counts it and hands it to the
     * capture pipeline. Callable from any hot thread.
     */
    @Override
    public void logPacket(String kind, String direction, byte[] data, int off, int len,
                          int sourcePort, int destPort, int peerIp, String note) {
        if (!devMode) return;
        if (!devSessionActive.get()) startSessionLazily();

        count(kind);
        countSize(len);
        devEventSeq.incrementAndGet();
        devPacketEvents.incrementAndGet();

        PacketCapture sink = captureSink;
        if (sink != null) {
            sink.recordControl(kind, note, data, off, len, peerIp, sourcePort, destPort,
                    !"IN".equals(direction));
        }
    }

    /**
     * Control/state event. These are rare (hundreds per session, not hundreds of
     * thousands), so formatting one line here costs nothing measurable and keeps
     * the dev report readable on its own.
     */
    @Override
    public void logControl(String kind, String note) {
        if (!devMode) return;
        if (!devSessionActive.get()) startSessionLazily();

        count(kind);
        long seq = devEventSeq.incrementAndGet();
        if (devControlCount.get() < DEV_MAX_CONTROL_EVENTS) {
            devControlCount.incrementAndGet();
            devControlEvents.add("#" + seq
                    + " +" + (System.currentTimeMillis() - devSessionStartMs) + "ms"
                    + " kind=" + kind
                    + " dir=CTRL"
                    + " note=" + note);
        } else {
            devControlDropped.incrementAndGet();
        }

        PacketCapture sink = captureSink;
        if (sink != null) sink.recordControl(kind, note);
        FileWatchLog.line(kind, note != null ? note : "");
    }

    @Override
    public void logProtocolDrop(String kind, byte[] data, int off, int len,
                                int sourcePort, int destPort, int peerIp, String note) {
        if (!markProtocolDrop(kind)) return;
        logPacket(kind, "OUT", data, off, len, sourcePort, destPort, peerIp, note);
    }

    private boolean markProtocolDrop(String kind) {
        if (!devMode) return false;
        if ("DROP_NON_IPV4".equals(kind)) {
            devNonIpv4Drops.incrementAndGet();
        } else if ("DROP_NON_UDP".equals(kind)) {
            devNonUdpDrops.incrementAndGet();
        }
        if (devProtocolDropEvents.get() >= DEV_PROTOCOL_DROP_MAX) return false;
        devProtocolDropEvents.incrementAndGet();
        return true;
    }

    private void startSessionLazily() {
        if (devSessionActive.compareAndSet(false, true)) {
            devSessionStartMs = System.currentTimeMillis();
            devSessionEndMs = 0L;
            devUnknownActiveEvents.set(0);
        }
    }

    /**
     * Per-kind counter. {@code get} on an existing key allocates nothing; only
     * the first sighting of a kind (a handful per session) takes the slow path.
     */
    private void count(String kind) {
        AtomicLong c = devKindCounts.get(kind);
        if (c == null) {
            AtomicLong fresh = new AtomicLong(0);
            AtomicLong prev = devKindCounts.putIfAbsent(kind, fresh);
            c = prev != null ? prev : fresh;
        }
        c.incrementAndGet();
    }

    /** Size histogram in a flat int array: no boxing, no map, no allocation. */
    private void countSize(int len) {
        int bucket = len < 0 ? 0 : (len >= SIZE_BUCKETS ? SIZE_BUCKETS - 1 : len);
        devSizeCounts.incrementAndGet(bucket);
    }

    private void dumpDevReportLocked() {
        devSessionEndMs = System.currentTimeMillis();
        File dir = context.getExternalFilesDir("dev_logs");
        if (dir == null) dir = new File(context.getFilesDir(), "dev_logs");
        try {
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(devSessionEndMs));
            File out = new File(dir, "dev_packet_log_" + stamp + ".txt");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"));
            try {
                writer.write("DEV MODE PACKET REPORT\n");
                writer.write("package=" + packageString() + "\n");
                writer.write("started=" + formatTime(devSessionStartMs) + "\n");
                writer.write("ended=" + formatTime(devSessionEndMs) + "\n");
                writer.write("duration_ms=" + (devSessionEndMs - devSessionStartMs) + "\n");
                writer.write("packet_events=" + devPacketEvents.get() + "\n");
                writer.write("control_events_saved=" + devControlCount.get() + "\n");
                writer.write("control_events_dropped_by_log_cap=" + devControlDropped.get() + "\n");
                writer.write("packet_payloads=capture_pipeline\n");
                writer.write("unknown_active_log_cap=" + DEV_UNKNOWN_ACTIVE_MAX + "\n");
                writer.write("unknown_active_logged=" + devUnknownActiveEvents.get() + "\n");
                writer.write("protocol_drop_log_cap=" + DEV_PROTOCOL_DROP_MAX + "\n");
                writer.write("protocol_drop_logged=" + devProtocolDropEvents.get() + "\n");
                writer.write("non_ipv4_drops_seen=" + devNonIpv4Drops.get() + "\n");
                writer.write("non_udp_drops_seen=" + devNonUdpDrops.get() + "\n");
                writer.write("icmp_echo_reply_enabled=" + ICMP_ECHO_REPLY_ENABLED + "\n");
                writer.write("tele_buf_max=" + TELE_BUF_MAX + "\n\n");

                writer.write("SUMMARY_BY_KIND\n");
                for (Map.Entry<String, AtomicLong> entry : devKindCounts.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue().get() + "\n");
                }

                writer.write("\nSUMMARY_BY_PAYLOAD_SIZE\n");
                for (int i = 0; i < SIZE_BUCKETS; i++) {
                    int n = devSizeCounts.get(i);
                    if (n == 0) continue;
                    if (i == SIZE_BUCKETS - 1) {
                        writer.write(i + "+ bytes=" + n + "\n");
                    } else {
                        writer.write(i + " bytes=" + n + "\n");
                    }
                }

                writer.write("\nCONTROL_EVENTS\n");
                ArrayList<String> events = new ArrayList<String>(devControlEvents);
                for (int i = 0; i < events.size(); i++) {
                    writer.write(events.get(i));
                    writer.write("\n");
                }
            } finally {
                writer.close();
            }
            Prefs.get(context).edit()
                    .putString(Prefs.KEY_DEV_LAST_LOG_FILE, out.getAbsolutePath())
                    .commit();
        } catch (Exception e) {
            Log.e("DEVLOG", "Cannot write dev packet report", e);
        } finally {
            devSessionActive.set(false);
            resetCounters();
        }
    }

    private String packageString() {
        Set<String> pkgs = Prefs.getTargetPackages(context);
        if (pkgs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String formatTime(long timeMs) {
        if (timeMs <= 0L) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(timeMs));
    }
}
