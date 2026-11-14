package com.nepdon.wire.protocol;

/**
 * Fire signal from outbound command ids. START opens a pulse that holds
 * FIRE_HOLD_MS past the last START, so a tap still registers. STOP and STOP_ALL
 * clear it.
 */
public final class FireState {

    public static final int CMD_STOP_ALL   = 103;
    public static final int CMD_FIRE_START = 104;
    public static final int CMD_FIRE_STOP  = 105;

    private static final long FIRE_HOLD_MS = 700L;

    private static volatile boolean firing = false;
    private static volatile long firePulseEndsAt = 0L;

    private FireState() { }

    public static void observeOutbound(int cmd) {
        long now = System.currentTimeMillis();
        switch (cmd) {
            case CMD_FIRE_START:
                firing = true;
                firePulseEndsAt = FIRE_HOLD_MS + now;
                break;
            case CMD_FIRE_STOP:
                firing = false;
                firePulseEndsAt = 0L;
                break;
            case CMD_STOP_ALL:
                firing = false;
                firePulseEndsAt = 0L;
                break;
            default:
                break;
        }
    }

    public static boolean isFiring() {
        return isFireActive(System.currentTimeMillis());
    }

    private static boolean isFireActive(long now) {
        return firing || now < firePulseEndsAt;
    }
}
