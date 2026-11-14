package com.nepdon.wire.ui.aim;

/**
 * Every tunable the aim has, in one place.
 *
 * <h3>Why these are fields and not a settings object</h3>
 *
 * <p>Each one is read on a hot path — the renderer's per-frame target pick, or
 * the swipe loop — so they are plain {@code volatile} statics that compile to a
 * field load. A {@code Setting} in this codebase owns no value either; it binds
 * a reference to exactly this kind of static, which is what lets the settings
 * screen and the in-game panel be two views of one number rather than two
 * copies of it.</p>
 *
 * <h3>Why they are here rather than on the class that uses them</h3>
 *
 * <p>They used to live on the injector, mixed in among the daemon's process
 * handle, the Shizuku listeners and the swipe state. Finding "the list of
 * things a user can change" meant reading a thousand lines. Persistence lives
 * in {@code EspTuning}; the ranges live here, next to the values, so a clamp
 * and its default can never drift apart.</p>
 */
public final class AimConfig {

    private AimConfig() { }

    // ====================================================================
    // Calibration
    // ====================================================================

    /**
     * Degrees of view rotation per pixel of swipe, at the user's sensitivity.
     *
     * <p>From the decrypted binary: {@code px_per_deg = 300 / (15 + 20*sens)}
     * with sens 0..1, i.e. 10.0 px/deg at the shipped GAME SENS of 75. The base
     * term is DPI-independent; the acceleration term is not, which is why the
     * daemon re-grips every {@link #MAX_TRAVEL_PX}.</p>
     *
     * <p><b>Re-verify before trusting.</b> Free Fire moved to a 0-200
     * sensitivity scale with six per-scope sliders around OB53/OB54, and the
     * 15/20 constants are the binary's compiled fallbacks. Scoped rotation is
     * additionally multiplied by 0.2 x a per-attachment factor that no public
     * source has measured for any scope. Overshoots: lower. Undershoots: raise.
     * That is the whole calibration procedure.</p>
     */
    public static final float DEF_PX_PER_DEG = 10.0f;
    public static volatile float PX_PER_DEG = DEF_PX_PER_DEG;
    public static final float PX_PER_DEG_MIN = 1f, PX_PER_DEG_MAX = 60f;

    /**
     * Longest single drag before the daemon lifts and re-plants, px.
     *
     * <p>Sent with every swipe, so the editable value and the daemon's cap
     * cannot drift. It exists because the game's touch-look has a
     * distance-based ACCELERATION term (~3.4x at 300 px on a 400 dpi panel), so
     * one long drag turns far more than gain x distance predicts. A workaround
     * for a client bug, not a taste knob.</p>
     */
    public static final float DEF_MAX_TRAVEL_PX = 100f;
    public static volatile float MAX_TRAVEL_PX = DEF_MAX_TRAVEL_PX;
    public static final float MAX_TRAVEL_MIN = 20f, MAX_TRAVEL_MAX = 400f;

    // ====================================================================
    // Where the finger goes
    // ====================================================================

    /**
     * Numeric fallback plant point, as a fraction of the screen. Used only
     * while {@code ui.edit.Layout} is unplaced; the editor replaces it.
     */
    public static final float DEF_SWIPE_X_FRAC = 0.72f;
    public static volatile float SWIPE_X_FRAC = DEF_SWIPE_X_FRAC;
    public static final float DEF_SWIPE_Y_FRAC = 0.45f;
    public static volatile float SWIPE_Y_FRAC = DEF_SWIPE_Y_FRAC;
    public static final float FRAC_MIN = 0.05f, FRAC_MAX = 0.95f;

    /**
     * Plant on the placed fire button rather than the paths' crossing.
     *
     * <p>True is the scoped-correct answer and the default: scoped, the look
     * region IS the reticle, and a drag starting anywhere else runs perfectly
     * and turns nothing.</p>
     */
    public static volatile boolean PLANT_ON_FIRE = true;

    // ====================================================================
    // Policy
    // ====================================================================

    /** Master switch for the aim acting at all. */
    public static volatile boolean AIM_ENABLED = com.nepdon.wire.config.FeatureGate.AIM;

    /**
     * Enable the wire-level camera rotation supplement
     * ({@link com.nepdon.wire.filter.AimRotationFilter}).
     *
     * <p>When true, every outbound {@code Cmd 2001} keepalive has its reported
     * camera direction nudged toward the current target by up to
     * {@link #AIM_ROTATION_MAX_STEP_DEG} degrees. This pre-reports the
     * corrected view to the server about 100 ms before the touch injection
     * physically moves the camera, tightening the overall loop delay.</p>
     *
     * <p>Does NOT affect body-part hit selection (tested 2026-09-20;
     * see "AIM BIAS REMOVED -- DISPROVEN" in PacketLoop). Primarily useful
     * for entering the game's aim-assist acquisition cone.</p>
     */
    public static volatile boolean AIM_ROTATION_ENABLED = true;

