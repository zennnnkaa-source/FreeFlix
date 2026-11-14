package com.nepdon.wire.mod;

import android.content.Context;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.dev.FileWatchLog;

/**
 * Mod menu switches. All default off. Each change is written through to prefs,
 * so it survives a restart.
 */
public final class ModConfig {

    private ModConfig() { }

    public static volatile boolean AUTO_FREEZE = false;
    public static volatile boolean FREEZE_ON_FIRE = false;
    public static volatile boolean FREEZE_ON_SCOPE = false;
    /** When true, TcpProxy / UdpSessionManager drop destinations in IpFirewall. */
    public static volatile boolean FIREWALL = false;
    /** Resolved ban/report/anti-cheat hosts, checked by IpFirewall.isBlocked. */
    public static volatile boolean DOMAIN = false;
    /** Local UDP port for relay sockets. 0 = mirror the game's own source port. */
    public static volatile int RELAY_PORT = 0;

    public static void load(Context ctx) {
        android.content.SharedPreferences sp = Prefs.get(ctx);
        AUTO_FREEZE = sp.getBoolean(Prefs.KEY_MOD_AUTO_FREEZE, false);
        FREEZE_ON_FIRE = sp.getBoolean(Prefs.KEY_MOD_FREEZE_FIRE, false);
        FREEZE_ON_SCOPE = sp.getBoolean(Prefs.KEY_MOD_FREEZE_SCOPE, false);
        FIREWALL = sp.getBoolean(Prefs.KEY_MOD_FIREWALL, false);
        DOMAIN = sp.getBoolean(Prefs.KEY_MOD_DOMAIN, false);
        RELAY_PORT = sp.getInt(Prefs.KEY_MOD_RELAY_PORT, 0);
        FileWatchLog.line("MOD", "load auto=" + AUTO_FREEZE
                + " fire=" + FREEZE_ON_FIRE
                + " scope=" + FREEZE_ON_SCOPE
                + " firewall=" + FIREWALL);
    }

    public static void setAutoFreeze(Context ctx, boolean v) {
        AUTO_FREEZE = v;
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_MOD_AUTO_FREEZE, v).apply();
    }

    public static void setFreezeOnFire(Context ctx, boolean v) {
        FREEZE_ON_FIRE = v;
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_MOD_FREEZE_FIRE, v).apply();
    }

    public static void setFreezeOnScope(Context ctx, boolean v) {
        FREEZE_ON_SCOPE = v;
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_MOD_FREEZE_SCOPE, v).apply();
    }

    public static void setFirewall(Context ctx, boolean v) {
        FIREWALL = v;
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_MOD_FIREWALL, v).apply();
        FileWatchLog.line("MOD", "Firewall=" + v);
    }

    public static void setDomain(Context ctx, boolean v) {
        DOMAIN = v;
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_MOD_DOMAIN, v).apply();
        FileWatchLog.line("MOD", "Domain=" + v);
    }

    public static void setRelayPort(Context ctx, int v) {
        RELAY_PORT = v;
        Prefs.get(ctx).edit().putInt(Prefs.KEY_MOD_RELAY_PORT, v).apply();
        FileWatchLog.line("MOD", "RelayPort=" + v);
    }

    public static void allOff(Context ctx) {
        setAutoFreeze(ctx, false);
        setFreezeOnFire(ctx, false);
        setFreezeOnScope(ctx, false);
        setFirewall(ctx, false);
        setDomain(ctx, false);
    }
}
