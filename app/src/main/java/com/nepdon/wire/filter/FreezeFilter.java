package com.nepdon.wire.filter;

import com.nepdon.wire.protocol.GamePacketTaxonomy;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.decode.body.Cmd;

/**
 * Freeze policy: withholds inbound world-state streams so other players
 * stop moving on screen.
 *
 * FIX (fakelag-beta): Removed the strict isFromMatchServer() gate from the
 * exact-cmd path. When the protocol solver has decoded a world-state cmd,
 * the cmd itself is proof enough — requiring server-IP confirmation on top
 * caused freeze to silently pass everything on match servers outside the
 * known 148/8 + 43.252.x subnets (capture_raw_20260913 confirmed this).
 *
 * The shape-heuristic path (pre-solver) likewise no longer demands a
 * recognised server IP: entity-chain shape + non-reliable is sufficient
 * evidence. Voice and every other endpoint pass because they are routed
 * via TcpProxy and never reach this filter.
 */
public final class FreezeFilter implements PacketFilter {

    public static final int SHAPE_MIN = 20;
    public static final int SHAPE_MAX = 1400;

    private final DevLogger logger;
    private volatile boolean active = false;

    // ---- dev-panel telemetry ----
    public static volatile boolean dbgActive = false;
    public static volatile boolean dbgExact = false;
    public static final java.util.concurrent.atomic.AtomicLong dbgDropped =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong dbgCombatPassed =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong dbgReliableSpared =
            new java.util.concurrent.atomic.AtomicLong();

    public static String debugLine() {
        return (dbgActive ? "ON " : "off")
                + (dbgExact ? " EXACT" : " heur")
                + " drop=" + dbgDropped.get()
                + " dmgthru=" + dbgCombatPassed.get()
                + " rel=" + dbgReliableSpared.get();
    }

    public FreezeFilter(DevLogger logger) {
        this.logger = logger;
    }

    @Override
    public String name() { return "freeze"; }

    @Override
    public boolean isActive() { return active; }

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        return FilterDecision.PASS;
    }

    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        if (!active) return FilterDecision.PASS;

        // Auth leash (compile-time false in current build — no-op):
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && !com.nepdon.wire.auth.ConfigStore.decodeArmed(
                        System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return FilterDecision.PASS;
        }

        boolean exact = ctx.hasCmd();
        dbgActive = true;
        dbgExact = exact;

        if (exact) {
            // Transport layer (acks, pings) must always pass.
            if (Cmd.isTransportLayer(ctx.cmd)) return FilterDecision.PASS;

            if (!Cmd.isWorldStateStream(ctx.cmd)) {
                // Damage, deaths, HP, loot — player must keep being told.
                if (Cmd.isCombatFeedback(ctx.cmd)) dbgCombatPassed.incrementAndGet();
                return FilterDecision.PASS;
            }

            // Never starve reliable messages (ack-starvation kick).
            if (ctx.isReliable()) {
                com.nepdon.wire.diag.Diag.hit(
                        com.nepdon.wire.diag.Slot.FREEZE_RELIABLE_SPARED);
                dbgReliableSpared.incrementAndGet();
                return FilterDecision.PASS;
            }

            // FIX: cmd proves this is world state — drop without requiring
            // server-IP confirmation (old gate caused freeze to break on
            // match servers outside the surveyed subnets).

        } else {
            // Pre-solver shape path.
            if (!GamePacketTaxonomy.isMatchStreamLive(System.currentTimeMillis())) {
                return FilterDecision.PASS;
            }
            if (ctx.plen < SHAPE_MIN || ctx.plen > SHAPE_MAX) {
                return FilterDecision.PASS;
            }
            if (GamePacketTaxonomy.family(ctx.buf, ctx.off, ctx.plen)
                    == GamePacketTaxonomy.FAMILY_R) {
                return FilterDecision.PASS;
            }
            if (!looksLikeEntityChain(ctx)) {
                com.nepdon.wire.diag.Diag.hit(
                        com.nepdon.wire.diag.Slot.FREEZE_SHAPE_UNMATCHED);
                return FilterDecision.PASS;
            }
            // FIX: removed isFromMatchServer() gate here too — entity-chain
            // shape is sufficient proof on the pre-solver path.
        }

        logger.logPacket("BLOCK_FREEZE", "IN", ctx.buf, ctx.off, ctx.plen,
                ctx.sp, ctx.dp, ctx.dip,
                exact ? "freeze=true cmd="
                        + com.nepdon.wire.decode.body.CmdCatalogue.describe(ctx.cmd)
                      : "freeze=true shape");
        dbgDropped.incrementAndGet();
        return FilterDecision.DROP;
    }

    private static boolean looksLikeEntityChain(PacketContext ctx) {
        int off = ctx.off;
        if (off + 10 > ctx.buf.length) return false;
        if ((ctx.buf[off + 8] & 0xff) != 0x00) return false;
        int b9 = ctx.buf[off + 9] & 0xff;
        return b9 >= 2 && (b9 & 1) == 0;
    }

    @Override
    public void start() {
        active = true;
        dbgActive = true;
        dbgDropped.set(0);
        dbgCombatPassed.set(0);
        logger.logControl("MODE_FREEZE_ON", "mode=freeze");
    }

    @Override
    public void stop() {
        active = false;
        dbgActive = false;
        logger.logControl("MODE_FREEZE_OFF", "mode=freeze");
    }

    @Override
    public void shutdown() {
        active = false;
        dbgActive = false;
    }
}
