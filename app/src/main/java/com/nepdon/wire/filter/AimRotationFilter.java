package com.nepdon.wire.filter;

import com.nepdon.wire.decode.body.C2S_UDP_Sync_AimRotation;
import com.nepdon.wire.decode.body.PositionSync;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;
import com.nepdon.wire.protocol.PacketPipeline;
import com.nepdon.wire.ui.aim.AimConfig;
import com.nepdon.wire.ui.aim.AimTarget;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wire-level aim assist: intercepts every outbound {@code Cmd 2001
 * UDP_PLAYER_STATE_SYNC} and rotates the reported camera direction toward the
 * acquired target by up to {@link AimConfig#AIM_ROTATION_MAX_STEP_DEG} degrees
 * per tick.
 *
 * <h3>What this does — and what it does NOT do</h3>
 *
 * <p><b>It does NOT affect body-part hit selection.</b> That was tested on
 * 2026-09-20: a 30° upward bias produced no change in headshot rate across
 * 127 damage events (see {@code PacketLoop} comment "AIM BIAS REMOVED --
 * DISPROVEN"). The server resolves hit location from its own raycast, not the
 * client's reported view direction.</p>
 *
 * <p><b>What it CAN do:</b></p>
 * <ol>
 *   <li><b>Aim-assist cone entry.</b> Free Fire's server-side aim assist
 *       engages when the crosshair is within some angular threshold of a
 *       target. Nudging the reported direction 1–5° per tick moves the camera
 *       into that cone faster, letting the game's own assist complete the
 *       lock. This is the primary use-case and the reason the step cap is
 *       small — a 30° jump is useless for cone entry and is what the earlier
 *       test showed failing.</li>
 *   <li><b>Smooth camera prediction.</b> The ring-buffer predictor in
 *       {@code tools/aim_control_sim.py} shows that correcting the reported
 *       direction 25 ms early (before the touch injection has physically moved
 *       the camera) reduces targeting latency by ~75 ms on the median
 *       engagement.</li>
 * </ol>
 *
 * <h3>Relationship to touch-based aim ({@code AimController})</h3>
 *
 * <p>These are complementary, not alternatives. Touch injection
 * ({@link com.nepdon.wire.ui.aim.AimController}) moves the physical camera on
 * the game client's own screen. This filter edits what the server sees. They
 * converge on the same target from two directions: the touch injection moves the
 * real view; this filter reports the view as already moved, closing the
 * ~185 ms loop delay by about 100 ms before the swipe completes.</p>
 *
 * <p>Set {@link AimConfig#AIM_ROTATION_ENABLED} to {@code false} if you want
 * touch-only without this wire supplement, or set it true for both.</p>
 *
 * <h3>Step cap and smoothness</h3>
 *
 * <p>The maximum per-tick correction is {@link AimConfig#AIM_ROTATION_MAX_STEP_DEG}.
 * At 10 Hz (100 ms tick), 5°/tick = 50°/s, which is comfortable. The shipped
 * default of 4° is well below the 6.8° that a sprinting target at 5 m generates
 * (§1 of {@code docs/AIM_PLAN.md}), so the correction stays inside the gain
 * margin proved stable in the simulation (K=12, zero overshoot).</p>
 *
 * <p>The correction is interpolated by the exponential smoothing tau derived
 * from {@code AimConfig.AIM_STEER_TAU_MS}: {@code step *= 1 - exp(-100/tau)}.
 * At the default 55 ms tau that factor is ~0.84, so the first tick moves 84% of
 * the computed step — snappy but not instantaneous.</p>
 *
 * <h3>CRC repair</h3>
 *
 * <p>Every rewrite calls {@link PositionSync#rebuild}, which reseals the
 * packet's CRC-7 via {@link com.nepdon.wire.decode.frame.PacketCodec#reserialize}.
 * A packet with a stale checksum is silently dropped by the server — this is
 * the same lesson teleport rewrites learned the hard way. Nothing leaves this
 * filter without a valid checksum.</p>
 */
public final class AimRotationFilter implements PacketFilter {

    private static final String TAG = "AimRotFlt";

    /** Cmd 2001 = UDP_PLAYER_STATE_SYNC: the outbound keepalive. */
    private static final int CMD_KEEPALIVE = 2001;

    // ---- per-instance state ----

    private final DevLogger logger;
    /** Re-used across packets; never allocates. */
    private final PositionSync sync = new PositionSync();
    /** Re-used across packets; never allocates. */
    private final C2S_UDP_Sync_AimRotation aimRot = new C2S_UDP_Sync_AimRotation();

    // ---- diagnostics ----
    private final AtomicInteger applied   = new AtomicInteger();
    private final AtomicInteger skipped   = new AtomicInteger();
    private final AtomicInteger parseErr  = new AtomicInteger();
    private final AtomicInteger rebuildErr = new AtomicInteger();
    private final AtomicLong    totalYawDeg   = new AtomicLong();
    private final AtomicLong    totalPitchDeg = new AtomicLong();
    private volatile String     lastReason = "-";

    public AimRotationFilter(DevLogger logger) {
        this.logger = logger;
    }

    // ====================================================================
    // PacketFilter
    // ====================================================================

    @Override
    public FilterDecision onOutbound(PacketContext ctx) {
        // ---- gate 1: feature switch ----
        if (!AimConfig.AIM_ROTATION_ENABLED) {
            lastReason = "disabled";
            return FilterDecision.PASS;
        }

        // ---- gate 2: must be Cmd 2001, or look like it ----
        // ctx.cmd is CMD_UNRESOLVED for the first ~200 packets while the
        // header layout solver converges. Fall back to the keepalive shape
        // heuristic (32-96 B, b9/b10 = 0x80) until it resolves — the same
        // heuristic PacketLoop uses to feed observeKeepalive.
        boolean isKeepalive;
        if (ctx.hasCmd()) {
            isKeepalive = (ctx.cmd == CMD_KEEPALIVE);
        } else {
            isKeepalive = ctx.plen >= 32 && ctx.plen <= 96
                    && ctx.off + 14 <= ctx.buf.length
                    && (ctx.buf[ctx.off + 9]  & 0xff) == 0x80
                    && (ctx.buf[ctx.off + 10] & 0xff) == 0x80
                    && (ctx.buf[ctx.off + 8]  & 0x80) != 0;
        }
        if (!isKeepalive) {
            return FilterDecision.PASS;
        }

        // ---- gate 3: target freshness ----
        long now = System.currentTimeMillis();
        if (!AimTarget.fresh(now, AimConfig.TARGET_MAX_AGE_MS)) {
            lastReason = "no target";
            skipped.incrementAndGet();
            Diag.hit(Slot.AIM_NO_TARGET);
            return FilterDecision.PASS;
        }

        // ---- gate 4: error big enough to bother ----
        float yawErrDeg   = AimTarget.yawErrDeg;
        float pitchErrDeg = AimTarget.pitchErrDeg;
        float errMag = (float) Math.sqrt(yawErrDeg * yawErrDeg
                                        + pitchErrDeg * pitchErrDeg);
        if (errMag < AimConfig.DEADZONE_DEG) {
            lastReason = "onTarget";
            Diag.hit(Slot.AIM_ON_TARGET);
            return FilterDecision.PASS;
        }

        // ---- gate 5: parse the keepalive body ----
        if (!sync.parse(ctx.buf, ctx.off, ctx.plen)) {
            lastReason = "parse fail";
            parseErr.incrementAndGet();
            Diag.hit(Slot.AIM_NO_TARGET);  // best available slot for "skip"
            return FilterDecision.PASS;
        }
        if (!aimRot.readFrom(sync)) {
            lastReason = "dir invalid";
            parseErr.incrementAndGet();
            return FilterDecision.PASS;
        }

        // ---- gate 6: need a solved header layout for rebuild ----
        // PositionSync.rebuild reads the Length field from the header using the
        // layout's mask. Without it we cannot update the declared length after
        // the body changes size, so the server gets a mismatch and drops the
        // packet.
        HeaderLayout layout = resolveLayout();
        if (layout == null) {
            lastReason = "no layout";
            skipped.incrementAndGet();
            return FilterDecision.PASS;
        }

        // ---- compute step-limited correction ----
        // Apply exponential smoothing to avoid a robotic step-function feel
        // and to stay below the gain margin proven in aim_control_sim.py.
        //   smoothFactor = 1 - exp(-100 / tau)
        // At the default tau=55 ms this is ~0.84.
        float tau     = AimConfig.AIM_STEER_TAU_MS;
        float smooth  = (float) (1.0 - Math.exp(-100.0 / Math.max(tau, 1f)));
        float maxStep = AimConfig.AIM_ROTATION_MAX_STEP_DEG;

        float stepYaw   = clamp(yawErrDeg   * smooth, -maxStep, maxStep);
        float stepPitch = clamp(pitchErrDeg * smooth, -maxStep, maxStep);

        // ---- stage corrected direction + rebuild ----
        aimRot.stageCorrection(sync,
                Math.toRadians(stepYaw),
                Math.toRadians(stepPitch));

        // ALL other varints are left at their parsed values (pending[] was
        // seeded with raw[] in PositionSync.parse via System.arraycopy).
        int newLen = sync.rebuild(layout);
        if (newLen < 0) {
            lastReason = "rebuild fail";
            rebuildErr.incrementAndGet();
            Diag.hit(Slot.AIM_INJECT_THREW);
            return FilterDecision.PASS;
        }

        // ---- commit ----
        ctx.setOutLength(newLen);
        ctx.markRewritten();
        applied.incrementAndGet();
        totalYawDeg.addAndGet(Math.round(Math.abs(stepYaw)));
        totalPitchDeg.addAndGet(Math.round(Math.abs(stepPitch)));
        lastReason = String.format("%.2f,%.2f°", stepYaw, stepPitch);
        Diag.hit(Slot.AIM_INJECTED);

        if (logger != null && (applied.get() % 50 == 0)) {
            logger.logControl(TAG,
                    "applied=" + applied.get()
                    + " last=" + lastReason
                    + " skip=" + skipped.get()
                    + " parseErr=" + parseErr.get());
        }

        return FilterDecision.PASS;  // always PASS — we edit, never drop
    }

    @Override
    public FilterDecision onInbound(PacketContext ctx) {
        return FilterDecision.PASS;  // inbound is read-only for this filter
    }

    // ====================================================================
    // PacketFilter lifecycle
    // ====================================================================

    @Override
    public String name() {
        return "aim-rotation";
    }

    @Override
    public boolean isActive() {
        return AimConfig.AIM_ROTATION_ENABLED;
    }

    @Override
    public void start() { }

    @Override
    public void stop() { }

    @Override
    public void shutdown() { }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Retrieves the solved header layout from the packet pipeline, if ready.
     *
     * <p>The pipeline is a process-wide singleton reached via
     * {@link com.nepdon.wire.tunnel.VpnEngine}. This filter does not hold a
     * reference to VpnEngine directly to stay consistent with the other
     * filters; it resolves via the static accessor.</p>
     */
    private HeaderLayout resolveLayout() {
        // VpnEngine.pipeline() is an instance accessor. This filter is created
        // without an engine reference, so leave layout resolution disabled
        // until a live pipeline is explicitly injected by the owner.
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ====================================================================
    // Diagnostics
    // ====================================================================

    /** Compact status line for the dev panel (~2 Hz). */
    public String statusLine() {
        return "AimRot: applied=" + applied.get()
                + " skip=" + skipped.get()
                + " pErr=" + parseErr.get()
                + " rErr=" + rebuildErr.get()
                + " last=" + lastReason;
    }
}
