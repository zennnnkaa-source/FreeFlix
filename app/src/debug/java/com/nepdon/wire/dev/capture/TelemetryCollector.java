package com.nepdon.wire.dev.capture;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Always-on, low-cost event telemetry. Records engine-level events (match
 * start/end, filter toggles, freeze health, hits, restarts) into a packed
 * lock-free ring — no payload capture, no per-event allocation on the hot
 * path — so the user never needs a full packet capture to answer "what did
 * the engine do?".
 *
 * <p>Event encoding: three longs per event in a pre-allocated ring —
 * {@code [timestamp, (type<<32)|arg0, arg1]}. The only true hot-path events
 * are HIT_CONFIRMs (length-gated, ~10 byte reads for non-matching packets);
 * everything else is emitted from cold paths or the 500 ms poller thread.</p>
 */
public final class TelemetryCollector {

    /** Ring capacity in events (3 longs each = 12 KB). */
    public static final int CAPACITY = 512;

    // ---- Event types ----
    public static final int EV_MATCH_START         = 1;   // args: dip, dp
    public static final int EV_MATCH_END           = 2;   // arg: ms since last movement
    public static final int EV_FREEZE_ON           = 10;
    public static final int EV_FREEZE_OFF          = 11;
    public static final int EV_GHOST_ON            = 12;
    public static final int EV_GHOST_OFF           = 13;
    public static final int EV_JITTER_ON           = 14;
    public static final int EV_JITTER_OFF          = 15;
    public static final int EV_VOICE_ACTIVE        = 20;  // arg: frames in window
    public static final int EV_ENDPOINT_SWITCH     = 21;  // args: packed old, new
    public static final int EV_HIT_CONFIRM         = 22;  // args: damage, zone
    public static final int EV_DROP_WINDOW         = 23;  // args: drops, total (10 s)
    public static final int EV_FREEZE_DEATH_SUSPECT = 24; // drops==0 while freeze on + stream live
    public static final int EV_TUNNEL_RESTART      = 30;
    public static final int EV_ROUTE_FALLBACK      = 31;
    public static final int EV_CAPTURE_ON          = 32;
    public static final int EV_CAPTURE_OFF         = 33;  // args: records, dropped

    private final long[] slots = new long[CAPACITY * 3];
    private final AtomicLong writeIdx = new AtomicLong(0);
    private volatile boolean running;
    private Thread poller;

    /** Records one event (hot path: 1 CAS + 3 stores, zero allocation). */
    public void event(int type, int arg0, int arg1) {
        long i = writeIdx.getAndAdd(3);
        int s = (int) (((i / 3) % CAPACITY)) * 3;
        slots[s] = System.currentTimeMillis();
        slots[s + 1] = ((long) type << 32) | (arg0 & 0xFFFFFFFFL);
        slots[s + 2] = arg1 & 0xFFFFFFFFL;
    }

    public void event(int type) {
        event(type, 0, 0);
    }

