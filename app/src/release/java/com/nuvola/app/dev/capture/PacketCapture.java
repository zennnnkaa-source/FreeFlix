package com.nepdon.wire.dev.capture;

import android.content.Context;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * RELEASE no-op stub. The real packet-capture engine is DEBUG-ONLY
 * (src/debug); release ships only main features and never records packets
 * (docs/SECURITY_MODEL.md). CaptureEvent/CaptureRecord stay in main so the
 * tunnel's call sites compile unchanged.
 */
public final class PacketCapture {

    public static final int MAX_PAYLOAD_BYTES = 2048;
    public static final int MAX_CAPTURE_PAYLOAD_BYTES = 65535;
    public static final int MAX_RECORDS = 120000;

    public static final int MODE_FULL = 0;
    public static final int MODE_MATCH = 1;
    public static final int MODE_COMBAT = 2;
    public static final int MODE_EVENTS = 3;
    public static final String MODE_FULL_NAME = "full";
    public static final String MODE_MATCH_NAME = "match";
    public static final String MODE_COMBAT_NAME = "combat";
    public static final String MODE_EVENTS_NAME = "events";

    public static int modeFromName(String name, int fallback) { return fallback; }
    public static String modeName(int mode) { return MODE_FULL_NAME; }

    public PacketCapture(Context context) { }

    public void start(int hexLimit, int mode, int retroSec) { }
    public void stop() { }
    public boolean isRunning() { return false; }
    public void setFilterMask(int mask) { }
    public void setLayoutSolved(boolean solved) { }

    public void recordOutbound(int plen, int dip, int sp, int dp, int teleType,
                               CaptureRecord.Decision decision, byte[] buf, int off) { }
    public void recordOutbound(int plen, int dip, int sp, int dp, int teleType,
                               int decision, byte[] buf, int off) { }
    public void recordInbound(int plen, int dip, int sp, int dp,
                              CaptureRecord.Decision decision, byte[] buf, int off) { }
    public void recordInbound(int plen, int dip, int sp, int dp,
                              int decision, byte[] buf, int off) { }
    public void recordControl(String kind, String note) { }
    public void recordControl(String kind, String note, byte[] buf, int off, int len,
                              int peerIp, int sp, int dp, boolean outbound) { }

    public List<CaptureRecord> snapshot() { return Collections.emptyList(); }
    public long droppedByCapacity() { return 0L; }
    public File writeRawCapture(File dir) { return null; }
    public void writeDiagBlock() { }
}
