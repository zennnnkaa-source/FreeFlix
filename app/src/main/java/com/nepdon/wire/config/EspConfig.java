package com.nepdon.wire.config;

/**
 * Every persisted ESP/aim projection knob, in one place.
 *
 * <h3>Why these moved out of the renderer</h3>
 *
 * <p>They were 931 lines of {@code public static volatile} declared inside an
 * Android {@code View}, and <b>twelve files reached into that View purely to
 * read floats</b> -- {@link EspTuning} alone at 119 sites. Nothing about a
 * projection constant needs a {@code Canvas}, and having the settings live in a
 * UI class is what made the dependency graph read backwards.</p>
 *
 * <h3>Rules that still apply, unchanged</h3>
 *
 * <ul>
 *   <li><b>Every field stays {@code volatile}.</b> They are written on the
 *       settings/UI thread and read in {@code onDraw} on the render thread. A
 *       dropped {@code volatile} costs nothing at compile time and loses
 *       cross-thread visibility silently.</li>
 *   <li><b>A {@code Setting} owns no value</b> -- it binds a reference to the
 *       static that lives here, so {@code onDraw} keeps a plain field load and
 *       the panel keeps writing through. Moving a field without moving its
 *       {@code Refs} binding would leave a slider that moves and does nothing,
 *       while still persisting the wrong value.</li>
 *   <li><b>Never rename a {@code Prefs.KEY_*} string.</b> Nothing here touches
 *       one; the keys are unchanged and every device keeps its tuning.</li>
 *   <li>{@code onDraw} allocates nothing. A cross-class static read is the same
 *       {@code getstatic} bytecode as a same-class one -- no boxing, no
 *       dispatch, no cost.</li>
 * </ul>
 *
 * <h3>What deliberately did NOT move</h3>
 *
 * <p>The renderer's TELEMETRY -- {@code VP_*}, {@code LIVE_*}, {@code SEP_*},
 * {@code CAL_BOX_*}. Those are written BY the renderer and read by the setup
 * panel; they are outputs, not settings, and filing them under {@code config}
 * would be a lie the next reader pays for.</p>
 *
 * <p>The values below were moved verbatim, never retyped -- a mistyped
 * initialiser is the one failure in this refactor that the compiler cannot
 * catch.</p>
 */
public final class EspConfig {

    private EspConfig() { }

