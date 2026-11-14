package com.nepdon.wire.ui.aim;

/**
 * The single aim target the renderer chose on the last frame, plus the swipe
 * maths derived from it. Written in {@code onDraw}; read by the debug panel and
 * (later) by {@link AimController}.
 *
 * <h3>Why this is a separate holder</h3>
 *
 * <p>The renderer already projects every enemy every frame. Picking the aim
 * target there costs one comparison per enemy and reuses the exact projection
 * the boxes are drawn with — so what the aim believes and what you see are the
 * same number by construction, not by two code paths agreeing. A second
 * projection would be a second thing to get wrong.</p>
 *
 * <p><b>All fields are plain statics and nothing here allocates</b>, because
 * {@code onDraw} allocates nothing (hard rule 6).</p>
 *
 * <h3>The teammate gate — read this before loosening anything</h3>
 *
 * <p>{@link #acquire} refuses any entity whose team is not <b>server-published
 * and known</b>. {@code EspEngine}'s classifier is explicit that rung 2 — the
 * server's own team byte — is "the ONLY rung that may say TEAM", and its two
 * sources (Cmd 118 {@code RUDP_BIND_PRI} and the Cmd 101 roster) are both
 * reliable, therefore TEA-encrypted. So when the reliable channel cannot be
 * decrypted, every surviving classifier rung can only ever say ENEMY, and an
 * aim that trusted {@code role == ROLE_ENEMY} alone would happily swing at
 * your own squad.</p>
 *
 * <p>Requiring {@code teamKnown} makes that failure <b>loud and safe</b>: with
 * no team data the aim acquires nothing and {@link #rejectedNoTeam} climbs,
 * instead of silently mis-targeting. Never relax this to "role == ENEMY" — the
 * whole point is that ENEMY is also what "we have no idea" looks like.</p>
 */
public final class AimTarget {

    private AimTarget() { }

    /** Entity id of the chosen target, or -1 when nothing is eligible. */
    public static volatile int id = -1;
    /** Head point on screen, px. Valid only while {@link #id} >= 0. */
    public static volatile float headX, headY;
    /** Crosshair (aim centre) the error was measured from, px. */
    public static volatile float aimX, aimY;
    /** Angular error to the head, degrees. Right/up positive. */
    public static volatile float yawErrDeg, pitchErrDeg;
    /** Straight-line distance to the target, metres. */
    public static volatile float distM;
    /** How long the target has been stationary, ms (bigger is stiller). */
    public static volatile long stillMs;
    /** Wall clock of the last successful acquire. */
    public static volatile long atMs;

    // ---- rejection counters: WHY nothing was acquired, per frame ----
    public static volatile int seen, rejectedNoTeam, rejectedMoving, rejectedOffScreen, rejectedRange, rejectedCone;
    /**
     * Rejected because the player is not scoped.
     *
     * <p>Its own counter rather than folding into another, because "the aim
     * does nothing" and "the scope latch is stuck" look identical from the
     * outside and only this number tells them apart.</p>
     */
    public static volatile int rejectedNoScope;
    /**
     * Posture/mount refusals, one counter each. Split rather than pooled
     * because they mean entirely different things to the user: "everyone is
     * knocked" is a won fight, "everyone is mounted" is a vehicle round, and
     * "unmodelled pose" is a gap in {@link Posture}. A single `refused=N`
     * would make all three read the same.
     */
    public static volatile int rejectedPose, rejectedKnocked, rejectedAirborne,
            rejectedSwim, rejectedMounted, rejectedStaleSrc;
    /**
     * Refused because WE are mounted -- not about the target at all. Cmd 2001
     * stops in vehicles, so the camera basis is frozen and every bearing is
     * computed against a stale view.
     */
    public static volatile boolean selfMounted;
    /**
     * Census of what the renderer actually saw this frame, for the dev dump.
     *
     * <p>{@code seen} alone cannot distinguish "no enemies streaming" from
     * "enemies streaming but all off screen" from "on screen but outside the
     * cone" -- and those three have completely different fixes. Each gets its
     * own number.</p>
     */
    public static volatile int drawn, onScreen, inCone, enemies;
    /** Angular error of the BEST candidate even when it was rejected. */
    public static volatile float bestAngDeg = -1f;
    /** Pose of the acquired target, and the anchor height used, for the HUD. */
    public static volatile int pose;
    public static volatile float anchorM;

    /** Clears the per-frame tallies. Call once at the top of the entity loop. */
    public static void beginFrame() {
        seen = 0; rejectedNoTeam = 0; rejectedMoving = 0;
        rejectedOffScreen = 0; rejectedRange = 0; rejectedCone = 0;
        rejectedNoScope = 0;
        rejectedPose = 0; rejectedKnocked = 0; rejectedAirborne = 0;
        rejectedSwim = 0; rejectedMounted = 0; rejectedStaleSrc = 0;
        drawn = 0; onScreen = 0; inCone = 0; enemies = 0; bestAngDeg = -1f;
    }

    /** Drops the current acquisition (no eligible target this frame). */
    public static void clear() {
        id = -1;
    }

