package com.nepdon.wire.net;

import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.mod.ModConfig;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the ban, report, anti-cheat and telemetry hosts from the app process,
 * which sits outside the VPN, and keeps their IPv4 addresses in a sorted array.
 * IpFirewall.isBlocked checks that array on every packet, so the tunnel never
 * has to parse DNS. Only runs while the Domain switch is on.
 */
public final class DomainBlock {

    private static final String[] HOSTS = {
            "gamesecurity.us.freefiremobile.com",
            "gamesecurity.sea.freefiremobile.com",
            "device-check.garena.com",
            "anti-cheat.garena.com",
            "anticheat.garena.com",
            "anti.garena.com",
            "guard.garena.com",
            "ff-anticheat.garena.com",
            "ff-report.garena.com",
            "ff-antihack.garena.com",
            "ff-security.garena.com",
            "report.freefiremobile.com",
            "anticheat.freefiremobile.com",
            "security.freefiremobile.com",
            "ffban.garena.com",
            "ban.garena.com",
            "crashlytics.com",
            "app-measurement.com",
            "anticheatexpert.com",
            "m.anticheatexpert.com",
            "down.anticheatexpert.com",
            "cs.mbgame.anticheatexpert.com",
            "cschannel.anticheatexpert.com",
            "firebase-settings.crashlytics.com",
            "firebaselogging-pa.googleapis.com",
            "crashlytics-reporting.googleapis.com",
            "connectivitycheck.gstatic.com",
            "bugly.qq.com",
            "rqd.qq.com",
            "appsflyer.com",
            "garenanow.com",
            "connect.garenanow.com",
            "gin.freefiremobile.com",
            "dl.castle.freefiremobile.com",
            "csoversea.stronghold.freefiremobile.com",
            "ff.rtc.grtc.garenanow.com",
            "ff.rtc.grtc.garenanow.vn",
            "garena.com",
            "report.garena.com",
            "auth.garena.com",
            "logcollector.data.garenanow.com",
    };

    private static final long REFRESH_MS = 10L * 60L * 1000L;
    private static final long POLL_MS = 5000L;

    private static final Map<String, int[]> perHost = new HashMap<String, int[]>();
    private static volatile int[] blocked = new int[0];
    private static volatile boolean started;

    private DomainBlock() {}

    /** Host-order IPv4, same representation as the ipHost passed to IpFirewall. */
    public static boolean contains(int ipHost) {
        return Arrays.binarySearch(blocked, ipHost) >= 0;
    }

    public static int size() {
        return blocked.length;
    }

    public static void ensureStarted() {
        if (started) return;
        synchronized (DomainBlock.class) {
            if (started) return;
            started = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "fw-domain");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        long last = 0L;
        while (true) {
            if (ModConfig.DOMAIN && !IpFirewall.inLobby()
                    && System.currentTimeMillis() - last >= REFRESH_MS) {
                refresh();
                last = System.currentTimeMillis();
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void refresh() {
        for (String host : HOSTS) {
            try {
                Set<Integer> got = new HashSet<Integer>();
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    byte[] b = a.getAddress();
                    if (b.length != 4) continue;
                    got.add(((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                            | ((b[2] & 0xff) << 8) | (b[3] & 0xff));
                }
                if (!got.isEmpty()) perHost.put(host, toSorted(got));
            } catch (Exception e) {
                FileWatchLog.line("FW", "resolve failed " + host + ": " + e.getMessage());
            }
        }
        Set<Integer> all = new HashSet<Integer>();
        for (int[] arr : perHost.values()) {
            for (int v : arr) all.add(v);
        }
        blocked = toSorted(all);
        FileWatchLog.line("FW", "domain block ips=" + blocked.length);
    }

    private static int[] toSorted(Set<Integer> s) {
        int[] out = new int[s.size()];
        int i = 0;
        for (Integer v : s) out[i++] = v;
        Arrays.sort(out);
        return out;
    }
}
