package com.nepdon.wire.config;

import com.nepdon.wire.esp.EspEngine;

import android.content.Context;
import android.content.SharedPreferences;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.ui.render.EspRenderView;

/**
 * Live calibration store for the ESP projection.
 *
 * <p>Two of the numbers the world-to-screen transform needs are simply NOT in
 * the packet stream and never will be: the camera field of view and the
 * handedness of the engine horizontal axes. The server sends positions and a
 * unit forward vector; it does not send the client projection matrix. Every
 * previous attempt to pin these down from captures alone was a guess dressed up
 * as a measurement -- the view-axis scoring table in
 * {@link EspRenderView#VIEW_MODE} is the record of that failure (best mapping
 * only 1.6x above chance).</p>
 *
 * <p>So they are solved the only way they can be: by eye, in game, against
 * ground truth. This class is the persistence layer for that --
 * {@link #load} pushes saved values into the renderer volatile statics at
 * service start, and each {@code set*} writes through to both the static and
 * SharedPreferences so a tweak survives a restart.</p>
 *
 * <p><b>Each knob has a distinct visual signature</b>, which is what makes
 * tuning by eye converge instead of wander:</p>
 * <ul>
 *   <li>{@code MIRROR_X} / {@code VIEW_MODE} -- box on the WRONG SIDE of
 *       the screen. Discrete, not continuous: no amount of FOV fixes it.</li>
 *   <li>{@code FOV_X_DEG} -- error is ~zero at the crosshair and grows
 *       with angle from centre. Too small a FOV pushes boxes outward.</li>
 *   <li>{@code FOV_Y_SCALE} -- same, but vertical only. Default 1.0
 *       assumes square pixels; the game may not agree.</li>
 *   <li>{@code TPS_BOOM_M} -- error grows as the target gets CLOSER, and
 *       swings when you rotate the view. That is the third-person camera
 *       orbiting the character.</li>
 *   <li>{@code TPS_PIVOT_H_M} -- constant vertical offset, same at all
 *       distances.</li>
 *   <li>{@code OFFSET_X_PX} / {@code OFFSET_Y_PX} -- constant offset in
 *       both axes; the escape hatch for a principal point that is not the
 *       screen centre (notch/cutout insets, letterboxing).</li>
 * </ul>
 */
public final class EspTuning {

    private EspTuning() { }

    // ---- bounds (also used by the in-game panel) ----
    public static final float FOV_MIN = 30f,  FOV_MAX = 150f;
    public static final float FOVY_MIN = 0.40f, FOVY_MAX = 2.50f;
    public static final float BOOM_MIN = 0f,  BOOM_MAX = 12f;
    public static final float PIVOT_MIN = -1f, PIVOT_MAX = 4f;
    public static final float SHOULDER_MIN = -3f, SHOULDER_MAX = 3f;
    public static final float OFF_MIN = -600f, OFF_MAX = 600f;
    public static final float LEAD_MIN = 0f, LEAD_MAX = 500f;
    public static final float SMOOTH_MIN = 0f, SMOOTH_MAX = 300f;
    public static final float CAM_EXTRAP_MS_MIN = 0f, CAM_EXTRAP_MS_MAX = 200f;
    /**
     * Aim acquisition bounds. Here rather than inline because these were each
     * written as literals in BOTH {@code load()} and the setter, and again in
     * {@code features/AimFeature} -- three copies of one range, which is the
     * exact shape that silently reset a saved 4000 m radar ceiling to 500 m
     * every launch (PLUGIN_ARCHITECTURE section 5b).
     */
    public static final float AIM_CONE_MIN = 1f, AIM_CONE_MAX = 45f;
    public static final float AIM_RANGE_MIN = 10f, AIM_RANGE_MAX = 500f;
    public static final float DEF_AIM_CONE_DEG = 10f, DEF_AIM_RANGE_M = 200f;
    public static final float CAM_EXTRAP_DEG_MIN = 0f, CAM_EXTRAP_DEG_MAX = 180f;
    public static final float EXTRAP_MAX_MIN = 0f, EXTRAP_MAX_MAX = 30f;
    public static final float HP_BAR_H_MIN = 2f, HP_BAR_H_MAX = 14f;
    public static final float RADAR_MIN_LO = 10f, RADAR_MIN_HI = 1000f;
    public static final float RADAR_MAX_LO = 100f, RADAR_MAX_HI = 8000f;