    /**
     * Maximum camera correction applied per 100 ms keepalive tick, degrees.
     *
     * <p>Kept at 4° by default: above 6.8° you overshoot a sprinter at 5 m,
     * and the simulation (aim_control_sim.py, GAIN=12) is still stable up to
     * 8°. The gain margin breaks above ~24°/tick.</p>
     */
    public static volatile float AIM_ROTATION_MAX_STEP_DEG = 4.0f;
    public static final float AIM_ROTATION_STEP_MIN = 0.5f;
    public static final float AIM_ROTATION_STEP_MAX = 20.0f;

    /**
     * Arm the input path and steer on scope-in.
     *
     * <p>On by default. While it is on the daemon holds the touchscreen grab
     * for the session and steers the finger nearest the fire button each time
     * the scope opens.</p>
     */
    public static volatile boolean TEST_MODE = true;

    /** How long an automatic scope-in steer runs, ms. */
    public static final float DEF_TEST_HOLD_MS = 5000f;
    public static volatile int TEST_HOLD_MS = (int) DEF_TEST_HOLD_MS;
    public static final float HOLD_MS_MIN = 500f, HOLD_MS_MAX = 20000f;

    /** Refuse to act on an acquisition older than this, ms. */
    public static final long TARGET_MAX_AGE_MS = 250L;

    /** Below this angular error the swipe is not worth making. */
    public static final float DEADZONE_DEG = 0.15f;

    // ================================================================
    // Target volume and aim anchor
    // ================================================================

    /**
     * Modelled standing capsule height, metres. See {@link Posture}.
     *
     * <p><b>1.75, and the old 1.8 is wrong.</b> Two independent residuals from
     * the scoped-zoom calibration disagree with each other but agree that 1.8
     * is too big: the 25 m standing sample implies 1.772, the session's own
     * closure analysis implies 1.705. Treat this as a tunable with a +/-0.08 m
     * band until the closure test is run, not as a constant.</p>
     *
     * <p>The real value is {@code CharacterController.height}, which is
     * serialized Unity data AND multiplied per match by
     * {@code CurrentMatch[+0x4c]} — so there may be no single right answer.</p>
     */
    public static volatile float AIM_H_STANDING_M = 1.75f;
    public static final float AIM_H_MIN = 1.40f, AIM_H_MAX = 2.10f;

    /**
     * Crouched height as a fraction of standing. <b>An assumption</b>, taken
     * from the ADS pivot table's 0.77/1.28 = 0.602 because no collider table
     * exists in the binary. Calibrate it on device against a crouched target;
     * it is the least-supported number in the aim path.
     */
    public static volatile float AIM_CROUCH_RATIO = 0.60f;

    /**
     * Where in the capsule to aim, as a fraction of its height. 0.82 is the
     * neck base — see {@link Posture#anchorM}.
     */
    public static volatile float AIM_ANCHOR_FRAC = 0.82f;
    public static final float AIM_FRAC_MIN = 0.30f, AIM_FRAC_MAX = 1.00f;

    /**
     * How far ahead of NOW the aim point is carried, ms — the client-side
     * pipeline between deciding and the shot being taken.
     *
     * <h3>Why this is ~185 and not ~650</h3>
     *
     * <p><b>Free Fire's bullets are hitscan</b> (owner-confirmed in game; no
     * bullet-speed field exists on any weapon schema — {@code Bullet_Type} is a
     * Wwise sound switch and {@code BulletLifeTime} is a tracer VFX lifetime).
     * So there is no flight time.</p>
     *
     * <p>And the server <b>rewinds to our own tick</b>: the Cmd 106 hit claim
     * carries {@code idx8} = the client's simulation timer and {@code idx21} =
     * the shooter's ping, and a server with no lag compensation has no use for
     * either field in a damage message. So every TRANSPORT term cancels — our
     * RTT, the enemy's uplink, the broadcast wait — and the lead is purely
     * client-side: swipe (~160) + pipe/daemon (~8) + the game applying the
     * rotation (~17).</p>
     *
     * <p><b>DISCONNECTED as of 2026-09-20, and defaulted to 0.</b> The aim now
     * projects the drawn box directly (see {@code EspConfig.AIM_BOX_FRAC}), so
     * nothing reads this. The reasoning above still holds — the lead really is
     * client-side only — but it was applied to a velocity that a live capture
     * showed is not a clean is-moving signal: a player standing still on the
     * ground reports {@code vel.y = -374} with {@code vel.x = vel.z = 0}, which
     * is gravity being integrated, not motion. Leading on that pushes a
     * stationary target downward.</p>
     *
     * <p>Before re-connecting it, gate the lead on horizontal velocity only and
     * confirm against a capture of a target that is genuinely stationary.</p>
     */
    public static volatile float AIM_PIPELINE_MS = 0f;
    public static final float AIM_PIPELINE_MIN = 0f, AIM_PIPELINE_MAX = 600f;

