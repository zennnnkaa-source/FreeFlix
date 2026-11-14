package com.nepdon.wire.config;

import android.app.ActivityManager;
import android.content.Context;

import com.nepdon.wire.config.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Knows which packages are "the game", and whether the tunnel is pointed at the
 * one actually being played.
 *
 * <p><b>Why this exists.</b> Free Fire ships under several package names —
 * global ({@code com.dts.freefireth}), MAX ({@code com.dts.freefiremax}) and
 * regional builds such as Garena Vietnam ({@code com.garena.game.kgvn}) — and a
 * player can have more than one installed. The VPN scopes itself with
 * {@code addAllowedApplication}, so it carries the traffic of the packages in
 * {@link Prefs#KEY_TARGET_PACKAGES} and <b>nothing else</b>. Point it at a
 * package the player is not currently in and the result is not a warning or a
 * crash: it is total, silent failure. Every feature reads the packet stream, so
 * ESP, ghost, freeze, teleport and the aim all go dead at once, while the app
 * still says the engine is running.</p>
 *
 * <p>That is exactly what happened here. A capture of a 170 s session
 * (2026-09-13 14:21) recorded <b>1 outbound packet against 10 886 inbound</b>,
 * every one of them unparsed ({@code cmd=-1}, {@code flags=0x80},
 * {@code foreign=10887}) — because the tunnel was scoped to
 * {@code com.dts.freefiremax} while {@code com.garena.game.kgvn} was the
 * process actually running. The inbound was the targeted app idling in a lobby;
 * the real match never entered the tunnel at all. Nothing in the UI said so,
 * which is why it read as "sometimes a match works and sometimes nothing does".
 * This class exists so that can never be invisible again.</p>
 */
public final class GameTargets {

    private GameTargets() {}

    /** The one target build the app is scoped to offer: Free Fire MAX. */
    public static final String FREE_FIRE_MAX = "com.dts.freefiremax";

    /**
     * Every Free Fire package we know of. Order is preference order for the
     * "which one should I be targeting" hint. {@link #FREE_FIRE_MAX} is the only
     * one the target picker offers (see AppPicker); the rest stay here so the
     * "you're playing an untargeted Free Fire build" diagnostics still fire.
     */
    public static final String[] KNOWN = {
            FREE_FIRE_MAX,
            "com.dts.freefireth",
            "com.garena.game.kgvn",
            "com.garena.game.kgth",
            "com.garena.game.kgid",
    };

    public static boolean isKnownGame(String pkg) {
        if (pkg == null) return false;
        for (String k : KNOWN) {
            if (k.equals(pkg)) return true;
        }
        return false;
    }

    public static boolean isInstalled(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Known game packages installed on this device. */
    public static List<String> installedGames(Context ctx) {
        List<String> out = new ArrayList<>(4);
        for (String k : KNOWN) {
            if (isInstalled(ctx, k)) out.add(k);
        }
        return out;
    }

    /**
     * True when the package currently has a running process.
     *
     * <p>{@code getRunningAppProcesses} only reports our own process on modern
     * Android, so this reads {@code /proc} instead: a package's process is
     * named after it, and {@code /proc/<pid>/cmdline} is world-readable for
     * ordinary apps. No root, no Shizuku, no {@code QUERY_ALL_PACKAGES}
     * subtleties — and it works while the game is in the foreground, which is
     * the only moment the answer matters.</p>
     */
    public static boolean isRunning(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        java.io.File proc = new java.io.File("/proc");
        String[] pids = proc.list();
        if (pids == null) return fallbackRunning(ctx, pkg);
        for (String p : pids) {
            if (p.isEmpty() || !Character.isDigit(p.charAt(0))) continue;
            java.io.RandomAccessFile f = null;
            try {
                f = new java.io.RandomAccessFile("/proc/" + p + "/cmdline", "r");
                byte[] b = new byte[128];
                int n = f.read(b);
                if (n <= 0) continue;
                int end = 0;
                while (end < n && b[end] != 0) end++;
                if (end == pkg.length() && new String(b, 0, end, "UTF-8").equals(pkg)) {
                    return true;
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (f != null) f.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean fallbackRunning(Context ctx, String pkg) {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (pkg.equals(p.processName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * A known game package that is running but is NOT in the tunnel's target
     * set — i.e. the exact misconfiguration that silently kills every feature.
     * Null when there is nothing to report.
     */
    public static String untargetedRunningGame(Context ctx) {
        Set<String> targets = Prefs.getTargetPackages(ctx);
        for (String k : KNOWN) {
            if (targets.contains(k)) continue;
            if (!isInstalled(ctx, k)) continue;
            if (isRunning(ctx, k)) return k;
        }
        return null;
    }

    /**
     * One line for the dev HUD: what the tunnel is scoped to, and whether that
     * is the thing being played.
     */
    public static String debugLine(Context ctx) {
        Set<String> targets = Prefs.getTargetPackages(ctx);
        if (targets.isEmpty()) return "NO TARGET APP SELECTED";
        StringBuilder sb = new StringBuilder(96);
        boolean anyRunning = false;
        for (String t : targets) {
            boolean inst = isInstalled(ctx, t);
            boolean run = inst && isRunning(ctx, t);
            anyRunning |= run;
            sb.append(shortName(t));
            sb.append(!inst ? "(NOT INSTALLED)" : (run ? "(run)" : "(stopped)"));
            sb.append(' ');
        }
        String other = untargetedRunningGame(ctx);
        if (other != null) {
            sb.append("<< PLAYING ").append(shortName(other)).append(" — NOT TARGETED");
        } else if (!anyRunning) {
            sb.append("<< target not running");
        }
        return sb.toString();
    }

    private static String shortName(String pkg) {
        int i = pkg.lastIndexOf('.');
        return i >= 0 ? pkg.substring(i + 1) : pkg;
    }
}