    // ---- defaults ----
    /**
     * MEASURED FROM THE BINARY, 2026-09-13 — not tuned by eye.
     *
     * <p>The game's camera FOV is a <b>vertical 60°</b>:
     * {@code FollowCamera::GetFovNormal} @ 0x0722320c returns the static
     * {@code C_FOV_NORMAL}, filled by the .cctor from the literal pool at
     * 0x18bfeb0 = [60.0, 60.0, 0.2, 2.5]. It is a Unity vertical FOV — the
     * gameplay camera never sets a custom projection matrix, never sets
     * aspect, and is not orthographic — and the game's own vertical→horizontal
     * conversion is visible in GrassSystem::UpdateVisibleCell @ 0x06c39a50:
     * {@code atan(tan(fov*0.5*Deg2Rad) * aspect)}.
     *
     * <p>This knob is the equivalent HORIZONTAL FOV at the 16:9 reference the
     * HOR+ model below uses, so it must satisfy
     * {@code tan(FOV/2)/(16/9) == tan(30°)}, giving
     * {@code FOV = 2*atan(tan(30°)*16/9) = 91.49°}. The old 90° made the
     * implied vertical half-tangent 0.5625 against a true 0.57735 — a 2.6%
     * MULTIPLICATIVE horizontal scale error, i.e. invisible on a close target
     * and obvious on a distant one. That was the far-target drift.</p>
     */
    public static final float DEF_FOV = 91.49f;
    public static final float DEF_FOVY = 1.0f;
    /**
     * MEASURED: {@code FollowCamera::SetOffestRightAndBack} @ 0x07222344
     * writes {@code OFFSET_FOR_NORMAL = 2.50} into the back-offset slot on
     * every camera change, so prefab values never survive. Cross-checked by
     * {@code GetMaxDistanceToTarget} @ 0x07226c88, which computes
     * sqrt(0.4² + 0.05² + 2.5²) = 2.5323 — only meaningful if these three are
     * the rig vector. NOTE this is the MAXIMUM: a collision trace shortens the
     * boom near walls (spring arm), so the live eye can be closer.
     */
    public static final float DEF_BOOM = 2.50f;
    public static final float DEF_PIVOT = 1.6f;
    /** Shoulder offset, MEASURED in game 2026-09-10; see EspRenderView. */
    /**
     * MEASURED magnitude 0.40 ({@code C_OFFSET_RIGHT}, same function as
     * DEF_BOOM). The SIGN is not settled by the binary — the composition
     * happens behind virtual slots in {@code AHCBLOCABDP} @ 0x0722519c — so the
     * negative is kept from the in-game observation. The old -0.70 had absorbed
     * part of the FOV error, which is why all three must be adopted together.
     */
    public static final float DEF_SHOULDER = -0.40f;
    /**
     * Scoped rig, MEASURED from the binary 2026-09-20. ADS replaces the pivot
     * rather than adjusting it: {@code FollowCamera::AHCBLOCABDP} @ 0x0722519c
     * jumps past the whole shoulder/height block to
     * {@code Player::get_AimStartPostion} @ 0x070e2bb0, which is
     * {@code Entity.Position + up*1.28} when standing. The boom is unchanged at
     * 2.50 in both states.
     */
    public static final float DEF_SCOPE_PIVOT = 1.28f;
    public static final float DEF_SCOPE_SHOULDER = 0f;
    /**
     * Handedness, VERIFIED in game 2026-09-10: the view basis needs the screen
     * X axis mirrored. This is a default, not a guess -- see
     * {@link EspRenderView#MIRROR_X}.
     */
    public static final boolean DEF_MIRROR_X = true;
    public static final float DEF_OFF = 0f;
    /**
     * Lead applied to a target's position, ms.
     *
     * <p>215 ms was the MEASURED server tick-plus-transport figure and is still
     * the honest number for "how stale is this position". It is NOT the default,
     * because prediction is now off by default: with EXTRAPOLATE and AUTO_LEAD
     * false a non-zero lead just throws the box off a strafing target. The owner
     * tuned these in game on 2026-09-17 and 0 ms is what actually tracks. Turn
     * EXTRAPOLATE back on in the tune panel before raising this.</p>
     */
    public static final float DEF_LEAD_MS = 0f;
    /**
     * Prediction defaults, adopted from the owner's in-game tune 2026-09-17.
     *
     * <p>Both off. Prediction was on by default, which is why boxes led moving
     * targets. These pair with {@link #DEF_LEAD_MS} == 0 — changing one without
     * the other gives a half-configured predictor.</p>
     *
     * <p>They are constants rather than inline literals because the SAME two
     * values were previously written in four places ({@code load()},
     * {@code reset()} and the {@code EspRenderView} static initialisers) and a
     * default that lives in more than one place is the bug this codebase keeps
     * relearning. {@code EspRenderView} still holds its own initialiser for the
     * pre-{@code load()} window; keep the two in step.</p>
     */
    public static final boolean DEF_EXTRAPOLATE = false;
    public static final boolean DEF_AUTO_LEAD = false;
    /**
     * Facing indicator, off. Added 2026-09-20 for the same reason the two above
     * exist: {@code features/EspFeature} was stating its own {@code true} and
     * winning, because {@code Registry.install()} runs AFTER {@link #load()}.
     */
    public static final boolean DEF_SHOW_POSE = false;
    /** Cap on how far prediction may move a box, metres. Owner-tuned 2026-09-18: 0 (off). */
    /**
     * Absurdity cap on a predicted displacement, metres. 8.0, and NEVER 0.
     *
     * <p>It shipped at 0.0 labelled "off". The code read
     * {@code if (mag > maxU && mag > 1e-6) k = maxU/mag} -- at maxU == 0 that
     * is k == 0, so every predicted displacement was multiplied to nothing on
     * all three axes. <b>A cap of zero clamps to zero; it does not mean "no
     * cap".</b> Target prediction could therefore be switched ON and move
     * nothing, silently, which is what shipped.</p>
     *
     * <p>8.0 m is above any real displacement the predictor can produce -- the
     * horizon is capped at {@code EXTRAP_STOP_MS} = 600 ms and the measured
     * speed ceiling is 13.7 m/s, so 8.2 m is the physical maximum. It fires
     * only on a corrupt velocity, which is the whole intent. Capping tighter
     * was measured and every value made prediction worse (no cap 1044 RMS,
     * 400 ms 1086, 250 ms 1201).</p>
     */
    public static final float DEF_EXTRAP_MAX_M = 8.0f;
    public static final float DEF_SMOOTH_MS = 0f;
    /** Camera-forward angular lead, ms. See EspConfig.CAM_EXTRAP_MS. Owner-tuned 2026-09-18. */
    /**
     * Camera-basis lead, ms. 50 because the outbound Cmd 2001 tick is ~99 ms
     * (measured p50 100 / p99 103), so at any instant our own view angle is
     * 0-99 ms stale and the MEAN staleness is half a tick. This compensates the
     * mean; it is not a prediction of where the camera is going.
     *
     * <p>A scope multiplies the uncorrected error by the zoom: at 3.27x a 1°
     * lag is 53 px, about one box width at 32 m, which a gentle 20 deg/s pan
     * reaches. That is why it was invisible at hip fire and obvious scoped.</p>
     */
    public static final float DEF_CAM_EXTRAP_MS = 0f;
    /** Cap on the applied camera-forward extrapolation angle, degrees. */
    public static final float DEF_CAM_EXTRAP_MAX_DEG = 180f;
    public static final float DEF_RADAR_MIN = 50f;
    public static final float DEF_RADAR_MAX = 4000f;

    private static SharedPreferences sp;

