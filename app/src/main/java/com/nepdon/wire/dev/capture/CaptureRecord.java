package com.nepdon.wire.dev.capture;

import com.nepdon.wire.protocol.GamePacketTaxonomy;
import com.nepdon.wire.filter.TelePacket;

/**
 * One immutable captured packet + the freeze phase it was seen in + the
 * decision the engine made for it + its taxonomy classification (family and
 * event kind). Payload is a defensive copy made at capture time on the hot
 * path, so the record never aliases the read buffer; the taxonomy fields are
 * computed on the copy by the writer thread, keeping the hot path unchanged.
 */
public final class CaptureRecord {

    /** Traffic direction. */
    public enum Dir { OUT, IN }

    /** Engine decision recorded for this packet. */
    public enum Decision { PASS, DROP, HOLD }

    /** Which freeze phase this packet was captured in. */
    public enum Phase {
        /** Engine on, freeze off (the "before" baseline). */
        BEFORE,
        /** Engine on, freeze on (the "after" measurement). */
        FREEZE,
        /** Engine off (capture still recording; excluded from the diff). */
        OFF
    }

    public final long timestampMs;
    public final Dir dir;
    public final Phase phase;
    public final Decision decision;
    public final int payloadLen;
    public final int dip;
    public final int sp;
    public final int dp;
    public final int teleType;
    /** Bitmask of the effects active when this packet was seen. */
    public final int filterMask;
    /** Taxonomy family (see {@link GamePacketTaxonomy}). */
    public final int family;
    /** Taxonomy event kind (see {@link GamePacketTaxonomy}). */
    public final int kind;
    public final byte[] payload;

    /** Effect bits for {@link #filterMask} (mirrors {@code FilterKind}). */
    public static final int F_FREEZE = 1;
    public static final int F_GHOST = 2;
    public static final int F_JITTER = 4;

    public CaptureRecord(long timestampMs, Dir dir, Phase phase, Decision decision,
                         int payloadLen, int dip, int sp, int dp, int teleType,
                         int family, int kind, byte[] payload) {
        this(timestampMs, dir, phase, decision, payloadLen, dip, sp, dp, teleType,
                family, kind, payload, phase == Phase.FREEZE ? F_FREEZE : 0);
    }

    public CaptureRecord(long timestampMs, Dir dir, Phase phase, Decision decision,
                         int payloadLen, int dip, int sp, int dp, int teleType,
                         int family, int kind, byte[] payload, int filterMask) {
        this.timestampMs = timestampMs;
        this.dir = dir;
        this.phase = phase;
        this.decision = decision;
        this.payloadLen = payloadLen;
        this.dip = dip;
        this.sp = sp;
        this.dp = dp;
        this.teleType = teleType;
        this.filterMask = filterMask;
        this.family = family;
        this.kind = kind;
        this.payload = payload;
    }

    public String decisionName() {
        switch (decision) {
            case PASS: return "PASS";
            case DROP: return "DROP";
            case HOLD: return "HOLD";
            default:   return decision.name();
        }
    }

    public String dirName() {
        return dir == Dir.OUT ? "OUT" : "IN";
    }

    /**
     * Phase token for the raw dump. Encodes the full effect mask without
     * adding a column (no spaces), so existing whitespace-split parsers keep
     * working: BEFORE, FREEZE, GHOST, FREEZE|GHOST, JITTER, ... or OFF.
     */
    public String phaseName() {
        if (phase == Phase.OFF) return "OFF";
        if (filterMask == 0) return "BEFORE";
        StringBuilder sb = new StringBuilder();
        if ((filterMask & F_FREEZE) != 0) sb.append("FREEZE");
        if ((filterMask & F_GHOST) != 0) { if (sb.length() > 0) sb.append('|'); sb.append("GHOST"); }
        if ((filterMask & F_JITTER) != 0) { if (sb.length() > 0) sb.append('|'); sb.append("JITTER"); }
        return sb.toString();
    }

    public String typeName() {
        return TelePacket.typeName(teleType);
    }

    public String familyName() {
        return GamePacketTaxonomy.familyName(family);
    }

    public String kindName() {
        return GamePacketTaxonomy.kindName(kind);
    }
}
