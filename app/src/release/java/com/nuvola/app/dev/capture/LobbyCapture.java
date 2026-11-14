package com.nepdon.wire.dev.capture;

import java.io.File;

/** RELEASE no-op stub. Real lobby capture is DEBUG-ONLY (docs/SECURITY_MODEL.md). */
public final class LobbyCapture {
    private LobbyCapture() { }

    public static void onLobbyBytes(int connKey, int peerIp, int peerPort,
                                    boolean outbound, byte[] buf, int off, int len) { }
    public static void onLobbyClose(int connKey) { }
    public static synchronized void start(File dir) { }
    public static synchronized void stop() { }
    public static synchronized void shutdown() { }
    public static String coverageLine() { return ""; }
    public static String statusLine() { return ""; }
}
