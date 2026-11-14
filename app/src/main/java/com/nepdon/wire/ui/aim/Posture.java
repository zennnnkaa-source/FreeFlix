package com.nepdon.wire.ui.aim;

/**
 * What posture a target is in, how tall they are because of it, and whether we
 * are willing to aim at them at all.
 *
 * <h3>Why this exists</h3>
 *
 * <p>There is <b>no per-bone data on the wire</b> — settled by an exhaustive
 * 4.96M-candidate sweep that found body-scale offsets at 0.0214% against a
 * 0.5141% chance control, i.e. 24x BELOW chance. So a target's hittable volume
 * cannot be read; it has to be MODELLED from the entity root plus a posture.
 * That makes posture the single input the whole aim anchor rests on.</p>
 *
 * <h3>The wire field</h3>
 *
 * <p>Remote entity record {@code varint[11]} ("state") is
 * <b>{@code EPHYSXPOSE}</b>, and {@code varint[10]} ("mode") is
 * {@code EPHYSXSTATE}. That identification is a strong inference, not a proof,
 * and it rests on four independent matches between the measured value
 * distribution and the enum:</p>
 *
 * <ul>
 *   <li>0 idle → STANDING</li>
 *   <li>2 sprint, measured p50 5.79 m/s → DASHING</li>
 *   <li><b>8 crawl, tightly clamped 0.59-0.74 m/s → KNOCKDOWN</b> — a knocked
 *       player crawling is exactly that speed band, and nothing else is</li>
 *   <li>5 falling → SKYDIVING, 11 airborne → LOWFALLING</li>
 * </ul>
 *
 * <p>Read as {@code EPHYSXSTATE} instead, those same values would be
 * FOOTBALL(8), HITFLY(5) and ONGRAPPLINGHOOK(11) — nonsense. It is also
 * consistent with own-keepalive v11/v12 matching remote 10/11 at 94.4%/87.2%,
 * and the measured fact that neither field ever reaches 0x80 is PREDICTED by
 * {@code EPHYSXPOSE_COUNT = 38} and {@code EPHYSXSTATE_COUNT = 22}.</p>
 *
 * <p><b>Falsify it in one capture:</b> crouch on camera and read field 11 —
 * expect 1. Go down — expect 8.</p>
 *
 * <h3>Heights</h3>
 *
 * <p><b>There is no posture-to-collider-height table in the binary.</b> The
 * table that demonstrably exists is the ADS camera PIVOT
 * ({@code Player::get_AimStartPostion} @ 0x070e2bb0: standing 1.28, crouching
 * 0.77, swim-surface 2.30), which is a different quantity. The real collider is
 * {@code CharacterController.height}, serialized Unity data — and worse,
 * {@code Player::DOHMNGCOEBM} @ 0x07096414 multiplies the hit capsule by
 * {@code CurrentMatch[+0x48]}/{@code [+0x4c]}, so it is <b>server-configurable
 * per match</b>. A single measured number is not universally right.</p>
 *
 * <p>So the heights here are calibrated constants, not lifted values. The
 * crouch ratio is the pivot table's 0.77/1.28 = 0.602 — an <b>assumption</b>,
 * flagged as such, and the first thing to check on device.</p>
 *
 * <h3>The refuse list is an allow-list on purpose</h3>
 *
 * <p>An unknown pose byte must REFUSE, not fall through to STANDING. The cost
 * of a wrong refusal is one missed shot; the cost of a wrong aim is swinging
 * the player's view at nothing. This codebase's recurring failure is a guard
 * that bails silently, so every refusal here is counted.</p>
 */
public final class Posture {

    private Posture() { }

    // ---- EPHYSXPOSE values we model (from dump.cs; order/count confirmed in
    //      the real image, the integers are not).
    public static final int POSE_STANDING   = 0;
    public static final int POSE_CROUCHING  = 1;
    public static final int POSE_DASHING    = 2;
    public static final int POSE_CREEPING   = 3;
    public static final int POSE_SWIMDASH   = 4;
    public static final int POSE_SKYDIVING  = 5;
    public static final int POSE_CROSSOVER  = 6;
    public static final int POSE_CLIMBOVER  = 7;
    public static final int POSE_KNOCKDOWN  = 8;
    public static final int POSE_SWIMSURF   = 9;
    public static final int POSE_HIGHFALL   = 10;
    public static final int POSE_LOWFALL    = 11;

    // ---- refusal reasons, mirrored by the AimTarget counters ----
    public static final int OK              = 0;
    public static final int REFUSE_POSE     = 1;   // unmodelled or unknown
    public static final int REFUSE_KNOCKED  = 2;   // downed: body is horizontal
    public static final int REFUSE_AIRBORNE = 3;   // falling / skydiving
    public static final int REFUSE_SWIM     = 4;   // root is submerged
    public static final int REFUSE_MOUNTED  = 5;   // attached to a vehicle

    /**
     * EPHYSXSTATE values that mean the target is attached to something whose
     * geometry we do not model. The entity root may be the vehicle's, and
     * {@code GetCharacterControllerTopPosition} @ 0x071026ec confirms the game
     * itself switches to a scaled attachment offset in this case.
     */
    private static boolean mountedMode(int mode) {
        return mode == 3      // ONBOARD
                || mode == 7  // ONSTROP (zipline)
                || mode == 9  // JETFLY
                || mode == 10 // GLIDING
                || mode == 11 // ONGRAPPLINGHOOK
                || mode == 12; // SKATEBOARDING
    }

