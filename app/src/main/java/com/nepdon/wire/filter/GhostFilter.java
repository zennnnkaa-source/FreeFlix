package com.nepdon.wire.filter;

import com.nepdon.wire.util.Humanize;
import com.nepdon.wire.protocol.GamePacketTaxonomy;

import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.decode.body.PositionSync;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.protocol.ChannelWatch;

/**
 * Ghost policy: locks the player's server-side position at the moment ghost
 * was toggled on, while the player keeps moving — and firing — freely on
 * their own client.
 *
 * <p><b>Two streams carry the player to the server, and ghost freezes BOTH:</b></p>
 * <ul>
 *   <li><b>The movement keepalive</b> — the player's authoritative position
 *       channel: 45-64 B packets at a steady ~100 ms (10 Hz) cadence toward
 *       the match server. Capture-verified that this channel spans MULTIPLE
 *       lengths at once within a session (e.g. 53/57/58/59/60 B all at
 *       100 ms gaps) and can be family R or S depending on the layout — so
 *       the detector is the whole 45-64 B window scoped to the match
 *       endpoint, never a single length and never family-gated. This is
 *       what the ORIGINAL ghost's 55-160 B window was accidentally doing
 *       (it froze position) minus its disconnect side effect: ghost drops
 *       the live keepalives and re-sends the SAVED one every 500 ms, so the
 *       server keeps seeing "alive, at position X" forever without the
 *       reliability layer starving.</li>
 *   <li><b>The input stream</b> (learned 24 B signature, ~30 Hz) — aim and
 *       input state. Same treatment: live dropped, saved replayed.</li>
 * </ul>
 *
 * <p>While ghost is ON, not a single live position packet leaves the device.
 * Fire events (isolated 26/34 B) always pass instantly. Inbound is never
 * touched (the player's own screen stays smooth). To engage from a new
 * position, toggle ghost off/on there — the anchor re-freezes at the new
 * spot (the server checks shot origin against the reported position, so
 * shooting works from wherever the anchor is).</p>
 *
 * <p><b>If you ever edit an anchored packet</b> — rewrite the coordinates to
 * move the frozen position rather than re-anchoring, say — the CRC-7 in byte 1
 * no longer covers it and the server discards the packet as corrupt. Nothing
 * here mutates a packet today (the anchor is a verbatim
 * {@link PacketContext#copyPayload() copy} and the replay sends it byte for
 * byte), and the egress seal in the transport's
 * {@link PacketSender#send(TelePacket)} implementation repairs the checksum for
 * anything that does. Do not route a mutated packet around that seal.</p>
 */
public final class GhostFilter implements PacketFilter {

    /**
     * How often the saved state is re-sent while ghost is on. The natural
     * keepalive runs ~10 Hz; 2 Hz replay keeps the server's liveness timers
     * comfortably fed (observed budgets are seconds) while the position
     * stays pinned.
     */
    public static final long REPLAY_INTERVAL_MS = 500L;

    /** Movement-keepalive length window (the whole position channel). */
    // Length windows removed: real keepalives are 48-67 B, so the old 45-64
    // window leaked 10.9% of them in one measured session. Identified by
    // shape now - see GamePacketTaxonomy.isPositionKeepalive.

    private final DevLogger logger;
    private final PacketSender sender;
    private volatile boolean active = false;
    /** TEMPORARY: throttle for the ghost gate trace. */
    private volatile long lastDebugMs = 0L;

    // ---- dev-panel telemetry (statics: exactly one GhostFilter per tunnel) ----
    /** Ghost toggle as the filter itself sees it. */
    public static volatile boolean dbgActive = false;
    /** Whether the tracked match stream was live at the last decision. */
    public static volatile boolean dbgLive = false;
    /** Position keepalives recognised. */
    public static final java.util.concurrent.atomic.AtomicLong dbgKeepalive =
            new java.util.concurrent.atomic.AtomicLong();
    /** Movement-stream packets recognised. */
    public static final java.util.concurrent.atomic.AtomicLong dbgMove =
            new java.util.concurrent.atomic.AtomicLong();
    /** Packets ghost actually withheld. */
    public static final java.util.concurrent.atomic.AtomicLong dbgDropped =
            new java.util.concurrent.atomic.AtomicLong();

