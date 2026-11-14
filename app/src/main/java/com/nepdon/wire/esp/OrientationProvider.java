package com.nepdon.wire.esp;

/**
 * Pluggable source of the camera/view orientation for the ESP renderer.
 *
 * <p><b>Frame convention.</b> {@link #yaw()} returns theta such that the
 * horizontal facing unit vector over the own-position axes (posX, posY) is
 * (sin(theta), cos(theta)). The engine's decoded direction vector
 * (dirX, dirY, dirZ) lives in exactly this frame: dirX along posX, dirZ
 * along posY, dirY = height; yaw = atan2(dirX, dirZ), pitch =
 * asin(dirY). The renderer converts this frame to Unity Y-up world
 * coordinates in one place ({@code overlay/EspRenderView}).</p>
 *
 * <p>Two implementations:</p>
 * <ul>
 *   <li>{@link Full} — the decoded float32 unit direction vector (keepalive
 *       varints v13/v14/v15, |v| = 1.0 verified on 3427/3427 packets).
 *       Signed yaw AND pitch, no folding. Primary provider.</li>
 *   <li>{@link Folded} — fallback: folds the direction magnitudes to
 *       [0&deg;,90&deg;] and recovers the quadrant by correlating against
 *       the own-position velocity (movement shares the world frame with
 *       facing). Kept for robustness and as the calibration cross-check.</li>
 * </ul>
 */
public interface OrientationProvider {

    /** Feeds the latest engine state; call once per render tick (main thread). */
    void update(EspEngine esp);

    /**
     * Best-known full yaw in radians (atan2 frame over (posX, posY) axes:
     * atan2 of the posX-axis component over the posY-axis component).
     */
    double yaw();

    /** Best-known camera pitch in radians (positive = looking up). */
    double pitch();

    /** True once a usable yaw exists. */
    boolean yawKnown();

    /** Debug provenance of the current yaw. */
    String source();

    // ====================================================================
    // Primary: full decoded direction vector (keepalive v13/v14/v15)
    // ====================================================================

    /**
     * Full orientation from the decoded float32 unit direction vector:
     * yaw = atan2(dirX, dirZ), pitch = asin(dirY). Validated per update
     * against |v| &asymp; 1 and keepalive freshness; falls back to unknown
     * when the stream is stale or the vector is not unit-length (which
     * indicates a misparse, not a real orientation).
     */
    final class Full implements OrientationProvider {

        /** Keepalive older than this (ms) => orientation considered stale. */
        private static final long STALE_MS = 1500L;

        /**
         * Sign applied to the decoded direction vector to get camera
         * forward. +1 (identity) once positions are zigzag-decoded: the
         * earlier -1 was compensating for the mirrored coordinate frame
         * produced by reading positions raw.
         */
        public static volatile double VIEW_SIGN = +1.0;

        private double yawRad = 0.0;
        private double pitchRad = 0.0;
        private boolean have = false;

        @Override
        public void update(EspEngine esp) {
            if (esp == null) { have = false; return; }
            // ownStateMs, NOT ownLastUpdateMs. THE FOURTH READER OF THIS
            // MISTAKE, and the last one.
            //
            // ownLastUpdateMs tracks Cmd 2001 alone, and its going silent is
            // precisely how mounted() detects a vehicle -- so it stops by
            // design the moment the player mounts. Reading it here meant that
            // 1.6 s into every ride yawKnown() went false, the renderer fell
            // back to the Folded provider, and that provider's pitch() returns
            // a hard-coded 0.0 -- while Cmd 2069 and the self-echo were
            // supplying a perfectly good basis one layer up and having it
            // discarded.
            //
            // ownStateMs advances from ANY own-state source. See the note on
            // both fields in EspEngine: when a field's meaning is narrowed,
            // every reader has to be told, and this is the one that was missed
            // three times.
            long ownMs = esp.ownStateMs();
            if (ownMs <= 0 || System.currentTimeMillis() - ownMs > STALE_MS) {
                have = false;
                return;
            }
            EspEngine.CamSample c = esp.camSnapshot();
            if (c == null) { have = false; return; }
            float dx = c.x;
            float dy = c.y;
            float dz = c.z;
            double m = (double) dx * dx + (double) dy * dy + (double) dz * dz;
            if (!(m > 0.25 && m < 4.0)) {   // NaN-safe unit-length check
                have = false;
                return;
            }
            // CONVENTION (capture 150701, 945 sustained-run samples): the
            // decoded vector points BACKWARD along the view — the histogram
            // of (movement heading - atan2(dx,dz)) spikes hard at 180 deg
            // (24% of samples in the 170-190 band vs ~2% background).
            // Camera forward is therefore the negated vector.
            double sx = VIEW_SIGN * dx, sy = VIEW_SIGN * dy, sz = VIEW_SIGN * dz;
            yawRad = Math.atan2(sx, sz);
            double cy = sy / Math.sqrt(m);
            if (cy > 1.0) cy = 1.0;
            if (cy < -1.0) cy = -1.0;
            pitchRad = Math.asin(cy);
            have = true;
        }

        @Override
        public double yaw() { return yawRad; }

        @Override
        public double pitch() { return pitchRad; }

        @Override
        public boolean yawKnown() { return have; }

        @Override
        public String source() { return have ? "full" : "full?"; }
    }

    // ====================================================================
    // Fallback: folded direction + movement-quadrant inference
    // ====================================================================

