package com.nepdon.wire.filter;

import com.nepdon.wire.util.Humanize;
import com.nepdon.wire.protocol.GamePacketTaxonomy;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.esp.EspEngine;
import com.nepdon.wire.decode.body.Cmd;
import com.nepdon.wire.decode.body.PositionSync;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Teleport: moves where the server thinks the player is.
 *
 * <h3>Why the old implementation could never work</h3>
 *
 * <p>Teleport used to be a hold-and-replay ("jitter"): buffer outbound packets
 * while the player walks, then flush them at 1-50 ms spacing. Three measured
 * facts each kill that design on their own.</p>
 *
 * <ol>
 *   <li><b>It buffered the wrong packets.</b> The learned "movement" signature
 *       resolved to the 24-byte encrypted input stream, and the fire
 *       re-classifier swept in the reliable control channel. Across 229
 *       captures there are exactly <b>9 HOLD rows, all 26 B and 34 B control
 *       packets — not one position report was ever held</b>. The position
 *       channel matched neither window and was passed straight through, so the
 *       server always knew exactly where the player was.</li>
 *   <li><b>A flush is not a teleport.</b> Replaying the buffer at 1-50 ms
 *       replays the <i>walked path</i> compressed 20-100x: a speedrun, not a
 *       jump, every packet an oversized delta.</li>
 *   <li><b>The server rejects jumps.</b> Over 52 release events the accept /
 *       reject split is perfectly separated by the distance between the newly
 *       reported position and the server's held one: <b>largest accepted
 *       2.77 m, smallest rejected 5.31 m</b> (p = 4.5e-5 by chance). Rejection
 *       snaps the player back after a hard 399-402 ms. It is a distance gate,
 *       not a speed gate — a 12.9 m jump was rejected at a walkable 0.97 m/s,
 *       while 13.47 m/s was accepted because each individual step was small.
 *       So no amount of withholding can ever produce a jump; releasing one is
 *       exactly what gets it refused.</li>
 * </ol>
 *
 * <h3>What this does instead: hold like ghost, then walk the real route</h3>
 *
 * <p>The two phases use <b>different mechanisms</b>, deliberately.</p>
 *
 * <p><b>Holding uses ghost's mechanism</b> — drop the live report, re-send the
 * anchored one verbatim at {@link #REPLAY_INTERVAL_MS}. Holding and rewriting
 * reach the same server-side state, but only one is proven in play: ghost has
 * never disconnected anyone, while rewriting during the hold dropped the player
 * out of the match on every attempt. That isolates the <i>mechanism</i> rather
 * than the effect, so the hold uses the one that works. A verbatim replay also
 * passes any body integrity check the server might have — which is exactly why
 * ghost was never able to probe for one (docs/TELEPORT.md §8).</p>
 *
 * <p><b>The release rewrites</b>, because it has to: catching the server up
 * means emitting positions the client never sent. Each report moves the belief
 * by at most {@link #MAX_STEP_M} metres — under the 2.77 m ceiling — so the
 * distance gate never fires.</p>
 *
 * <pre>
 *   press  ON   report = the anchor, velocity = 0. The server sees a player
 *               standing still while the real player walks anywhere, and the
 *               route they walk is recorded. (Inbound is untouched, so the
 *               player's own screen is normal.)
 *   press  OFF  the server is caught up along that recorded route at the step
 *               budget. This runs itself to completion and reports as OFF --
 *               the button must agree with the press.
 * </pre>
 *
 * <p><b>The catch-up follows the walked route, never a straight line.</b> A
 * straight line from anchor to player cuts through walls and terrain and, near
 * an edge, leaves the playable area — the server answers that with
 * out-of-bounds damage and a correction, which is precisely how the first
 * build failed in play. The recorded route is legal by construction: the
 * player physically walked it. Loops and backtracking are collapsed as they
 * are recorded, so walking out and back does not replay the detour.</p>
 *
 * <p>At the default {@link #DEFAULT_STEP_M} m per 10 Hz report that is 25 m/s,
 * roughly five times sprint speed. That is the fastest this server will accept;
 * a true instant jump is refused by the distance gate no matter how it is
 * delivered.</p>
 *
 * <h3>Combat is never touched</h3>
 *
 * <p>Only {@code Cmd 2001} is rewritten. Fire events, damage, reloads, the
 * input stream, the reliable control channel and both liveness families are
 * passed through untouched and unbuffered, so shooting and taking damage work
 * normally throughout — unlike the old design, which held fire packets and
 * dropped live ones during the flush.</p>
 *
 * <h3>Why this is quieter than the old design</h3>
 *
 * <ul>
 *   <li>During the release, velocity is rewritten with the position, avoiding
 *       the "position moved, declared velocity zero" shape that is absent from
 *       real traffic.</li>
 *   <li>The cadence is untouched: one rewritten report per real report, no
 *       burst. Size changes by the two or three bytes a larger velocity varint
 *       needs, inside a channel whose real lengths already span 48-67 B.</li>
 *   <li>The hold behaves exactly like ghost, which is known-good in play.</li>
 * </ul>
 */
public final class TeleportFilter implements PacketFilter {

    /**
     * Hard ceiling per report. The largest jump ever observed to be accepted
     * was 2.77 m; staying under it is the whole reason this works. Never raise
     * this without re-running the bracket experiment in {@code docs/TELEPORT.md}.
     */
    public static final double MAX_STEP_M = 2.70;

    /**
     * Default step: 1.35 m per 100 ms report = 13.5 m/s, ~2.7x sprint.
     *
     * <p><b>It was 2.50, which left no margin for a single lost datagram.</b>
     * UDP position reports are non-reliable and are not retransmitted. When one
     * is lost the server's belief stays a step behind, so the next report is
     * {@code 2 * stepM} away from what the server holds — 5.00 m at the old
     * default, past the 5.31 m smallest jump ever observed to be rejected and
     * well past the 2.77 m largest ever accepted. One dropped packet was enough
     * to snap the player back.</p>
     *
     * <p>At 1.35 m a single loss produces 2.70 m, which is inside the measured
     * accepted ceiling. The cost is that a ramp takes about twice as long. That
     * is the right trade: a slower teleport that always lands beats a fast one
     * that rubber-bands.</p>
     */
    public static final double DEFAULT_STEP_M = 1.35;

    /** Minimum step, so a misconfigured slider cannot stall a teleport. */
    public static final double MIN_STEP_M = 0.50;

    /**
     * Per-report step budget as a function of the report's own movement state.
     *
     * <h3>Why a single constant was wrong</h3>
     *
     * <p>{@link #MAX_STEP_M} came from {@code docs/TELEPORT.md} §1, which
     * measured a <i>global</i> "largest accepted jump 2.77 m, smallest rejected
     * 5.31 m". <b>An absolute gate in that interval is refuted.</b> A 6.206 m
     * outbound step at a normal 99 ms tick was accepted — the self-echo
     * followed it — while a 5.31 m step was rejected. The intervals overlap, so
     * no single constant separates them. What differs is the movement state the
     * report declares.</p>
     *
     * <p>Measured per state over <b>19,213 outbound reports</b> across two
     * sessions (metres per ~99 ms tick, {@code platformId == 0}):</p>
     *
     * <pre>
     *   wire state            p50     p99     max     n
     *   0  walking/idle      0.084   0.561   1.980   2256
     *   0  sprint (pose 2)   0.561   0.682   1.084   3025
     *   1  falling           0.539   0.998   1.168    655
     *   2  parachuting       1.346   1.766   2.212    132
     *   4  skydiving         3.043   4.869   6.206    122
     *   7  zipline           1.485   2.227   2.228    281   (hard cap 2.250)
     * </pre>
     *
     * <p><b>On foot the client has never emitted a step above 2.000 m.</b> The
     * old 2.50 m default therefore asked the server to believe a player was
     * moving at 3.7x sprint p99 <i>while the same packet's {@code physState}
     * still said sprinting</i> — {@code writeState} rewrites position and
     * velocity and never touches {@link PositionSync#V_PHYS_STATE} or
     * {@link PositionSync#V_POSE}. That incoherence is sustained for the whole
     * ramp, not for a tick or two.</p>
     *
     * <p>Values below sit at or under each state's measured p99, so a rewritten
     * report is indistinguishable in magnitude from something the client
     * genuinely emits in that state. <b>Do not raise these toward the observed
     * max</b> — the max is a single sample, and a lost datagram doubles the
     * effective step.</p>
     *
     * <p>Skydiving is the outlier and the opportunity: ~4 m/tick is roughly
     * <b>six times</b> the on-foot budget, so a release during the parachute
     * descent is by far the fastest legal teleport in the game. Unproven in
     * play — nothing has ever been captured releasing in that state.</p>
     */
    private static double budgetFor(int physStateWire, int pose) {
        switch (physStateWire) {
            case 4:  return 4.00;   // SKYDIVING   (p99 4.869)
            case 2:  return 1.70;   // PARACHUTING (p99 1.766)
            case 7:  return 2.00;   // ONSTROP     (hard-capped at 2.250)
            case 3:  return 2.00;   // ONBOARD     (aircraft)
            case 1:  return 0.95;   // FALLING     (p99 0.998)
            case 0:
            default:
                // ON FOOT THE CEILING IS SET BY THE POSE, NOT THE STATE.
                //
                // This used to return a flat 0.90 for everything that encodes
                // as wire 0, which was measured to be over the line: the step
                // we inject becomes the declared speed, and against 30,258
                // genuine packets the all-time maxima per cell are
                //
                //     phys=0 pose=2 (sprint)   683 cm/s   0 of 9,631 above
                //     phys=0 pose=1 (crouch)   285 cm/s   0 of 2,768 above
                //     phys=0 pose=0 (walk)     683 cm/s   3 of 13,447 above
                //
                // 0.90 m per 100 ms tick is 900 cm/s -- above every one of
                // those, and more than 3x the crouch ceiling. 13 of the first
                // 20 real ramp reports were strictly above the all-time max
                // for the pose they themselves declared, which is free for a
                // server to test because it already holds our physState (it
                // echoes it back in every Cmd 144).
                switch (pose) {
                    case 1:  return 0.25;   // CROUCHING, ceiling 285 cm/s
                    case 2:  return 0.60;   // DASHING/sprint, ceiling 683
                    case 0:  return 0.60;   // STANDING/walking, ceiling 683
                    default: return 0.50;   // unfamiliar pose: stay well under
                }
        }
    }

    /** Close enough to the real position to stop rewriting. */
    public static final double LAND_EPS_M = 0.75;

    /**
     * Planar step below which the real {@code moveDir} is left untouched.
     *
     * <p>100 units = 0.10 m. Below that a centred stick is the norm, not an
     * anomaly: 82% of genuine sub-0.10 m ticks declare {@code moveDir (0,0)}.
     * Forcing a direction there would itself be the unusual shape.</p>
     */
    private static final double MOVE_DIR_DEADBAND_UNITS = 100.0;

    /**
     * How far ahead the declared velocity leads the step just taken.
     *
     * <p>The game's velocity field predicts the next tick rather than
     * reporting the last one. 1.0 would reproduce the backward difference
     * exactly, which is the 100%-versus-0.100% signature this exists to
     * remove.</p>
     */
    private static final double VEL_LOOKAHEAD = 1.02;

    /**
     * Fractional jitter applied to each ramp step, +/- this much.
     *
     * <p><b>A constant step is a fingerprint on its own.</b> Of the first 16
     * intra-burst ramp steps ever captured, 12 landed within 0.4% of exactly
     * 0.900 m. Genuine per-tick displacement is a continuous distribution
     * (p99 0.669 m, p99.9 1.008 m) and is never a constant, because a human
     * thumb on a stick is not a metronome. The ramp now varies its step so the
     * sequence has a spread instead of a signature.</p>
     */
    private static final double STEP_JITTER = 0.18;

    /**
     * Cheap deterministic jitter source in [0,1).
     *
     * <p>Deliberately not {@code java.util.Random}: this runs on the tunnel
     * thread for every rewritten packet, and the hot path allocates nothing.
     * An xorshift on a per-filter seed is sufficient — nothing here needs to
     * be unpredictable to an attacker, only non-constant.</p>
     */
    private long jitterState = 0x9E3779B97F4A7C15L;

    private double nextJitter() {
        long s = jitterState;
        s ^= (s << 13);
        s ^= (s >>> 7);
        s ^= (s << 17);
        jitterState = s;
        return ((s >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
    }

    /**
     * How often the anchor packet is re-sent while holding. The natural report
     * rate is ~10 Hz; 2 Hz keeps the server's liveness timers fed while the
     * position stays pinned. Same value ghost uses, for the same reason.
     */
    public static final long REPLAY_INTERVAL_MS = 500L;

    private final DevLogger logger;
    private final PacketSender sender;
    private final PositionSync sync = new PositionSync();

    /** The report captured at the moment of anchoring, re-sent verbatim. */
    private final Object anchorLock = new Object();
    private byte[] anchorPacket;
    private int anchorDip, anchorDp, anchorSp;
    private volatile long lastReplayMs;
    private final Humanize humanize = new Humanize();
    /** Gap to the next replay. Re-rolled on every replay so no fixed period survives. */
    private volatile long nextReplayGapMs = REPLAY_INTERVAL_MS;
    /** Replay gap is REPLAY_INTERVAL_MS plus up to this fraction. Never shorter. */
    public static final double REPLAY_JITTER_FRAC = 0.30;

    /** True when a replay is due now; re-rolls the next gap when it is. */
    private boolean replayDue(long now) {
        if (now - lastReplayMs < nextReplayGapMs) return false;
        lastReplayMs = now;
        nextReplayGapMs = humanize.upMs(REPLAY_INTERVAL_MS, REPLAY_JITTER_FRAC);
        return true;
    }

    private volatile TeleportState state = TeleportState.IDLE;
    private volatile double stepM = DEFAULT_STEP_M;

    /** The position we last told the server, in game units. */
    private long repX, repZ, repY;
    private boolean anchored;

    // ---- dev-panel telemetry (one filter per tunnel) ----
    public static volatile String dbgState = TeleportState.IDLE.label();
    public static volatile boolean dbgExact = false;
    /** Position reports seen while engaged. */
    public static final AtomicLong dbgSeen = new AtomicLong();
    /** Reports successfully rewritten. */
    public static final AtomicLong dbgRewritten = new AtomicLong();
    /** Reports withheld because no rewrite was possible (no layout / no room). */
    public static final AtomicLong dbgWidthDrop = new AtomicLong();
    /** Rewrites that fitted the existing field widths (packet size unchanged). */
    public static final AtomicLong dbgInPlace = new AtomicLong();
    /** Rewrites that needed a canonical rebuild (packet grew a few bytes). */
    public static final AtomicLong dbgRebuilt = new AtomicLong();
    /** Reports withheld because the body did not parse (never leak a real one). */
    public static final AtomicLong dbgParseDrop = new AtomicLong();
    /** Reports withheld while holding the anchor (the ghost-style path). */
    public static final AtomicLong dbgDropped = new AtomicLong();
    /** Reports withheld because the player is mounted (2002/2006/2031). */
    public static final AtomicLong dbgMounted = new AtomicLong();
    /** Reports withheld because the position was platform-relative. */
    public static final AtomicLong dbgPlatform = new AtomicLong();
    /** Metres still to cover in the current ramp. */
    public static volatile double dbgRemainM = 0.0;
    /** Step budget the last ramp report actually used, after the state clamp. */
    public static volatile double dbgBudgetM = 0.0;
    /** Inbound Cmd 144 force-syncs seen for our own entity while engaged. */
    public static final java.util.concurrent.atomic.AtomicLong dbgForceSync =
            new java.util.concurrent.atomic.AtomicLong();

    public static String debugLine() {
        return dbgState
                + (dbgExact ? " EXACT" : " heur")
                + " seen=" + dbgSeen.get()
                + " rw=" + dbgRewritten.get()
                + " inpl=" + dbgInPlace.get()
                + " rb=" + dbgRebuilt.get()
                + " nofit=" + dbgWidthDrop.get()
                + " pdrop=" + dbgParseDrop.get()
                + " held=" + dbgDropped.get()
                + (dbgMounted.get() > 0 ? " mnt=" + dbgMounted.get() : "")
                + (dbgPlatform.get() > 0 ? " plat=" + dbgPlatform.get() : "")
                + String.format(java.util.Locale.US, " left=%.1fm bud=%.2f fsync=%d",
                        dbgRemainM, dbgBudgetM, dbgForceSync.get());
    }

    public TeleportFilter(PacketSender sender, DevLogger logger) {
        this.sender = sender;
        this.logger = logger;
    }

    /** Re-sends the anchored report verbatim, exactly as ghost does. */
    /**
     * Forgets the saved anchor packet so no stale bytes can be replayed.
     *
     * <p>Also clears {@code lastReplayMs}'s effect by leaving the packet null:
     * {@link #replayAnchor()} becomes a no-op until the next real position
     * report re-captures at the current hold.</p>
     */
    private void clearAnchorPacket() {
        synchronized (anchorLock) {
            anchorPacket = null;
        }
    }

    private void replayAnchor() {
        byte[] pkt;
        int dip, dp, sp;
        synchronized (anchorLock) {
            if (anchorPacket == null) {
                // Nothing safe to replay. Loud, because a silent no-op here is
                // "teleport did nothing and I don't know why".
                com.nepdon.wire.diag.Diag.hit(
                        com.nepdon.wire.diag.Slot.FLT_TELE_STALE_ANCHOR);
                return;
            }
            pkt = anchorPacket.clone();
            dip = anchorDip;
            dp = anchorDp;
            sp = anchorSp;
        }
        sender.send(new TelePacket(pkt, dip, dp, sp, TelePacket.TYPE_MOVE));
        logger.logPacket("REPLAY_TELE_ANCHOR", "OUT", pkt, 0, pkt.length,
                sp, dp, dip, "tele=anchor replay");
    }

    @Override
    public String name() {
        return "tele";
    }

    /**
     * True only while ANCHORED. The ramp deliberately reports as off: the user
     * pressed off, so the button must say off and finish the release on its
     * own. Reporting it as on read as a broken toggle and got pressed again.
     */
    @Override
    public boolean isActive() {
        return state.isEngaged();
    }

    public TeleportState state() {
        return state;
    }

    /** Step budget in metres per report, clamped to the measured safe range. */
    public void setStepMetres(double metres) {
        double v = metres;
        if (v < MIN_STEP_M) v = MIN_STEP_M;
        if (v > MAX_STEP_M) v = MAX_STEP_M;
        stepM = v;
    }

    public double stepMetres() {
        return stepM;
    }

    // ---- policy ----

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        // Server-gated leash: no live profile => never rewrite the position report
        // (PASS it untouched). Compile-time-false flag => R8 strips this.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && !com.nepdon.wire.auth.ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return FilterDecision.PASS;
        }
        if (!isPositionReport(ctx)) {
            // Fire, damage, reloads, input, control, liveness: untouched.
            return FilterDecision.PASS;
        }
        TeleportState s = state;
        if (s == TeleportState.IDLE) return FilterDecision.PASS;

        dbgState = s.label();
        dbgSeen.incrementAndGet();

        // MOUNTED: the position rides 2002/2006/2031, whose bodies we have not
        // decoded. Withholding is still safe and still hides us, so keep doing
        // that -- but the ramp must stand down rather than rewrite a body it
        // cannot parse. The route keeps its last on-foot point, so releasing
        // after dismounting still works.
        if (ctx.hasCmd() && Cmd.isMountedPositionCarrier(ctx.cmd)) {
            dbgMounted.incrementAndGet();
            long now = System.currentTimeMillis();
            if (replayDue(now)) {
                replayAnchor();
            }
            dbgDropped.incrementAndGet();
            return FilterDecision.DROP;
        }

        if (!sync.parse(ctx.buf, ctx.off, ctx.plen)) {
            // Identified as our position channel but not parseable (a key
            // rotation, an unexpected variant). Withhold rather than transmit
            // a real position — a dropped report costs nothing, liveness rides
            // other families entirely.
            dbgParseDrop.incrementAndGet();
            logger.logPacket("DROP_TELE_UNPARSED", "OUT", ctx.buf, ctx.off, ctx.plen,
                    ctx.sp, ctx.dp, ctx.dip, "tele=" + s.label() + " parse=fail");
            return FilterDecision.DROP;
        }

        // PLATFORM-RELATIVE GUARD. When the platform id is non-zero the sender
        // replaces the world position with an offset relative to that platform,
        // so v0-v2 is a few centimetres, not a map coordinate. Recording that
        // as a route point would inject a jump to near the origin. Withhold and
        // wait -- hiding still works, the route just does not grow.
        if (sync.rawAt(PositionSync.V_PLATFORM_ID) != 0) {
            dbgPlatform.incrementAndGet();
            long nowP = System.currentTimeMillis();
            if (replayDue(nowP)) {
                replayAnchor();
            }
            dbgDropped.incrementAndGet();
            return FilterDecision.DROP;
        }

        long realX = sync.signedAt(PositionSync.V_POS_X);
        long realZ = sync.signedAt(PositionSync.V_POS_Z);
        long realY = sync.signedAt(PositionSync.V_POS_Y);

        if (!anchored) {
            repX = realX;
            repZ = realZ;
            repY = realY;
            anchored = true;
            pathReset(realX, realZ, realY);
            dbgRemainM = 0.0;
            synchronized (anchorLock) {
                anchorPacket = ctx.copyPayload();
                anchorDip = ctx.dip;
                anchorDp = ctx.dp;
                anchorSp = ctx.sp;
            }
            lastReplayMs = System.currentTimeMillis();
            logger.logControl("TELE_ANCHOR", "tele=anchor"
                    + " x=" + repX + " z=" + repZ + " y=" + repY);
            return FilterDecision.PASS;   // this packet already reports the anchor
        }

        // HOLDING USES GHOST'S MECHANISM, NOT A REWRITE.
        //
        // Holding position and rewriting position are two ways to reach the
        // same server-side state, and only one of them is proven in play:
        // ghost drops the live report and re-sends the saved one verbatim, and
        // it has never disconnected anyone. The rewrite did -- turning teleport
        // ON dropped the player out of the match, while ghost doing the same
        // job did not. That isolates the mechanism rather than the effect, so
        // the anchor phase now uses the one that works.
        //
        // A verbatim replay also passes any body integrity check the server
        // might have (docs/TELEPORT.md section 8, still unanswered) -- which is
        // exactly why ghost was never able to probe for one.
        if (s == TeleportState.ANCHOR) {
            pathAppend(realX, realZ, realY);
            dbgRemainM = pathRemainingMetres(realX, realZ, realY);
            long now = System.currentTimeMillis();
            if (replayDue(now)) {
                replayAnchor();
            }
            dbgDropped.incrementAndGet();
            return FilterDecision.DROP;
        }

        long targetX = repX;
        long targetZ = repZ;
        long targetY = repY;

        // RAMP. Recording continues through the release: the player keeps
        // moving while the server is being caught up, and without those points
        // the final leg straight-lines to wherever they have reached --
        // reintroducing the very shortcut through scenery this design exists to
        // avoid. The chase still converges, because the ramp consumes 2.5 m of
        // route per report while the player adds at most ~0.7 m (p90 of real
        // movement).
        pathAppend(realX, realZ, realY);

        // THE CURSOR MUST NEVER RUN AHEAD OF WHAT THE SERVER WAS TOLD.
        //
        // This is the bug that stranded the player at the anchor. pathAdvance
        // MUTATES cursorX/Z/Y and pathAt; repX/Z/Y -- the only record of what
        // was actually transmitted -- updated only on the success path below.
        // So every dropped ramp report desynchronised the two, permanently and
        // cumulatively:
        //
        //   1 drop  -> the next accepted report moves the server 2 * stepM
        //              = 5.0 m in one packet, over the measured 2.77 m ceiling.
        //   3 drops -> 10 m, past the 5.31 m smallest-ever-rejected jump.
        //   all dropped -> the cursor still walks the whole route, pathAdvance
        //              returns -1, land("arrived") fires, the state goes IDLE
        //              and the next REAL report announces B while the server
        //              still holds A. One jump of the entire distance.
        //
        // That last case is exactly the reported symptom: "I anchor at A, walk
        // to B, press off, and I get put back at A and I am stuck." The server
        // rejects the jump and corrects the client 399-402 ms later; repeating
        // that reads as stuck.
        //
        // And the drop is not rare. A 2.5 m step needs the velocity field to
        // carry 2500 units (zigzag 5000 = 2 bytes), but 58% of real velocity
        // fields are ONE byte (PositionSync), so the in-place path usually
        // fails and the canonical rebuild runs -- which returns -1 whenever the
        // header layout is stale. That is the whole re-convergence window after
        // a match or room transition rotates the key: precisely when a player
        // is toggling teleport.
        //
        // Fix: snapshot the cursor, and roll it back if the write fails. The
        // server's belief and our bookkeeping then advance together or not at
        // all, which is the invariant the 2.70 m clamp silently depended on.
        final long saveCursorX = cursorX;
        final long saveCursorZ = cursorZ;
        final long saveCursorY = cursorY;
        final int savePathAt = pathAt;

        // The budget follows the state THIS report declares, because that is
        // what the server is being asked to believe. writeState never rewrites
        // physState/pose, so a step far outside the declared state's normal
        // range is a self-contradiction inside one packet. See budgetFor().
        final int physState = (int) sync.rawAt(PositionSync.V_PHYS_STATE);
        final int pose = (int) sync.rawAt(PositionSync.V_POSE);
        final double stateCeilM = budgetFor(physState, pose);
        double budgetM = Math.min(stepM, stateCeilM);
        // Break the metronome. A constant per-tick displacement is itself a
        // signature -- 12 of the first 16 captured ramp steps were within 0.4%
        // of each other, against a genuine distribution that is continuous and
        // never constant. Jitter DOWN only, so the state ceiling stays a hard
        // ceiling.
        budgetM *= (1.0 - STEP_JITTER * nextJitter());
        // MIN_STEP_M guards the USER'S SLIDER against being set uselessly low.
        // It must never raise the step back above the state's measured
        // ceiling: crouching allows 0.25 m/tick and MIN_STEP_M is 0.50, so
        // applying it unconditionally would have put every crouched ramp step
        // at twice the ceiling -- reintroducing the exact defect above.
        double floorM = Math.min(MIN_STEP_M, stateCeilM);
        if (budgetM < floorM) budgetM = floorM;
        dbgBudgetM = budgetM;

        double moved = pathAdvance(budgetM * EspEngine.UNITS_PER_METRE,
                realX, realZ, realY);
        if (moved < 0.0) {
            // Arrival is decided against repX/Z/Y, NOT the cursor. Landing on a
            // cursor the server was never told about is what released the full
            // jump above.
            double gapM = dist(repX, repZ, repY, realX, realZ, realY)
                    / EspEngine.UNITS_PER_METRE;
            if (gapM > MAX_STEP_M) {
                // The route is spent but the server is still behind us, so the
                // cursor had run ahead. Re-aim at the player and keep ramping
                // in legal steps instead of landing into a rejected jump.
                com.nepdon.wire.diag.Diag.hit(
                        com.nepdon.wire.diag.Slot.FLT_TELE_CURSOR_BEHIND);
                cursorX = repX;
                cursorZ = repZ;
                cursorY = repY;
                pathAt = pathN;
                moved = pathAdvance(stepM * EspEngine.UNITS_PER_METRE,
                        realX, realZ, realY);
            }
            if (moved < 0.0) {
                land("arrived");
                return FilterDecision.PASS;
            }
        }
        targetX = cursorX;
        targetZ = cursorZ;
        targetY = cursorY;
        dbgRemainM = pathRemainingMetres(realX, realZ, realY);

        int newLen = writeState(targetX, targetZ, targetY);
        if (newLen < 0) {
            // Could not rewrite this one: no solved header layout yet, or no
            // room in the buffer. Withhold rather than transmit the real
            // position — the effect degrades to "frozen", never to "leaked".
            //
            // ROLL THE CURSOR BACK. Nothing was sent, so nothing moved.
            cursorX = saveCursorX;
            cursorZ = saveCursorZ;
            cursorY = saveCursorY;
            pathAt = savePathAt;
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.FLT_TELE_WRITE_FAILED);
            dbgWidthDrop.incrementAndGet();
            logger.logPacket("DROP_TELE_NOFIT", "OUT", ctx.buf, ctx.off, ctx.plen,
                    ctx.sp, ctx.dp, ctx.dip, "tele=" + s.label() + " write=fail");
            return FilterDecision.DROP;
        }

        ctx.setOutLength(newLen);
        // Record the edit so the capture shows REWRITE rather than PASS. Its
        // absence is why ramp rewrites were invisible in every dump.
        ctx.markRewritten();
        repX = targetX;
        repZ = targetZ;
        repY = targetY;
        com.nepdon.wire.diag.Diag.hit(
                com.nepdon.wire.diag.Slot.FLT_TELE_REWRITTEN);
        dbgRewritten.incrementAndGet();
        return FilterDecision.PASS;
    }

    /**
     * Inbound is never touched. The player's own client keeps rendering the
     * real world normally; only the server's belief about the player is moved.
     */
    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        return FilterDecision.PASS;
    }

    /**
     * Writes position and the matching velocity, then reseals the checksum.
     *
     * <p>Prefers the in-place path, which keeps the packet byte-identical in
     * size and needs no length repair. That alone cannot carry a teleport
     * though: 58% of real velocity fields are a single byte, holding
     * &plusmn;0.066 m, which caps a ramp at a measured 1.15 m/s. So a step of
     * any useful size falls through to the canonical rebuild, which re-encodes
     * the body and repairs the packet's own {@code Length}. Fit is checked for
     * every component before anything is written, so a packet is never left
     * half-rewritten.</p>
     *
     * @return the packet's new total length, or -1 when it was left untouched
     */
    private int writeState(long x, long z, long y) {
        // VELOCITY MUST NOT BE THE EXACT BACKWARD DIFFERENCE OF OUR OWN
        // POSITIONS. That is what it used to be -- literally x - repX -- and it
        // is the single most damning signature measured on the first real ramp:
        //
        //     ours     16 of 16 non-opening rewrites = 100%
        //     genuine  16 of 15,962 moving packets   =   0.100%
        //
        // The game's velocity field LEADS: it predicts the NEXT tick's
        // displacement, not the last one (median relative error 0.0125 forward
        // versus 0.0143 backward, and only 0.47% of genuine packets are an
        // exact forward match). A field that reproduces the previous step to
        // the integer, every single tick, is a thousand times rarer than
        // anything the client emits, and costs a server nothing to test.
        //
        // The ramp knows where it is going, so it can declare a genuine
        // lookahead: the direction of the step just taken, carried forward at
        // the same rate. That is forward-leaning like the real field, and it
        // breaks the integer-exact identity because it is scaled, not copied.
        long bx = x - repX;
        long bz = z - repZ;
        long by = y - repY;
        double back = Math.sqrt((double) bx * bx + (double) bz * bz + (double) by * by);
        long vx, vz, vy;
        if (back >= 1.0) {
            // Lead by LOOKAHEAD, and jitter it. Genuine velocity/displacement
            // agreement has a spread; ours had none at all.
            double lead = VEL_LOOKAHEAD * (0.97 + 0.06 * nextJitter());
            vx = Math.round(bx * lead);
            vz = Math.round(bz * lead);
            vy = Math.round(by * lead);
        } else {
            vx = bx;
            vz = bz;
            vy = by;
        }

        // Declare the INPUT that would produce this step, not just the motion.
        //
        // Rewriting position and velocity while leaving moveDir alone emits
        // "I travelled 0.9 m with the stick centred" -- a shape that occurs in
        // 2 of 20,021 genuine on-foot ticks (0.010%), and which the first
        // captured ramp emitted three and four times in a row. It is exactly
        // the incoherence the velocity rewrite exists to avoid, in a field
        // nobody had looked at. Unproven as the cause of the rejection, but it
        // is a measured implausibility we are creating for free.
        //
        // moveDir is a horizontal unit vector scaled by 100 (measured: values
        // pile up at +-100 and +-96..99). Below the deadband the step is small
        // enough that a centred stick is ordinary -- 82% of genuine sub-0.10 m
        // ticks declare (0,0) -- so leave the real value alone there.
        long mdx = sync.signedAt(PositionSync.V_MOVE_DIR_X);
        long mdz = sync.signedAt(PositionSync.V_MOVE_DIR_Z);
        double planar = Math.sqrt((double) vx * vx + (double) vz * vz);
        if (planar >= MOVE_DIR_DEADBAND_UNITS) {
            mdx = Math.round(100.0 * vx / planar);
            mdz = Math.round(100.0 * vz / planar);
        }

        boolean inPlace =
                sync.fitsSigned(PositionSync.V_POS_X, x)
                && sync.fitsSigned(PositionSync.V_POS_Z, z)
                && sync.fitsSigned(PositionSync.V_POS_Y, y)
                && sync.fitsSigned(PositionSync.V_VEL_X, vx)
                && sync.fitsSigned(PositionSync.V_VEL_Z, vz)
                && sync.fitsSigned(PositionSync.V_VEL_Y, vy)
                && sync.fitsSigned(PositionSync.V_MOVE_DIR_X, mdx)
                && sync.fitsSigned(PositionSync.V_MOVE_DIR_Z, mdz);

        if (inPlace) {
            sync.setSigned(PositionSync.V_POS_X, x);
            sync.setSigned(PositionSync.V_POS_Z, z);
            sync.setSigned(PositionSync.V_POS_Y, y);
            sync.setSigned(PositionSync.V_VEL_X, vx);
            sync.setSigned(PositionSync.V_VEL_Z, vz);
            sync.setSigned(PositionSync.V_VEL_Y, vy);
            sync.setSigned(PositionSync.V_MOVE_DIR_X, mdx);
            sync.setSigned(PositionSync.V_MOVE_DIR_Z, mdz);
            dbgInPlace.incrementAndGet();
            return sync.seal() ? sync.length() : -1;
        }

        // Canonical rebuild. It needs the solved header layout to repair
        // Length; before the solver converges there is none and the packet is
        // withheld instead, which still freezes the position — just without
        // the ramp.
        com.nepdon.wire.protocol.PacketPipeline pipeline =
                com.nepdon.wire.protocol.PacketPipeline.current();
        if (pipeline == null) return -1;
        com.nepdon.wire.decode.frame.HeaderLayout layout = pipeline.layout();
        if (layout == null) return -1;

        sync.stageSigned(PositionSync.V_POS_X, x);
        sync.stageSigned(PositionSync.V_POS_Z, z);
        sync.stageSigned(PositionSync.V_POS_Y, y);
        sync.stageSigned(PositionSync.V_VEL_X, vx);
        sync.stageSigned(PositionSync.V_VEL_Z, vz);
        sync.stageSigned(PositionSync.V_VEL_Y, vy);
        sync.stageSigned(PositionSync.V_MOVE_DIR_X, mdx);
        sync.stageSigned(PositionSync.V_MOVE_DIR_Z, mdz);
        int n = sync.rebuild(layout);
        if (n > 0) dbgRebuilt.incrementAndGet();
        return n;
    }

    // ---- the walked route ------------------------------------------------
    //
    // A straight line from the anchor to the player is NOT a legal path. It
    // cuts through walls and terrain and, near an edge, leaves the playable
    // area entirely -- which the server answers with out-of-bounds damage and
    // a correction. That was the first build's real defect, reported as
    // "teleports me somewhere off the map, damages me, then sets me back".
    //
    // So the ramp does not interpolate. While anchored, every real position is
    // recorded, and the ramp replays THAT polyline, fast. The route is legal by
    // construction: the player physically walked it, so it stays on the ground,
    // inside the map, and around obstacles.

    /** Points held. 4096 at the decimation below is about 1.6 km of walking. */
    private static final int PATH_MAX = 4096;
    /** Minimum spacing between recorded points. */
    private static final double PATH_STEP_M = 0.40;
    /**
     * Coming back within this of an earlier point collapses the loop between
     * them. Walking out and back is the common case, and without this the ramp
     * would faithfully replay the entire detour.
     */
    private static final double PATH_LOOP_M = 2.00;

    private final long[] pathX = new long[PATH_MAX];
    private final long[] pathZ = new long[PATH_MAX];
    private final long[] pathY = new long[PATH_MAX];
    private int pathN;
    /** Index of the next route point the ramp is walking toward. */
    private int pathAt;
    /** The ramp's exact position along the route, in game units. */
    private long cursorX, cursorZ, cursorY;

    private void pathReset(long x, long z, long y) {
        pathN = 1;
        pathAt = 1;
        pathX[0] = x;
        pathZ[0] = z;
        pathY[0] = y;
        cursorX = x;
        cursorZ = z;
        cursorY = y;
    }

    /** Records one real position, decimated, with loops collapsed. */
    private void pathAppend(long x, long z, long y) {
        if (pathN == 0) {
            pathReset(x, z, y);
            return;
        }
        int last = pathN - 1;
        if (dist(x, z, y, pathX[last], pathZ[last], pathY[last])
                < PATH_STEP_M * EspEngine.UNITS_PER_METRE) {
            return;
        }
        // Walked back over our own route: drop everything after that point, so
        // the replay takes the shortcut the player effectively took.
        //
        // Only points the cursor has NOT reached yet are candidates. Collapsing
        // behind the cursor would leave pathAt past the end, the route would
        // read as spent, and the ramp would straight-line home from wherever it
        // had got to — the exact failure this class is built to avoid.
        double loop = PATH_LOOP_M * EspEngine.UNITS_PER_METRE;
        for (int i = Math.max(0, pathAt); i < pathN - 1; i++) {
            if (dist(x, z, y, pathX[i], pathZ[i], pathY[i]) < loop) {
                pathN = i + 1;
                break;
            }
        }
        if (pathN >= PATH_MAX) {
            // Ran out of room. Keep the anchor (index 0 -- it is where the
            // server believes we are) and halve the rest by dropping every
            // other point; at 0.4 m spacing the coarser route is still legal.
            int w = 1;
            int newAt = pathAt;
            for (int i = 1; i < pathN; i += 2) {
                if (i <= pathAt) newAt = w;   // follow the cursor through the remap
                pathX[w] = pathX[i];
                pathZ[w] = pathZ[i];
                pathY[w] = pathY[i];
                w++;
            }
            pathN = w;
            pathAt = Math.min(newAt, pathN);
        }
        pathX[pathN] = x;
        pathZ[pathN] = z;
        pathY[pathN] = y;
        pathN++;
    }

    /**
     * Walks the cursor up to {@code budget} units further along the recorded
     * route, then on to the player's live position once the route is spent.
     *
     * <p>Consuming route <i>length</i> bounds the straight-line displacement
     * this report by the same budget, so the server's distance gate is
     * respected around corners as well as on straights.</p>
     *
     * @return units actually moved, or -1 when the player has been reached
     */
    private double pathAdvance(double budget, long realX, long realZ, long realY) {
        double remaining = budget;
        double moved = 0.0;

        while (remaining > 0.0 && pathAt < pathN) {
            double d = dist(cursorX, cursorZ, cursorY,
                    pathX[pathAt], pathZ[pathAt], pathY[pathAt]);
            if (d <= remaining) {
                cursorX = pathX[pathAt];
                cursorZ = pathZ[pathAt];
                cursorY = pathY[pathAt];
                remaining -= d;
                moved += d;
                pathAt++;
            } else {
                stepToward(pathX[pathAt], pathZ[pathAt], pathY[pathAt], remaining / d);
                moved += remaining;
                remaining = 0.0;
            }
        }

        if (remaining > 0.0) {
            // Route spent: close the last gap to wherever the player is now.
            double d = dist(cursorX, cursorZ, cursorY, realX, realZ, realY);
            if (d <= LAND_EPS_M * EspEngine.UNITS_PER_METRE) return -1.0;
            if (d <= remaining) {
                cursorX = realX;
                cursorZ = realZ;
                cursorY = realY;
                moved += d;
            } else {
                stepToward(realX, realZ, realY, remaining / d);
                moved += remaining;
            }
        }
        return moved;
    }

    private void stepToward(long tx, long tz, long ty, double f) {
        cursorX += Math.round((tx - cursorX) * f);
        cursorZ += Math.round((tz - cursorZ) * f);
        cursorY += Math.round((ty - cursorY) * f);
    }

    /** Route length still to replay, plus the gap to the player, in metres. */
    private double pathRemainingMetres(long realX, long realZ, long realY) {
        double u = 0.0;
        long px = cursorX, pz = cursorZ, py = cursorY;
        for (int i = pathAt; i < pathN; i++) {
            u += dist(px, pz, py, pathX[i], pathZ[i], pathY[i]);
            px = pathX[i];
            pz = pathZ[i];
            py = pathY[i];
        }
        u += dist(px, pz, py, realX, realZ, realY);
        return u / EspEngine.UNITS_PER_METRE;
    }

    private static double dist(long ax, long az, long ay, long bx, long bz, long by) {
        double dx = ax - bx, dz = az - bz, dy = ay - by;
        return Math.sqrt(dx * dx + dz * dz + dy * dy);
    }

    /**
     * Highest id in {@code GCommon.UDPMessageIDReserved} — the transport
     * namespace: {@code UDP_HELLO=1, UDP_ACK=2, UDP_PING=3, UDP_BYEBYE=4,
     * UDP_LONGTIMENOSEE=5, MUDP_CONNECT_ACK=6, RUDP_SPLITTED_DATAGRAM=99}.
     * Application messages start at 100.
     */
    private static final int RESERVED_CMD_MAX = 99;

    /**
     * Identifies the player's own position report. Prefers the exactly decoded
     * command id and falls back to the shape test only while the session header
     * is still being solved — the same ordering that fixed ghost, which went
     * silently dead when one link in the old learned chain broke.
     *
     * <p><b>The transport layer is explicitly excluded and must stay that way.</b>
     * Acks, pings, hellos and the split-datagram reassembly channel all live in
     * the reserved 1-99 id space; withholding any of them starves the reliable
     * layer or drops the connection — that is the old "locking heartbeat"
     * disconnect bug. Nothing reachable here should ever classify one as a
     * position report, but this is the guarantee rather than a consequence of
     * one, so it is checked rather than argued.</p>
     *
     * <p>Measured on this device's own captures: of 57 579 outbound packets in
     * the 9-30 byte liveness/ack/input bands, <b>zero</b> pass the shape test,
     * whose real span is 47-67 B (19 707 matches).</p>
     */
    private boolean isPositionReport(PacketContext ctx) {
        if (ctx.hasCmd()) {
            dbgExact = true;
            if (ctx.cmd <= RESERVED_CMD_MAX) return false;   // transport: never touch
            // Every carrier of our own position, not just 2001 -- the game
            // switches channel when mounted (2002 driving, 2006 firing
            // passenger, 2031 cannon). Keying on 2001 alone made this go
            // silently dead in vehicles.
            return Cmd.isOwnPositionCarrier(ctx.cmd);
        }
        dbgExact = false;
        return GamePacketTaxonomy.isPositionKeepalive(ctx.buf, ctx.off, ctx.plen)
                && GamePacketTaxonomy.isFromMatchServer(ctx.dip, ctx.dp);
    }

    // ---- lifecycle ----

    /**
     * One toggle press. On from idle, off (into the self-completing ramp) from
     * anchored, and re-arm if pressed while a ramp is still running.
     */
    public void press() {
        TeleportState prev = state;
        TeleportState next = prev.onPress();

        if (next == TeleportState.ANCHOR) {
            if (prev == TeleportState.RAMP) {
                // Re-arming mid-release. The server currently believes we are
                // at the cursor, NOT at the player -- so hold the report there
                // and start a fresh route from it. Re-anchoring on the real
                // position instead would report a jump of the whole remaining
                // distance in one packet, which is exactly what the distance
                // gate rejects.
                anchored = true;
                repX = cursorX;
                repZ = cursorZ;
                repY = cursorY;
                pathReset(cursorX, cursorZ, cursorY);
                // The LOGICAL hold just moved to the cursor, so the PHYSICAL
                // packet we replay must stop describing the old anchor. Leaving
                // it meant the very next replay announced a position the server
                // had already been walked away from -- a backward jump of the
                // whole ramped distance, which is the ON->OFF->ON kick.
                // Dropping it is safe: the next real report re-captures at the
                // new hold (the !anchored path), and until then replayAnchor()
                // simply does nothing.
                clearAnchorPacket();
            } else {
                anchored = false;
                // Never replay an anchor left over from a previous engagement.
                // The mounted and platform-relative branches call
                // replayAnchor() BEFORE the !anchored capture can refresh it,
                // so arming teleport while in a vehicle or on a moving platform
                // used to replay a packet from an earlier teleport -- possibly
                // from an earlier match.
                clearAnchorPacket();
                dbgSeen.set(0);
                dbgRewritten.set(0);
                dbgWidthDrop.set(0);
                dbgParseDrop.set(0);
                dbgInPlace.set(0);
                dbgRebuilt.set(0);
                dbgDropped.set(0);
                dbgMounted.set(0);
                dbgPlatform.set(0);
            }
        }
        state = next;
        dbgState = next.label();
        logger.logControl("MODE_TELE_" + next.name(),
                "mode=tele state=" + next.label()
                        + String.format(java.util.Locale.US, " step=%.2fm", stepM));
        if (next == TeleportState.IDLE) {
            anchored = false;
            dbgRemainM = 0.0;
        }
    }

    /** Arms the effect (equivalent to the first press). */
    @Override
    public void start() {
        if (state == TeleportState.IDLE) press();
    }

    /**
     * A press, not a hard stop — from ANCHOR this releases into the ramp, which
     * is the teleport itself. Use {@link #abort()} to cancel outright.
     */
    @Override
    public void stop() {
        if (state.isEngaged()) press();
    }

    /**
     * Stops the effect as safely as the situation allows.
     *
     * <p><b>This does not snap back to passthrough.</b> Going straight to IDLE
     * while the server still believes we are at the cursor means the very next
     * report carries the player's real position — a jump of the whole
     * outstanding distance in one packet. A jump that large is not merely
     * rejected: it reads as a protocol violation and ends the session, which is
     * what the player sees as a network error dropping them back to the lobby.
     * It was reachable from "on, off, on" in the first build, where the third
     * press aborted a ramp in flight.</p>
     *
     * <p>So an abort releases into the ramp instead and lets it finish
     * normally. It costs a couple of seconds and cannot disconnect anyone.</p>
     */
    public void abort() {
        if (state == TeleportState.ANCHOR) {
            press();                       // -> RAMP, which lands by itself
        }
        // Already ramping: leave it alone, it is on its way home.
    }

    /** True when nothing is being rewritten — neither anchored nor ramping. */
    public boolean isIdle() {
        return state == TeleportState.IDLE;
    }

    /**
     * The server told us where we really are. Give up and re-anchor there.
     *
     * <p>Inbound {@code Cmd 144 RUDP_FORCE_SYNC_PLAYER_STATE} is the server's
     * correction, and it is <b>the only inbound message carrying a position for
     * our own entity</b>. The client obeys it within 45-58 ms, measured. So it
     * is ground truth for "the rewrite was refused", available before anything
     * else in the system notices — previously a snap-back was indistinguishable
     * from a working ramp.</p>
     *
     * <p><b>Two things must happen and both are corrections of real defects.</b></p>
     *
     * <p>First, the ramp must stop. The forced position is behind the cursor,
     * so continuing would immediately re-report a position the server has just
     * rejected.</p>
     *
     * <p>Second — and this is what turned one snap into being <i>stuck</i> — the
     * forced position must never be appended to the recorded route. The route
     * recorder runs on every report, and loop collapse only scans points at or
     * after the cursor, so a snap that lands <i>behind</i> the cursor is not
     * collapsed: it is appended as a new endpoint, creating a long leg from B
     * back to A that the ramp then walks in a <b>straight line</b> (the
     * route-spent fallback), cutting through whatever is in between. Meanwhile
     * every real report is still being replaced, so the player's actual
     * movement never reaches the server. Each further force-sync appends
     * another leg, roughly as fast as the ramp consumes it, and {@code land()}
     * is never reached.</p>
     *
     * <p>Re-anchoring at the forced position instead keeps our belief and the
     * server's identical, which is the only state from which anything can
     * legally proceed.</p>
     *
     * @param fx forced X, @param fz forced Z, @param fy forced Y, in millimetres
     */
    public void onForceSync(long fx, long fz, long fy) {
        if (state == TeleportState.IDLE) return;
        dbgForceSync.incrementAndGet();
        com.nepdon.wire.diag.Diag.hit(
                com.nepdon.wire.diag.Slot.FLT_TELE_FORCE_SYNC);
        logger.logControl("TELE_FORCE_SYNC",
                "mode=tele state=" + state.label() + " server_put_us_back");
        // Re-anchor on the server's own answer, and start a fresh route from
        // it. Never carry the old route across: its points are relative to a
        // belief the server has just discarded.
        repX = fx;
        repZ = fz;
        repY = fy;
        cursorX = fx;
        cursorZ = fz;
        cursorY = fy;
        pathReset(fx, fz, fy);
        clearAnchorPacket();
        anchored = false;      // re-capture the hold from the next real report
        state = TeleportState.ANCHOR;
        dbgState = state.label();
        dbgRemainM = 0.0;
    }

    /**
     * Stands the effect down completely, for when another filter is about to
     * take the position channel away.
     *
     * <p>{@link #abort()} is wrong for that: it presses ANCHOR into RAMP and
     * then the new owner drops every report the ramp needs, leaving it pinned
     * and invisible (see {@code DefaultFilterEngine.toggle(GHOST)}). There is
     * no way to walk the server home without the channel, so drop the state
     * rather than pretend.</p>
     */
    public void standDown() {
        hardReset("stand-down");
    }

    private void land(String why) {
        state = TeleportState.IDLE;
        anchored = false;
        dbgState = TeleportState.IDLE.label();
        dbgRemainM = 0.0;
        logger.logControl("MODE_TELE_IDLE", "mode=tele state=off reason=" + why);
    }

    /**
     * A tunnel restart invalidates buffered bytes, but this filter buffers
     * none — it holds two integer coordinates in the match's own coordinate
     * space, which a restart does not change. The effect therefore survives,
     * exactly as the user's toggle implies it should. If the session encoding
     * rotated, the next report simply fails to parse and is withheld.
     */
    public void onTunnelRestart() {
        // NOT empty any more. "Nothing stale is held" was wrong: repX/repZ/repY
        // and the whole recorded path are world coordinates from the match that
        // just ended, and anchorPacket is a datagram encoded under the OLD
        // session's header layout and key.
        //
        // A restart also does not stop the filter. PacketLoop calls
        // filterEngine.onOutbound() with no isFilterEnabled gate, and
        // DefaultFilterEngine.disableAll()/shutdown() only reach abort(), which
        // does nothing once the state is RAMP. So a teleport left ramping
        // survived into the next match and rewrote the first position report of
        // match 2 to a match-1 coordinate: an instant maximal-distance jump,
        // rejected, and the most likely shape of a kick with no trail.
        //
        // isKindActive(JITTER) reports ANCHOR only, so the UI showed this as
        // OFF the entire time.
        hardReset("tunnel-restart");
    }

    /**
     * Drops every piece of cross-match state and returns to IDLE.
     *
     * <p>Unlike {@link #abort()} this does <b>not</b> try to walk the server
     * home. There is no server left to walk home to — the session, its header
     * layout and its coordinate frame are all gone.</p>
     */
    private void hardReset(String why) {
        boolean wasEngaged = (state != TeleportState.IDLE);
        state = TeleportState.IDLE;
        dbgState = state.label();
        anchored = false;
        dbgRemainM = 0.0;
        pathN = 0;
        pathAt = 0;
        repX = 0;
        repZ = 0;
        repY = 0;
        cursorX = 0;
        cursorZ = 0;
        cursorY = 0;
        clearAnchorPacket();
        if (wasEngaged) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.FLT_TELE_BOUNDARY_RESET);
            logger.logControl("MODE_TELE_RESET", "mode=tele why=" + why);
        }
    }

    /**
     * There is nothing to release — no threads, no queues, only a fixed array —
     * so this releases into the ramp for the same reason {@link #abort} does,
     * rather than snapping to passthrough and reporting a jump. Reached from
     * {@code disableAll()}, which fires on a network change or an app switch
     * with the tunnel still up.
     */
    @Override
    public void shutdown() {
        // Hard reset, not abort(). abort() is a no-op once the state is RAMP,
        // which left the filter rewriting outbound position reports after the
        // engine had been told to stop.
        hardReset("shutdown");
    }
}
