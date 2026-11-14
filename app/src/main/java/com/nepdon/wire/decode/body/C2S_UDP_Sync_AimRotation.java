package com.nepdon.wire.decode.body;

/**
 * Camera-rotation slice of {@code Cmd 2001 UDP_PLAYER_STATE_SYNC}.
 *
 * <h3>What this class is for</h3>
 *
 * <p>The 10 Hz outbound keepalive carries 23 body varints. Three of them —
 * {@code v13/v14/v15} — are the float32 IEEE-754 bit pattern of the player's
 * view direction unit vector, stored as raw unsigned LEB128 varints (NOT
 * zigzag-encoded). This class provides a clean API for:</p>
 * <ul>
 *   <li>Reading yaw/pitch from a parsed {@link PositionSync}</li>
 *   <li>Computing a corrected direction given angular errors</li>
 *   <li>Staging the new float32 raw bits back into the sync for rebuild</li>
 * </ul>
 *
 * <h3>Wire convention</h3>
 *
 * <p>The decoded vector points <b>BACKWARD</b> along the camera (it was
 * verified over 3427/3427 keepalives in
 * {@code docs/SESSION_2026_09_19_aim_build.md} — the histogram of
 * {@code (movement heading - atan2(dirX, dirZ))} spikes at 180°). So:</p>
 * <pre>
 *   camera_forward = (-dirX, -dirY, -dirZ) / |dir|
 *   camYaw   = atan2(-dirX, -dirZ)   [sin over cos in the XZ plane]
 *   camPitch = asin(-dirY / |dir|)   [positive = looking up]
 * </pre>
 *
 * <h3>Body-part selection: read before routing through this class</h3>
 *
 * <p><b>Rewriting v13-v15 does NOT affect body-part hit selection.</b>
 * Tested exhaustively on 2026-09-20 ({@code PacketLoop.java} comment "AIM BIAS
 * REMOVED -- DISPROVEN"): a 30° upward bias left hit distribution completely
 * unchanged across 81 control / 46 biased damage events. The server resolves
 * the hit body-part from its own raycast, independent of what the client claims
 * as its view direction.</p>
 *
 * <p>What rewriting CAN do: nudge the reported direction into the game's own
 * aim-assist acquisition envelope (a few degrees of angular correction), so the
 * server's assist takes over for the remaining delta. This is the "get into the
 * cone" use-case and it works at small angles where a 30° bias test is
 * irrelevant. Use {@link #stageCorrection} for this.</p>
 *
 * <h3>Thread safety</h3>
 *
 * <p>Not thread-safe by design. One instance per tun loop, reused every packet
 * to stay allocation-free on the hot path.</p>
 */
public final class C2S_UDP_Sync_AimRotation {

    // ---- body varint indices (DEFAULT; kaSrv indices from ConfigStore differ) ----

    /** float32 view direction X component (raw uint32 bits as LEB128 varint). */
    public static final int V_CAM_X = 13;
    /** float32 view direction Y component (raw uint32 bits as LEB128 varint). */
    public static final int V_CAM_Y = 14;
    /** float32 view direction Z component (raw uint32 bits as LEB128 varint). */
    public static final int V_CAM_Z = 15;

    // ---- scratch: one instance, never allocates ----

    /**
     * Yaw of the camera forward axis after the last {@link #readFrom} call,
     * in radians. {@code atan2(sin, cos)} over the X/Z plane; right-turning
     * increases this value.
     */
    private double camYawRad;
    /**
     * Pitch of the camera forward axis after the last {@link #readFrom} call,
     * in radians. Positive = looking up.
     */
    private double camPitchRad;
    private boolean valid;

    /** Resets state; call before each packet. */
    public void reset() {
        valid = false;
    }

    // ====================================================================
    // Read
    // ====================================================================

    /**
     * Extracts the camera yaw and pitch from an already-parsed
     * {@link PositionSync}. Returns {@code false} when the direction vector is
     * not unit-length (misparse, or sync not valid for this packet).
     *
     * @param sync a {@link PositionSync} that has been successfully
     *             {@link PositionSync#parse parsed}; {@link PositionSync#isValid}
     *             must return true
     * @param camXIdx varint index for camX — pass {@link #V_CAM_X} for default
     *                sessions, or {@code ConfigStore.kaCamX()} for server-gated
     * @param camYIdx varint index for camY
     * @param camZIdx varint index for camZ
     */
    public boolean readFrom(PositionSync sync, int camXIdx, int camYIdx, int camZIdx) {
        valid = false;
        if (!sync.isValid()) return false;

        // v13/v14/v15 are raw float32 bits stored as unsigned uint32 LEB128,
        // NOT zigzag. rawAt() returns the unsigned integer; cast to int then
        // reinterpret as IEEE-754 float.
        float dx = Float.intBitsToFloat((int) (sync.rawAt(camXIdx) & 0xFFFFFFFFL));
        float dy = Float.intBitsToFloat((int) (sync.rawAt(camYIdx) & 0xFFFFFFFFL));
        float dz = Float.intBitsToFloat((int) (sync.rawAt(camZIdx) & 0xFFFFFFFFL));

        double m = (double) dx * dx + (double) dy * dy + (double) dz * dz;
        if (!(m > 0.25 && m < 4.0)) return false;   // NaN-safe unit-length check

        // Wire convention: vector points BACKWARD. Camera forward = negated.
        double len = Math.sqrt(m);
        double fwdX = -dx / len;
        double fwdY = -dy / len;
        double fwdZ = -dz / len;

        camYawRad   = Math.atan2(fwdX, fwdZ);
        double cy   = fwdY;
        if (cy >  1.0) cy =  1.0;
        if (cy < -1.0) cy = -1.0;
        camPitchRad = Math.asin(cy);
        valid = true;
        return true;
    }