    /** Starts the 500 ms poller thread (match state, drop windows, health). */
    public void start(final PollerSource source) {
        if (running) return;
        running = true;
        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop(source);
            }
        }, "Telemetry");
        poller.setPriority(Thread.MIN_PRIORITY);
        poller.start();
    }

    /** Stops the poller (drains nothing; the ring stays readable). */
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
            poller = null;
        }
    }

    /** Data source the poller samples (implemented by VpnEngine). */
    public interface PollerSource {
        boolean isMatchLive();
        boolean isFreezeActive();
        long matchEndpointPacked();      // (ip << 16) | port, 0 when unset
        int matchServerIp();
        int matchServerPort();
        long droppedPackets();
        long sentPackets();
        int voiceFramesAndReset();
    }

    private void pollLoop(PollerSource src) {
        boolean lastLive = false;
        long lastEndpoint = 0L;
        long lastDrops = 0L, lastTotal = 0L;
        int tick = 0;
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
            tick++;
            boolean live = src.isMatchLive();
            if (live != lastLive) {
                if (live) {
                    long ep = src.matchEndpointPacked();
                    if (lastEndpoint != 0 && ep != lastEndpoint) {
                        event(EV_ENDPOINT_SWITCH, (int) (lastEndpoint >> 32), (int) (lastEndpoint & 0xffffffffL));
                    }
                    lastEndpoint = ep;
                    event(EV_MATCH_START, src.matchServerIp(), src.matchServerPort());
                } else {
                    event(EV_MATCH_END, 0, 0);
                }
                lastLive = live;
            }
            if (tick % 20 == 0) {   // every 10 s
                long drops = src.droppedPackets();
                long total = src.sentPackets() + drops;
                event(EV_DROP_WINDOW, (int) (drops - lastDrops), (int) (total - lastTotal));
                if (src.isFreezeActive() && live && (drops - lastDrops) == 0) {
                    event(EV_FREEZE_DEATH_SUSPECT, 0, 0);
                }
                lastDrops = drops;
                lastTotal = total;
                int voice = src.voiceFramesAndReset();
                if (voice > 0) event(EV_VOICE_ACTIVE, voice, 0);
            }
        }
    }

    /** Ordered snapshot of the ring (oldest surviving to newest). */
    public List<long[]> snapshot() {
        long w = writeIdx.get();
        List<long[]> out = new ArrayList<>();
        long total = w / 3;
        int start = (int) Math.max(0, total - CAPACITY);
        for (long e = start; e < total; e++) {
            int s = (int) ((e % CAPACITY)) * 3;
            long ts = slots[s];
            if (ts == 0) continue;
            out.add(new long[]{ts, slots[s + 1], slots[s + 2]});
        }
        return out;
    }

    /** Writes the snapshot as JSONL and returns the file. */
    public File dump(File dir, String sourceLabel) {
        File out = new File(dir, "telemetry_" + stamp() + ".jsonl");
        try {
            if (!dir.exists()) dir.mkdirs();
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(out), "UTF-8"), 1 << 14);
            try {
                w.write("# telemetry " + sourceLabel + "\n");
                for (long[] e : snapshot()) {
                    int type = (int) (e[1] >>> 32);
                    int a0 = (int) e[1];
                    int a1 = (int) e[2];
                    w.write("{\"ts\":" + e[0] + ",\"type\":\"" + typeName(type)
                            + "\",\"a0\":" + a0 + ",\"a1\":" + a1 + "}\n");
                }
            } finally {
                w.close();
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    /** Human-readable event name. */
    public static String typeName(int type) {
        switch (type) {
            case EV_MATCH_START: return "MATCH_START";
            case EV_MATCH_END: return "MATCH_END";
            case EV_FREEZE_ON: return "FREEZE_ON";
            case EV_FREEZE_OFF: return "FREEZE_OFF";
            case EV_GHOST_ON: return "GHOST_ON";
            case EV_GHOST_OFF: return "GHOST_OFF";
            case EV_JITTER_ON: return "JITTER_ON";
            case EV_JITTER_OFF: return "JITTER_OFF";
            case EV_VOICE_ACTIVE: return "VOICE_ACTIVE";
            case EV_ENDPOINT_SWITCH: return "ENDPOINT_SWITCH";
            case EV_HIT_CONFIRM: return "HIT_CONFIRM";
            case EV_DROP_WINDOW: return "DROP_WINDOW";
            case EV_FREEZE_DEATH_SUSPECT: return "FREEZE_DEATH_SUSPECT";
            case EV_TUNNEL_RESTART: return "TUNNEL_RESTART";
            case EV_ROUTE_FALLBACK: return "ROUTE_FALLBACK";
            case EV_CAPTURE_ON: return "CAPTURE_ON";
            case EV_CAPTURE_OFF: return "CAPTURE_OFF";
            default: return "EV" + type;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
}
