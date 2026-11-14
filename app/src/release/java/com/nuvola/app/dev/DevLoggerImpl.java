package com.nepdon.wire.dev;

import android.content.Context;

import com.nepdon.wire.dev.capture.PacketCapture;

/**
 * RELEASE no-op DevLogger. The real logging + capture wiring is DEBUG-ONLY
 * (docs/SECURITY_MODEL.md). VpnEngine's {@code instanceof DevLoggerImpl} guard
 * still recognises this so its capture-sink wiring is skipped harmlessly, and
 * every log call is a no-op.
 */
public class DevLoggerImpl implements DevLogger {
    public DevLoggerImpl(Context context) { }

    public void setCaptureSink(PacketCapture sink) { }
    public void setEnabled(boolean enabled) { }

    @Override public boolean isEnabled() { return false; }
    @Override public void logPacket(String kind, String direction, byte[] data, int off,
                                    int len, int sourcePort, int destPort, int peerIp,
                                    String note) { }
    @Override public void logControl(String kind, String note) { }
    @Override public void logProtocolDrop(String kind, byte[] data, int off, int len,
                                          int sourcePort, int destPort, int peerIp,
                                          String note) { }
}