    /**
     * Current camera-calibration schema. Bump when a camera DEFAULT or the
     * MEANING of a camera key changes, and add the reason below.
     *
     * <p>1 -> 2 (2026-09-13): FOV, boom and shoulder were measured out of
     * {@code libil2cpp_dec.so} instead of guessed. {@code DEF_FOV} 90 ->
     * 91.49 (the value for which {@code tan(FOV/2)/(16/9) == tan(30°)}, i.e.
     * the game's real VERTICAL 60° at the HOR+ reference aspect),
     * {@code DEF_BOOM} 2.50, {@code DEF_SHOULDER} -0.40. Any value saved
     * before this was tuned against either the wrong FOV or the pre-HOR+
     * maths, so it must not survive the upgrade.</p>
     *
     * <p>2 -&gt; 3 (2026-09-19): {@code esp_scope_zoom} shipped at 8.0 on the
     * assumption that a scope magnifies the camera. It does not appear to:
     * {@code plan/assets/scope2.jpg} shows a road, trees and a tree line all
     * outside the scope ring at 8x, which a 6-13° horizontal FOV cannot
     * contain, and the binary has no ADS path to
     * {@code Camera::set_fieldOfView} at all. 8.0 made every box 8x too large
     * and multiplied every angular error by 8. Default is now 1.0 — scoped
     * behaves as unscoped — and any 8.0 already saved must not survive.</p>
     *
     * <p>3 -&gt; 4 (2026-09-19): <b>schema 3 was wrong and is reverted.</b> At
     * 1.0 the boxes were compressed toward the centre by ~10x. The peripheral
     * scenery really is unmagnified, but the game's own world-anchored markers
     * are not — they project through the magnified camera — and an ESP should
     * agree with those. A box 8x too large at 32 m is also not a bug: at a true
     * 8x a 1.8 m player genuinely occupies ~40% of screen height. Default back
     * to 8.0, and the 1.0 that schema 3 seeded must not survive.</p>
     *
     * <p>4 -&gt; 5 (2026-09-20): <b>1.0 and 8.0 were both wrong, and both were
     * derived the same broken way.</b> Every value up to here was inferred
     * from where a box SAT, and a box's position cannot be read without first
     * agreeing where the optic centre is — which moves the answer from 7 to 21
     * on a ±50 px disagreement. Schema 3 and 4 are two confident numbers off
     * one unmeasurable premise, in opposite directions.</p>
     *
     * <p>Default is now <b>3.30, measured</b>, from a quantity with no centre
     * in it: a character's on-screen HEIGHT is a difference of two projected
     * points, so the centre, the nudge and the handedness all cancel. Two
     * 8x-scoped screenshots — a player at 25 m standing 224 device px tall,
     * another at 32 m standing ~170 px — both give ~3100 px/radian against
     * 935.3 unscoped, i.e. a scoped vertical field of ~19-20° and a
     * magnification near 3.3x. <b>The HUD's "8X" is not the on-screen
     * magnification.</b></p>
     *
     * <p>This also explains the symptom schema 4 left behind: a uniformly wrong
     * zoom misplaces a target by {@code (k-1) x offset}, which is exactly zero
     * at the crosshair and grows linearly away from it — "fine looking straight
     * at it, terrible slightly left or right". Whether a magnified inset sits
     * on top is refuted: the binary computes the scoped field as one scalar
     * divide over the whole frame, {@code C_FOV_NORMAL / sight_fov_amplify_ratio}
     * (FDIV @ 0x74e2bc4), and no caller of any camera-retarget API is a
     * sighting path. A ratio of exactly 3.0 gives 20.00 deg and a tangent zoom
     * of 3.2744 -- which is the measurement, to three decimals.</p>
     *
     * <p>5 -&gt; 6 (2026-09-20): camera lead is re-enabled and its saved value
     * must not survive. {@code esp_cam_extrap_ms} was persisted as <b>0</b>
     * while the knob was DEAD — gated behind {@code EXTRAPOLATE}, which the
     * owner turned off for target prediction on 2026-09-17. That 0 is a stale
     * answer to a question that could not be asked at the time.</p>
     *
     * <p>The two are different corrections. Target prediction guesses where a
     * PLAYER will be and overshoots when they stop; camera lead corrects OUR
     * OWN view angle being up to 99 ms stale, which is a rigid rotation of the
     * whole basis with nothing to overshoot. A scope multiplies the second by
     * the zoom, so at 3.27x an uncorrected gentle 20 deg/s pan slides every box
     * by about one box width — the reported symptom, and the reason this only
     * became visible once the scoped projection was right.
     * {@link #DEF_CAM_EXTRAP_MS} 30 -&gt; 50, half the measured tick.</p>
     */
    private static final int CAM_SCHEMA = 6;

    /** Camera keys re-seeded by a schema bump; nothing else is touched. */
    private static final String[] CAM_KEYS = {
            Prefs.KEY_ESP_FOV_X, Prefs.KEY_ESP_FOV_Y_SCALE,
            Prefs.KEY_ESP_BOOM_M, Prefs.KEY_ESP_PIVOT_M, Prefs.KEY_ESP_SHOULDER_M,
            Prefs.KEY_ESP_OFF_X, Prefs.KEY_ESP_OFF_Y,
            // Camera projection, so a schema bump must re-seed it too. Added
            // WITHOUT a bump: no existing default or meaning changed.
            Prefs.KEY_ESP_SCOPE_ZOOM,
            Prefs.KEY_ESP_SCOPE_BOX,
            Prefs.KEY_ESP_SCOPE_OFF_X, Prefs.KEY_ESP_SCOPE_OFF_Y,
            Prefs.KEY_ESP_SCOPE_XEX,
            // Scoped rig: camera geometry, so a schema bump re-seeds it.
            Prefs.KEY_ESP_SCOPE_BOOM, Prefs.KEY_ESP_SCOPE_SHLD,
            Prefs.KEY_ESP_SCOPE_PIVOT,
            // Camera lead: its saved 0 was set while the knob was dead, gated
            // behind EXTRAPOLATE. Decoupling it made that 0 a stale answer to a
            // question that could not previously be asked.
            Prefs.KEY_ESP_CAM_EXTRAP_MS,
    };

    /** True when this launch re-seeded the camera keys (surfaced on the HUD). */
    private static volatile boolean camMigrated = false;

    /** @return true if the camera calibration was reset by a schema bump. */
    public static boolean cameraWasMigrated() { return camMigrated; }

    /**
     * Drops saved camera calibration that predates {@link #CAM_SCHEMA} so the
     * measured defaults actually apply. Deliberately deletes rather than
     * overwrites: {@code load()} runs straight after and a missing key takes
     * the default, so there is exactly one place the values are defined.
     */
    private static void migrateCamera() {
        int have = sp.getInt(Prefs.KEY_ESP_CAM_SCHEMA, 1);
        if (have >= CAM_SCHEMA) return;
        SharedPreferences.Editor ed = sp.edit();
        for (String k : CAM_KEYS) ed.remove(k);
        ed.putInt(Prefs.KEY_ESP_CAM_SCHEMA, CAM_SCHEMA);
        ed.apply();
        camMigrated = true;
    }

