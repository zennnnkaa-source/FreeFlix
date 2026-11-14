package com.nepdon.wire.filter;

import android.content.Context;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.dev.DevLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link FilterEngine}. Holds the three {@link PacketFilter} policies
 * (freeze, ghost, tele), enforces their mutual exclusion, and owns the replay
 * their own buffers.
 *
 * <p><b>Abort gate.</b> A single {@link AtomicBoolean} is shared with the
 * the filters; it is set to {@code true} by {@link #disableAll()}
 * and {@link #shutdown()} so in-flight replay loops stop, and reset to
 * {@code false} by {@link #toggle(FilterKind)} when the engine is used again.</p>
 */
public final class DefaultFilterEngine implements FilterEngine {

    private final DevLogger logger;
    private final AtomicBoolean abortGate = new AtomicBoolean(false);

    private final FreezeFilter freeze;
    private final GhostFilter ghost;
    private final TeleportFilter teleport;
    private final MarkFilter mark;
    private final AimRotationFilter aimRotation;
    private final AntiHackBypass antiHack;

    public DefaultFilterEngine(PacketSender sender, DevLogger logger, Context context) {
        this.logger = logger;
        // THE REPLAY SUBSYSTEM WAS DELETED, NOT DISABLED.
        //
        // filter/replay/{ReplayEngine,PacketBuffer} had exactly one entry
        // point, teleReplayTask(), and it had zero callers -- PacketBuffer was
        // never instantiated anywhere in the tree. The only inbound edges were
        // these four lines: a field, a constructor call, and a preference read
        // feeding a delay nothing ever waited on.
        //
        // Prefs.KEY_TELE_REPLAY_DELAY is left orphaned rather than renamed:
        // it is persisted on the device, and hard rule 5 forbids renaming a
        // key, not leaving one unread.

        this.freeze = new FreezeFilter(logger);
        this.ghost = new GhostFilter(sender, logger);
        this.teleport = new TeleportFilter(sender, logger);
        this.mark = new MarkFilter(logger);
        this.aimRotation = new AimRotationFilter(logger);
        this.antiHack = new AntiHackBypass(sender, logger, ghost);
    }

    /** The teleport policy, for the tune panel and the dev readout. */
    public TeleportFilter teleport() {
        return teleport;
    }

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        if (ctx.hasCmd()) com.nepdon.wire.protocol.ChannelWatch.observe(
                ctx.cmd, true, ghost.isActive(), System.currentTimeMillis());
        // Anti-hack soft path before anything else mutates or holds the stream.
        FilterDecision ah = antiHack.onOutbound(ctx);
        if (ah == FilterDecision.DROP) return ah;
        // Ghost first: it withholds the position channel outright, so when it
        // is on there is nothing left for teleport to rewrite. The two are
        // mutually exclusive at the toggle, so this ordering only matters
        // during the moment one is replacing the other.
        FilterDecision d = ghost.onOutbound(ctx);
        if (d != FilterDecision.PASS) return d;
        d = teleport.onOutbound(ctx);
        if (d != FilterDecision.PASS) return d;
        // (The Cmd-106 hit-zone/headshot rewrite was REMOVED 2026-09-18 — it forged
        // the claimed hit body part, which is out of scope; see AimFilter deletion.)
        // Mark owns Cmd 148 only -- a squad map pin the player emits by hand.
        // It shares no channel with anything above: not the position stream
        // (2001/2002/2006/2031) that ghost and teleport own. It also never holds
        // or drops, only rewrites in place, so it cannot starve anything.
        if (ctx.hasCmd() && ctx.cmd == MarkFilter.CMD_MAPMARK_CHANGED) {
            return mark.onOutbound(ctx);
        }
        // Wire-level aim rotation: nudges the reported camera direction in
        // Cmd 2001 toward the acquired target. Runs after teleport so that
        // a teleport rewrite and a direction correction can coexist on the
        // same packet (teleport rewrites position/velocity; this only touches
        // v13-v15). Always returns PASS — it edits in place and never holds.
        aimRotation.onOutbound(ctx);
        return FilterDecision.PASS;
    }

    // reclassifyFire DELETED. It re-labelled every 21-54 byte packet as
    // "fire" and HELD it, which on real traffic is the reliable control/RPC
    // channel plus, in some sessions, 9-23% of position reports. It was one
    // of the two reasons teleport buffered everything except the packet that
    // actually matters. The replay catch-up path went with it: nothing is
    // held any more, so there is nothing to catch up.

    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        if (ctx.hasCmd()) com.nepdon.wire.protocol.ChannelWatch.observe(
                ctx.cmd, false, ghost.isActive(), System.currentTimeMillis());
        antiHack.onInbound(ctx);
        return freeze.onInbound(ctx);
    }

    @Override
    public void toggle(FilterKind kind) {
        abortGate.set(false);
        switch (kind) {
            case FREEZE:
                if (freeze.isActive()) {
                    freeze.stop();
                } else {
                    freeze.start();
                }
                break;
            case GHOST:
                if (ghost.isActive()) {
                    ghost.stop();
                } else {
                    // Ghost and teleport both own the position channel; ghost
                    // withholding it would starve teleport's rewrite.
                    //
                    // abort() is NOT enough. From ANCHOR it presses into RAMP,
                    // and ghost.start() on the very next line then drops every
                    // position report the ramp needs -- so the ramp cannot
                    // advance a single step and sits pinned at the anchor for
                    // the whole ghost session, while isActive() reports false
                    // and the UI shows teleport off. When ghost is later turned
                    // off, the stale ramp resumes from a cursor frozen wherever
                    // it was interrupted and straight-lines to wherever the
                    // player has since walked -- through whatever is in the way.
                    //
                    // Stand teleport fully down instead. Losing the ramp is the
                    // correct trade: the player asked for ghost.
                    teleport.standDown();
                    ghost.start();
                }
                break;
            case JITTER:
                // Not a binary toggle: each press advances the teleport state
                // machine (arm -> go -> done). Pressing it while ghost is on
                // takes the channel over, since ghost would otherwise drop the
                // very packets teleport needs to rewrite.
                // isActive() is ANCHOR-only, so a RAMP in progress satisfied
                // !teleport.isActive() and fell through here -- stopping ghost
                // and then taking press()'s RE-ARM branch against a cursor that
                // ghost had frozen. Test the state machine, not the toggle
                // label.
                if (teleport.isIdle() && ghost.isActive()) {
                    ghost.stop();
                }
                teleport.press();
                break;
        }
    }

    @Override
    public boolean isKindActive(FilterKind kind) {
        switch (kind) {
            case FREEZE: return freeze.isActive();
            case GHOST:  return ghost.isActive();
            case JITTER: return teleport.isActive();
            default:     return false;
        }
    }

    @Override
    public void disableAll() {
        abortGate.set(true);
        freeze.shutdown();
        ghost.shutdown();
        teleport.shutdown();
    }

    @Override
    public void onTunnelRestart() {
        // Abort in-flight replays (their packets carry the old session's
        // encoding) and clear every held buffer, but keep the user's active
        // toggles so freeze/ghost resume once the new session re-learns.
        // The gate is left set; the next toggle() clears it (existing
        // semantics) — a replay aborted this way was stale anyway.
        abortGate.set(true);
        teleport.onTunnelRestart();
        ghost.onTunnelRestart();
    }

    @Override
    public void onOwnForceSync(long x, long z, long y) {
        com.nepdon.wire.protocol.ServerSignal.noteOwnCorrection(System.currentTimeMillis());
        teleport.onForceSync(x, z, y);
    }

    @Override
    public boolean isKindRewriting(FilterKind kind) {
        // Only teleport has a phase that edits packets with the toggle off.
        return kind == FilterKind.JITTER && !teleport.isIdle();
    }

    @Override
    public boolean hasAnyActive() {
        return freeze.isActive() || ghost.isActive() || teleport.isActive();
    }

    @Override
    public void shutdown() {
        abortGate.set(true);
        freeze.shutdown();
        ghost.shutdown();
        teleport.shutdown();
    }
}
