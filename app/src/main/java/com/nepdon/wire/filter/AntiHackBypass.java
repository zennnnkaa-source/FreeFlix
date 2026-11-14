package com.nepdon.wire.filter;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.protocol.ChannelWatch;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Soft handling of client→server anti-hack / report channels.
 *
 * <p>Pure silence on 431/453 is itself a detectable signal (the client is
 * expected to emit them). Strategy:</p>
 * <ol>
 *   <li>Cache the last outbound 431 and 453 seen while NOT ghosting.</li>
 *   <li>While ghosting (or when ANTIHACK_SOFT): DROP the live packet so it
 *       cannot carry a "blocked data" fingerprint from the hold, and at a
 *       slow cadence re-emit the cached pre-hold sample so the channel is
 *       not dead.</li>
 *   <li>Gin-check (1079) and player report (642) are dropped only while
 *       ghost is active; otherwise they pass.</li>
 *   <li>Everything is counted in {@link ChannelWatch} and written to
 *       {@link FileWatchLog}.</li>
 * </ol>
 *
 * <p>Body contents of 431/453 are not decoded here — we never synthesize a
 * fresh anti-hack body. Replay of a prior real sample is the only spoof.</p>
 */
public final class AntiHackBypass implements PacketFilter {

    public static final int CMD_ANTIHACK = 431;
    public static final int CMD_ANTIHACK_EXT = 453;
    public static final int CMD_GIN_CHECK = 1079;
    public static final int CMD_REPORT = 642;
    public static final int CMD_VOICE_REPORT_PUNISH = 1087;

    /** Minimum gap between cached re-emits (ms). */
    private static final long REPLAY_GAP_MS = 8_000L;

    private final PacketSender sender;
    private final DevLogger logger;
    private final GhostFilter ghost;

    private final Object cacheLock = new Object();
    private byte[] cached431;
    private int c431Dip, c431Dp, c431Sp;
    private byte[] cached453;
    private int c453Dip, c453Dp, c453Sp;
    private long lastReplayMs;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong cachedReplay = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();

    public AntiHackBypass(PacketSender sender, DevLogger logger, GhostFilter ghost) {
        this.sender = sender;
        this.logger = logger;
        this.ghost = ghost;
    }

    @Override public String name() { return "antihack"; }
    @Override public boolean isActive() { return HoldExperiments.ANTIHACK_SOFT; }

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        if (!ctx.hasCmd()) return FilterDecision.PASS;
        final int cmd = ctx.cmd;
        final boolean holding = ghost != null && ghost.isActive();

        if (cmd == CMD_ANTIHACK || cmd == CMD_ANTIHACK_EXT) {
            // Always cache while free so a later hold has a real sample.
            if (!holding) {
                cache(cmd, ctx);
                passed.incrementAndGet();
                return FilterDecision.PASS; // NEVER drop outside ghost — was causing 10s loot lag
            }
            if (!HoldExperiments.ANTIHACK_SOFT) {
                passed.incrementAndGet();
                return FilterDecision.PASS;
            }
            // Soft path ONLY while ghost holding: drop live, slow-replay cache.
            dropped.incrementAndGet();
            logger.logPacket("BLOCK_ANTIHACK", "OUT", ctx.buf, ctx.off, ctx.plen,
                    ctx.sp, ctx.dp, ctx.dip, "cmd=" + cmd + " hold=true");
            FileWatchLog.line("ANTIHACK", "drop live cmd=" + cmd + " hold=true len=" + ctx.plen);
            maybeReplayCache(System.currentTimeMillis());
            return FilterDecision.DROP;
        }

        if (cmd == CMD_GIN_CHECK || cmd == CMD_REPORT || cmd == CMD_VOICE_REPORT_PUNISH) {
            if (holding && HoldExperiments.ANTIHACK_DROP_ON_GHOST) {
                dropped.incrementAndGet();
                logger.logPacket("BLOCK_AC_AUX", "OUT", ctx.buf, ctx.off, ctx.plen,
                        ctx.sp, ctx.dp, ctx.dip, "cmd=" + cmd);
                FileWatchLog.line("ANTIHACK", "drop aux cmd=" + cmd + " during ghost");
                return FilterDecision.DROP;
            }
            passed.incrementAndGet();
            return FilterDecision.PASS;
        }

        return FilterDecision.PASS;
    }

    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        if (!ctx.hasCmd()) return FilterDecision.PASS;
        int cmd = ctx.cmd;
        // Observation only — never drop inbound punish/ban; the client must
        // see them or the session state desyncs. Log for capture.
        if (cmd == 939 || cmd == 943 || cmd == 490 || cmd == 812 || cmd == 1087 || cmd == 1088) {
            FileWatchLog.packet("AC_IN", "IN", cmd, ctx.plen, ctx.dip, ctx.dp, ctx.sp);
            logger.logPacket("AC_IN", "IN", ctx.buf, ctx.off, ctx.plen,
                    ctx.sp, ctx.dp, ctx.dip, "cmd=" + cmd);
        }
        return FilterDecision.PASS;
    }

    private void cache(int cmd, PacketContext ctx) {
        byte[] copy = ctx.copyPayload();
        if (copy == null) return;
        synchronized (cacheLock) {
            if (cmd == CMD_ANTIHACK) {
                cached431 = copy;
                c431Dip = ctx.dip; c431Dp = ctx.dp; c431Sp = ctx.sp;
            } else if (cmd == CMD_ANTIHACK_EXT) {
                cached453 = copy;
                c453Dip = ctx.dip; c453Dp = ctx.dp; c453Sp = ctx.sp;
            }
        }
    }

    private void maybeReplayCache(long now) {
        if (now - lastReplayMs < REPLAY_GAP_MS) return;
        byte[] a = null, b = null;
        int aDip = 0, aDp = 0, aSp = 0, bDip = 0, bDp = 0, bSp = 0;
        synchronized (cacheLock) {
            if (cached431 != null) {
                a = cached431.clone();
                aDip = c431Dip; aDp = c431Dp; aSp = c431Sp;
            }
            if (cached453 != null) {
                b = cached453.clone();
                bDip = c453Dip; bDp = c453Dp; bSp = c453Sp;
            }
        }
        if (a == null && b == null) return;
        lastReplayMs = now;
        if (a != null) {
            sender.send(new TelePacket(a, aDip, aDp, aSp, TelePacket.TYPE_NONE));
            cachedReplay.incrementAndGet();
            FileWatchLog.line("ANTIHACK", "replay cached 431 len=" + a.length);
        }
        if (b != null) {
            sender.send(new TelePacket(b, bDip, bDp, bSp, TelePacket.TYPE_NONE));
            cachedReplay.incrementAndGet();
            FileWatchLog.line("ANTIHACK", "replay cached 453 len=" + b.length);
        }
    }

    public String summary() {
        return "ah_drop=" + dropped.get()
                + " ah_replay=" + cachedReplay.get()
                + " ah_pass=" + passed.get();
    }

    @Override public void start() { }
    @Override public void stop() { }
    @Override public void shutdown() { }
}
