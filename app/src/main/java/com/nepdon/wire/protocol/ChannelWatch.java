package com.nepdon.wire.protocol;

import com.nepdon.wire.dev.FileWatchLog;

import com.nepdon.wire.decode.body.PositionSync;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observation only. Counts the anti-cheat and punishment channels so the next
 * iteration is decided by measurement, and audits the clock the server sees
 * during a ghost hold. Nothing here changes a packet.
 *
 * <p>Channel names are from {@code udp_all.h}:
 * out 431 RUDP_SEND_FFANTIHACKINFO, out 453 RUDP_SEND_FFANTIHACKINFOEXT,
 * out 1079 RUDP_C2S_GIN_CHECK_INFO, out 642 RUDP_C2S_REPORT,
 * in 939 RUDP_PUNISH_PLAYER_FROM_BACKEND, in 943 RUDP_S2C_SENSITIVE_GAMEVAR,
 * in 490 RUDP_S2C_BAN_SKILLACTIVE, in 812 RUDP_S2C_REPORTFEEDBACK,
 * in 144 RUDP_FORCE_SYNC_PLAYER_STATE (the server's correction of us).</p>
 *
 * <p>During a hold the packets that reach the wire are the replays, so the
 * clock audited is the one inside the replayed keepalive. The catalogue lists
 * clientMs as field 23 of cmd 2001; PositionSync counts from entityId as
 * index 0 and the catalogue from 1, so that field is index 22. Unverified
 * until a capture confirms it: the summary prints the first value so a wrong
 * index shows up as an implausible number.</p>
 */
public final class ChannelWatch {

    public static final int CLIENT_MS_INDEX = 22;
    private static final long REPORT_EVERY_MS = 10_000L;

    public static final AtomicLong OUT_ANTIHACK = new AtomicLong();
    public static final AtomicLong OUT_GIN_CHECK = new AtomicLong();
    public static final AtomicLong OUT_REPORT = new AtomicLong();
    public static final AtomicLong IN_PUNISH = new AtomicLong();
    public static final AtomicLong IN_SENSITIVE_VAR = new AtomicLong();
    public static final AtomicLong IN_BAN_SKILL = new AtomicLong();
    public static final AtomicLong IN_REPORT_FEEDBACK = new AtomicLong();
    public static final AtomicLong IN_CORRECTION = new AtomicLong();
    public static final AtomicLong IN_CORRECTION_HOLD = new AtomicLong();
    public static final AtomicLong HOLD_REPLAYS = new AtomicLong();
    public static final AtomicLong HOLD_CLOCK_STALE = new AtomicLong();
    public static final AtomicLong HOLD_CLOCK_ADVANCING = new AtomicLong();

    private static volatile long lastClientMs = -1L;
    private static volatile long firstClientMs = -1L;
    private static volatile long lastReportMs = 0L;

    private ChannelWatch() { }

    /** Called for every packet whose cmd resolved. outbound = true for client to server. */
    public static void observe(int cmd, boolean outbound, boolean ghostHolding, long nowMs) {
        if (nowMs - lastReportMs >= REPORT_EVERY_MS) {
            lastReportMs = nowMs;
            android.util.Log.i("FakelagWatch", summary());
            FileWatchLog.line("FakelagWatch", summary());
        }
        if (outbound) {
            switch (cmd) {
                case 431:
                case 453: OUT_ANTIHACK.incrementAndGet(); break;
                case 1079: OUT_GIN_CHECK.incrementAndGet(); break;
                case 642: OUT_REPORT.incrementAndGet(); break;
                case 1087: OUT_REPORT.incrementAndGet(); break; // voice report punish path
                default: break;
            }
        } else {
            switch (cmd) {
                case 939: IN_PUNISH.incrementAndGet(); break;
                case 943: IN_SENSITIVE_VAR.incrementAndGet(); break;
                case 490: IN_BAN_SKILL.incrementAndGet(); break;
                case 812: IN_REPORT_FEEDBACK.incrementAndGet(); break;
                case 144:
                    IN_CORRECTION.incrementAndGet();
                    if (ghostHolding) IN_CORRECTION_HOLD.incrementAndGet();
                    break;
                default: break;
            }
        }
    }

    /** Audits the clock inside a keepalive that is being replayed during a hold. */
    public static void noteReplayedKeepalive(byte[] payload) {
        if (payload == null) return;
        PositionSync s = new PositionSync();
        if (!s.parse(payload, 0, payload.length)) return;
        long clk = s.rawAt(CLIENT_MS_INDEX);
        HOLD_REPLAYS.incrementAndGet();
        if (firstClientMs < 0L) firstClientMs = clk;
        if (clk == lastClientMs) HOLD_CLOCK_STALE.incrementAndGet();
        else HOLD_CLOCK_ADVANCING.incrementAndGet();
        lastClientMs = clk;
    }

    public static String summary() {
        return "antihack_out=" + OUT_ANTIHACK.get()
                + " gin_out=" + OUT_GIN_CHECK.get()
                + " report_out=" + OUT_REPORT.get()
                + " punish_in=" + IN_PUNISH.get()
                + " sensitive_in=" + IN_SENSITIVE_VAR.get()
                + " banskill_in=" + IN_BAN_SKILL.get()
                + " reportfb_in=" + IN_REPORT_FEEDBACK.get()
                + " correction_in=" + IN_CORRECTION.get()
                + " correction_in_hold=" + IN_CORRECTION_HOLD.get()
                + " hold_replays=" + HOLD_REPLAYS.get()
                + " clock_stale=" + HOLD_CLOCK_STALE.get()
                + " clock_advancing=" + HOLD_CLOCK_ADVANCING.get()
                + " clock_first=" + firstClientMs
                + " clock_last=" + lastClientMs;
    }
}
