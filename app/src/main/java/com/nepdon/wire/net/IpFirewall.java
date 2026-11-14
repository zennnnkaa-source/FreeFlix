package com.nepdon.wire.net;

import com.nepdon.wire.mod.ModConfig;
import com.nepdon.wire.protocol.GamePacketTaxonomy;
import com.nepdon.wire.protocol.SessionTimeline;

/**
 * IP/CIDR + domain block list (ported from the connect/getaddrinfo hook).
 * Active only when {@link com.nepdon.wire.mod.ModConfig#FIREWALL} is on.
 */
public final class IpFirewall {

    private IpFirewall() {}

    private static final int[] SPECIFIC = {
            ip("27.22.99.69"),
            ip("27.71.98.117"),
            ip("148.153.112.184"),
            ip("148.153.35.194"),
            ip("148.153.116.185"),
            ip("148.222.67.91"),
            ip("148.222.67.92"),
            ip("148.222.67.157"),
            ip("148.222.70.31"),
            ip("202.81.96.10"),
            ip("202.81.99.105"),
            ip("143.92.120.196"),
            ip("143.92.123.133")
    };

    /** Host-order IPv4 (same endian as PacketContext.dip / TcpProxy dstIp). */
    /**
     * Single entry point for the tunnel. Firewall switch: the IP/CIDR list in
     * isIpBlocked. Domain switch: the addresses resolved by DomainBlock.
     */
    public static boolean isBlocked(int ipHost, int port) {
        if (inLobby()) return false;
        if (ModConfig.FIREWALL && isIpBlocked(ipHost, port)) return true;
        if (!ModConfig.DOMAIN) return false;
        if (com.nepdon.wire.protocol.GamePacketTaxonomy.isFromMatchServer(ipHost, port)) return false;
        DomainBlock.ensureStarted();
        return DomainBlock.contains(ipHost);
    }

    /**
     * True in the lobby, where nothing needs blocking. A live match movement
     * stream overrides a stale lobby phase, so a missed transition cannot
     * switch the firewall off in the middle of a match.
     */
    static boolean inLobby() {
        return SessionTimeline.phaseId() == SessionTimeline.P_LOBBY
                && !GamePacketTaxonomy.isMatchStreamLive(System.currentTimeMillis());
    }

    public static boolean isIpBlocked(int ipHost, int port) {
        // The live match server is never blocked, whatever range it sits in.
        if (com.nepdon.wire.protocol.GamePacketTaxonomy.isFromMatchServer(ipHost, port)) return false;
        if (matchCidr(ipHost, "35.0.0.0", 8)) return true;
        if (matchCidr(ipHost, "34.0.0.0", 8)) return true;
        if (matchCidr(ipHost, "148.222.64.0", 21)) return true;
        if (matchCidr(ipHost, "143.92.120.0", 21)) return true;
        if (matchCidr(ipHost, "202.81.96.0", 24) && port == 80) return true;
        for (int s : SPECIFIC) {
            if (ipHost == s) return true;
        }
        return false;
    }

    public static boolean isDomainBlocked(String node) {
        if (node == null || node.isEmpty()) return false;
        String n = node.toLowerCase();
        final String[] blocked = {
                "anticheatexpert.com",
                "m.anticheatexpert.com",
                "down.anticheatexpert.com",
                "cs.mbgame.anticheatexpert.com",
                "cschannel.anticheatexpert.com",
                "crashlytics.com",
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
                "logcollector.data.garenanow.com"
        };
        for (String d : blocked) {
            if (n.contains(d)) return true;
        }
        return false;
    }

    public static String reason(int ipHost, int port) {
        if (DomainBlock.contains(ipHost)) return "domain ip";
        if (matchCidr(ipHost, "35.0.0.0", 8)) return "cidr 35.0.0.0/8";
        if (matchCidr(ipHost, "34.0.0.0", 8)) return "cidr 34.0.0.0/8";
        if (matchCidr(ipHost, "148.222.64.0", 21)) return "cidr 148.222.64.0/21";
        if (matchCidr(ipHost, "143.92.120.0", 21)) return "cidr 143.92.120.0/21";
        if (matchCidr(ipHost, "202.81.96.0", 24) && port == 80) return "cidr 202.81.96.0/24:80";
        for (int s : SPECIFIC) {
            if (ipHost == s) return "exact " + formatIp(ipHost);
        }
        return ModConfig.DOMAIN && DomainBlock.contains(ipHost) ? "domain ip" : "list";
    }

    public static String formatIp(int ipHost) {
        return ((ipHost >>> 24) & 0xff) + "."
                + ((ipHost >>> 16) & 0xff) + "."
                + ((ipHost >>> 8) & 0xff) + "."
                + (ipHost & 0xff);
    }

    private static int ip(String dotted) {
        String[] p = dotted.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        int c = Integer.parseInt(p[2]);
        int d = Integer.parseInt(p[3]);
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static boolean matchCidr(int ipHost, String net, int prefix) {
        int netHost = ip(net);
        int mask = prefix == 0 ? 0 : (~0 << (32 - prefix));
        return (ipHost & mask) == (netHost & mask);
    }
}