    /**
     * Horizontal field of view in degrees (FF Max default is roughly 90).
     *
     * <p>This value is NOT recoverable from the packet stream — the server
     * sends world positions, never the client's projection matrix — so it is
     * tuned by eye in game through {@link com.nepdon.wire.ui.panel.EspSetupPanel}
     * and persisted by {@link com.nepdon.wire.config.EspTuning}. Its signature:
     * the error is
     * ~zero at the crosshair and grows with angle from screen centre.</p>
     */
    public static volatile float FOV_X_DEG = com.nepdon.wire.config.EspTuning.DEF_FOV;
    /**
     * Vertical FOV trim. The vertical half-angle is derived from the
     * horizontal one assuming square pixels ({@code tan(fovY/2) =
     * tan(fovX/2)*h/w}); this multiplies that result. 1.0 = square pixels.
     * Needed when the game renders at a different aspect than the window it
     * is composited into (letterboxing, cutout insets, render-scale).
     */
    public static volatile float FOV_Y_SCALE = 1.0f;
    /**
     * Scope zoom multiplier. <b>1x hip-fire, 2x, 4x, 8x (AWM).</b>
     *
     * <p>Opening a scope narrows the real camera FOV, so every projected box
     * is wrong while scoped — and the error grows with angle from the
     * crosshair, which is why it looks fine when you are aimed straight at
     * someone and wildly wrong for anyone off to the side. At 4x a target
     * 10 degrees off-axis is drawn <b>half a screen</b> from where it belongs.</p>
     *
     * <p>The correction is exact and needs no calibration. The projection
     * scale is {@code K = 1/tan(FOV_X/2)}, and zoom multiplies K linearly, so
     * one multiplier covers every level: 2x, 4x and 8x correspond to 53.13,
     * 28.07 and 14.25 degrees from a 90 degree base. Only Red Dot is
     * uncertain, somewhere in 1.0-1.25x.</p>
     *
     * <p><b>This has to be set by hand.</b> Scope state was searched for
     * exhaustively and is NOT in the packet stream: the keepalive body is
     * fully accounted for (23 of 23 fields identified), the remote entity
     * record is structurally complete, every plaintext outbound family is a
     * fixed-cadence beacon rather than an event, and the outbound input
     * stream is two ECB blocks — a 256-cycle counter and one fully random
     * payload block (entropy 7.98/8) where the scope button actually lives.
     * A behavioural guess was measured and rejected: "slow and not turning"
     * is true of 43.7% of all playtime, so such a detector would quadruple
     * the projection scale during half of normal play.</p>
     */
    public static volatile float FOV_ZOOM = 1.0f;
    /**
     * Apply {@link #SCOPED_ZOOM} automatically while the scope is up.
     *
     * <p>On by default. The scope latch is a measured, reliable signal —
     * outbound Cmd 137/138, 242/242 paired across six captures — so there is no
     * reason to make the user press a button for something the stream already
     * says.</p>
     */
    public static volatile boolean AUTO_SCOPE_ZOOM = false;
    /**
     * Projection zoom to use while scoped. <b>Empirical — tune it by eye.</b>
     *
     * <p>It is NOT the scope's advertised magnification, and it is not
     * derivable. Enumerating all 85 callers of {@code Camera::set_fieldOfView}
     * in the binary found <b>no ADS path that changes the camera FOV at all</b>
     * ({@code docs/GAME_CLIENT_CONSTANTS.md}); {@code COW.ESightAmplifyRatio}
     * (X1/X2/X4/X8) never reaches a camera and the {@code X*ScopeMaxSetting}
     * vars are <i>sensitivity</i>. If magnification is visible it is most
     * likely an animated {@code fieldOfView} clip in asset data, which no
     * capture can recover. Every published scoped-FOV figure surveyed in
     * {@code plan/research/RESEARCH_FF_SCOPE_VALUES.md} is unsourced.</p>
     *
     * <p>So this is a knob, for the same reason {@link #FOV_X_DEG} is: put it in
     * front of the eyes that can see the error. Turn on AIM OVERLAY while
     * scoped — the cone is drawn through these exact tangents, so it grows as
     * the value narrows the field, and the dot marks the centre every angular
     * error is measured from.</p>
     *
     * <p>The tier (2x/4x/8x) is <b>not known</b> — the varId-12 probe in
     * {@code ScopeState} is an instrument, not a decoder, and the measurement
     * that would settle it has not been run. One value, tuned for the scope
     * actually used, is the honest design until it has been.</p>
     *
     * <h3>Why the default is 3.30, after two wrong turns</h3>
     *
     * <p>This shipped at 1.0, then at 8.0. Both were wrong, and the way they
     * were wrong is the point: <b>every previous value was inferred from where
     * a box SAT, and a box's position is the one thing that cannot be read
     * without first agreeing where the optic centre is.</b> A target 90 px from
     * centre swings the implied zoom from 7 to 21 on a ±50 px disagreement
     * about centre, so each attempt produced a confident number from an
     * unmeasurable premise.</p>
     *
     * <p><b>3.30 is measured, and from a quantity with no centre in it.</b> A
     * character's on-screen HEIGHT is a difference of two projected points:
     * the optic centre cancels, the nudge cancels, the handedness cancels.
     * Two independent readings from two 8x-scoped screenshots:</p>
     *
     * <ul>
     *   <li>a player at 25 m stood 224 device px tall,</li>
     *   <li>a different player at 32 m stood ~170 px (running, so slightly
     *       short, which biases the estimate DOWN).</li>
     * </ul>
     *
     * <p>For a 1.8 m character that is ~3100 px per radian, against 935.3 at
     * the unscoped 60° vertical FOV. Both land on a scoped vertical field of
     * <b>~19-20°</b>, i.e. a magnification of about <b>3.3x</b> — not 8x,
     * despite the HUD reading 8X. The advertised tier is not the on-screen
     * magnification, and nothing in the binary ever said it was:
     * {@code COW.ESightAmplifyRatio} (X1/X2/X4/X8) never reaches a camera, and
     * the {@code X*ScopeMaxSetting} vars are SENSITIVITY. "8X" is a string on a
     * sight item wired to a sensitivity table. It was never a claim about the
     * projection, so the mismatch needs no explaining away.</p>
     *
     * <h3>CONFIRMED IN THE BINARY, 2026-09-20</h3>
     *
     * <p>The game computes its scoped FOV as a single scalar divide, and the
     * instruction is readable: {@code COW.GamePlay.FDAEPHMIEPC::LKHKGOFALIB}
     * @ 0x074e208c ends with</p>
     *
     * <pre>
     *   LDR  S12,[X8,#0x20]    ; C_FOV_NORMAL = 60.0, FollowCamera STATICS
     *   LDR  S10,[X23+0xC0 -> +0x3C] ; sight_fov_amplify_ratio -- an INSTANCE
     *                          ; field of the attachment data, NOT a static;
     *                          ; 1.0 @0x74e27d4 when no sight is fitted
     *   FDIV S9, S12, S10      ; @0x74e2bc4  ->  effective VERTICAL FOV
     * </pre>
     *
     * <p>{@code [X8,#0x20]} is the same static slot
     * {@code FollowCamera::GetFovNormal} returns. The divisor is NOT a sibling
     * static -- {@code FollowCamera} static +0x3c is an unrelated 0.5 lerp
     * rate. So <b>the scoped field is
     * {@code 60 / ratio} applied to the WHOLE FRAME</b>. The measurement above
     * implies a ratio of 2.85-3.05; at exactly <b>3.0</b> the vertical field is
     * <b>20.00°</b> and the tangent zoom is {@code tan(30°)/tan(10°) = 3.2744}.
     * Measurement and binary agree to three decimals, from completely
     * independent directions.</p>
     *
     * <p><b>The tier and the FOV divisor are different fields.</b>
     * {@code sight_amplify_ratio} is the int that prints "8X" and picks a
     * sensitivity; {@code sight_fov_amplify_ratio} is the float at
     * sightProperty+0x3C that divides the FOV. Nothing requires them to match,
     * and here they do not: 8 and 3.</p>
     *
     * <p>Neither is in the .so — they are CSV columns of the attachment table,
     * shipped as game data. So the per-tier numbers still have to be measured,
     * one tier at a time: {@code ratio = 30 / atan(tan(30°)/M)}.</p>
     *
     * <p>Ruled out on the way: 8x in ANGLE rather than tangent would give
     * 60/8 = 7.5° vertical, a tangent zoom of 8.8 — further from the
     * measurement, not closer.</p>
     *
     * <p><b>This also explains the symptom exactly.</b> A uniformly wrong zoom
     * draws a target at {@code k x} its true offset from the optic axis, so the
     * error is {@code (k-1) x offset}: identically zero at the crosshair and
     * growing linearly away from it. "Fine when I look straight at it,
     * terrible slightly left or right" is that equation, not a basis fault.
     * At 8 against a true 3.3 the error reaches ~1000 px by the screen edge.</p>
     *
     * <p>To re-measure, the best reading is {@code sep=}: with two targets both
     * OUTSIDE the glass, {@code trueZoom = SCOPED_ZOOM * G / S}, where G is the
     * pixel gap between the two bodies and S is the printed {@code sep=}. Both
     * are differences of two screen positions, so the optic centre, the
     * character height, the range and {@code BOX_H_M} all cancel. It is the
     * only reading here with no assumptions in it at all.</p>
     *
     * <p>{@code box=NNNpx@NNm} is the easier one-target version —
     * {@code trueZoom = SCOPED_ZOOM * charPx / boxPx} — but it <b>inherits our
     * range error one for one</b>, because the box height is computed FROM our
     * range. It measures {@code zoom * ourRange / trueRange}, not the zoom.
     * That is not hypothetical: the 2026-09-20 frame read 363 px, implying
     * 37.1 m, against a true ~32.9 m, and the resulting 3.75 is high by exactly
     * that 13%. Use it for a sanity check; use {@code sep=} for a number.</p>
     *
     * <h3>There is no magnified inset. The scope ring is a vignette.</h3>
     *
     * <p>The picture-in-picture reading — a second camera rendering the glass
     * into a texture over a normal frame — was the live hypothesis for two
     * sessions and is <b>refuted</b>. Every direct caller of every Unity API
     * that could produce one was enumerated in {@code libil2cpp_dec.so}:
     * {@code set_fieldOfView} (58 callers), {@code set_targetTexture} (56),
     * {@code set_rect} (6), {@code set_projectionMatrix} (3),
     * {@code set_aspect} (6). <b>Not one is a sighting path</b> — they are
     * glide, vehicle, skydive, grapple, cannon, catapult, reflections, minimap
     * and UI preview. {@code Player::GetSightCameraRot} resolves through
     * {@code get_MyFollowCamera} to the SAME camera and only touches rotation.
     * The screenshots agree: world edges run continuously across the glass.</p>
     *
     * <p>So the 316 px aperture is a UI sprite over an already-magnified
     * full-screen render. <b>Do not clip to it.</b> A box outside the ring is
     * legitimately visible and belongs where the projection puts it.</p>
     */
    public static volatile float SCOPED_ZOOM = 3.27f;

