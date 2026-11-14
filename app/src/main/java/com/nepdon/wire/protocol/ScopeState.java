package com.nepdon.wire.protocol;

import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * Latches whether the player is currently looking down a scope, from the two
 * commands the client already volunteers on its own outbound stream:
 * {@code Cmd 137 RUDP_START_SNIPER} and {@code Cmd 138 RUDP_STOP_SNIPER}.
 *
 * <h3>Why there is no image recognition here</h3>
 *
 * <p>The scope state does not have to be recognised, inferred, or modelled —
 * <b>the client announces it.</b> Both messages carry an EMPTY body (the game's
 * own {@code Serialize} is empty), so the command id alone is the state. On the
 * wire they are reliable-and-encrypted, 26 bytes, sent by the Weapon class, and
 * measured across six captures they are <b>perfectly paired, 242/242 reliable</b>
 * — 2/2, 19/19, 25/25, 45/45, 60/60, 91/91 START/STOP per session, with pair
 * intervals of 0.4–3.4 s. Full survey:
 * {@code plan/research/RESEARCH_WIRE_SCOPE_STATE.md} §1.3.</p>
 *
 * <p>A screen-capture recogniser (let alone a trained model) would cost a
 * capture permission, a per-frame budget, and a false-positive rate, to answer
 * a question one integer comparison already answers exactly. The recognition
 * research ({@code plan/research/RESEARCH_SCOPE_RECOGNITION_CV.md}) stands only
 * as the cross-check path if the wire ever disagrees with the screen.</p>
 *
 * <h3>What this does NOT tell you</h3>
 *
 * <ul>
 *   <li><b>Not which magnification.</b> 137/138 are edges, not levels. The tier
 *       (2x/4x/8x) is {@code Cmd 1900 varId 12 EPRI_PLAYER_SIGHTING_ID}, which
 *       is already decoded and currently discarded in {@code PriTracker}.</li>
 *   <li><b>Not the scoped FOV.</b> That number exists in no public source and
 *       in no code path in the binary; it has to be measured on-device (plan
 *       §9). This class says <i>when</i>, never <i>how much</i>.</li>
 *   <li><b>Not proven for non-sniper ADS.</b> Every observed pair came from
 *       sniper-class play. Whether a red dot or a 2x on an AR also emits 137 is
 *       the one open question (plan §11.3) — until it is settled, treat "not
 *       scoped" as "no sniper scope", not "no magnification".</li>
 * </ul>
 *
 * <h3>The blind window that matters</h3>
 *
 * <p>The outbound command id is only trustworthy once the header-layout solver
 * has converged, and the solver <b>collapses on every match/room key
 * rotation</b> ({@code PacketLoop}). So there are real windows — match start,
 * and a few hundred packets after any rotation — in which edges are simply not
 * seen. A latched boolean would read "hip" through all of them and look
 * identical to working; that is the {@code poseValid} trap.</p>
 *
 * <p><b>Therefore this class exposes time, not just truth.</b>
 * {@link #sinceEdgeMs()} is how long since we last saw ANY edge and
 * {@link #hasEverSeenEdge()} whether we have seen one at all this match; a
 * consumer that needs certainty must gate on those, not on {@link #isScoped()}
 * alone. The latch also self-clears when {@link SessionTimeline#matchSeq()}
 * changes, so a scope held across a match end cannot survive into the next
 * one.</p>
 */
public final class ScopeState {

    /** Cmd 137 — scope-in edge, outbound only. See {@code Cmd.RUDP_START_SNIPER}. */
    public static final int CMD_START_SNIPER = 137;
    /** Cmd 138 — scope-out edge, outbound only. See {@code Cmd.RUDP_STOP_SNIPER}. */
    public static final int CMD_STOP_SNIPER = 138;

    /** 0 when not scoped; otherwise the ms clock at the scope-in edge. */
    private static volatile long scopedSinceMs;
    /** ms clock of the last edge of either kind; 0 = none seen this match. */
    private static volatile long lastEdgeMs;
    private static volatile int starts;
    private static volatile int stops;
    /** Edges that repeated the state they were already in (START after START). */
    private static volatile int desyncs;
    /** The match this latch belongs to; a change clears it. */
    private static volatile int matchSeq = -1;

    // ====================================================================
    // Tier probe — WHICH scope, and why this is a measurement and not a map
    // ====================================================================
    //
    // 137/138 are edges, not levels: they say "scoped" and never "8x". The
    // candidate carrier of the level is Cmd 1900 varId 12
    // EPRI_PLAYER_SIGHTING_ID, which is NAMED in the game's own property enum
    // and has never been measured on the wire by this project. It could be a
    // tier ordinal (0/1/2/3), an item id, a bitfield, or a per-weapon slot
    // index -- guessing which would be exactly the kind of assumption this
    // codebase keeps having to retract.
    //
    // So this is an INSTRUMENT, not a decoder. It records every distinct value
    // seen, how often, and -- the discriminating measurement -- the value that
    // was live at the instant of each scope-in edge. Scope in with a 2x, a 4x
    // and an 8x in Training Ground and the mapping reads itself off the panel.
    // Until then the tier is reported as raw numbers and nothing pretends to
    // know what they mean.

    private static final int SIGHT_SLOTS = 8;
    private static final long[] sightVals = new long[SIGHT_SLOTS];
    private static final int[] sightHits = new int[SIGHT_SLOTS];
    private static int sightDistinct;
    /** Most recent varId-12 value from any owner, and when. */
    private static volatile long sightLast = -1L;
    /** The varId-12 value that was live when the last scope-in edge fired. */
    private static volatile long sightAtScopeIn = -1L;
    /** Most recent weapon id from EPRI_ON_HAND — the fixed-scope fallback. */
    private static volatile long weaponLast = -1L;

    private ScopeState() { }

    /**
     * Records one {@code Cmd 1900 varId 12 EPRI_PLAYER_SIGHTING_ID} value.
     *
     * <p><b>Not filtered to our own player.</b> The owner→self join in
     * {@code PriTracker} is fuzzy and takes a while to converge, and filtering
     * on a join that has not converged would silently record nothing — the
     * usual silent-skip. Recording every owner's values and reading the one
     * that moves when YOU scope is strictly more informative for the
     * measurement this exists to serve. {@code ownerId} is kept in the
     * signature so a later self-filter needs no call-site change.</p>
     *
     * <p>Allocation-free: a linear scan of at most 8 longs, on a path that
     * already runs per decoded property.</p>
     */
    public static void observeSighting(long ownerId, long value) {
        rollMatch();
        sightLast = value;
        for (int i = 0; i < sightDistinct; i++) {
            if (sightVals[i] == value) { sightHits[i]++; return; }
        }
        if (sightDistinct < SIGHT_SLOTS) {
            sightVals[sightDistinct] = value;
            sightHits[sightDistinct] = 1;
            sightDistinct++;
            Diag.hit(Slot.SCOPE_SIGHT_VALUE);
        }
    }

    /**
     * Records the weapon id half of {@code EPRI_ON_HAND}. This is the
     * FIXED-scope fallback for tier: AWM/Kar98k/M24 ship 8x pre-attached and
     * SKS/SVD/AC80 are fixed 4x (research B), so the held weapon narrows the
     * magnification even when varId 12 turns out to be useless. It says
     * nothing about a swapped scope on a weapon that accepts one.
     */
    public static void observeWeapon(long weaponId) {
        weaponLast = weaponId;
    }

    /**
     * Feeds one CRC-validated OUTBOUND command id. Does nothing unless it is
     * 137 or 138, so the hot-path cost is two integer comparisons.
     *
     * <p>Call only where the id is known-good: a command read out of an
     * unrelated datagram lands in the MsgKey range about 8% of the time, and an
     * id trusted without its CRC would flip the scope state from noise.</p>
     *
     * @return true when the packet was a scope edge and was consumed
     */
    public static boolean observeOutbound(int cmd) {
        if (cmd != CMD_START_SNIPER && cmd != CMD_STOP_SNIPER) return false;
        rollMatch();
        long now = System.currentTimeMillis();
        boolean in = (cmd == CMD_START_SNIPER);
        // A repeated edge is not a state change; count it and say so rather
        // than let a silent double-START look like a clean scope-in.
        if (in == (scopedSinceMs != 0L)) {
            desyncs++;
            Diag.hit(Slot.SCOPE_PAIR_DESYNC);
        }
        if (in) {
            if (scopedSinceMs == 0L) scopedSinceMs = now;
            // The discriminating sample: whatever varId 12 last said at the
            // moment the scope actually came up.
            sightAtScopeIn = sightLast;
            starts++;
            Diag.hit(Slot.SCOPE_IN);
        } else {
            scopedSinceMs = 0L;
            stops++;
            Diag.hit(Slot.SCOPE_OUT);
        }
        lastEdgeMs = now;
        return true;
    }

    /** Clears the latch when the match changed under us. */
    private static void rollMatch() {
        int seq = SessionTimeline.matchSeq();
        if (seq != matchSeq) {
            matchSeq = seq;
            scopedSinceMs = 0L;
            lastEdgeMs = 0L;
            starts = 0;
            stops = 0;
            desyncs = 0;
            sightDistinct = 0;
            sightLast = -1L;
            sightAtScopeIn = -1L;
            weaponLast = -1L;
        }
    }

    /**
     * True while a scope-in edge is outstanding. <b>Read
     * {@link #sinceEdgeMs()} with it</b> — see the class comment on the solver
     * blind window.
     */
    public static boolean isScoped() {
        rollMatch();
        return scopedSinceMs != 0L;
    }

    /** How long the current scope has been held, or 0 when not scoped. */
    public static long scopedForMs() {
        rollMatch();
        long t = scopedSinceMs;
        return t == 0L ? 0L : System.currentTimeMillis() - t;
    }

    /**
     * ms since the last edge of either kind, or {@link Long#MAX_VALUE} when no
     * edge has been seen this match. The freshness half of the answer.
     */
    public static long sinceEdgeMs() {
        rollMatch();
        long t = lastEdgeMs;
        return t == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /** True once any edge has been observed in the current match. */
    public static boolean hasEverSeenEdge() {
        rollMatch();
        return lastEdgeMs != 0L;
    }

    /** The most recent varId-12 value from any owner, or -1 if none yet. */
    public static long lastSightingValue() {
        return sightLast;
    }

    /** The varId-12 value live at the last scope-in edge, or -1. */
    public static long sightingAtScopeIn() {
        return sightAtScopeIn;
    }

    /** One line for the dev panel: the scope ON/OFF latch. */
    public static String debugLine() {
        rollMatch();
        StringBuilder sb = new StringBuilder(48);
        if (lastEdgeMs == 0L) {
            sb.append("hip (no edge yet)");
        } else if (scopedSinceMs != 0L) {
            sb.append("SCOPED ").append(scopedForMs() / 100L / 10f).append('s');
        } else {
            sb.append("hip");
        }
        sb.append(" n=").append(starts).append('/').append(stops);
        if (desyncs > 0) sb.append(" desync=").append(desyncs);
        return sb.toString();
    }

    /**
     * Second line for the dev panel: the tier PROBE.
     *
     * <p>Reads {@code @in=} the varId-12 value that was live at the last
     * scope-in, {@code now=} the latest value from any owner, {@code wpn=} the
     * held weapon id, and then every distinct varId-12 value seen this match
     * with its hit count. <b>Scope in with a 2x, then a 4x, then an 8x</b> and
     * compare the three {@code @in=} readings — if they differ, varId 12 is the
     * tier and the mapping is right there. If they do not, varId 12 is not the
     * level and {@code wpn=} plus Cmd 124 {@code RUDP_ATTACHMENT_CHANGED} are
     * the next two candidates.</p>
     */
    public static String probeLine() {
        rollMatch();
        StringBuilder sb = new StringBuilder(64);
        sb.append("@in=").append(sightAtScopeIn < 0 ? "-" : Long.toString(sightAtScopeIn));
        sb.append(" now=").append(sightLast < 0 ? "-" : Long.toString(sightLast));
        sb.append(" wpn=").append(weaponLast < 0 ? "-" : Long.toString(weaponLast));
        if (sightDistinct == 0) {
            sb.append("  (no varId-12 seen)");
        } else {
            sb.append("  seen:");
            for (int i = 0; i < sightDistinct; i++) {
                sb.append(' ').append(sightVals[i]).append('x').append(sightHits[i]);
            }
            if (sightDistinct >= SIGHT_SLOTS) sb.append(" +more");
        }
        return sb.toString();
    }
}