    /**
     * Reads every saved value into the renderer statics. Call once at start.
     *
     * <p><b>Being migrated to the feature registry.</b> Settings declared by a
     * {@link com.nepdon.wire.config.plugin.Feature} are loaded by
     * {@code Features.install} below, which runs AFTER this method and
     * therefore wins. The two overlap deliberately during the migration: a
     * setting lives in both until its feature is written, and the build stays
     * green the whole way rather than needing one 99-setting flag day.</p>
     *
     * <p>When a key appears in a {@code Feature}, delete its line from this
     * method — do not leave both, or the loser will be whichever ran last and
     * that is not a thing anyone should have to reason about.</p>
     */
    public static void load(Context ctx) {
        sp = Prefs.get(ctx.getApplicationContext());
        migrateCamera();
        EspConfig.FOV_X_DEG      = clamp(sp.getFloat(Prefs.KEY_ESP_FOV_X, calFov()), FOV_MIN, FOV_MAX);
        EspConfig.FOV_Y_SCALE    = clamp(sp.getFloat(Prefs.KEY_ESP_FOV_Y_SCALE, DEF_FOVY), FOVY_MIN, FOVY_MAX);
        EspConfig.TPS_BOOM_M     = clamp(sp.getFloat(Prefs.KEY_ESP_BOOM_M, calBoom()), BOOM_MIN, BOOM_MAX);
        EspConfig.TPS_PIVOT_H_M  = clamp(sp.getFloat(Prefs.KEY_ESP_PIVOT_M, DEF_PIVOT), PIVOT_MIN, PIVOT_MAX);
        EspConfig.TPS_SHOULDER_M = clamp(sp.getFloat(Prefs.KEY_ESP_SHOULDER_M, calShoulder()), SHOULDER_MIN, SHOULDER_MAX);
        EspConfig.LEAD_MS        = clamp(sp.getFloat(Prefs.KEY_ESP_LEAD_MS, DEF_LEAD_MS), LEAD_MIN, LEAD_MAX);
        EspConfig.EXTRAP_MAX_M   = clamp(sp.getFloat(Prefs.KEY_ESP_EXTRAP_MAX, DEF_EXTRAP_MAX_M), EXTRAP_MAX_MIN, EXTRAP_MAX_MAX);
        EspConfig.EXTRAPOLATE    = sp.getBoolean(Prefs.KEY_ESP_EXTRAPOLATE, DEF_EXTRAPOLATE);
        EspConfig.AUTO_LEAD      = sp.getBoolean(Prefs.KEY_ESP_AUTO_LEAD, DEF_AUTO_LEAD);
        EspConfig.SMOOTH_MS      = clamp(sp.getFloat(Prefs.KEY_ESP_SMOOTH_MS, DEF_SMOOTH_MS), SMOOTH_MIN, SMOOTH_MAX);
        EspConfig.CAM_EXTRAP_MS  = clamp(sp.getFloat(Prefs.KEY_ESP_CAM_EXTRAP_MS, DEF_CAM_EXTRAP_MS), CAM_EXTRAP_MS_MIN, CAM_EXTRAP_MS_MAX);
        EspConfig.CAM_EXTRAP_MAX_DEG = clamp(sp.getFloat(Prefs.KEY_ESP_CAM_EXTRAP_DEG, DEF_CAM_EXTRAP_MAX_DEG), CAM_EXTRAP_DEG_MIN, CAM_EXTRAP_DEG_MAX);
        // aim (same keys AimFeature declares)
        com.nepdon.wire.ui.aim.AimConfig.PX_PER_DEG =
                clamp(sp.getFloat(Prefs.KEY_AIM_SWIPE, com.nepdon.wire.ui.aim.AimConfig.DEF_PX_PER_DEG),
                        com.nepdon.wire.ui.aim.AimConfig.PX_PER_DEG_MIN, com.nepdon.wire.ui.aim.AimConfig.PX_PER_DEG_MAX);
        com.nepdon.wire.ui.aim.AimConfig.SWIPE_X_FRAC =
                clamp(sp.getFloat(Prefs.KEY_AIM_SWIPE_X, com.nepdon.wire.ui.aim.AimConfig.DEF_SWIPE_X_FRAC),
                        com.nepdon.wire.ui.aim.AimConfig.FRAC_MIN, com.nepdon.wire.ui.aim.AimConfig.FRAC_MAX);
        com.nepdon.wire.ui.aim.AimConfig.SWIPE_Y_FRAC =
                clamp(sp.getFloat(Prefs.KEY_AIM_SWIPE_Y, com.nepdon.wire.ui.aim.AimConfig.DEF_SWIPE_Y_FRAC),
                        com.nepdon.wire.ui.aim.AimConfig.FRAC_MIN, com.nepdon.wire.ui.aim.AimConfig.FRAC_MAX);
        com.nepdon.wire.ui.aim.AimConfig.MAX_TRAVEL_PX =
                clamp(sp.getFloat(Prefs.KEY_AIM_REGRIP, com.nepdon.wire.ui.aim.AimConfig.DEF_MAX_TRAVEL_PX),
                        com.nepdon.wire.ui.aim.AimConfig.MAX_TRAVEL_MIN, com.nepdon.wire.ui.aim.AimConfig.MAX_TRAVEL_MAX);
        EspConfig.AIM_CONE_DEG = clamp(sp.getFloat(Prefs.KEY_AIM_FOV,
                DEF_AIM_CONE_DEG), AIM_CONE_MIN, AIM_CONE_MAX);
        EspConfig.AIM_MAX_RANGE_M = clamp(sp.getFloat(Prefs.KEY_AIM_HEIGHT,
                DEF_AIM_RANGE_M), AIM_RANGE_MIN, AIM_RANGE_MAX);
        EspConfig.AIM_STILL_MS = (long) clamp(sp.getFloat(Prefs.KEY_AIM_LOCK, 600f), 0f, 3000f);
        EspConfig.SHOW_AIM_EDITOR = sp.getBoolean(Prefs.KEY_AIM_CIRCLE, false);
        EspConfig.AIM_SCOPE_ONLY = sp.getBoolean(Prefs.KEY_AIM_SCOPE_ONLY, true);
        
        com.nepdon.wire.ui.aim.AimConfig.TEST_MODE =
                sp.getBoolean(Prefs.KEY_AIM_TEST_MODE, true);
        com.nepdon.wire.ui.aim.AimConfig.TEST_HOLD_MS =
                (int) clamp(sp.getFloat(Prefs.KEY_AIM_SPIN_MS, com.nepdon.wire.ui.aim.AimConfig.DEF_TEST_HOLD_MS),
                        com.nepdon.wire.ui.aim.AimConfig.HOLD_MS_MIN, com.nepdon.wire.ui.aim.AimConfig.HOLD_MS_MAX);
        com.nepdon.wire.ui.aim.AimConfig.AIM_H_STANDING_M = clamp(sp.getFloat(Prefs.KEY_AIM_BODY_H,
                1.75f), com.nepdon.wire.ui.aim.AimConfig.AIM_H_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_H_MAX);
        com.nepdon.wire.ui.aim.AimConfig.AIM_CROUCH_RATIO = clamp(sp.getFloat(Prefs.KEY_AIM_CROUCH_R,
                0.60f), 0.30f, 1.0f);
        com.nepdon.wire.ui.aim.AimConfig.AIM_ANCHOR_FRAC = clamp(sp.getFloat(Prefs.KEY_AIM_ANCHOR,
                0.82f), com.nepdon.wire.ui.aim.AimConfig.AIM_FRAC_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_FRAC_MAX);
        com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MS = clamp(sp.getFloat(Prefs.KEY_AIM_PIPELINE,
                185f), com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MAX);
        com.nepdon.wire.ui.aim.AimConfig.AIM_MAX_SRC_AGE_MS = clamp(sp.getFloat(Prefs.KEY_AIM_SRC_AGE,
                260f), 60f, 1000f);
        com.nepdon.wire.ui.aim.AimConfig.AIM_POSTURE_GATE = sp.getBoolean(Prefs.KEY_AIM_POSE_GATE, false);
        com.nepdon.wire.ui.aim.AimConfig.AIM_TEST_TRACK = sp.getBoolean(Prefs.KEY_AIM_TEST_TRACK, true);
        com.nepdon.wire.ui.aim.AimConfig.AIM_SCOPED_GAIN = clamp(sp.getFloat(Prefs.KEY_AIM_SCOPED_GAIN,
                5.0f), com.nepdon.wire.ui.aim.AimConfig.SCOPED_GAIN_MIN, com.nepdon.wire.ui.aim.AimConfig.SCOPED_GAIN_MAX);
        com.nepdon.wire.ui.aim.AimConfig.AIM_STEER_TAU_MS = clamp(sp.getFloat(Prefs.KEY_AIM_STEER_TAU,
                55f), com.nepdon.wire.ui.aim.AimConfig.STEER_TAU_MIN, com.nepdon.wire.ui.aim.AimConfig.STEER_TAU_MAX);
        com.nepdon.wire.config.EspConfig.AIM_BOX_FRAC = clamp(sp.getFloat(Prefs.KEY_AIM_BOX_FRAC,
                0.55f), 0f, 1f);
        com.nepdon.wire.ui.aim.AimConfig.AIM_BASIS_STALE_MS = clamp(sp.getFloat(Prefs.KEY_AIM_BASIS_AGE,
                1200f), 200f, 5000f);
        com.nepdon.wire.ui.aim.AimConfig.PLANT_ON_FIRE =
                sp.getBoolean(Prefs.KEY_SETUP_PLANT_FIRE, true);
        EspConfig.OFFSET_X_PX    = clamp(sp.getFloat(Prefs.KEY_ESP_OFF_X, DEF_OFF), OFF_MIN, OFF_MAX);
        EspConfig.OFFSET_Y_PX    = clamp(sp.getFloat(Prefs.KEY_ESP_OFF_Y, DEF_OFF), OFF_MIN, OFF_MAX);
        EspConfig.RADAR_MIN_RANGE_M =
                clamp(sp.getFloat(Prefs.KEY_ESP_RADAR_MIN, DEF_RADAR_MIN), RADAR_MIN_LO, RADAR_MIN_HI);
        EspConfig.RADAR_MAX_RANGE_M =
                clamp(sp.getFloat(Prefs.KEY_ESP_RADAR_MAX, DEF_RADAR_MAX), RADAR_MAX_LO, RADAR_MAX_HI);
        EspConfig.MIRROR_X        = sp.getBoolean(Prefs.KEY_ESP_MIRROR_X, DEF_MIRROR_X);
        com.nepdon.wire.esp.EspEngine.MOUNT_VEHICLE_CAMERA =
                sp.getBoolean(Prefs.KEY_ESP_MOUNT_CAM, true);
        EspConfig.ENEMY_AXIS_SWAP = sp.getBoolean(Prefs.KEY_ESP_AXIS_SWAP, false);
        EspConfig.ENEMY_NEG_AXIS1 = sp.getBoolean(Prefs.KEY_ESP_NEG_A1, false);
        EspConfig.ENEMY_NEG_AXIS2 = sp.getBoolean(Prefs.KEY_ESP_NEG_A2, false);
        EspConfig.PITCH_SIGN      = sp.getBoolean(Prefs.KEY_ESP_PITCH_NEG, false) ? -1f : 1f;
        EspConfig.VIEW_MODE       = clampI(sp.getInt(Prefs.KEY_ESP_VIEW_MODE, 0), 0, 7);
        EspConfig.VIEW_AUTOCYCLE  = sp.getBoolean(Prefs.KEY_ESP_AUTOCYCLE, false);
        EspConfig.SHOW_TEAM       = sp.getBoolean(Prefs.KEY_ESP_SHOW_TEAM, false);
        EspConfig.SHOW_POSE       = sp.getBoolean(Prefs.KEY_ESP_POSE, DEF_SHOW_POSE);
        EspConfig.FOV_ZOOM        = clamp(sp.getFloat(Prefs.KEY_ESP_ZOOM, 1f), 1f, 8f);
        EspConfig.AUTO_SCOPE_ZOOM = sp.getBoolean(Prefs.KEY_ESP_SCOPE_AUTO, false);
        EspConfig.SCOPED_ZOOM     =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_ZOOM, 3.27f), 1f, 16f);
        EspConfig.SCOPE_BOX_SCALE =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_BOX, 1f), 0.05f, 4f);
        EspConfig.SCOPE_OFF_X_PX  =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_OFF_X, 0f), -1600f, 1600f);
        EspConfig.SCOPE_OFF_Y_PX  =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_OFF_Y, 0f), -1600f, 1600f);
        EspConfig.SCOPE_X_EXTRA   =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_XEX, 1f), 0.05f, 20f);
        EspConfig.SCOPE_SQUARE    = sp.getBoolean(Prefs.KEY_ESP_SCOPE_SQ, false);
        EspConfig.SCOPE_BOOM_M    =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_BOOM, DEF_BOOM), 0f, 12f);
        EspConfig.SCOPE_SHOULDER_M =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_SHLD, DEF_SCOPE_SHOULDER), -3f, 3f);
        EspConfig.SCOPE_PIVOT_M   =
                clamp(sp.getFloat(Prefs.KEY_ESP_SCOPE_PIVOT, DEF_SCOPE_PIVOT), -1f, 4f);
        EspConfig.CAM_LEAD_ALWAYS = sp.getBoolean(Prefs.KEY_ESP_CAM_LEAD_ALWAYS, true);
        EspConfig.TARGET_AGE_CARRY = sp.getBoolean(Prefs.KEY_ESP_AGE_CARRY, false);
        com.nepdon.wire.filter.MarkFilter.ENABLED =
                sp.getBoolean(Prefs.KEY_MARK_RETARGET, false);
        EspEngine.PITCH_DEG_PER_LSB   = clamp(sp.getFloat(Prefs.KEY_ESP_PITCH_LSB, 1.29f), 0.5f, 2.5f);
        EspConfig.SHOW_UNCLASSIFIED = sp.getBoolean(Prefs.KEY_ESP_SHOW_UNK, true);
        EspConfig.HP_BAR = sp.getBoolean(Prefs.KEY_ESP_HP_BAR, true);
        EspConfig.SHOW_NAMES = sp.getBoolean(Prefs.KEY_ESP_NAMES, true);
        EspConfig.HP_BAR_H_PX = clamp(sp.getFloat(Prefs.KEY_ESP_HP_BAR_H, 4f),
                HP_BAR_H_MIN, HP_BAR_H_MAX);
    }

    // (The hit-rewrite write-through setters — setHitMove/HeadHeight/Idx15Mode/
    // TargetZone/UpperBodyOnly/UbMinHeight/CpPatch/Harvest — were REMOVED with
    // AimFilter on 2026-09-18. The Cmd-106 headshot forgery is gone.)

    /** Restores every projection knob to its shipped default. */
    public static void reset() {
        // Stamp the camera schema: a manual reset already writes the current
        // defaults, so the next launch must not treat them as legacy values.
        if (sp != null) sp.edit().putInt(Prefs.KEY_ESP_CAM_SCHEMA, CAM_SCHEMA).apply();
        putF(Prefs.KEY_ESP_FOV_X, EspConfig.FOV_X_DEG = DEF_FOV);
        putF(Prefs.KEY_ESP_FOV_Y_SCALE, EspConfig.FOV_Y_SCALE = DEF_FOVY);
        putF(Prefs.KEY_ESP_BOOM_M, EspConfig.TPS_BOOM_M = DEF_BOOM);
        putF(Prefs.KEY_ESP_PIVOT_M, EspConfig.TPS_PIVOT_H_M = DEF_PIVOT);
        putF(Prefs.KEY_ESP_SHOULDER_M, EspConfig.TPS_SHOULDER_M = DEF_SHOULDER);
        putF(Prefs.KEY_ESP_LEAD_MS, EspConfig.LEAD_MS = DEF_LEAD_MS);
        putF(Prefs.KEY_ESP_EXTRAP_MAX, EspConfig.EXTRAP_MAX_M = DEF_EXTRAP_MAX_M);
        putB(Prefs.KEY_ESP_EXTRAPOLATE, EspConfig.EXTRAPOLATE = DEF_EXTRAPOLATE);
        putB(Prefs.KEY_ESP_AUTO_LEAD, EspConfig.AUTO_LEAD = DEF_AUTO_LEAD);
        putF(Prefs.KEY_ESP_SMOOTH_MS, EspConfig.SMOOTH_MS = DEF_SMOOTH_MS);
        putF(Prefs.KEY_ESP_CAM_EXTRAP_MS, EspConfig.CAM_EXTRAP_MS = DEF_CAM_EXTRAP_MS);
        putF(Prefs.KEY_ESP_CAM_EXTRAP_DEG, EspConfig.CAM_EXTRAP_MAX_DEG = DEF_CAM_EXTRAP_MAX_DEG);
        putF(Prefs.KEY_ESP_OFF_X, EspConfig.OFFSET_X_PX = DEF_OFF);
        putF(Prefs.KEY_ESP_OFF_Y, EspConfig.OFFSET_Y_PX = DEF_OFF);
        putB(Prefs.KEY_ESP_MIRROR_X, EspConfig.MIRROR_X = DEF_MIRROR_X);
        putB(Prefs.KEY_ESP_AXIS_SWAP, EspConfig.ENEMY_AXIS_SWAP = false);
        putB(Prefs.KEY_ESP_NEG_A1, EspConfig.ENEMY_NEG_AXIS1 = false);
        putB(Prefs.KEY_ESP_NEG_A2, EspConfig.ENEMY_NEG_AXIS2 = false);
        putB(Prefs.KEY_ESP_PITCH_NEG, false);
        EspConfig.PITCH_SIGN = 1f;
        putI(Prefs.KEY_ESP_VIEW_MODE, EspConfig.VIEW_MODE = 0);
    }

    // ---- write-through setters ----

    public static void setFovX(float v) {
        putF(Prefs.KEY_ESP_FOV_X, EspConfig.FOV_X_DEG = clamp(v, FOV_MIN, FOV_MAX));
    }

    public static void setFovYScale(float v) {
        putF(Prefs.KEY_ESP_FOV_Y_SCALE, EspConfig.FOV_Y_SCALE = clamp(v, FOVY_MIN, FOVY_MAX));
    }

    public static void setBoom(float v) {
        putF(Prefs.KEY_ESP_BOOM_M, EspConfig.TPS_BOOM_M = clamp(v, BOOM_MIN, BOOM_MAX));
    }

    public static void setPivot(float v) {
        putF(Prefs.KEY_ESP_PIVOT_M, EspConfig.TPS_PIVOT_H_M = clamp(v, PIVOT_MIN, PIVOT_MAX));
    }

    public static void setShoulder(float v) {
        putF(Prefs.KEY_ESP_SHOULDER_M,
                EspConfig.TPS_SHOULDER_M = clamp(v, SHOULDER_MIN, SHOULDER_MAX));
    }

    public static void setLeadMs(float v) {
        putF(Prefs.KEY_ESP_LEAD_MS, EspConfig.LEAD_MS = clamp(v, LEAD_MIN, LEAD_MAX));
    }

    public static void setExtrapMax(float v) {
        putF(Prefs.KEY_ESP_EXTRAP_MAX,
                EspConfig.EXTRAP_MAX_M = clamp(v, EXTRAP_MAX_MIN, EXTRAP_MAX_MAX));
    }

    public static void setAutoLead(boolean b) {
        putB(Prefs.KEY_ESP_AUTO_LEAD, EspConfig.AUTO_LEAD = b);
    }

    /** Recommended delay: owner-tuned camera lead and a light box filter. */
    public static final float REC_CAM_EXTRAP_MS = 50f;
    public static final float REC_SMOOTH_MS    = 20f;

    /** Mod menu RESET: both delay knobs to the recommended values. */
    public static void resetDelay() {
        setCamExtrapMs(REC_CAM_EXTRAP_MS);
        setSmoothMs(REC_SMOOTH_MS);
    }

    public static void setSmoothMs(float v) {
        putF(Prefs.KEY_ESP_SMOOTH_MS,
                EspConfig.SMOOTH_MS = clamp(v, SMOOTH_MIN, SMOOTH_MAX));
    }

    public static void setCamExtrapMs(float v) {
        putF(Prefs.KEY_ESP_CAM_EXTRAP_MS,
                EspConfig.CAM_EXTRAP_MS = clamp(v, CAM_EXTRAP_MS_MIN, CAM_EXTRAP_MS_MAX));
    }

    public static void setCamExtrapMaxDeg(float v) {
        putF(Prefs.KEY_ESP_CAM_EXTRAP_DEG,
                EspConfig.CAM_EXTRAP_MAX_DEG = clamp(v, CAM_EXTRAP_DEG_MIN, CAM_EXTRAP_DEG_MAX));
    }

    // ---- aim ----
    // Persisted through the SAME KEY_AIM_* strings AimFeature declares, so the
    // settings screen and the in-game tune panel are two views of one value.
    // Never rename these keys: they are on the device (hard rule 5).

    public static void setAimPxPerDeg(float v) {
        putF(Prefs.KEY_AIM_SWIPE,
                com.nepdon.wire.ui.aim.AimConfig.PX_PER_DEG = clamp(v, 1f, 60f));
    }

    public static void setAimSwipeX(float v) {
        putF(Prefs.KEY_AIM_SWIPE_X,
                com.nepdon.wire.ui.aim.AimConfig.SWIPE_X_FRAC = clamp(v, 0.05f, 0.95f));
    }

    public static void setAimSwipeY(float v) {
        putF(Prefs.KEY_AIM_SWIPE_Y,
                com.nepdon.wire.ui.aim.AimConfig.SWIPE_Y_FRAC = clamp(v, 0.05f, 0.95f));
    }

    public static void setAimMaxTravel(float v) {
        putF(Prefs.KEY_AIM_REGRIP,
                com.nepdon.wire.ui.aim.AimConfig.MAX_TRAVEL_PX = clamp(v, 20f, 400f));
    }

    public static void setAimCone(float v) {
        putF(Prefs.KEY_AIM_FOV, EspConfig.AIM_CONE_DEG =
                clamp(v, AIM_CONE_MIN, AIM_CONE_MAX));
    }

    public static void setAimRange(float v) {
        putF(Prefs.KEY_AIM_HEIGHT, EspConfig.AIM_MAX_RANGE_M =
                clamp(v, AIM_RANGE_MIN, AIM_RANGE_MAX));
    }

    public static void setAimStillMs(float v) {
        float c = clamp(v, 0f, 3000f);
        EspConfig.AIM_STILL_MS = (long) c;
        putF(Prefs.KEY_AIM_LOCK, c);
    }

    public static void setAimScopeOnly(boolean b) {
        putB(Prefs.KEY_AIM_SCOPE_ONLY, EspConfig.AIM_SCOPE_ONLY = b);
    }

    public static void setAimPlantOnFire(boolean b) {
        putB(Prefs.KEY_SETUP_PLANT_FIRE,
                com.nepdon.wire.ui.aim.AimConfig.PLANT_ON_FIRE = b);
    }

    public static void setAimSpinMs(float v) {
        float c = clamp(v, 500f, 20000f);
        com.nepdon.wire.ui.aim.AimConfig.TEST_HOLD_MS = (int) c;
        putF(Prefs.KEY_AIM_SPIN_MS, c);
    }

    public static void setAimTestMode(boolean b) {
        putB(Prefs.KEY_AIM_TEST_MODE, b);
        if (b) com.nepdon.wire.ui.aim.AimController.arm();
        else com.nepdon.wire.ui.aim.AimController.disarm();
    }

    public static void setAimSpinOnScope(boolean b) {
        putB(Prefs.KEY_AIM_SPIN_SCOPE, b);
        
    }

    public static void setAimEditor(boolean b) {
        putB(Prefs.KEY_AIM_CIRCLE, EspConfig.SHOW_AIM_EDITOR = b);
    }

    public static void setExtrapolate(boolean b) {
        putB(Prefs.KEY_ESP_EXTRAPOLATE, EspConfig.EXTRAPOLATE = b);
    }

    public static void setOffX(float v) {
        putF(Prefs.KEY_ESP_OFF_X, EspConfig.OFFSET_X_PX = clamp(v, OFF_MIN, OFF_MAX));
    }

    public static void setOffY(float v) {
        putF(Prefs.KEY_ESP_OFF_Y, EspConfig.OFFSET_Y_PX = clamp(v, OFF_MIN, OFF_MAX));
    }

    public static void setRadarMin(float v) {
        putF(Prefs.KEY_ESP_RADAR_MIN,
                EspConfig.RADAR_MIN_RANGE_M = clamp(v, RADAR_MIN_LO, RADAR_MIN_HI));
    }

    public static void setRadarMax(float v) {
        putF(Prefs.KEY_ESP_RADAR_MAX,
                EspConfig.RADAR_MAX_RANGE_M = clamp(v, RADAR_MAX_LO, RADAR_MAX_HI));
    }

    public static void setMirrorX(boolean b) {
        putB(Prefs.KEY_ESP_MIRROR_X, EspConfig.MIRROR_X = b);
    }

    public static void setAxisSwap(boolean b) {
        putB(Prefs.KEY_ESP_AXIS_SWAP, EspConfig.ENEMY_AXIS_SWAP = b);
    }

    public static void setNegA1(boolean b) {
        putB(Prefs.KEY_ESP_NEG_A1, EspConfig.ENEMY_NEG_AXIS1 = b);
    }

    public static void setNegA2(boolean b) {
        putB(Prefs.KEY_ESP_NEG_A2, EspConfig.ENEMY_NEG_AXIS2 = b);
    }

    public static void setPitchNeg(boolean b) {
        putB(Prefs.KEY_ESP_PITCH_NEG, b);
        EspConfig.PITCH_SIGN = b ? -1f : 1f;
    }

    public static void setViewMode(int m) {
        putI(Prefs.KEY_ESP_VIEW_MODE, EspConfig.VIEW_MODE = clampI(m, 0, 7));
    }

    public static void setAutocycle(boolean b) {
        putB(Prefs.KEY_ESP_AUTOCYCLE, EspConfig.VIEW_AUTOCYCLE = b);
    }

    public static void setShowTeam(boolean b) {
        putB(Prefs.KEY_ESP_SHOW_TEAM, EspConfig.SHOW_TEAM = b);
    }

    public static void setShowUnk(boolean b) {
        putB(Prefs.KEY_ESP_SHOW_UNK, EspConfig.SHOW_UNCLASSIFIED = b);
    }

    public static void setHpBar(boolean b) {
        putB(Prefs.KEY_ESP_HP_BAR, EspConfig.HP_BAR = b);
    }

    public static void setShowNames(boolean b) {
        putB(Prefs.KEY_ESP_NAMES, EspConfig.SHOW_NAMES = b);
    }

    public static void setHpBarH(float v) {
        putF(Prefs.KEY_ESP_HP_BAR_H,
                EspConfig.HP_BAR_H_PX = clamp(v, HP_BAR_H_MIN, HP_BAR_H_MAX));
    }

    /**
     * Retarget the squad map pin at the ESP target.
     *
     * <p>Off by default and never defaulted on: this rewrites a packet that
     * other players act on, unlike every other knob here, which only changes
     * what we draw for ourselves.</p>
     */
    public static void setMarkRetarget(boolean b) {
        putB(Prefs.KEY_MARK_RETARGET, com.nepdon.wire.filter.MarkFilter.ENABLED = b);
    }

    /**
     * Scope zoom: 1x / 2x / 4x / 8x. Exact optics, no calibration needed —
     * see EspConfig.FOV_ZOOM. Not persisted as a "setting" so much as a
     * live state the user flips when they open a scope.
     */
    /**
     * The zoom used while scoped.
     *
     * <p>Range runs to 16x, past any shipped scope, because the number is a
     * projection correction tuned by eye rather than the scope's advertised
     * magnification -- and the binary says ADS does not change the camera FOV
     * at all, so the value that makes the boxes line up is an empirical fact
     * about this client, not a figure to look up.</p>
     */
    public static void setScopeZoom(float z) {
        putF(Prefs.KEY_ESP_SCOPE_ZOOM,
                EspConfig.SCOPED_ZOOM = clamp(z, 1f, 16f));
    }

    public static void setScopeBoxScale(float v) {
        putF(Prefs.KEY_ESP_SCOPE_BOX,
                EspConfig.SCOPE_BOX_SCALE = clamp(v, 0.05f, 4f));
    }

    public static void setScopeXExtra(float v) {
        putF(Prefs.KEY_ESP_SCOPE_XEX,
                EspConfig.SCOPE_X_EXTRA = clamp(v, 0.05f, 20f));
    }

    public static void setScopeSquare(boolean b) {
        putB(Prefs.KEY_ESP_SCOPE_SQ, EspConfig.SCOPE_SQUARE = b);
    }

    public static void setAimSteerTau(float v) {
        putF(Prefs.KEY_AIM_STEER_TAU, com.nepdon.wire.ui.aim.AimConfig.AIM_STEER_TAU_MS =
                clamp(v, com.nepdon.wire.ui.aim.AimConfig.STEER_TAU_MIN, com.nepdon.wire.ui.aim.AimConfig.STEER_TAU_MAX));
    }

    public static void setAimBoxFrac(float v) {
        putF(Prefs.KEY_AIM_BOX_FRAC, com.nepdon.wire.config.EspConfig.AIM_BOX_FRAC = clamp(v, 0f, 1f));
    }

    public static void setAimScopedGain(float v) {
        putF(Prefs.KEY_AIM_SCOPED_GAIN, com.nepdon.wire.ui.aim.AimConfig.AIM_SCOPED_GAIN =
                clamp(v, com.nepdon.wire.ui.aim.AimConfig.SCOPED_GAIN_MIN, com.nepdon.wire.ui.aim.AimConfig.SCOPED_GAIN_MAX));
    }

    public static void setAimTestTrack(boolean b) {
        putB(Prefs.KEY_AIM_TEST_TRACK, com.nepdon.wire.ui.aim.AimConfig.AIM_TEST_TRACK = b);
    }

    public static void setAimPostureGate(boolean b) {
        putB(Prefs.KEY_AIM_POSE_GATE, com.nepdon.wire.ui.aim.AimConfig.AIM_POSTURE_GATE = b);
    }

    public static void setAimBasisStale(float v) {
        putF(Prefs.KEY_AIM_BASIS_AGE, com.nepdon.wire.ui.aim.AimConfig.AIM_BASIS_STALE_MS =
                clamp(v, 200f, 5000f));
    }

    public static void setAimBodyH(float v) {
        putF(Prefs.KEY_AIM_BODY_H, com.nepdon.wire.ui.aim.AimConfig.AIM_H_STANDING_M =
                clamp(v, com.nepdon.wire.ui.aim.AimConfig.AIM_H_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_H_MAX));
    }

    public static void setAimCrouchRatio(float v) {
        putF(Prefs.KEY_AIM_CROUCH_R, com.nepdon.wire.ui.aim.AimConfig.AIM_CROUCH_RATIO = clamp(v, 0.30f, 1.0f));
    }

    public static void setAimAnchorFrac(float v) {
        putF(Prefs.KEY_AIM_ANCHOR, com.nepdon.wire.ui.aim.AimConfig.AIM_ANCHOR_FRAC =
                clamp(v, com.nepdon.wire.ui.aim.AimConfig.AIM_FRAC_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_FRAC_MAX));
    }

    public static void setAimPipelineMs(float v) {
        putF(Prefs.KEY_AIM_PIPELINE, com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MS =
                clamp(v, com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MIN, com.nepdon.wire.ui.aim.AimConfig.AIM_PIPELINE_MAX));
    }

    public static void setAimMaxSrcAge(float v) {
        putF(Prefs.KEY_AIM_SRC_AGE, com.nepdon.wire.ui.aim.AimConfig.AIM_MAX_SRC_AGE_MS = clamp(v, 60f, 1000f));
    }

    public static void setScopeBoom(float v) {
        putF(Prefs.KEY_ESP_SCOPE_BOOM,
                EspConfig.SCOPE_BOOM_M = clamp(v, 0f, 12f));
    }

    public static void setScopePivot(float v) {
        putF(Prefs.KEY_ESP_SCOPE_PIVOT,
                EspConfig.SCOPE_PIVOT_M = clamp(v, -1f, 4f));
    }

    public static void setScopeShoulder(float v) {
        putF(Prefs.KEY_ESP_SCOPE_SHLD,
                EspConfig.SCOPE_SHOULDER_M = clamp(v, -3f, 3f));
    }

    public static void setTargetAgeCarry(boolean b) {
        putB(Prefs.KEY_ESP_AGE_CARRY, EspConfig.TARGET_AGE_CARRY = b);
    }

    public static void setCamLeadAlways(boolean b) {
        putB(Prefs.KEY_ESP_CAM_LEAD_ALWAYS, EspConfig.CAM_LEAD_ALWAYS = b);
    }

    public static void setScopeOffX(float v) {
        putF(Prefs.KEY_ESP_SCOPE_OFF_X,
                EspConfig.SCOPE_OFF_X_PX = clamp(v, -1600f, 1600f));
    }

    public static void setScopeOffY(float v) {
        putF(Prefs.KEY_ESP_SCOPE_OFF_Y,
                EspConfig.SCOPE_OFF_Y_PX = clamp(v, -1600f, 1600f));
    }

    public static void setAutoScopeZoom(boolean b) {
        putB(Prefs.KEY_ESP_SCOPE_AUTO, EspConfig.AUTO_SCOPE_ZOOM = b);
    }

    public static void setZoom(float z) {
        putF(Prefs.KEY_ESP_ZOOM, EspConfig.FOV_ZOOM = clamp(z, 1f, 8f));
    }

    /** Cycles 1 -> 2 -> 4 -> 8 -> 1. */
    public static float cycleZoom() {
        float z = EspConfig.FOV_ZOOM;
        float next = (z < 1.5f) ? 2f : (z < 3f) ? 4f : (z < 6f) ? 8f : 1f;
        setZoom(next);
        return next;
    }

    public static void setShowPose(boolean b) {
        putB(Prefs.KEY_ESP_POSE, EspConfig.SHOW_POSE = b);
    }

    public static void setPitchScale(float v) {
        putF(Prefs.KEY_ESP_PITCH_LSB,
                EspEngine.PITCH_DEG_PER_LSB = clamp(v, 0.5f, 2.5f));
    }

    public static void setShowBoxes(boolean b) {
        putB(Prefs.KEY_ESP_BOXES, EspConfig.SHOW_BOXES = b);
    }

    public static void setShowRadar(boolean b) {
        putB(Prefs.KEY_ESP_RADAR, EspConfig.SHOW_RADAR = b);
    }

    /**
     * Whether the vehicle's heading stands in for the camera while mounted.
     *
     * <p>Defaults ON. There is no player look direction on the wire at all
     * while riding, so the alternative is not a better camera -- it is the
     * camera frozen at the instant you mounted.</p>
     */
    public static void setMountVehicleCamera(boolean b) {
        putB(Prefs.KEY_ESP_MOUNT_CAM,
                com.nepdon.wire.esp.EspEngine.MOUNT_VEHICLE_CAMERA = b);
    }

    public static boolean pitchNeg() {
        return EspConfig.PITCH_SIGN < 0f;
    }

    private static void putF(String k, float v) {
        if (sp != null) sp.edit().putFloat(k, v).apply();
    }

    private static void putB(String k, boolean v) {
        if (sp != null) sp.edit().putBoolean(k, v).apply();
    }

    private static void putI(String k, int v) {
        if (sp != null) sp.edit().putInt(k, v).apply();
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- server-sourced calibration DEFAULTS (anti-crack, migration prep) ------
    // The projection rig (FOV/boom/shoulder) came from the DECRYPTED il2cpp image;
    // the shipped libil2cpp.so has encrypted method bodies, so an APK-only attacker
    // cannot recompute them. When a live profile is armed these become the getFloat
    // DEFAULT (a value the user saved in the tune panel still wins); otherwise the
    // baked DEF_* — identical to today. DECODE_LEASH is a compile-time-false
    // constant, so R8 strips the server branch from release. Parity-guarded equal to
    // the baked DEF_* by tools/verify_decode_maps.py until the owner sets an offset.
    private static float calFov() {
        return (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.calibrationArmed())
                ? com.nepdon.wire.auth.ConfigStore.calFovXDeg() : DEF_FOV;
    }
    private static float calBoom() {
        return (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.calibrationArmed())
                ? com.nepdon.wire.auth.ConfigStore.calBoomM() : DEF_BOOM;
    }
    private static float calShoulder() {
        return (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.calibrationArmed())
                ? com.nepdon.wire.auth.ConfigStore.calShoulderM() : DEF_SHOULDER;
    }
}