    /**
     * Quadrant inference without sign bits: the direction vector gives
     * |sin(theta)| and |cos(theta)|, so theta is one of four candidates
     * atan2(&plusmn;|dx|, &plusmn;|dz|). The own-position velocity (derived
     * from successive posX/posY samples) is a signed vector in the same
     * world frame; when the player moves roughly along their facing (the
     * dominant case), the candidate best aligned with the velocity gives
     * the quadrant. When still, or moving nearly perpendicular to facing
     * (strafing), the last inferred quadrant is held. Switching quadrant
     * requires stronger alignment than keeping it (hysteresis against
     * brief backward moves flipping the estimate 180&deg;).
     *
     * <p>Known limitation: sustained backward running produces the opposite
     * quadrant (indistinguishable without the sign bits, which the Full
     * provider now has anyway).</p>
     */
    final class Folded implements OrientationProvider {

        /** Own-velocity magnitude (units/ms) that counts as "moving" (~0.5 m/s). */
        private static final double MOVE_MIN = 2.0;

        /** Normalized alignment to keep the current quadrant. */
        private static final double ALIGN_KEEP = 0.55;

        /** Normalized alignment required to SWITCH quadrant (hysteresis). */
        private static final double ALIGN_SWITCH = 0.80;

        // quadrant index -> sign pair: 0:(+,+) 1:(+,-) 2:(-,+) 3:(-,-)
        // (first sign applies to the posX-axis component / sin, second to
        // the posY-axis component / cos)
        private int quadrant = -1;
        private double absSin = 0.0;   // |sin(theta)|
        private double absCos = 1.0;   // |cos(theta)|
        private boolean haveFolded = false;
        private String src = "init";

        // own-position velocity sampling
        private double lastX = Double.NaN;
        private double lastY = Double.NaN;
        private long lastMs = 0L;
        private double velX = 0.0;
        private double velY = 0.0;
        private boolean haveVel = false;

        @Override
        public void update(EspEngine esp) {
            if (esp == null) { haveFolded = false; src = "init"; return; }
            double dx = esp.dirX();
            double dy = esp.dirY();
            double dz = esp.dirZ();
            double m = dx * dx + dy * dy + dz * dz;
            if (!(m > 0.25 && m < 4.0)) { haveFolded = false; src = "init"; return; }
            absSin = Math.abs(dx) / Math.sqrt(m);
            absCos = Math.abs(dz) / Math.sqrt(m);
            haveFolded = true;

            // Own-position velocity (signed, same world frame as facing).
            // Samples are consumed only when 40..400 ms apart: update() runs
            // every ~33 ms, so a too-fresh sample is kept until the keepalive
            // cadence (~100 ms) makes the delta meaningful.
            long now = System.currentTimeMillis();
            double px = esp.posX();
            double py = esp.posY();
            if (Double.isNaN(lastX)) {
                lastX = px; lastY = py; lastMs = now;
                haveVel = false;
            } else {
                long dt = now - lastMs;
                if (dt >= 40 && dt <= 400) {
                    velX = (px - lastX) / dt;
                    velY = (py - lastY) / dt;
                    // Teleport-scale velocity (respawn/round jump) is not
                    // movement evidence — resample instead (~100 u/ms = 24 m/s).
                    if (velX * velX + velY * velY > 100.0 * 100.0) {
                        haveVel = false;
                    } else {
                        haveVel = true;
                    }
                    lastX = px; lastY = py; lastMs = now;
                } else if (dt > 400) {
                    // Stale gap (renderer paused / respawn jump): resample.
                    lastX = px; lastY = py; lastMs = now;
                    haveVel = false;
                }
                // 0 <= dt < 40: keep the previous sample, let dt accumulate.
            }

            if (!haveVel) {
                if (quadrant >= 0) src = "hold";
                return;
            }
            double vMag = Math.sqrt(velX * velX + velY * velY);
            if (vMag < MOVE_MIN) {
                if (quadrant >= 0) src = "hold";
                return;
            }
            // Pick the sign pattern whose facing candidate aligns best with
            // the movement vector (both live in the same world frame).
            double inv = 1.0 / (vMag * Math.sqrt(absSin * absSin + absCos * absCos));
            double best = -2.0;
            int bestQ = -1;
            for (int q = 0; q < 4; q++) {
                double sSin = ((q & 1) == 0) ? 1.0 : -1.0;
                double sCos = ((q & 2) == 0) ? 1.0 : -1.0;
                double align = (sSin * absSin * velX + sCos * absCos * velY) * inv;
                if (align > best) { best = align; bestQ = q; }
            }
            if (bestQ == quadrant && best >= ALIGN_KEEP) {
                src = "move";
            } else if (best >= (bestQ == quadrant ? ALIGN_KEEP : ALIGN_SWITCH)) {
                quadrant = bestQ;
                src = "move";
            } else if (quadrant >= 0) {
                src = "hold";
            }
        }

        @Override
        public double yaw() {
            int q = (quadrant >= 0) ? quadrant : 0;
            double sSin = ((q & 1) == 0) ? 1.0 : -1.0;
            double sCos = ((q & 2) == 0) ? 1.0 : -1.0;
            return Math.atan2(sSin * absSin, sCos * absCos);
        }

        @Override
        public double pitch() {
            // Pitch needs the sign of dirY; folded fallback assumes level.
            return 0.0;
        }

        @Override
        public boolean yawKnown() {
            return haveFolded && quadrant >= 0;
        }

        @Override
        public String source() {
            return src;
        }
    }
}
