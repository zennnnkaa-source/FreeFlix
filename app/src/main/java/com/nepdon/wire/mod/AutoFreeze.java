package com.nepdon.wire.mod;

import com.nepdon.wire.protocol.FireState;
import com.nepdon.wire.protocol.ScopeState;
import com.nepdon.wire.protocol.ServerSignal;
import com.nepdon.wire.util.Humanize;

/**
 * Decides when freeze should be on, and whether an ON/OFF toggle must be sent.
 * It never touches the filter; the caller sends the same ACTION_FREEZE the
 * overlay button sends. It only releases a freeze it switched on itself.
 *
 * <p>The governor only ever blocks a new ON. An OFF is never blocked.</p>
 * <ul>
 *   <li>Duty cycle: ON time is capped at {@link #MAX_ON_FRACTION} of each
 *       {@link #WINDOW_MS} tumbling window.</li>
 *   <li>Rest: after an OFF, the next ON waits {@link #MIN_OFF_MS} plus a random
 *       extra of up to the same amount.</li>
 *   <li>Stand-down: no new ON for {@link #CORRECTION_QUIET_MS} after the server
 *       corrected our own position or retransmitted our reliable traffic.</li>
 * </ul>
 */
public final class AutoFreeze {

    private static final long COOLDOWN_MS = 250L;
    private static final double COOLDOWN_JITTER_FRAC = 0.4;

    static final long WINDOW_MS = 60_000L;
    static final double MAX_ON_FRACTION = 0.35;
    static final long MIN_OFF_MS = 1_500L;
    static final long CORRECTION_QUIET_MS = 8_000L;
    /** A tick gap longer than this is a stall, not ON time. */
    private static final long MAX_TICK_GAP_MS = 200L;

    private final Humanize humanize = new Humanize();
    private boolean engaged = false;
    private long lastSendMs = 0L;
    private long gapMs = COOLDOWN_MS;

    private long lastTickMs = 0L;
    private long windowStartMs = 0L;
    private long onAccumMs = 0L;
    private boolean wasFreezeOn = false;
    private long lastOffMs = 0L;
    private long offGapMs = MIN_OFF_MS;

    /** Call about every 50 ms on the main looper. Returns true to send ACTION_FREEZE. */
    public boolean tick(boolean tunnelOn, boolean freezeOn, long now) {
        accountDutyCycle(freezeOn, now);
        if (!tunnelOn) {
            engaged = false;
            return false;
        }
        boolean want = ModConfig.AUTO_FREEZE
                && ((ModConfig.FREEZE_ON_SCOPE && ScopeState.isScoped())
                        || (ModConfig.FREEZE_ON_FIRE && FireState.isFiring()));
        boolean cooled = now - lastSendMs >= gapMs;
        if (want) {
            if (!freezeOn && cooled && mayTurnOn(now)) {
                engaged = true;
                markSent(now);
                return true;
            }
            return false;
        }
        if (!engaged) return false;
        if (!freezeOn) {
            engaged = false;
            return false;
        }
        if (cooled) {
            markSent(now);
            return true;
        }
        return false;
    }

    private void markSent(long now) {
        lastSendMs = now;
        gapMs = humanize.upMs(COOLDOWN_MS, COOLDOWN_JITTER_FRAC);
    }

    private boolean mayTurnOn(long now) {
        if (onAccumMs >= (long) (WINDOW_MS * MAX_ON_FRACTION)) return false;
        if (now - lastOffMs < offGapMs) return false;
        long lastSignal = Math.max(ServerSignal.ownCorrectionMs(),
                ServerSignal.retransmitAlarmMs());
        return now - lastSignal >= CORRECTION_QUIET_MS;
    }

    private void accountDutyCycle(boolean freezeOn, long now) {
        if (lastTickMs != 0L && freezeOn) {
            long dt = now - lastTickMs;
            if (dt > 0L && dt <= MAX_TICK_GAP_MS) onAccumMs += dt;
        }
        lastTickMs = now;
        if (windowStartMs == 0L || now - windowStartMs >= WINDOW_MS) {
            windowStartMs = now;
            onAccumMs = 0L;
        }
        if (wasFreezeOn && !freezeOn) {
            lastOffMs = now;
            offGapMs = humanize.upMs(MIN_OFF_MS, 1.0);
        }
        wasFreezeOn = freezeOn;
    }
}
