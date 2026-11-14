package com.nepdon.wire.tunnel;

import android.content.Context;
import android.content.SharedPreferences;

import com.nepdon.wire.config.Prefs;

import java.util.Random;

/**
 * Allocates the tun interface IPv4 address from a bounded pool. The cursor is
 * persisted so successive tunnel establishments walk the pool instead of
 * reusing the same address (which can confuse the OS routing table).
 */
public final class TunAddressPool {

    private static final int TUN_IP_B_BASE = 12;
    private static final int TUN_IP_B_COUNT = 50;
    private static final int TUN_IP_C_COUNT = 254;
    private static final int TUN_IP_POOL_SIZE = TUN_IP_B_COUNT * TUN_IP_C_COUNT;

    /** Immutable result of {@link #nextTunAddress()}. */
    public static final class TunAddress {
        public final String ip;
        public final int ipInt;

        TunAddress(String ip, int ipInt) {
            this.ip = ip;
            this.ipInt = ipInt;
        }
    }

    private final Context context;
    private final Random rng = new Random();

    public TunAddressPool(Context context) {
        this.context = context;
    }

    public TunAddress nextTunAddress() {
        synchronized (TunAddressPool.class) {
            SharedPreferences prefs = Prefs.get(context);
            int last = prefs.getInt(Prefs.KEY_TUN_IP_CURSOR, Integer.MIN_VALUE);
            int next;
            if (last < 0 || last >= TUN_IP_POOL_SIZE) {
                next = rng.nextInt(TUN_IP_POOL_SIZE);
            } else {
                next = (last + 1) % TUN_IP_POOL_SIZE;
            }
            prefs.edit().putInt(Prefs.KEY_TUN_IP_CURSOR, next).commit();

            int b = TUN_IP_B_BASE + (next / TUN_IP_C_COUNT);
            int c = next % TUN_IP_C_COUNT;
            String ip = "26." + b + "." + c + ".2";
            int ipInt = 0x1a000000 | (b << 16) | (c << 8) | 0x02;
            return new TunAddress(ip, ipInt);
        }
    }
}