    /**
     * @return {@link #OK}, or the reason this target must not be aimed at.
     *
     * <h3>REFUSALS ARE OFF BY DEFAULT, and this is why</h3>
     *
     * <p>The EPHYSXPOSE identification was recorded as "a strong inference,
     * not a proof". <b>It failed its first contact with real data.</b> A live
     * capture on 2026-09-20 gave this distribution over 8,071 samples:</p>
     *
     * <pre>
     *   pose=0  4430   STANDING     ok
     *   pose=2  1774   DASHING      ok
     *   pose=11 1195   LOWFALLING   &lt;-- 15% of all samples
     *   pose=1   593   CROUCHING    ok
     *   pose=10   47   HIGHFALLING
     *   pose=3    21   CREEPING
     *   pose=6    11   CROSSOVER
     * </pre>
     *
     * <p><b>Players are not airborne 15% of the time.</b> Whatever 11 is, it is
     * an ordinary ground state, and refusing it cost 733 real targets at
     * 3.6/s — the aim refused more than it accepted. The numeric values came
     * from {@code dump.cs} (a DIFFERENT build); the real image confirmed only
     * the field ORDER and COUNT, never the integers.</p>
     *
     * <p>So the refuse-list is now opt-in. The height model stays on, because
     * a wrong crouch height moves the anchor a few centimetres while a wrong
     * refusal blocks the shot entirely. Turn {@link AimConfig#AIM_POSTURE_GATE}
     * on only after the mapping is confirmed by a deliberate capture: crouch on
     * camera and read field 11 (expect 1), then go down (expect 8).</p>
     */
    public static int verdict(int pose, int mode, boolean inVehicle) {
        // The mount check stays unconditional ONLY for the explicit in-vehicle
        // flag, which comes from Cmd 125/the 2002 list rather than from the
        // unverified enum.
        if (inVehicle) return REFUSE_MOUNTED;
        if (!AimConfig.AIM_POSTURE_GATE) return OK;
        if (mountedMode(mode)) return REFUSE_MOUNTED;
        switch (pose) {
            case POSE_STANDING:
            case POSE_DASHING:
            case POSE_CROUCHING:
                return OK;
            case POSE_KNOCKDOWN:
                return REFUSE_KNOCKED;
            case POSE_SKYDIVING:
            case POSE_HIGHFALL:
            case POSE_LOWFALL:
                return REFUSE_AIRBORNE;
            case POSE_SWIMDASH:
            case POSE_SWIMSURF:
                return REFUSE_SWIM;
            default:
                // CREEPING included: the game reads a BONE for creep
                // (0x070e2eb0), not a constant, so we have no model at all.
                return REFUSE_POSE;
        }
    }

    /**
     * Standing height of the modelled capsule, metres, for this pose.
     * Only meaningful when {@link #verdict} returned {@link #OK}.
     */
    public static float heightM(int pose) {
        if (pose == POSE_CROUCHING) {
            return AimConfig.AIM_H_STANDING_M * AimConfig.AIM_CROUCH_RATIO;
        }
        return AimConfig.AIM_H_STANDING_M;
    }

    /**
     * Height above the entity root to aim at, metres.
     *
     * <p>{@link AimConfig#AIM_ANCHOR_FRAC} of the capsule — the neck base.
     * <b>Not the head, and not centre of mass</b>, because our error is
     * anisotropic: the horizontal error is the staleness error (~0.4 m at
     * 2 m/s after prediction) while the VERTICAL error is almost nil — the
     * transmitted height is the character root, it is exact, and crouch and
     * prone provably do not move it (37,543 stationary pairs, no step). Height
     * is the one axis we can spend precision in, so aiming high costs nothing
     * where we are bad.</p>
     *
     * <p>0.82 is the highest anchor whose DOWNWARD miss is still chest and
     * whose upward miss already clips the head — the error distribution is
     * favourably asymmetric there and nowhere higher. It also leaves ~0.32 m of
     * upward travel as a controllable nudge, which is where a human player's
     * drag-headshot starts.</p>
     */
    public static float anchorM(int pose) {
        return heightM(pose) * AimConfig.AIM_ANCHOR_FRAC;
    }

    /** Short label for the HUD. */
    public static String name(int pose) {
        switch (pose) {
            case POSE_STANDING:  return "stand";
            case POSE_CROUCHING: return "crouch";
            case POSE_DASHING:   return "dash";
            case POSE_CREEPING:  return "creep";
            case POSE_KNOCKDOWN: return "knocked";
            case POSE_SKYDIVING: return "skydive";
            case POSE_HIGHFALL:  return "hifall";
            case POSE_LOWFALL:   return "lofall";
            case POSE_SWIMDASH:  return "swimdash";
            case POSE_SWIMSURF:  return "swim";
            case POSE_CROSSOVER: return "crossover";
            case POSE_CLIMBOVER: return "climb";
            default:             return "p" + pose;
        }
    }
}
