package com.nepdon.wire.dev.capture;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** RELEASE no-op stub. Real telemetry is DEBUG-ONLY (docs/SECURITY_MODEL.md). */
public final class TelemetryCollector {

    public static final int CAPACITY = 512;
    public static final int EV_MATCH_START = 1;
    public static final int EV_MATCH_END = 2;
    public static final int EV_FREEZE_ON = 10;
    public static final int EV_FREEZE_OFF = 11;
    public static final int EV_GHOST_ON = 12;
    public static final int EV_GHOST_OFF = 13;
    public static final int EV_JITTER_ON = 14;
    public static final int EV_JITTER_OFF = 15;
    public static final int EV_VOICE_ACTIVE = 20;
    public static final int EV_ENDPOINT_SWITCH = 21;
    public static final int EV_HIT_CONFIRM = 22;
    public static final int EV_DROP_WINDOW = 23;
    public static final int EV_FREEZE_DEATH_SUSPECT = 24;
    public static final int EV_TUNNEL_RESTART = 30;
    public static final int EV_ROUTE_FALLBACK = 31;
    public static final int EV_CAPTURE_ON = 32;
    public static final int EV_CAPTURE_OFF = 33;

    public interface PollerSource {
        boolean isMatchLive();
        boolean isFreezeActive();
        long matchEndpointPacked();
        int matchServerIp();
        int matchServerPort();
        long droppedPackets();
        long sentPackets();
        int voiceFramesAndReset();
    }

    public void event(int type, int arg0, int arg1) { }
    public void event(int type) { }
    public void start(PollerSource source) { }
    public void stop() { }
    public List<long[]> snapshot() { return Collections.emptyList(); }
    public File dump(File dir, String sourceLabel) { return null; }
    public static String typeName(int type) { return ""; }
}
