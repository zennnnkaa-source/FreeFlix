package com.nepdon.wire.dev.capture;

/** RELEASE no-op stub. Real annotator is DEBUG-ONLY (docs/SECURITY_MODEL.md). */
public final class CaptureAnnotator {
    private CaptureAnnotator() { }

    public static void resetCounters() { }
    public static void annotate(CaptureEvent e) { }
    public static String coverageLine() { return ""; }
    public static String describe(CaptureEvent e) { return ""; }
    public static String flagLetters(int flags) { return ""; }
    public static boolean isTransport(int cmd) { return cmd >= 1 && cmd <= 99; }
}