    /**
     * Where on the drawn box the aim points, as a fraction of box height.
     *
     * <p>0 is the feet, 1 the box top. 0.55 is upper chest — just below the
     * midpoint, because the box top sits a little above the actual head.</p>
     *
     * <p>This is the ONLY thing standing between the aim point and the box
     * outline, on purpose. The aim projects the same {@code ex}, {@code ez},
     * {@code groundY} and {@code boxHUnits} the box is drawn from, so whatever
     * error the box has, the aim has identically — they cannot drift apart, and
     * any offset you can see is a box-calibration question, not an aim one.</p>
     */
    public static volatile float AIM_BOX_FRAC = 0.55f;
    /**
     * Camera boom while SCOPED, metres. Replaces {@link #TPS_BOOM_M} for the
     * duration of the scope and nothing else.
     *
     * <h3>Why 0, when the binary says 2.50</h3>
     *
     * <p>{@code FollowCamera::SetOffestRightAndBack} @ 0x07222344 has no
     * sighting branch, so the THIRD-PERSON rig keeps its 2.50 m boom while
     * scoped. That reading is correct and beside the point: scoped Free Fire is
     * <b>first person</b>. The screenshots show no character body and the optic
     * filling the frame — the view is at the weapon, so the follow camera whose
     * offsets that function sets is not the camera doing the rendering.</p>
     *
     * <h3>The symptom this fixes, and why it is unambiguous</h3>
     *
     * <p>A camera modelled {@code B} metres too far BACK computes a bearing of
     * {@code atan(D sin t / (D cos t + B))} instead of {@code t} — an error of
     * about {@code t x B/(D+B)}. Three properties, all of them observed:</p>
     *
     * <ul>
     *   <li><b>Exactly zero on-axis, at every range.</b> Not approximately:
     *       {@code t = 0} kills it identically. The user reports the crosshair
     *       is exact at all distances, which no scale error can give you for
     *       free and which a shoulder error would break.</li>
     *   <li><b>Pulls boxes TOWARD the crosshair</b>, growing with angle. Pan
     *       right and targets sweep left while our boxes lag right of them;
     *       pan left and they sit left. Both reported.</li>
     *   <li><b>Bounded</b>, because the scoped screen only spans ±20.6°.</li>
     * </ul>
     *
     * <p>Magnitude, with no free parameter: at B = 2.5 m, D = 32 m, t = 13°
     * the error is 0.95° = 51 px at 53.4 px/deg — <b>about one box width</b>,
     * which is what was reported. That the number falls out of the geometry
     * rather than being fitted is the reason this is the default and not a
     * switch.</p>
     *
     * <p><b>The shoulder is deliberately NOT zeroed.</b> A lateral offset is
     * visible AT the crosshair (roughly {@code atan(S/D)}, 0.72° = 38 px at
     * 32 m), and the crosshair is exact — so whatever lateral offset we carry
     * is already right, and zeroing it would break the one thing that works.
     * See {@link #SCOPE_SHOULDER_M}.</p>
     *
     * <h3>Why the default is 2.50 anyway — the side effect I missed</h3>
     *
     * <p>Shortening the boom does not only change the bearing. It changes the
     * DEPTH, from {@code D + 2.5} to {@code D}, so every box gets taller by
     * {@code (D+2.5)/D}: <b>+25% at 10 m, +7.8% at 32 m, +2.5% at 100 m</b>.
     * The user had just confirmed that box sizes were correct, and a fix that
     * silently inflates them by a quarter at close range is not a fix.</p>
     *
     * <p>So this ships at the measured 2.50 and the hypothesis is left as a
     * knob with a one-screenshot falsifier: <b>put a target at 8-12 m and set
     * this to 0.</b> If the box is now visibly too tall, the boom was right all
     * along. If it fits better AND the off-axis offset goes away, it was
     * wrong.</p>
     *
     * <p>That size coupling is also what makes this separable from
     * {@link #SCOPED_ZOOM} at all: a zoom error scales boxes by the same factor
     * at EVERY range, a boom error scales them by {@code (D+b)/D}, which is 25%
     * at 10 m and 2.5% at 100 m. <b>Neither can be identified at 25-40 m</b>,
     * where the two predictions differ by under 2% — which is where every
     * previous measurement was taken, and why this keeps resurfacing.</p>
     */
    public static volatile float SCOPE_BOOM_M = 2.50f;
    /**
     * Camera shoulder offset while SCOPED, metres. Defaults to the hip-fire
     * {@link #TPS_SHOULDER_M}, i.e. changes nothing.
     *
     * <p><b>MEASURED 0 from the binary, 2026-09-20.</b> While sighting,
     * {@code FollowCamera::AHCBLOCABDP} @ 0x0722519c jumps <i>past</i> the
     * entire shoulder/height block (0x072256ac-0x072259e0) to the AIM branch at
     * 0x072255d0, so the right and up offsets are never applied and their
     * smoothed copies at this+0x8c / this+0x90 are not even updated.
     * Corroborated by {@code GBAEBHHKPMI +0xf8} @ 0x07219dec, which forces the
     * right-offset term to 0 on the same condition.</p>
     *
     * <p>Worth 0.72° of lateral bias at 32 m (38 px) and 2.3° at 10 m (122 px),
     * <b>constant across the screen and present AT the crosshair</b> — so this
     * is one of the two corrections that should tighten the centre rather than
     * the edges. It shrinks with range, which is what distinguishes it from
     * {@link #SCOPE_OFF_X_PX}: a fixed pixel nudge would fix one distance and
     * break every other.</p>
     */
    public static volatile float SCOPE_SHOULDER_M = 0f;
    /**
     * Camera pivot height while SCOPED, metres above the entity origin.
     *
     * <p><b>MEASURED 1.28 from the binary, 2026-09-20.</b> ADS does not adjust
     * the third-person pivot — it <i>replaces</i> it.
     * {@code Player::get_AimStartPostion} @ 0x070e2bb0 returns
     * {@code Entity.Position + Vector3.up * k}, and k comes from a posture
     * table:</p>
     *
     * <ul>
     *   <li><b>standing / running: 1.28</b> — {@code GameVarDef[0x1f74]},
     *       {@code 0x3fa3d70a} @ 0x06e1c278, read at 0x070e2e20</li>
     *   <li>crouching: 0.77 — hardcoded @ 0x18ba3b4, read at 0x070e2c84</li>
     *   <li>swim-surface / dash: 2.30 — {@code GameVarDef[0x770]}</li>
     *   <li>creep: a bone position plus 0.0</li>
     * </ul>
     *
     * <p>Hip fire uses a different pivot entirely — the character capsule top,
     * roughly 1.7-1.8 m — so the scope drops the eye by about 0.4 m. We model
     * only the standing case; <b>crouch and prone are not handled</b>, and the
     * posture enum needed to do it is already on the wire. That is the next
     * refinement, not a reason to keep 1.6.</p>
     *
     * <p>A pivot error is a pure VERTICAL translation: 0.32 m of error is 0.57°
     * = 30 px at 32 m, present at the crosshair and shrinking with range.
     * <b>Vertical has never once been checked in this project</b> — every
     * calibration target so far sat near the horizon, where a vertical bias is
     * hardest to see.</p>
     *
     * <p>These are {@code GameVarDef} .cctor defaults and the server can
     * override them, so treat 1.28 as the shipped value, not a guarantee.</p>
     */
    public static volatile float SCOPE_PIVOT_M = 1.28f;
    /**
     * Extra multiplier on BOX SIZE while scoped, on top of {@link #SCOPED_ZOOM}.
     *
     * <p>Exists to separate two things one knob cannot. A box's height is the
     * gap between two projected points, so it already carries the zoom — at 8x
     * it is 8x taller. That is only right if the scope magnifies the whole
     * camera.</p>
     *
     * <p><b>The measurement:</b> set {@code SCOPED_ZOOM} by where the box
     * <i>sits</i>, then set this by how <i>big</i> it is. If the two agree, the
     * scope is a camera zoom and the model is right. If a box can be put in the
     * right place only at one zoom and given the right size only at another,
     * the scope is NOT a camera FOV change — which is what the binary already
     * says (no ADS path touches {@code Camera::set_fieldOfView}), and the
     * difference between the two numbers is the first real evidence of what it
     * actually is.</p>
     */
    public static volatile float SCOPE_BOX_SCALE = 1.0f;
    /**
     * Constant screen-space nudge, px, applied only while scoped.
     *
     * <p><b>CORRECTED 2026-09-20. This is a pure TRANSLATION, and the previous
     * javadoc was wrong about it in a way that would mislead tuning.</b> It
     * claimed the nudge "grows with the zoom", on the reasoning that a zoom
     * magnifies every offset from the centre it expands about. That holds when
     * you scale an already-rasterised image. We do not: {@code scratchX = cx +
     * (a term that does not contain cx)}, so adding to {@code cx} moves every
     * point by exactly that many pixels, at any zoom and any angle. It is
     * algebraically identical to {@link #OFFSET_X_PX} gated on the scope.</p>
     *
     * <p>Which makes it <b>useless against the error it was built for.</b> A
     * residual that is zero at the crosshair and grows off-axis is a SCALE
     * error; a translation shifts the crosshair too. Setting this to cancel an
     * off-axis error necessarily breaks the centre — and the centre is the one
     * place the projection is currently exact. Reach for {@link #SCOPED_ZOOM}
     * or {@link #SCOPE_BOOM_M} instead, and leave this at 0 unless the WHOLE
     * picture, crosshair included, is uniformly displaced.</p>
     *
     * <p>The measured aperture centre sits on the exact screen centre anyway,
     * so there is no known reason for it to be non-zero.</p>
     */
    public static volatile float SCOPE_OFF_X_PX = 0f;
    public static volatile float SCOPE_OFF_Y_PX = 0f;
    /**
     * Extra HORIZONTAL-only zoom while scoped, on top of {@link #SCOPED_ZOOM}.
     *
     * <p>Exists because one zoom cannot fix both axes if the scoped view is not
     * HOR+. Measured on device at {@code SCOPED_ZOOM = 1}: a 32 m target the
     * game draws ~900 px right of centre was drawn by us ~90 px right of
     * centre — about <b>10x too compressed horizontally</b> — while vertical
     * placement looked roughly right. One shared factor cannot produce that.</p>
     *
     * <p><b>Procedure:</b> set {@code SCOPED_ZOOM} until a box sits at the right
     * HEIGHT, then set this until it sits at the right SIDEWAYS position. If it
     * converges on 1.0, the scoped view is HOR+ after all and the cause is
     * elsewhere. If it converges near the screen aspect (~2.17 here), the optic
     * is isotropic and {@link #SCOPE_SQUARE} is the real fix.</p>
     */
    public static volatile float SCOPE_X_EXTRA = 1.0f;
    /**
     * While scoped, treat the optic as square rather than applying the screen
     * aspect to the horizontal tangent.
     *
     * <p>The HOR+ model says a wider screen shows more horizontally at the same
     * vertical FOV. That is how the game's own camera behaves unscoped — it is
     * what fixed the far-target right-drift. But a circular optic has no reason
     * to obey it: it magnifies about its own axis equally in both directions,
     * and the screen aspect is then a property of the frame around it, not of
     * the magnified image. This is the same hypothesis as
     * {@link #SCOPE_X_EXTRA} = aspect, expressed as a switch rather than a
     * number, so it can be tested in one tap.</p>
     */
    public static volatile boolean SCOPE_SQUARE = false;
    /**
     * Constant screen-space trim in pixels, applied after projection. The
     * escape hatch for a principal point that is not the window centre —
     * display cutouts and notch insets shift the game's viewport inside our
     * full-screen overlay, and that shows up as a fixed offset no FOV value
     * can remove.
     */
    public static volatile float OFFSET_X_PX = 0f;
    /** Constant vertical screen-space trim in pixels (see {@link #OFFSET_X_PX}). */
    public static volatile float OFFSET_Y_PX = 0f;
    /**
     * Manual camera pitch override in radians, applied ONLY when the
     * decoded direction vector is unavailable (Folded fallback). 0 =
     * horizontal view.
     */
    public static volatile double PITCH_FALLBACK_RAD = 0.0;
    /**
     * Enemy axis mapping (identity expected per the varint finding; keep
     * configurable until verified against in-game ground truth):
     * false = engine Track.x maps to the own posX axis, Track.y to posY;
     * true  = the two horizontal axes are swapped.
     */
    public static volatile boolean ENEMY_AXIS_SWAP = false;
    /** Negate the enemy Track.x axis before mapping (identity default). */
    public static volatile boolean ENEMY_NEG_AXIS1 = false;
    /** Negate the enemy Track.y axis before mapping (identity default). */
    public static volatile boolean ENEMY_NEG_AXIS2 = false;
    /**
     * Mirror the projected screen X axis.
     *
     * <p><b>VERIFIED true in game, 2026-09-10.</b> This is the handedness of
     * the view basis and it was the actual cause of the long-running "boxes
     * render crap" problem — with it set, the boxes track the right targets
     * along the right path. It was never determinable from captures (the
     * eight-way scoring table under {@link #VIEW_MODE} is the record of that
     * dead end); one toggle in the in-game panel answered it in seconds.</p>
     *
     * <p>Equivalent to negating the {@code right} basis vector, since
     * {@code right} feeds only the screen-X term. Kept as a flag rather than
     * folded into the basis so an existing install's saved preference does not
     * double-flip.</p>
     */
    public static volatile boolean MIRROR_X = true;
    /**
     * Third-person camera boom length, metres behind the character.
     *
     * <p>Free Fire is THIRD person: the camera is not at the player's
     * position, it orbits on a boom arm behind and above the character. We
     * were projecting from the character's own coordinates, so as the view
     * rotated the true camera swung around a ~3 m circle that our maths did
     * not model — which is exactly the reported symptom, "the boxes move
     * when I move my third-person view". The parallax error equals the boom
     * length, so at a 10 m engagement it throws a target off by up to
     * ~17 deg, and it grows as the target gets closer.</p>
     *
     * <p>camera = player + up*{@link #TPS_PIVOT_H_M} - forward*TPS_BOOM_M</p>
     */
    public static volatile float TPS_BOOM_M = com.nepdon.wire.config.EspTuning.DEF_BOOM;
    /** Height of the boom pivot above the character's origin, metres. */
    public static volatile float TPS_PIVOT_H_M = 1.6f;
    /**
     * Lateral ("over the shoulder") camera offset, metres along the right
     * vector. Third-person shooters rarely centre the camera on the
     * character's spine — it sits off to one side so the body does not block
     * the crosshair. That lateral offset is part of the camera position and
     * omitting it shows up exactly as a constant-looking horizontal bias that
     * gets worse as the target gets closer.
     *
     * <p>Signed: pick whichever sign reduces the error, since which way the
     * shoulder offset points depends on the same handedness convention as
     * {@link #MIRROR_X}.</p>
     *
     * <p>Distinguish it from {@link #OFFSET_X_PX}: a true pixel offset is
     * constant at every distance, while a shoulder offset shrinks as the
     * target gets further away. If the bias vanishes at long range, it is
     * this; if it does not, it is the pixel trim.</p>
     *
     * <p><b>MEASURED -0.70 m in game, 2026-09-10.</b> Corroborated visually:
     * the player's own character renders left of screen centre, which is the
     * camera itself telling you it sits to the character's right. Modelling
     * it at zero made every projected point land ~0.7 m too far right, which
     * is a whole body width at close range.</p>
     */
    public static volatile float TPS_SHOULDER_M = com.nepdon.wire.config.EspTuning.DEF_SHOULDER;
    /**
     * Dead reckoning: carry each entity's position forward by its measured
     * velocity instead of drawing where it was when the last packet arrived.
     *
     * <p>The server streams positions only a few times a second while we draw
     * at display refresh, so without this the box always sits at a stale
     * position — visibly behind a running target, and snapping forward on
     * each update. That is the "sometimes ahead, sometimes behind" symptom.
     * Switch off to see the raw packet positions.</p>
     */
    public static volatile boolean EXTRAPOLATE = false;
    /**
     * Extra lead in ms added on top of the record's own age, covering the
     * latency between the server sampling a position and us seeing it.
     *
     * <p><b>MEASURED 215 ms</b>, as the midpoint of two independent methods
     * that agreed to within 30 ms:</p>
     * <ul>
     *   <li><b>230 ms</b> - cross-correlating the server's echo of our own
     *       player against our own keepalive and sweeping the lag for minimum
     *       disagreement. Per session: 200/205/225/230/260/260 ms, residual
     *       at the optimum 0-6 cm.</li>
     *   <li><b>200 ms</b> - minimising end-to-end prediction error on the
     *       same echo over 56 303 samples.</li>
     * </ul>
     *
     * <p>The optimum is SHARP, not flat: half-width 15-35 ms, and while a
     * target is moving, being 50 ms off costs 0.20-0.31 m and 100 ms off
     * costs 0.38-0.54 m. 180-250 ms is within noise; outside it error grows
     * about 4 units per ms.</p>
     */
    public static volatile float LEAD_MS = 0f;
    /**
     * Measure the lead from the live stream instead of using {@link #LEAD_MS}.
     *
     * <p>The staleness we are compensating is dominated by the round trip to
     * the server, so it is a property of the CONNECTION, not of the game:
     * roughly {@code ping + 99 ms} (the average wait for the 198 ms broadcast
     * tick). It differs between matches and drifts within one. A fixed
     * constant can only ever be right for the connection it was measured on —
     * the shipped 215 ms came from sessions whose round trip was about
     * 130 ms, and per-session values ranged 200-260 ms.</p>
     *
     * <p>{@link com.nepdon.wire.esp.EspEngine#measuredLagMs()} measures
     * it live off the server's echo of our own player. When it has enough
     * samples it overrides LEAD_MS; until then, and whenever we have been
     * standing still too long to measure, LEAD_MS is the fallback.</p>
     */
    public static volatile boolean AUTO_LEAD = false;
    /**
     * Render-side position smoothing time constant, ms. <b>This is what makes
     * the boxes stop twitching.</b>
     *
     * <p>Prediction alone produces a sawtooth: between packets the box glides
     * along the predicted velocity, then every 198 ms a fresh measurement
     * lands and corrects whatever error accumulated, so the box jumps. The
     * result reads as "sometimes ahead, sometimes behind, never stable" — and
     * crucially <b>no value of LEAD removes it</b>, because it is a
     * discontinuity in time, not a constant offset. Chasing it with the lead
     * knob is chasing the wrong variable.</p>
     *
     * <p>So the rendered position follows the predicted position
     * exponentially rather than snapping to it. Smoothing the RENDERED
     * position is specifically right, and smoothing the velocity is
     * specifically wrong — velocity smoothing was measured and it made
     * prediction worse at every setting.</p>
     *
     * <p>0 disables it. Larger is calmer but laggier; it trades a little of
     * the lead back for stability, which is why it pairs with AUTO_LEAD.</p>
     */
    public static volatile float SMOOTH_MS = 0f;
    /**
     * Absurdity guard on predicted displacement, in metres - NOT a tuning
     * knob. Capping the horizon was measured and every cap made things
     * worse: no cap 1044 RMS, 400 ms cap 1086, 250 ms cap 1201. Staleness is
     * handled by fading confidence with age (see {@link #EXTRAP_FADE_MS} and
     * {@link #EXTRAP_STOP_MS}), which is what the data actually supports;
     * this only stops a corrupt velocity throwing a box across the map.
     */
    public static volatile float EXTRAP_MAX_M = 0.0f;
    /**
     * Camera-forward angular extrapolation, in ms of lead. <b>This is what
     * stops every box drifting together when you turn the view.</b>
     *
     * <p>Entity positions are already led forward in time, but the camera
     * forward came straight off the last ~10 Hz keepalive with no lead of its
     * own. During a turn the whole projection basis therefore trails the real
     * camera by up to a full ~100 ms sample, so every box slides uniformly in
     * the turn direction and snaps back on the next keepalive - and an edge
     * target can briefly fall behind the near plane and vanish. The engine
     * measures the forward's angular velocity between the two most recent
     * samples ({@link com.nepdon.wire.esp.EspEngine#extrapolatedDir}); this
     * value is the extra lead added on top of the catch-up to the present frame.</p>
     *
     * <p>Calibrate by eye: turn the camera and raise this until the boxes track
     * the targets through the turn instead of lagging, without overshooting when
     * you stop. <b>0 disables it entirely</b> - the basis is then the raw packet
     * forward, byte-for-byte the previous behaviour, so on-axis alignment is
     * never affected.</p>
     */
    public static volatile float CAM_EXTRAP_MS = 0f;
    /**
     * Run the camera lead even when {@link #EXTRAPOLATE} (target prediction)
     * is off. Off by default: this reverses a deliberate coupling, so it gets
     * its own switch rather than a silent revert.
     *
     * <p>The two are not the same correction. Target prediction guesses where a
     * PLAYER will be and overshoots when they stop. Camera lead corrects the
     * staleness of YOUR OWN view angle, which arrives on the ~99 ms outbound
     * tick — a rigid rotation of the whole basis, with nothing to overshoot.
     * Coupling them meant that turning target prediction off, which the owner
     * did deliberately on 2026-09-17, also turned this off.</p>
     *
     * <p><b>Its signature is dynamic:</b> every box slides together in the
     * direction you are turning, and settles the moment you stop. That is what
     * distinguishes it from the rig and scale errors, which do not move when
     * you hold still. A scope multiplies it by the zoom.</p>
     */
    public static volatile boolean CAM_LEAD_ALWAYS = true; // A: always lead camera, never entity
    /**
     * Carry each target forward by its own record AGE, with target prediction
     * ({@link #EXTRAPOLATE}) still off. Off by default -- this changes where
     * every box sits, and the owner's 2026-09-17 tuning was done without it.
     *
     * <p><b>It is not prediction.</b> A record arrives stamped with a time and
     * a velocity; carrying it forward by {@code now - updateMs} places it where
     * the sender said it would be at this instant. Guessing beyond that is what
     * {@code EXTRAPOLATE} does, and only that guess can overshoot when a target
     * stops.</p>
     *
     * <p>With both off, a box is pinned to the last packet for a whole 198 ms
     * broadcast and then jumps: at 6 m/s that is a <b>0 to 1.19 m sawtooth at
     * 5 Hz</b>, dead-on the instant a packet lands and two body widths behind
     * just before the next. Turning this on should remove the snap while
     * leaving the box ON the character rather than ahead of it.</p>
     *
     * <p>If boxes now sit AHEAD of a sprinter, the game itself renders a tick
     * behind and the right basis is {@code age - 198 ms}. That single
     * observation settles which of three render models the game uses, and it
     * cannot be run while {@link #EXTRAP_MAX_M} is zeroing the result.</p>
     */
    public static volatile boolean TARGET_AGE_CARRY = false;
    /**
     * Safety backstop on the camera-forward extrapolation angle, degrees. The
     * lead is bounded in TIME (EspEngine.CAM_MAX_AHEAD_MS), so this only guards
     * rotations the engine should not extrapolate at all. At 180 it cannot bind
     * on any rotation the engine accepts: the camera-cut limit is 120 deg per
     * keepalive, and a 150 ms lead at that rate is 180 deg. At the old 30 deg it
     * engaged above roughly 200 deg/s and the basis snapped forward on every
     * keepalive. (A single-sample rotation larger than a turn - a camera cut /
     * respawn - is rejected outright in the engine.)
     */
    public static volatile float CAM_EXTRAP_MAX_DEG = 180f;
    /** Draw the world-space enemy boxes. */
    public static volatile boolean SHOW_BOXES = true;
    /**
     * Draw a facing/aim indicator on each entity.
     *
     * <p>The server sends three pose bytes per remote player: body yaw, aim
     * yaw and aim pitch. Actual bone positions are NOT on the wire and cannot
     * be — the whole remote record is 20-21 bytes, where the cheapest real
     * skeleton would need four times that — so this draws the pose that does
     * exist rather than a fake skeleton.</p>
     *
     * <p>Body yaw and aim yaw are independent: they diverge by more than 90
     * degrees in about 12% of samples, so the torso/head twist is real and
     * worth seeing. The aim ray is accurate enough to read intent — at the
     * moment someone fires, their decoded aim points within 5 degrees of
     * their victim 60% of the time and within 10 degrees 74%.</p>
     */
    public static volatile boolean SHOW_POSE = false;
    /** Length of the drawn aim ray, metres. */
    public static volatile float AIM_RAY_M = 6f;
    // ---- aim acquisition tunables (see ui/aim/AimTarget) ----
    /** Acquisition cone, |yaw|+|pitch| degrees from the crosshair. */
    public static volatile float AIM_CONE_DEG = 10f;
    /** Max engagement distance, metres. */
    public static volatile float AIM_MAX_RANGE_M = 200f;
    /**
     * How long a target must have been stationary to be eligible, ms.
     * The thesis is still-targets-only: dead reckoning has measured RMS error
     * 0.573/0.622/0.679 over one/two/three ticks and the swipe itself takes
     * 100-200 ms, so a runner outruns the correction. 600 ms is a starting
     * value to be measured against the capture corpus, not a derived constant.
     */
    public static volatile long AIM_STILL_MS = 600L;
    /** Draw the scope reticle dot and the acquisition cone. */
    public static volatile boolean SHOW_AIM_EDITOR = false;
    /**
     * Acquire ONLY while the player is scoped.
     *
     * <p>The aim exists for scoped shots. Hip-fire has the game's own assist
     * friction fighting the last pixels of every correction, and a hip-fire
     * crosshair is wide enough that the correction is usually smaller than the
     * spread -- so a swipe there costs more than it buys. Scoped, the reticle is
     * a point and the correction is the whole shot.</p>
     *
     * <p>It is a setting, not a constant, because the scope latch is inferred
     * from outbound Cmd 137/138 and those have already been measured NOT always
     * pairing (one live test produced a double START 0.51 s apart). If the latch
     * ever sticks closed the aim would simply never fire, which is
     * indistinguishable from broken -- so the state is printed in the AIM SETUP
     * panel and this gate can be turned off.</p>
     */
    public static volatile boolean AIM_SCOPE_ONLY = true;
    /** Draw the player's nickname above each box (from the match roster). */
    public static volatile boolean SHOW_NAMES = true;
    /** Draw a health bar under each box (real Cmd 1900 health only). */
    public static volatile boolean HP_BAR = true;
    /** Health bar height in px before density scaling. */
    public static volatile float HP_BAR_H_PX = 4f;
    /**
     * Enemy ESP box colour as a packed ARGB int.
     *
     * <p>Settable from the Overlay tab colour picker. The stroke uses this
     * value directly; the fill is derived at render time as the same RGB
     * components at alpha 26 (10%), so the two always track together without
     * a second pref key.</p>
     *
     * <p>Default is the original hardcoded red: {@code argb(235,255,64,64)}.</p>
     */
    public static volatile int BOX_COLOR_ARGB = android.graphics.Color.argb(235, 255, 64, 64);