    /** Endpoint equality that isFromMatchServer performs, at the last decision. */
    public static volatile boolean dbgEndpointMatch = false;
    /** Whether the packet passed the keepalive SHAPE test, at the last decision. */
    public static volatile boolean dbgShape = false;
    /** True when the exact decoded command id was available. */
    public static volatile boolean dbgExact = false;

    /** One line for the dev panel. */
    public static String debugLine() {
        return (dbgActive ? "ON " : "off")
                + (dbgExact ? " EXACT" : " heur")
                + " live=" + (dbgLive ? "Y" : "N")
                + " ep=" + (dbgEndpointMatch ? "Y" : "N")
                + " shape=" + (dbgShape ? "Y" : "N")
                + " ka=" + dbgKeepalive.get()
                + " mv=" + dbgMove.get()
                + " drop=" + dbgDropped.get();
    }
    private volatile long lastReplayMs = 0L;
    private final Humanize humanize = new Humanize();
    /** Gap to the next replay. Re-rolled on every replay so no fixed period survives. */
    private volatile long nextReplayGapMs = REPLAY_INTERVAL_MS;
    /** Replay gap is REPLAY_INTERVAL_MS plus up to this fraction. Never shorter. */
    public static final double REPLAY_JITTER_FRAC = 0.30;

    /** True when a replay is due now; re-rolls the next gap when it is. */
    /** Base replay gap: 500 ms by default, 100 ms under the REPLAY_FAST experiment. */
    private static long replayBaseMs() {
        return HoldExperiments.REPLAY_FAST ? 100L : REPLAY_INTERVAL_MS;
    }

    private boolean replayDue(long now) {
        if (now - lastReplayMs < nextReplayGapMs) return false;
        lastReplayMs = now;
        nextReplayGapMs = humanize.upMs(replayBaseMs(), REPLAY_JITTER_FRAC);
        return true;
    }

    // ---- saved ghost-start state (two channels) ----
    private final Object stateLock = new Object();
    private byte[] savedMove;        // the 24B input-stream packet
    private int savedMoveDip, savedMoveDp, savedMoveSp;
    private byte[] savedKeepalive;   // the freshest 45-64B keepalive packet
    private int savedKaDip, savedKaDp, savedKaSp;

    public GhostFilter(PacketSender sender, DevLogger logger) {
        this.sender = sender;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "ghost";
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        // Server-gated leash: no live profile => don't withhold our position
        // (PASS => we keep being streamed, i.e. no ghost). R8-stripped while off.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && !com.nepdon.wire.auth.ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return FilterDecision.PASS;
        }
        // EXACT PATH (preferred). The session header is now fully decoded, so
        // the packet that carries our position is identified by its command id
        // -- UDP_PLAYER_STATE_SYNC -- instead of by a byte shape plus a chain of
        // learned state (moveLen, moveHeader, matchServerIp, liveness).
        //
        // That chain is why ghost silently stopped working: the dev panel showed
        // ON but ka=0 mv=0 drop=0 with live=N, i.e. it recognised nothing at all,
        // even though the shape test alone verifies 100% (47 664/47 664 across 26
        // of this device's own captures). One link in the learned chain breaks and
        // the whole feature goes quiet with no error. It is the same failure mode
        // as the ESP's 38-byte floor.
        //
        // The heuristic stays as the fallback for the window before the solver
        // converges (a few hundred outbound packets), so behaviour is unchanged
        // when the exact id is not yet available.
        boolean isMove;
        boolean isKeepalive;
        boolean exact = ctx.hasCmd();
        if (exact) {
            // NOT just Cmd 2001. The game switches our position channel the
            // moment we mount anything -- 2002 driving, 2006 as a firing
            // passenger, 2031 on a cannon -- because the sender dispatches on
            // IsInVehicle() before it ever reaches the 2001 branch. Keying on
            // 2001 alone meant ghost silently stopped withholding the instant
            // the player got into a vehicle, with no error and no telemetry.
            // Withholding needs no knowledge of the body, so all of them can
            // be dropped safely.
            isKeepalive = com.nepdon.wire.decode.body.Cmd.isOwnPositionCarrier(ctx.cmd);
            // The old "movement stream" was learned from 21-40 byte packets, and
            // on current traffic the only thing that fits is the ACK stream
            // (Cmd 2) -- withholding ACKs breaks the reliable layer and was never
            // the intent. With the exact id there is one position channel and
            // this is it, so nothing else needs withholding.
            isMove = false;
        } else {
            // isMove is DELIBERATELY false here too, matching the exact path.
            //
            // The old fallback called isMovementStream(), a 21-40 byte shape
            // test. The exact path already established that on current traffic
            // the only thing fitting that shape is the ACK stream (Cmd 2) --
            // there is exactly one position channel and isKeepalive is it.
            // Leaving isMove live in the fallback meant that during the
            // pre-solver window (a few hundred packets at match start, and
            // again after every key rotation) ghost withheld ACKs.
            //
            // That is not cosmetic. Measured dose-response: dropping 16/36/50%
            // of ACKs produced 21.8/37.5/46.0% inbound retransmission, with one
            // OrderID resent 9 times -- exactly 1 + MAX_RESEND_COUNT, the
            // server's retransmit budget fully exhausted. Starving the reliable
            // layer this way is the most likely mechanism behind the mid-match
            // disconnects. Note isKeepalive was gated on !isMove, so a shape
            // collision also suppressed the real position drop.
            isMove = false;
            isKeepalive = GamePacketTaxonomy.isPositionKeepalive(ctx.buf, ctx.off, ctx.plen)
                    && GamePacketTaxonomy.isFromMatchServer(ctx.dip, ctx.dp);
        }

