package com.nepdon.wire.filter;

import com.nepdon.wire.protocol.GamePacketTaxonomy;

/**
 * Legacy length-window classification kept ONLY for the jitter replay path
 * and dev-capture labeling. The real classification lives in
 * {@link GamePacketTaxonomy} (adaptive, mask-independent); nothing here
 * should be used for live drop decisions — the windows below describe the
 * game's historical layouts and the encoding rotates every session.
 */
public final class PacketClassifier {

    public static final int TELE_MOVE_MIN = 0x37;
    public static final int TELE_MOVE_MAX = 0xa0;
    public static final int TELE_FIRE_MIN = 0x15;
    public static final int TELE_FIRE_MAX = 0x1c2;
    public static final int TELE_BROAD_FIRE_MAX = 0x36;

    private PacketClassifier() { }

    public static int classify(byte[] data, int off, int payloadLen) {
        if (GamePacketTaxonomy.isMovementStream(data, off, payloadLen)) {
            return TelePacket.TYPE_MOVE;
        }
        // Pre-learning fallback for jitter: the historical length window,
        // minus the reliability family (holding the R keepalive starves the
        // connection — the old "locking heartbeat" disconnect bug). Only
        // active until the movement signature is learned.
        if (!GamePacketTaxonomy.movementLearned()
                && payloadLen >= TELE_MOVE_MIN && payloadLen <= TELE_MOVE_MAX
                && GamePacketTaxonomy.family(data, off, payloadLen) != GamePacketTaxonomy.FAMILY_R) {
            return TelePacket.TYPE_MOVE;
        }
        return TelePacket.TYPE_NONE;
    }

}