    /**
     * When true the renderer draws a line from the screen's bottom-centre
     * to the projected feet of every tracked enemy, using the same colour
     * as the box stroke (BOX_COLOR_ARGB).
     */
    public static volatile boolean SHOW_ESP_LINE = false;

    /** Show the radar-lite square. */
    public static volatile boolean SHOW_RADAR = true;
    /** Draw teammates too (in a separate colour). Off = enemies only. */
    public static volatile boolean SHOW_TEAM = false;
    /**
     * Draw entities the classifier has not committed on, in a NEUTRAL colour.
     *
     * <p>On by default now that the classifier no longer guesses. Proximity is
     * allowed to promote an entity to TEAM but never to conclude ENEMY, so
     * whatever stays undecided is genuinely unknown rather than mislabelled —
     * and it is <b>64.1% enemies</b> in the measured corpus, so a neutral box
     * is still usually a box on a foe. You lose the colour, not the target.
     * Letting proximity call ENEMY instead would raise coverage from 92.9% to
     * 97.4% at the cost of 1 217 friends painted as enemies, which is the
     * worse trade for an ESP.</p>
     */
    public static volatile boolean SHOW_UNCLASSIFIED = true;
    /**
     * Horizontal view-axis mapping, 0-7. The decoded direction vector's
     * relation to the position axes is NOT settled: scoring "fraction of
     * nearby enemies within 30 deg of the view axis" (random = 0.167) over
     * two full matches gave
     *
     * <pre>
     *   mapping            150701   155351
     *   (dx, dz)            0.189    0.225
     *   (dx,-dz)  &lt;-- best  0.528    0.265
     *   (-dx, dz)           0.197    0.236
     *   (-dx,-dz)           0.520    0.233
     *   (dz, dx) family     0.036-0.083  0.089-0.208
     * </pre>
     *
     * {@code (dx,-dz)} wins on both, but only 1.6x above chance on the
     * cleaner capture — not enough to hard-code. Mode 1 is that default;
     * {@link #VIEW_AUTOCYCLE} steps through all eight so the correct one
     * can be identified visually in-game, then locked.
     *
     * <p>Mode = swap&lt;&lt;2 | negX&lt;&lt;1 | negZ, where swap exchanges the two
     * horizontal components.</p>
     */
    public static volatile int VIEW_MODE = 0;
    /** Cycle {@link #VIEW_MODE} every {@link #VIEW_CYCLE_MS} for calibration. */
    public static volatile boolean VIEW_AUTOCYCLE = false;
    /** Dwell time per mode while auto-cycling. */
    public static volatile long VIEW_CYCLE_MS = 5000L;
    /** Sign applied to the vertical component before asin() for pitch. */
    public static volatile float PITCH_SIGN = +1f;
    /**
     * Radar range in metres. Not a hard limit any more: the radar now
     * AUTO-SCALES to hold every tracked entity, between these bounds. A fixed
     * 100 m was fine for Clash Squad but useless in the big survival maps,
     * where squads sit kilometres apart — and it also clipped everything
     * during the plane drop, when the whole lobby is far away in one
     * direction. The chosen range is shown on the radar so the scale is never
     * ambiguous.
     */
    public static volatile float RADAR_MIN_RANGE_M = 50f;
    /** Upper bound on the auto-scaled radar range (metres). */
    public static volatile float RADAR_MAX_RANGE_M = 4000f;
}