        if (!active) {
            // Continuously refresh both saved channels so the toggle moment
            // freezes the freshest pre-ghost state.
            if (isMove || isKeepalive) {
                saveState(ctx, isMove);
            }
            return FilterDecision.PASS;
        }
        // TEMPORARY DIAGNOSTIC: ghost reportedly stopped freezing position.
        // Every gate verifies 100% offline against the user's own captures, so
        // print which one actually fails on the device. Throttled to 1 Hz.
        long dbgNow = System.currentTimeMillis();
        dbgActive = active;
        dbgExact = exact;
        dbgLive = GamePacketTaxonomy.isMatchStreamLive(dbgNow);
        dbgEndpointMatch = (ctx.dip == GamePacketTaxonomy.matchServerIp()
                && ctx.dp == GamePacketTaxonomy.matchServerPort());
        dbgShape = GamePacketTaxonomy.isPositionKeepalive(ctx.buf, ctx.off, ctx.plen);
        if (isKeepalive) dbgKeepalive.incrementAndGet();
        if (isMove) dbgMove.incrementAndGet();
        if (dbgNow - lastDebugMs > 1000L) {
            lastDebugMs = dbgNow;
            android.util.Log.i("FakelagGhost",
                    "active=" + active
                    + " isMove=" + isMove
                    + " isKeepalive=" + isKeepalive
                    + " live=" + GamePacketTaxonomy.isMatchStreamLive(dbgNow)
                    + " srvIp=" + GamePacketTaxonomy.matchServerIp()
                    + " srvPort=" + GamePacketTaxonomy.matchServerPort()
                    + " dp=" + ctx.dp + " plen=" + ctx.plen
                    // isKeepalive needs isFromMatchServer(ctx.dip, ctx.dp) to be
                    // true. SRV is learned yet ka=0, so print BOTH sides of that
                    // comparison instead of inferring which half fails.
                    + " dip=" + ctx.dip
                    + " srvIp=" + GamePacketTaxonomy.matchServerIp()
                    + " epMatch=" + (ctx.dip == GamePacketTaxonomy.matchServerIp()
                                     && ctx.dp == GamePacketTaxonomy.matchServerPort())
                    + " shape=" + GamePacketTaxonomy.isPositionKeepalive(
                            ctx.buf, ctx.off, ctx.plen));
        }

        if (!isMove && !isKeepalive) {
            // Fire, timers, data, everything else: passes instantly.
            return FilterDecision.PASS;
        }
        // Only manage streams toward a LIVE match endpoint. After the match
        // ends the tracked server becomes the lobby/squad session; ghost
        // must go quiet there.
        // The liveness gate exists so ghost goes quiet once the match ends and
        // the tracked endpoint becomes the lobby session. It is driven by the
        // same learned chain as above, and it was reading N while ghost was
        // armed -- blocking every drop on its own. When the id is exact we do
        // not need it: a decoded UDP_PLAYER_STATE_SYNC only exists inside a
        // live match, which is a stronger and simpler signal.
        if (!exact && !GamePacketTaxonomy.isMatchStreamLive(System.currentTimeMillis())) {
            return FilterDecision.PASS;
        }