    /**
     * Convenience overload using the default varint indices {@link #V_CAM_X} /
     * {@link #V_CAM_Y} / {@link #V_CAM_Z}.
     */
    public boolean readFrom(PositionSync sync) {
        return readFrom(sync, V_CAM_X, V_CAM_Y, V_CAM_Z);
    }

    /** Camera yaw in radians after a successful {@link #readFrom}. */
    public double camYawRad()   { return camYawRad; }

    /** Camera pitch in radians after a successful {@link #readFrom}. */
    public double camPitchRad() { return camPitchRad; }

    /** True when the last {@link #readFrom} succeeded. */
    public boolean isValid()    { return valid; }

    // ====================================================================
    // Correct + stage
    // ====================================================================

    /**
     * Applies an angular correction to the current camera direction and stages
     * the new float32 direction components into {@code sync} for a subsequent
     * {@link PositionSync#rebuild} call.
     *
     * <p>The correction is applied as:</p>
     * <pre>
     *   newYaw   = camYawRad   + yawDeltaRad
     *   newPitch = camPitchRad + pitchDeltaRad
     * </pre>
     *
     * <p>Pitch is clamped to ±88° so the forward vector never collapses to the
     * degenerate pole. Use {@code Math.toRadians(yawErrDeg)} as the delta when
     * feeding from {@link com.nepdon.wire.ui.aim.AimTarget}.</p>
     *
     * <p><b>Sign convention for AimTarget errors:</b> {@code yawErrDeg > 0}
     * means the target is to the RIGHT, so pass {@code +yawErrDeg} to rotate
     * right. {@code pitchErrDeg > 0} means the target is UP, so pass
     * {@code +pitchErrDeg} to look up.</p>
     *
     * @param sync     a parsed {@link PositionSync}
     * @param yawDeltaRad   yaw correction to add (radians; right = positive)
     * @param pitchDeltaRad pitch correction to add (radians; up = positive)
     * @param camXIdx  varint index for camX in {@code sync}
     * @param camYIdx  varint index for camY in {@code sync}
     * @param camZIdx  varint index for camZ in {@code sync}
     * @return false when the current state is invalid and nothing was staged
     */
    public boolean stageCorrection(PositionSync sync,
                                   double yawDeltaRad, double pitchDeltaRad,
                                   int camXIdx, int camYIdx, int camZIdx) {
        if (!valid) return false;

        double newYaw   = camYawRad   + yawDeltaRad;
        double newPitch = camPitchRad + pitchDeltaRad;

        // Clamp pitch: avoid the degenerate poles where cos(pitch) ~ 0.
        final double PITCH_LIMIT = Math.toRadians(88.0);
        if (newPitch >  PITCH_LIMIT) newPitch =  PITCH_LIMIT;
        if (newPitch < -PITCH_LIMIT) newPitch = -PITCH_LIMIT;

        double cp = Math.cos(newPitch);
        // Camera forward: (sin(yaw)*cos(pitch), sin(pitch), cos(yaw)*cos(pitch))
        // Wire dir = -camera_forward (backward convention; see class doc).
        float newDirX = (float) (-Math.sin(newYaw) * cp);
        float newDirY = (float) (-Math.sin(newPitch));
        float newDirZ = (float) (-Math.cos(newYaw) * cp);

        stageFloat(sync, camXIdx, newDirX);
        stageFloat(sync, camYIdx, newDirY);
        stageFloat(sync, camZIdx, newDirZ);
        return true;
    }

    /**
     * Convenience overload using the default varint indices.
     */
    public boolean stageCorrection(PositionSync sync,
                                   double yawDeltaRad, double pitchDeltaRad) {
        return stageCorrection(sync, yawDeltaRad, pitchDeltaRad,
                V_CAM_X, V_CAM_Y, V_CAM_Z);
    }

    /**
     * Stages a raw float32 direction vector into {@code sync} for rebuild.
     *
     * <p>Use when the caller has already computed the desired unit vector and
     * just wants it staged. The vector is NOT normalized here — pass a unit
     * vector or the server may reject it.</p>
     */
    public static void stageDirection(PositionSync sync,
                                      float dirX, float dirY, float dirZ,
                                      int camXIdx, int camYIdx, int camZIdx) {
        stageFloat(sync, camXIdx, dirX);
        stageFloat(sync, camYIdx, dirY);
        stageFloat(sync, camZIdx, dirZ);
    }

    /**
     * Stages a float32 value as its raw IEEE-754 uint32 bit pattern into the
     * pending array of {@code sync} at {@code index}.
     *
     * <p>Unlike {@link PositionSync#stageSigned} this does NOT zigzag-encode;
     * it passes the raw bit pattern directly to the LEB128 encoder in
     * {@link PositionSync#rebuild}. This is the correct encoding for the
     * camera direction fields, which are raw float32 on the wire.</p>
     */
    private static void stageFloat(PositionSync sync, int index, float f) {
        long rawBits = Float.floatToRawIntBits(f) & 0xFFFFFFFFL;
        sync.stageRaw(index, rawBits);
    }

    // ====================================================================
    // Diagnostics
    // ====================================================================

    /**
     * One-line diagnostic of the current state. Allocates — keep off the
     * hot path.
     */
    public String describe() {
        if (!valid) return "C2S_AimRot: invalid";
        return String.format("C2S_AimRot: yaw=%.2f° pitch=%.2f°",
                Math.toDegrees(camYawRad),
                Math.toDegrees(camPitchRad));
    }
}