    /**
     * Records the chosen target and its error. Caller has already applied the
     * eligibility rules; this only stores.
     */
    public static void set(int entityId, float hx, float hy, float ax, float ay,
                           float yawDeg, float pitchDeg, float dist, long still, long now,
                           boolean teamKnown, int poseEnum, float anchorMetres) {
        // HARD RULE 1, ENFORCED STRUCTURALLY. Only a server-published team byte
        // may mark a target as safe to engage, and ROLE_ENEMY is also what "we
        // could not decrypt Cmd 118/101" looks like. A comment is not enough
        // for this one: refuse the write and raise a slot that must read 0
        // forever.
        if (!teamKnown) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.AIM_TEAM_GATE_BYPASSED);
            id = -1;
            return;
        }
        pose = poseEnum;
        anchorM = anchorMetres;
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_ACQUIRED);
        id = entityId;
        headX = hx; headY = hy;
        aimX = ax; aimY = ay;
        yawErrDeg = yawDeg; pitchErrDeg = pitchDeg;
        distM = dist; stillMs = still; atMs = now;
    }

    /** True when the last acquisition is recent enough to act on. */
    public static boolean fresh(long now, long maxAgeMs) {
        return id >= 0 && (now - atMs) <= maxAgeMs;
    }

    /**
     * The heavy dev dump. Everything needed to answer "why is it not locking
     * on" without guessing, in the order the question is actually asked:
     * are there enemies, are they on screen, are they in the cone, was one
     * picked, did the swipe go out, and did the daemon take it.
     *
     * <p>Called at ~2 Hz from the panel, so a StringBuilder is fine; nothing
     * here runs on the draw path.</p>
     */
    public static String dumpLine() {
        StringBuilder sb = new StringBuilder(320);
        sb.append("ESP  drawn=").append(drawn)
          .append(" enemy=").append(enemies)
          .append(" onscr=").append(onScreen)
          .append(" inCone=").append(inCone);
        if (bestAngDeg >= 0f) sb.append(String.format(" best=%.2f°", bestAngDeg));
        sb.append("\nPICK ");
        if (id < 0) {
            sb.append("NONE");
        } else {
            sb.append("e").append(id)
              .append(String.format(" %.0fm err=%.2f,%.2f°", distM, yawErrDeg, pitchErrDeg))
              .append(' ').append(Posture.name(pose))
              .append(String.format(" anchor=%.2fm", anchorM))
              .append(" age=").append(System.currentTimeMillis() - atMs).append("ms");
        }
        sb.append("\nGATE seen=").append(seen);
        if (rejectedNoScope > 0)   sb.append(" noScope=").append(rejectedNoScope);
        if (rejectedNoTeam > 0)    sb.append(" noTeam=").append(rejectedNoTeam);
        if (rejectedMoving > 0)    sb.append(" moving=").append(rejectedMoving);
        if (rejectedOffScreen > 0) sb.append(" offscr=").append(rejectedOffScreen);
        if (rejectedRange > 0)     sb.append(" range=").append(rejectedRange);
        if (rejectedCone > 0)      sb.append(" cone=").append(rejectedCone);
        if (rejectedPose > 0)      sb.append(" pose=").append(rejectedPose);
        if (rejectedKnocked > 0)   sb.append(" knocked=").append(rejectedKnocked);
        if (rejectedAirborne > 0)  sb.append(" air=").append(rejectedAirborne);
        if (rejectedSwim > 0)      sb.append(" swim=").append(rejectedSwim);
        if (rejectedMounted > 0)   sb.append(" mounted=").append(rejectedMounted);
        if (rejectedStaleSrc > 0)  sb.append(" stale=").append(rejectedStaleSrc);
        if (selfMounted)           sb.append("  SELF-MOUNTED");
        if (AimConfig.AIM_TEST_TRACK) sb.append("  [TEST TRACK]");
        sb.append("\nAIM  ").append(AimController.aimLine());
        sb.append("\nSTEER ").append(AimController.steerLine());
        sb.append("\nGEOM ").append(AimController.geomLine());
        sb.append("\nTOUCH ").append(Daemon.lastDown());
        sb.append("\nDMN  ").append(Daemon.statusLine());
        sb.append("\nWIRE ").append(Daemon.lastLine());
        return sb.toString();
    }

    /** One compact line for the dev panel. Called ~2 Hz, so a StringBuilder is fine. */
    public static String debugLine() {
        StringBuilder sb = new StringBuilder(72);
        if (id < 0) {
            sb.append("none");
        } else {
            sb.append("e").append(id)
              .append(String.format(" %.0fm still=%.1fs", distM, stillMs / 1000f))
              .append(String.format(" err=%.2f,%.2f°", yawErrDeg, pitchErrDeg))
              .append(' ').append(Posture.name(pose))
              .append(String.format(" @%.2fm", anchorM));
        }
        sb.append("  seen=").append(seen);
        // Name the bail. A zero acquisition with no reason shown is exactly the
        // silent-skip failure this codebase keeps relearning.
        if (rejectedNoScope > 0)   sb.append(" noScope=").append(rejectedNoScope);
        if (rejectedNoTeam > 0)    sb.append(" noTeam=").append(rejectedNoTeam);
        if (rejectedMoving > 0)    sb.append(" moving=").append(rejectedMoving);
        if (rejectedOffScreen > 0) sb.append(" offscr=").append(rejectedOffScreen);
        if (rejectedRange > 0)     sb.append(" range=").append(rejectedRange);
        if (rejectedCone > 0)      sb.append(" cone=").append(rejectedCone);
        if (rejectedPose > 0)      sb.append(" pose=").append(rejectedPose);
        if (rejectedKnocked > 0)   sb.append(" knocked=").append(rejectedKnocked);
        if (rejectedAirborne > 0)  sb.append(" air=").append(rejectedAirborne);
        if (rejectedSwim > 0)      sb.append(" swim=").append(rejectedSwim);
        if (rejectedMounted > 0)   sb.append(" mounted=").append(rejectedMounted);
        if (rejectedStaleSrc > 0)  sb.append(" stale=").append(rejectedStaleSrc);
        if (selfMounted)           sb.append("  SELF-MOUNTED (basis frozen)");
        return sb.toString();
    }
}