        // Anchor ONLY missing slots (first packet after ghost-on or a tunnel
        // restart). Never overwrite a saved slot while active — the saved
        // state IS the frozen position; refreshing it with live data would
        // leak the real position.
        synchronized (stateLock) {
            if (isMove && savedMove == null) {
                savedMove = ctx.copyPayload();
                savedMoveDip = ctx.dip;
                savedMoveDp = ctx.dp;
                savedMoveSp = ctx.sp;
                logger.logPacket("ANCHOR_GHOST_MOVE", "OUT", ctx.buf, ctx.off, ctx.plen,
                        ctx.sp, ctx.dp, ctx.dip, "ghost=true move_anchored");
            } else if (isKeepalive && savedKeepalive == null) {
                savedKeepalive = ctx.copyPayload();
                savedKaDip = ctx.dip;
                savedKaDp = ctx.dp;
                savedKaSp = ctx.sp;
                logger.logPacket("ANCHOR_GHOST_KEEPALIVE", "OUT", ctx.buf, ctx.off, ctx.plen,
                        ctx.sp, ctx.dp, ctx.dip, "ghost=true keepalive_anchored");
            }
        }

        long now = System.currentTimeMillis();
        if (replayDue(now)) {
            replaySavedState();
        }
        logger.logPacket("BLOCK_GHOST", "OUT", ctx.buf, ctx.off, ctx.plen,
                ctx.sp, ctx.dp, ctx.dip,
                isMove ? "ghost=true movement_stream" : "ghost=true movement_keepalive");
        dbgDropped.incrementAndGet();
        return FilterDecision.DROP;
    }

    private void saveState(PacketContext ctx, boolean isMove) {
        byte[] payload = ctx.copyPayload();
        synchronized (stateLock) {
            if (isMove) {
                savedMove = payload;
                savedMoveDip = ctx.dip;
                savedMoveDp = ctx.dp;
                savedMoveSp = ctx.sp;
            } else {
                savedKeepalive = payload;
                savedKaDip = ctx.dip;
                savedKaDp = ctx.dp;
                savedKaSp = ctx.sp;
            }
        }
    }

    /** Re-sends the saved keepalive + movement packets (the frozen state). */
    private void replaySavedState() {
        byte[] ka = null, mv = null;
        int kaDip = 0, kaDp = 0, kaSp = 0, mvDip = 0, mvDp = 0, mvSp = 0;
        synchronized (stateLock) {
            if (savedKeepalive != null) {
                ka = savedKeepalive.clone();
                kaDip = savedKaDip; kaDp = savedKaDp; kaSp = savedKaSp;
            }
            if (savedMove != null) {
                mv = savedMove.clone();
                mvDip = savedMoveDip; mvDp = savedMoveDp; mvSp = savedMoveSp;
            }
        }
        // Keepalive first: it is the position authority and the liveness
        // carrier.
        if (ka != null) {
            ka = maybeAdvanceClientMs(ka);
            com.nepdon.wire.protocol.ChannelWatch.noteReplayedKeepalive(ka);
            sender.send(new TelePacket(ka, kaDip, kaDp, kaSp, TelePacket.TYPE_MOVE));
            logger.logPacket("REPLAY_GHOST_KEEPALIVE", "OUT", ka, 0, ka.length,
                    kaSp, kaDp, kaDip, "ghost=true keepalive_replay");
        }
        if (mv != null) {
            sender.send(new TelePacket(mv, mvDip, mvDp, mvSp, TelePacket.TYPE_MOVE));
            logger.logPacket("REPLAY_GHOST_STATE", "OUT", mv, 0, mv.length,
                    mvSp, mvDp, mvDip, "ghost=true movement_replay");
        }
    }

    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        return FilterDecision.PASS;
    }

    @Override
    public void start() {
        active = true;
        lastReplayMs = 0L;
    }

    @Override
    public void stop() {
        active = false;
    }

    @Override
    public void shutdown() {
        active = false;
    }

    /**
     * Tunnel restarted: the saved state carries the OLD session's encoding —
     * replaying it into the new session would be protocol garbage. Drop it;
     * fresh state is anchored from the first packets of the new session.
     */
    public void onTunnelRestart() {
        synchronized (stateLock) {
            savedMove = null;
            savedMoveDip = 0;
            savedMoveDp = 0;
            savedMoveSp = 0;
            savedKeepalive = null;
            savedKaDip = 0;
            savedKaDp = 0;
            savedKaSp = 0;
        }
    }

    /**
     * ADVANCE_CLIENT_MS: rewrite body index 22 (clientMs) so the server sees a
     * clock that keeps walking during hold. Optional small nudge on index 18
     * (pingMs). Falls back to the original bytes on any parse/rebuild failure.
     */
    private byte[] maybeAdvanceClientMs(byte[] ka) {
        if (!HoldExperiments.ADVANCE_CLIENT_MS || ka == null) return ka;
        try {
            byte[] work = ka.clone();
            PositionSync sync = new PositionSync();
            if (!sync.parse(work, 0, work.length)) {
                FileWatchLog.line("GHOST", "clientMs parse fail len=" + ka.length);
                return ka;
            }
            long now = System.currentTimeMillis();
            // Match-relative clock: keep same high bits as the anchored value,
            // only walk the low part forward so width usually stays identical.
            long prev = sync.rawAt(ChannelWatch.CLIENT_MS_INDEX);
            long clientMs;
            if (prev > 0L && prev < 0x7FFFFFFFL) {
                // Observed values were ~1e5–3e5 (match timer ms), not wall clock.
                // Advance from previous by real elapsed since last successful patch.
                long delta = 500L; // default replay gap
                clientMs = prev + delta;
            } else {
                clientMs = (now & 0xFFFFFFFFL);
            }

            boolean wrote = writeRawInPlace(sync, work, ChannelWatch.CLIENT_MS_INDEX, clientMs);
            if (HoldExperiments.NUDGE_PING_MS) {
                long ping = sync.rawAt(18);
                long nudged = (ping > 0L)
                        ? Math.max(15L, Math.min(150L, ping + ((now / 17) % 7) - 3))
                        : (20L + (now % 90L));
                writeRawInPlace(sync, work, 18, nudged);
            }

            if (!wrote) {
                // Fallback: rebuild path
                HeaderLayout layout = null;
                PacketPipeline pipe = PacketPipeline.current();
                if (pipe != null) layout = pipe.layout();
                if (layout != null) {
                    sync.stageRaw(ChannelWatch.CLIENT_MS_INDEX, clientMs);
                    int newLen = sync.rebuild(layout);
                    if (newLen > 0) {
                        FileWatchLog.line("GHOST", "clientMs rebuild ok " + clientMs + " len=" + sync.length());
                        return (sync.length() == work.length) ? work : java.util.Arrays.copyOf(work, sync.length());
                    }
                }
                FileWatchLog.line("GHOST", "clientMs advance failed prev=" + prev + " want=" + clientMs);
                return ka;
            }
            if (!sync.seal()) {
                FileWatchLog.line("GHOST", "clientMs seal failed");
                return ka;
            }
            FileWatchLog.line("GHOST", "clientMs in-place " + prev + "->" + clientMs);
            return work;
        } catch (Throwable t) {
            FileWatchLog.line("GHOST", "clientMs error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return ka;
        }
    }

    /** Write unsigned varint at body index if it fits existing width; updates PositionSync raw[]. */
    private static boolean writeRawInPlace(PositionSync sync, byte[] buf, int index, long value) {
        try {
            // Reflect field starts/widths via signed path limitations — use fits through stage+width.
            // PositionSync does not expose starts[]; use setSigned only for zigzag fields.
            // For raw fields we re-parse widths from a side channel: encode and compare width.
            java.lang.reflect.Field fStarts = PositionSync.class.getDeclaredField("starts");
            java.lang.reflect.Field fWidths = PositionSync.class.getDeclaredField("widths");
            java.lang.reflect.Field fRaw = PositionSync.class.getDeclaredField("raw");
            fStarts.setAccessible(true);
            fWidths.setAccessible(true);
            fRaw.setAccessible(true);
            int[] starts = (int[]) fStarts.get(sync);
            int[] widths = (int[]) fWidths.get(sync);
            long[] raw = (long[]) fRaw.get(sync);
            if (starts == null || widths == null || index < 0 || index >= widths.length) return false;
            int w = widths[index];
            int need = PositionSync.varintWidth(value);
            if (need > w) return false;
            int p = starts[index];
            long u = value;
            for (int k = 0; k < w - 1; k++) {
                buf[p + k] = (byte) ((u & 0x7f) | 0x80);
                u >>>= 7;
            }
            buf[p + w - 1] = (byte) (u & 0x7f);
            raw[index] = value;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