    /**
     * Refuse a target whose freshest record is older than this, ms.
     *
     * <p>~37% of wall-clock time an entity has no fresh record at all, and the
     * p99 dropout is 1782 ms — over which a moving player travels a median
     * 7.2 m. Predicting through that is not prediction, it is invention. 260 ms
     * is the project's existing staleness threshold.</p>
     */
    public static volatile float AIM_MAX_SRC_AGE_MS = 260f;

    /**
     * Refuse targets by POSTURE. <b>Off by default — the mapping is unproven
     * and a live capture contradicted it.</b> See {@link Posture#verdict}.
     */
    public static volatile boolean AIM_POSTURE_GATE = false;

    /**
     * TEST TRACK: acquire any enemy inside the cone, skipping every optional
     * gate. ON by default while the aim is being brought up.
     *
     * <p>The eligibility ladder grew seven gates before a single swipe had ever
     * been confirmed to land, and several of them were wrong: the posture gate
     * refused 15% of targets on an unverified enum, the self-mounted gate fired
     * once a second on a player who was walking, and the team gate refuses
     * nearly everything because the session key is not decrypting. The net
     * effect was an aim that acquired 68 targets and then nothing, which is
     * indistinguishable from an aim that does not work.</p>
     *
     * <p><b>You cannot debug a steering problem through an acquisition
     * problem.</b> This makes acquisition trivially permissive so the swipe can
     * be judged on its own, then goes back off once PX_PER_DEG is verified.</p>
     *
     * <p>What it skips: team-known, still-time, record age, posture. What it
     * KEEPS: role must be ENEMY, inside {@code AIM_CONE_DEG}, within
     * {@code AIM_MAX_RANGE_M}, on screen, and the scope gate if set.</p>
     */
    public static volatile boolean AIM_TEST_TRACK = true;

    /**
     * Extra px-per-degree multiplier while SCOPED.
     *
     * <p>{@link #PX_PER_DEG} = 10.0 is the HIP-FIRE figure, measured from the
     * binary as {@code 300/(15+20*sens)} at sens 75. Scoped, the game damps the
     * look: the rotation applied per unit of finger travel is multiplied by
     * roughly 0.2, so it takes about <b>five times</b> as much drag to turn the
     * same number of degrees.</p>
     *
     * <p>Using the hip figure while scoped is exactly the reported symptom --
     * the aim crawls toward the target "little by little", because every
     * correction it sends is a fifth of the size it needed to be, and the
     * residual is still there on the next tick.</p>
     *
     * <p>5.0 is the nominal damping inverse, not a measurement. It is also
     * per-attachment in the binary, so tune it: too low and the aim crawls,
     * too high and it overshoots and oscillates around the target.</p>
     */
    public static volatile float AIM_SCOPED_GAIN = 5.0f;

    /**
     * Chase time constant handed to the daemon, ms -- the time to close ~63%
     * of the remaining distance to the aim point.
     *
     * <p>This is the SMOOTHNESS knob. The daemon moves the contact by
     * {@code cur += (target - cur) * (1 - exp(-dt/tau))} every 16 ms, so the
     * offset is always moving and never restarts; tau only decides how hard it
     * pulls.</p>
     *
     * <p>Low is snappy and, far enough down, visibly robotic -- a machine
     * snapping rather than a hand moving. High is smooth but lags behind a
     * moving target. It wants to stay below the aim's ~120 ms re-solve period,
     * or corrections arrive faster than the offset can consume them.</p>
     */
    public static volatile float AIM_STEER_TAU_MS = 55f;
    public static final float STEER_TAU_MIN = 10f, STEER_TAU_MAX = 400f;
    public static final float SCOPED_GAIN_MIN = 0.5f, SCOPED_GAIN_MAX = 20f;

    /**
     * How long our own keepalive must be silent before the aim treats the
     * camera basis as unusable, ms.
     *
     * <p>{@code EspEngine.mounted()} uses a 400 ms window, which is right for
     * "are we in a vehicle" but far too tight for "is the basis safe to aim
     * from": the measured own-state rate is ~5.3/s (~190 ms), so 400 ms is two
     * missed ticks and it fired 1.1 times a second on a player who was walking
     * around on foot. A genuinely frozen basis lasts far longer than that.</p>
     */
    public static volatile float AIM_BASIS_STALE_MS = 1200f;

    /** Clamp helper, so callers do not each re-implement it. */
    public static float clamp(float v, float lo, float hi) {
        if (v != v) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
