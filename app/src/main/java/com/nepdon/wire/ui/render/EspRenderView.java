package com.nepdon.wire.ui.render;

import com.nepdon.wire.config.EspConfig;


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.nepdon.wire.esp.EspEngine;
import com.nepdon.wire.esp.Track;
import com.nepdon.wire.esp.OrientationProvider;

import java.util.Map;

/**
 * Full-screen ESP renderer: draws a 2D box + distance label over each
 * tracked enemy, plus a radar-lite square in the bottom-left corner.
 *
 * <p><b>World frame (Unity, Y-up).</b> Free Fire Max is Unity. The engine
 * gives posX/posY (horizontal) and posZ (height, varint v1). The renderer
 * works in world coordinates {@code (x, y, z) = (posX, posZ, posY)} &mdash;
 * exactly one conversion point, here in {@link #update(EspEngine)}. The
 * view basis is built per the approved design: forward
 * {@code f = (sin&theta;cos&phi;, sin&phi;, cos&theta;cos&phi;)}, right
 * {@code r = f &times; up_world}, up {@code u = r &times; f}, and
 * {@code V = [[r<sup>T</sup>, -r&middot;p],[u<sup>T</sup>, -u&middot;p],[-f<sup>T</sup>, f&middot;p],[0,1]]}.
 * The renderer evaluates {@code P&middot;V&middot;e} in factored form
 * (basis-vector dot products), which is the same math without the matrix
 * scaffolding: camera coords are {@code (d&middot;r, d&middot;u, d&middot;f)}
 * with {@code d = e - p}, and the point is drawable iff the OpenGL-style
 * clip w {@code = d&middot;f > near}. Projection P comes from the horizontal
 * FOV and the view aspect ratio (screen dimensions), assuming square
 * pixels.</p>
 *
 * <p><b>Orientation.</b> Primary: the decoded float32 unit direction
 * vector (keepalive varints v13/v14/v15) via {@link OrientationProvider.Full}
 * &mdash; full signed yaw and pitch. Fallback: {@link OrientationProvider.Folded}
 * (folded magnitudes + movement-quadrant inference) when the direction
 * vector is missing or not unit-length.</p>
 *
 * <p><b>Enemy axis mapping &mdash; THE calibration point.</b> The varint
 * finding established that enemy and own coordinates share the same frame
 * and units, so the identity mapping is expected to be correct &mdash; but
 * it remains configurable because the axis correspondence was never
 * directly verified against ground truth. The mapping lives ONLY in the
 * {@link #ENEMY_AXIS_SWAP}, {@link #ENEMY_NEG_AXIS1}, {@link #ENEMY_NEG_AXIS2}
 * flags below, applied ONLY in {@link #update(EspEngine)}. Calibrate live:
 * watch the radar dots / box bearings against the in-game minimap and flip
 * flags until they agree.</p>
 *
 * <p>All state is snapshotted on the main thread by {@link #update} before
 * {@link #invalidate()}; {@link #onDraw} performs no engine reads and (to
 * first order) no allocations.</p>
 */
public final class EspRenderView extends View {

    // ====================================================================
    // CALIBRATION CONFIG — the single source of truth for the projection.
    // ====================================================================











    /**
     * Last viewport the renderer resolved, mirrored for the setup panel.
     *
     * <p>Worth its own row because a viewport inset is the one prior cause of
     * "offset, growing with angle from the crosshair" that is <b>invisible on
     * screen</b> and indistinguishable from a wrong FOV: the overlay window
     * came out smaller than the game's and the projection inherited the
     * difference as a multiplicative error. It is structurally defended now,
     * and {@link #VP_EXACT} is how that gets confirmed in one glance instead of
     * re-derived.</p>
     */
    public static volatile int VP_W, VP_H, VP_OX, VP_OY;
    public static volatile boolean VP_EXACT;



    /**
     * The tangents and zooms the last frame actually projected with.
     *
     * <p>Published so the HUD reports the projection in force rather than one
     * recomputed from a subset of the inputs. Plain stores, no allocation.</p>
     */
    public static volatile float LIVE_TANX = 0f, LIVE_TANY = 0f;
    public static volatile float LIVE_ZOOMX = 1f, LIVE_ZOOMY = 1f;

    /**
     * Pixels per degree of view rotation the last frame actually projected at.
     * Isotropic by construction (vpW cancels out of the horizontal scale), so
     * one number covers both axes. Published so the HUD can turn a pan rate
     * into the pixel error it is currently costing.
     */
    public static volatile float LIVE_PXDEG = 0f;

    /**
     * Screen-x of the two nearest drawn entities, and their ids.
     *
     * <p>For the one measurement that needs no assumption about where the optic
     * centre is. A single target's offset-from-centre ratio is hypersensitive to
     * the assumed centre — with a box 90 px out, +-50 px on the centre swings
     * the implied zoom from 7 to 21. The SEPARATION between two targets cancels
     * the centre entirely: read the game's own two markers off the same frame
     * and the zoom is just (their gap) / (our gap).</p>
     */
    public static volatile float SEP_X1 = 0f, SEP_X2 = 0f;
    public static volatile int SEP_ID1 = -1, SEP_ID2 = -1;

    /**
     * Drawn box height in px, and the range it was drawn at, for the NEAREST
     * enemy on the last frame. The zoom measurement that needs no centre.
     *
     * <p>Every previous attempt to calibrate the scoped zoom compared a box's
     * POSITION against the screen centre, and a box 90 px out swings the answer
     * from 7x to 21x on a +/-50 px disagreement about where centre is. A box's
     * HEIGHT is a difference of two projected points: the centre cancels, the
     * optic nudge cancels, and the handedness cancels. What is left is the zoom
     * and the range, and the range is printed beside it.</p>
     *
     * <p><b>The procedure:</b> screenshot a target with a box on it, measure the
     * character's own height in px, and read this. The zoom in force is wrong by
     * exactly the ratio: {@code trueZoom = EspConfig.SCOPED_ZOOM * characterPx / boxPx}.
     * One frame, one division, and nothing assumed.</p>
     */
    public static volatile float CAL_BOX_H_PX = 0f;
    public static volatile float CAL_BOX_DIST_M = 0f;
    public static volatile int CAL_BOX_ID = -1;















    /** Beyond this jump (metres) the box teleports instead of gliding. */
    private static final float SMOOTH_SNAP_M = 8f;


    /**
     * Age past which a track is drawn faded: it is still extrapolated, but
     * it has missed at least one 198 ms tick so confidence is lower.
     */
    private static final long EXTRAP_FADE_MS = 260L;

    /**
     * Age past which extrapolation STOPS and the box freezes at the last
     * known position, heavily faded.
     *
     * <p>This matters more than it looks: the 198 ms tick is rock solid while
     * a target is tracked, but 37% of wall-clock time an entity has no fresh
     * record at all, and 28.7% of render instants see an age above one
     * second. At the 99th-percentile dropout (1 782 ms) a moving player has
     * travelled a median of 7.2 m and a p90 of 11.0 m - a confidently drawn
     * box there is worse than no box.</p>
     */
    private static final long EXTRAP_STOP_MS = 600L;
    /** A target still this long keeps its stored position; no dead-reckoning carry. */
    private static final long STILL_SKIP_MS = 3000L;


















    // ---- fixed render constants ----

    /**
     * Game units per metre. An ALIAS -- the value lives in
     * {@link com.nepdon.wire.esp.EspEngine#UNITS_PER_METRE}, which documents it.
     *
     * <p>This javadoc used to claim 1670 with its own cross-validation, sitting
     * directly above a field that resolves to <b>1000</b>. The 1000 is measured
     * from the client's own quantiser; 1670 and 950 are both retracted. A stale
     * claim attached to a live constant is the exact shape the project's
     * "treat comments as hypotheses" rule exists for, so it is now a pointer
     * and not a second copy of the reasoning.</p>
     */
    public static final double UNITS_PER_METER =
            com.nepdon.wire.esp.EspEngine.UNITS_PER_METRE;

    /** Enemy box height in meters (~1.8 m standing player). */
    private static final double BOX_H_M = 1.8;

    /** Enemy box width in meters (shoulder width). */
    private static final double BOX_W_M = 0.6;

    /**
     * An entity is dropped entirely past this age (ms). 3000 was far too
     * generous: at that age a moving player is many metres from where the box
     * would sit. See {@link #EXTRAP_STOP_MS} for the fade schedule.
     */
    private static final long STALE_MS = 1500L;

    /** Own keepalive older than this (ms) => no view state at all. */
    private static final long OWN_STALE_MS = 2000L;

    /**
     * The same limit while mounted, where the best source may be the 198 ms
     * self-echo rather than the 99 ms keepalive.
     *
     * <p>Deliberately generous, because this gate does not degrade the overlay
     * — it removes it, boxes and radar together. Six seconds of a slightly
     * old own position is a far better failure than a blank screen, and the
     * {@code OWN} HUD row names the source and its age throughout, so a genuine
     * loss is still visible rather than silently papered over.</p>
     */
    private static final long OWN_STALE_MOUNTED_MS = 6000L;

    /**
     * Maximum entities drawn per frame. 24 was sized for a Clash Squad
     * lobby; a survival match streams far more entities at once, and the
     * cost here is only preallocated array slots.
     */
    private static final int MAX_DRAW = 64;

    /** Skip entities closer than this (meters) — filters a self-track. */
    private static final float MIN_DIST_M = 1.0f;

    /**
     * Enemy-height plausibility gate, expressed RELATIVE to own height.
     * An absolute band is wrong: the height axis is map-dependent — capture
     * 150701 sat at Z 6 344-39 186 while capture 152127 (different map) sat
     * at Z 135 768-139 966. The old absolute 65 536 ceiling silently rejected
     * every enemy height on the second map and flattened the boxes to own
     * ground level. 500 m of vertical separation covers any real map.
     */
    private static final double HEIGHT_REL_MAX = 500.0 * UNITS_PER_METER;

    /** Near clip in game units (~13 cm) to keep the projection finite. */
    /**
     * Near plane in wire units. MEASURED: {@code FollowCamera::OnCameraChanged}
     * @ 0x07217914 sets {@code nearClipPlane} from the literal at 0x18ba780 =
     * 0.1 m, and the wire scale is 1000 units/m (confirmed at instruction level
     * in the Cmd 2001 packer), so 100 units. The old 30 was a guess; it is only
     * a drawable/behind-camera test so it was harmless, but matching the game
     * costs nothing.
     */
    private static final float NEAR_UNITS = 100f;

    /** Radar square size / margin / range (pixels / meters). */
    private static final int RADAR_PX = 150;
    private static final int RADAR_MARGIN_PX = 24;


    /** Live radar range, recomputed per frame in update(). */
    private float radarRangeM = 100f;

    private static final int BOX_STROKE_COLOR = Color.argb(235, 255, 64, 64);
    private static final int BOX_FILL_COLOR   = Color.argb(26,  255, 40, 40);
    private static final int LABEL_COLOR      = Color.argb(240, 255, 96, 96);

    /** Tracks the last applied EspConfig.BOX_COLOR_ARGB so we only re-derive the fill paint when the colour actually changes (onDraw must stay allocation-free). */
    private int lastBoxColor = BOX_STROKE_COLOR;
    private static final int RADAR_BG = Color.argb(120, 8, 18, 30);
    private static final int RADAR_BORDER = Color.argb(180, 90, 200, 255);
    private static final int RADAR_DOT = Color.argb(235, 255, 64, 64);
    private static final int RADAR_SELF = Color.argb(255, 240, 255, 255);
    private static final int RADAR_FACING = Color.argb(220, 80, 255, 140);
    private static final int TEAM_STROKE_COLOR = Color.argb(225, 70, 190, 255);
    private static final int TEAM_LABEL_COLOR = Color.argb(235, 120, 205, 255);
    /** Undecided: deliberately neither red nor blue. */
    private static final int UNK_STROKE_COLOR = Color.argb(215, 235, 205, 90);
    private static final int UNK_LABEL_COLOR = Color.argb(230, 245, 220, 120);

    // ====================================================================
    // Per-frame snapshot (written by update() on the main thread only)
    // ====================================================================

    private final OrientationProvider full = new OrientationProvider.Full();
    private final OrientationProvider folded = new OrientationProvider.Folded();

    private boolean hasState = false;
    private float ownX, ownY, ownZ;          // world (x, y=height, z)
    private float theta;                     // best-known full yaw (debug readout only)
    /** Camera forward, taken straight from the packet (no angle round-trip). */
    private float fwdX, fwdY, fwdZ;
    private boolean fwdValid;
    private float pitch;                     // best-known pitch
    private boolean thetaKnown;
    private double foldedDeg;                // folded yaw in degrees (debug)
    private String oriSource = "init";
    /** Preformatted view-mode labels, so oriSource never concatenates. */
    private static final String[] MODE_NAMES =
            { "m0", "m1", "m2", "m3", "m4", "m5", "m6", "m7" };

    private int enemyCount = 0;
    private final float[] enemyWX = new float[MAX_DRAW];
    private final float[] enemyWZ = new float[MAX_DRAW];
    private final float[] enemyH = new float[MAX_DRAW];   // ground height
    private final float[] enemyDistM = new float[MAX_DRAW];
    private final int[] enemyId = new int[MAX_DRAW];
    // RAW wire-frame position, straight off the Track and BEFORE the display
    // axis map. The map (EspConfig.ENEMY_AXIS_SWAP / NEG_AXIS1 / NEG_AXIS2) is a
    // rendering calibration; anything that goes back ON THE WIRE -- the Cmd 148
    // mark rewrite -- must use the untransformed values or it pins a mirrored
    // point. Wire order is (X, UP, Z) = (t.x, t.z, t.y); note the engine's
    // field named z is the VERTICAL one.
    private final double[] enemyRawX = new double[MAX_DRAW];
    private final double[] enemyRawUp = new double[MAX_DRAW];
    private final double[] enemyRawY = new double[MAX_DRAW];
    private final int[] enemyRole = new int[MAX_DRAW];
    /** Server-published team byte KNOWN for this entity (aim gate). */
    private final boolean[] enemyTeamKnown = new boolean[MAX_DRAW];
    /** ms since this entity last moved; bigger is stiller (aim gate). */
    private final long[] enemyStillMs = new long[MAX_DRAW];
    /**
     * Posture and movement-mode enums, straight off the wire.
     *
     * <p>{@code Track.state} / {@code Track.mode} have been decoded since the
     * entity parser was written and read by NOTHING -- they were not even
     * copied into this snapshot, so the aim could not see them. They are what
     * {@link com.nepdon.wire.ui.aim.Posture} needs to decide a target's height
     * and whether we are willing to aim at it at all.</p>
     *
     * <p>Named {@code enemyPoseEnum}, not {@code enemyPose}: that name is
     * already taken by the boolean pose-VALID flag two fields down, and a
     * collision there would be a silent type clash.</p>
     */
    private final int[] enemyPoseEnum = new int[MAX_DRAW];
    private final int[] enemyModeEnum = new int[MAX_DRAW];
    private final boolean[] enemyInVehicle = new boolean[MAX_DRAW];
    /** Aim anchor in world units above the entity root, per posture. */
    private final float[] enemyAnchorU = new float[MAX_DRAW];
    /**
     * The aim's own base position and velocity, in the SAME mapped axes as
     * {@code enemyWX/enemyWZ} but taken BEFORE the render smoothing.
     *
     * <p>EspConfig.SMOOTH_MS is cosmetic -- it stops each 198 ms correction showing as a
     * jump. It is also 40 ms of exponential lag, which at 6 m/s is 0.24 m, or
     * 23 px scoped at 32 m: half a body width, bought for nothing, because the
     * aim does not need to look smooth. So the aim gets the unsmoothed point
     * and the boxes keep the filter.</p>
     */
    private final float[] enemyAimBaseX = new float[MAX_DRAW];
    private final float[] enemyAimBaseZ = new float[MAX_DRAW];
    private final float[] enemyAimBaseH = new float[MAX_DRAW];
    /** Velocity through the same axis map, world units per ms. */
    private final float[] enemyVX = new float[MAX_DRAW];
    private final float[] enemyVZ = new float[MAX_DRAW];
    private final float[] enemyVY = new float[MAX_DRAW];
    private final boolean[] enemyVelValid = new boolean[MAX_DRAW];
    /** Estimated HP: a lower bound built from our own hits. Fallback only. */
    private final int[] enemyHp = new int[MAX_DRAW];
    /** Player name from the match roster, or null. */
    private final String[] enemyName = new String[MAX_DRAW];
    /** REAL HP from Cmd 1900, or -1 when this entity's owner is not bound. */
    private final int[] enemyRealHp = new int[MAX_DRAW];
    /** Announced max HP from Cmd 1900, or -1 when not announced. */
    private final int[] enemyMaxHp = new int[MAX_DRAW];
    /** Age of the newest record behind each drawn entity (ms), for fading. */
    private final long[] enemyAgeMs = new long[MAX_DRAW];
    private final float[] enemyBodyYaw = new float[MAX_DRAW];
    private final float[] enemyAimYaw = new float[MAX_DRAW];
    private final float[] enemyAimPitch = new float[MAX_DRAW];
    private final boolean[] enemyPose = new boolean[MAX_DRAW];

    /** Per-entity smoothed render position, indexed by entity id. */
    private static final int SMOOTH_SLOTS = 128;
    private final double[] smX = new double[SMOOTH_SLOTS];
    private final double[] smY = new double[SMOOTH_SLOTS];
    private final double[] smZ = new double[SMOOTH_SLOTS];
    private final long[] smMs = new long[SMOOTH_SLOTS];


    // ====================================================================
    // THE GAME'S VIEWPORT, NOT OURS
    // ====================================================================
    //
    // The projection must be built from the surface the GAME renders into,
    // which is the whole display. Our overlay window is NOT guaranteed to be
    // that surface: without FLAG_LAYOUT_NO_LIMITS and an explicit cutout mode
    // Android keeps an overlay clear of the display cutout and the nav bar, so
    // getWidth()/getHeight() can be smaller than the game's viewport and our
    // origin can sit a few pixels inside it.
    //
    // Why that produced the reported bug. Work the horizontal scale through:
    //
    //     scale_x = halfW / tanHalfX
    //             = (w/2) / (tanHalfV * w/h)
    //             = h / (2 * tanHalfV)                <-- the width CANCELS
    //
    // so the two insets fail in two different ways and only one of them is
    // recoverable with the EspConfig.OFFSET_X_PX knob:
    //
    //   * a HEIGHT inset (nav bar) shrinks the projection scale. The error is
    //     zero at the centre and grows with angle from it -- boxes slide
    //     sideways off distant/off-centre targets while a target you are
    //     looking straight at stays locked. That is exactly the reported
    //     symptom, and NO OFFSET value can correct it because it is
    //     multiplicative, not additive.
    //   * a WIDTH inset (cutout on a landscape short edge) moves the centre.
    //     That one is a constant offset, which EspConfig.OFFSET_X_PX could absorb.
    //
    // Fix: resolve the real display bounds, project in DISPLAY space, then
    // translate into view space by this view's own on-screen origin. Correct
    // whether or not the window manager gives us the full display.
    private int vpW = 0, vpH = 0;              // game viewport = display, px
    private int vpOriginX = 0, vpOriginY = 0;  // this view's (0,0) in display space
    private final int[] locScratch = new int[2];
    private long vpCheckMs = 0L;

    /** True when our window really is the whole display (nothing inset). */
    private boolean vpExact = true;

    /**
     * Refreshes the display bounds and our origin within them. Re-queried at
     * most once a second: it is a couple of binder-free calls, but onDraw runs
     * at display refresh and rotation is the only thing that changes it.
     */
    private void refreshViewport(int w, int h) {
        long now = android.os.SystemClock.uptimeMillis();
        if (vpW > 0 && now - vpCheckMs < 1000L) return;
        vpCheckMs = now;

        int dw = 0, dh = 0;
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                    dw = b.width();
                    dh = b.height();
                } else {
                    android.graphics.Point p = new android.graphics.Point();
                    wm.getDefaultDisplay().getRealSize(p);
                    dw = p.x;
                    dh = p.y;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the view's own bounds below.
        }
        if (dw <= 0 || dh <= 0) { dw = w; dh = h; }

        int ox = 0, oy = 0;
        try {
            getLocationOnScreen(locScratch);
            ox = locScratch[0];
            oy = locScratch[1];
        } catch (Throwable ignored) {
        }

        vpW = dw;
        vpH = dh;
        vpOriginX = ox;
        vpOriginY = oy;
        vpExact = (ox == 0 && oy == 0 && dw == w && dh == h);
        // Static mirror so a panel can report the viewport without holding the
        // view. Five plain stores, no allocation -- the formatting happens on
        // the panel's 250 ms tick, never here.
        VP_W = dw;
        VP_H = dh;
        VP_OX = ox;
        VP_OY = oy;
        VP_EXACT = vpExact;
    }

    /** Diagnostic: view bounds vs the game viewport, and the derived hFOV. */
    /**
     * The implied VERTICAL half-tangent, from {@link #FOV_X_DEG} read as a
     * horizontal FOV at the 16:9 reference (the HOR+ model).
     *
     * <p>ONE definition. It used to be derived in two places -- here and in
     * {@code onDraw} -- with only one of them naming the 16:9 constant. The
     * copy in {@link #viewportHint()} feeds a diagnostic string only, so a
     * divergence would have shown up as a WRONG READOUT while the render
     * stayed correct: a readout that can disagree with the renderer is the one
     * you end up calibrating against.</p>
     */
    private static float tanHalfV() {
        final float REF_ASPECT = 16f / 9f;
        return (float) Math.tan(Math.toRadians(EspConfig.FOV_X_DEG) * 0.5) / REF_ASPECT;
    }

    public String viewportHint() {
        if (vpW <= 0) return "viewport unknown";
        float hfov = (float) (2.0 * Math.toDegrees(
                Math.atan(tanHalfV() * ((double) vpW / (double) vpH))));
        return String.format("VP %dx%d org %d,%d %s  hFOV %.1f°",
                vpW, vpH, vpOriginX, vpOriginY,
                vpExact ? "exact" : "INSET-CORRECTED", hfov);
    }

    // ====================================================================
    // Preallocated paint / scratch (no allocations in onDraw)
    // ====================================================================

    /** Draws the four corner-bracket ESP box. Replaces the inline box draw. */
    private EspBox espBox;
    /** Draws the Moco-style purple name tag above the box. */
    private EspMoco espMoco;

    private final Paint boxStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFill = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarBg = new Paint();
    private final Paint radarBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarSelf = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarFacing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radarText = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * ESP line from screen bottom-centre to each enemy's feet.
     * Recoloured per-frame from BOX_COLOR_ARGB, same as the box stroke.
     */
    private final Paint espLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Pose indicator: the head/weapon aim-direction ray (EspConfig.SHOW_POSE). */
    private final Paint aimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Aim editor: swipe-origin marker + travel cap ring. */
    private final Paint aimZonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Aim editor: acquisition cone. */
    private final Paint aimConePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Solid, for the scope centre dot. aimZonePaint is STROKE. */
    private final Paint aimDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Pose indicator: the body-facing ray (EspConfig.SHOW_POSE). */
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Health bar: unfilled remainder. */
    private final Paint hpBarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Health bar: the filled portion, recoloured per entity. */
    private final Paint hpBarFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Health bar outline, so it stays readable over a bright scene. */
    private final Paint hpBarEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Display density, for marker and bar sizes. */
    private final float densityScale;
    private final StringBuilder labelBuf = new StringBuilder(24);
    /**
     * Second scratch, for the radar's range caption.
     *
     * <p>That caption was the last string concatenation left inside onDraw:
     * {@code Math.round(radarRangeM) + "m"} builds a StringBuilder, its
     * char[], a String and its char[] -- four objects, every frame, at 120 Hz
     * over live gameplay. Every other label already used the buffer above;
     * this one was missed.</p>
     */
    private final StringBuilder radarBuf = new StringBuilder(8);
    /** Wall-clock of the freshest camera-direction sample, for the aim loop. */
    private long lastCamSampleMs = 0L;

    /** Projection origin: the real third-person camera, not the character. */
    private float eyeX, eyeY, eyeZ;
    private boolean eyeValid = false;

    // per-frame view basis (world space)
    private float fX, fY, fZ;   // forward
    private float rX, rY, rZ;   // right
    private float uX, uY, uZ;   // up

    // scratch output of project()
    private float scratchX, scratchY;
    /**
     * Camera-space bearing of the point {@link #project} just handled, degrees,
     * signed the same way the screen axes are (yaw +right, pitch +up). Valid
     * only when that call returned true, and only until the next call.
     *
     * <p>This is the SOURCE quantity the aim needs. It does not depend on the
     * FOV, the zoom, the optic centre, or anything else about how the point is
     * drawn -- which is exactly why the aim reads it and not the pixels.</p>
     */
    private float scratchYawDeg, scratchPitchDeg;

    /**
     * Camera pan rate (deg/s, -1 when unknown) and the age of the direction
     * sample it came from, snapshotted on each update for the HUD. Fields
     * rather than a live call because briefLine() has no EspEngine reference,
     * and giving it one would hand a text formatter a handle on the engine.
     */
    private volatile float lastPanDegSec = -1f;
    private volatile long lastCamAgeMs = -1L;

    /** Lead actually used on the last frame (ms), for the debug readout. */
    private volatile float lastLeadMs = 0f;
    /** True when the lead came from the self-echo, false when from v18. */
    private volatile boolean lastLeadEcho = false;

    /** Scratch for the extrapolated camera forward (raw axes), no allocation. */
    private final float[] dirScratch = new float[3];
    /** Degrees the camera forward was extrapolated this frame, for the HUD. */
    private volatile float lastCamExtrapDeg = 0f;

    /** Measured round-trip staleness, ms; what the box lags by with predict off. */
    private volatile float lastMeasLagMs = 0f;

    /** True when own state came from the self-echo (mounted), not Cmd 2001. */
    /** Which feed is supplying own position/camera right now. */
    /** Rebuild period for the status strings; see update(). */
    private static final long STATUS_BUILD_MS = 400L;
    private long lastStatusBuildMs = 0L;
    private volatile String lastOwnSource = "OWN ?";
    /** Whether the header layout was seeded from the lobby or solved by voting. */
    private volatile String lastLayoutSource = "LAYOUT ?";

    // ---- nearest-target calibration readout (written in onDraw) ----
    // The panel needs a NUMBER to tune against, not just a picture. When the
    // crosshair is on an enemy, a correct projection puts that enemy at NDC
    // (0,0); whatever it actually reads is the error, and its behaviour as the
    // target moves tells you which knob is wrong.
    private volatile boolean nearValid = false;
    private volatile int nearId = 0;
    private volatile float nearDistM = 0f;
    private volatile float nearNdcX = 0f, nearNdcY = 0f;
    private volatile boolean nearOnScreen = false;

    public EspRenderView(Context context) {
        super(context);
        setWillNotDraw(false);

        float density = context.getResources().getDisplayMetrics().density;
        this.densityScale = density;
        espBox  = new EspBox(density);
        espMoco = new EspMoco(density);
        boxStroke.setColor(BOX_STROKE_COLOR);
        boxStroke.setStyle(Paint.Style.STROKE);
        boxStroke.setStrokeWidth(Math.max(1.5f, 1.5f * density));

        boxFill.setColor(BOX_FILL_COLOR);
        boxFill.setStyle(Paint.Style.FILL);

        labelPaint.setColor(LABEL_COLOR);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(10f * density);

        radarBg.setColor(RADAR_BG);
        radarBg.setStyle(Paint.Style.FILL);

        radarBorder.setColor(RADAR_BORDER);
        radarBorder.setStyle(Paint.Style.STROKE);
        radarBorder.setStrokeWidth(Math.max(1f, density));

        radarDot.setColor(RADAR_DOT);
        radarDot.setStyle(Paint.Style.FILL);

        radarSelf.setColor(RADAR_SELF);
        radarSelf.setStyle(Paint.Style.FILL);

        radarFacing.setColor(RADAR_FACING);
        radarFacing.setStyle(Paint.Style.STROKE);
        radarFacing.setStrokeWidth(Math.max(1.5f, 1.5f * density));

        espLinePaint.setColor(BOX_STROKE_COLOR);
        espLinePaint.setStyle(Paint.Style.STROKE);
        espLinePaint.setStrokeWidth(Math.max(1f, density));
        espLinePaint.setAlpha(160);

        aimPaint.setColor(Color.argb(230, 255, 210, 80));
        aimPaint.setStyle(Paint.Style.STROKE);
        aimPaint.setStrokeWidth(Math.max(1.5f, 1.5f * density));

        bodyPaint.setColor(Color.argb(190, 120, 255, 190));
        bodyPaint.setStyle(Paint.Style.STROKE);
        bodyPaint.setStrokeWidth(Math.max(1f, density));

        // Aim editor. Density-scaled, never hand-computed px: this unit's
        // density override is 398, not the 480 the panel reports.
        aimZonePaint.setColor(Color.argb(220, 255, 210, 80));
        aimZonePaint.setStyle(Paint.Style.STROKE);
        aimZonePaint.setStrokeWidth(Math.max(2f, density * 1.5f));
        aimConePaint.setColor(Color.argb(110, 120, 200, 255));
        aimConePaint.setStyle(Paint.Style.STROKE);
        aimConePaint.setStrokeWidth(Math.max(1f, density));
        aimDotPaint.setColor(Color.argb(235, 255, 235, 120));
        aimDotPaint.setStyle(Paint.Style.FILL);

        hpBarBg.setStyle(Paint.Style.FILL);
        hpBarBg.setColor(Color.argb(150, 10, 12, 16));
        hpBarFill.setStyle(Paint.Style.FILL);
        hpBarFill.setColor(Color.argb(235, 60, 220, 90));
        hpBarEdge.setStyle(Paint.Style.STROKE);
        hpBarEdge.setStrokeWidth(Math.max(1f, density));
        hpBarEdge.setColor(Color.argb(190, 0, 0, 0));

        radarText.setColor(RADAR_BORDER);
        radarText.setTextSize(8f * density);
        radarText.setTextAlign(Paint.Align.RIGHT);
    }

    // ====================================================================
    // State snapshot (main thread, once per render tick)
    // ====================================================================

    /**
     * Snapshots everything onDraw needs from the engine. Handles the
     * engine-off / mask-not-ready / stale-stream states by clearing the
     * snapshot (onDraw then draws nothing).
     */
    public void update(EspEngine esp) {
        // Kept so onDraw can ask for the camera-sample timestamp; update() and
        // onDraw() are both on the UI thread, so a plain field is enough.
        lastCamSampleMs = (esp != null) ? esp.dirSampleMs() : 0L;
        // Snapshotted here, BEFORE the early returns below, so the HUD keeps
        // reporting the pan rate in exactly the frames a gate bailed -- which
        // is when you most want to know whether the view was settled.
        lastPanDegSec = (esp != null) ? esp.camPanDegPerSec() : -1f;
        lastCamAgeMs = (esp != null)
                ? esp.camSampleAgeMs(System.currentTimeMillis()) : -1L;
        hasState = false;
        enemyCount = 0;
        fwdValid = false;
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_FRAME);
        if (esp == null) { com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_NO_ENGINE); return; }
        // BEFORE the gates below, not after. This row names the own-state
        // source, so it is most needed in exactly the frames where one of
        // those gates returns early -- and it used to be assigned past them,
        // which froze it at its last good value at the moment the overlay
        // went blank.
        if (!esp.maskReady()) { com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_MASK_NOT_READY); return; }
        long now = System.currentTimeMillis();
        // THESE ARE READ AT 2 Hz AND WERE BUILT AT 120.
        //
        // statusLine() is polled by the debug ticker every 500 ms; building
        // these every frame was 60x more often than anything looks at them,
        // and each is a fresh String on the render path. Gated to roughly the
        // rate they are actually consumed at.
        if (now - lastStatusBuildMs >= STATUS_BUILD_MS) {
            lastStatusBuildMs = now;
            lastOwnSource = esp.ownSourceLine();
        // WE ARE MOUNTED -> the aim must refuse everything.
        //
        // Cmd 2001 stops the moment the player rides anything, so our own view
        // direction stops updating and the whole camera basis freezes. Aiming
        // from a frozen basis does not miss by a little: it swings the view at
        // whatever bearing the basis was left holding. This is the one refusal
        // that is not about the target, and it is the most dangerous one to
        // leave out.
        // NOT esp.mounted(). That uses a 400 ms keepalive-silence window, which
        // is the right test for "are we riding" and the wrong one for "is the
        // basis safe to aim from": the measured own-state rate is ~5.3/s, so
        // 400 ms is two missed ticks, and it refused 1.1 times a SECOND on a
        // player walking around on foot. The wire flag is authoritative and
        // free; the silence test gets its own, much longer threshold.
        final long ownAge = esp.ownAgeMs();
        final boolean selfMounted = esp.mountedWire()
                || (ownAge >= 0L
                    && ownAge > com.nepdon.wire.ui.aim.AimConfig.AIM_BASIS_STALE_MS);
        com.nepdon.wire.ui.aim.AimTarget.selfMounted = selfMounted;
        if (selfMounted) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.AIM_REFUSE_SELF_MOUNTED);
        }
            com.nepdon.wire.protocol.PacketPipeline pp0 =
                    com.nepdon.wire.protocol.PacketPipeline.current();
            lastLayoutSource = (pp0 == null) ? "LAYOUT n/a" : pp0.layoutSourceLine();
        }
        // ownStateMs, NOT ownLastUpdateMs. The latter tracks Cmd 2001 alone,
        // which stops by design the moment the player mounts anything -- so
        // asking it here blanked the whole overlay ~1.6 s into every vehicle
        // ride, discarding a self-echo fallback that was working fine.
        // Identical on foot: both advance on the same keepalive.
        long ownMs = esp.ownStateMs();
        // Mounted states tolerate a longer gap. On foot Cmd 2001 arrives every
        // 99 ms and a 2 s silence genuinely means own state is lost. Mounted,
        // the best available source can be the inbound self-echo on a 198 ms
        // tick, and a seat change, a vehicle transition or a couple of dropped
        // ticks can exceed 2 s without our position being unknown -- and this
        // gate blanks the ESP AND the radar together, which is precisely the
        // reported "everything turns off in a vehicle".
        long ownLimit = esp.mounted() ? OWN_STALE_MOUNTED_MS : OWN_STALE_MS;
        if (ownMs <= 0 || now - ownMs > ownLimit) {
            // The gate that blanked the whole overlay 1.6 s into every vehicle
            // ride. Now it says so instead of just drawing nothing.
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_NO_OWN_STATE);
            return;
        }

        // Own position -> world frame. THIS is the own-axes conversion point:
        // world (x, y, z) = (posX, posZ, posY)  [Unity Y-up].
        double px = esp.posX();
        double py = esp.posY();
        double pz = esp.posZ();
        if (esp.ownPosChainFrame()) {
            // Own position is echo-derived, i.e. it came out of the inbound
            // entity chain rather than out of one of our own reports. Entity
            // coordinates get the axis map applied below and own coordinates
            // do not, because the map exists precisely to bring the chain
            // frame into the own frame -- so an echo-derived own position has
            // to go through it too. Skipping it put our own position in a
            // different frame from every entity whenever the map was turned
            // on, which is a silent, total ESP failure in exactly the states
            // where the echo is the only own-state source (passenger seat,
            // chair, cannon, catapult). Identity when the map is off, which
            // is the default -- hence never noticed.
            double q1 = EspConfig.ENEMY_NEG_AXIS1 ? -px : px;
            double q2 = EspConfig.ENEMY_NEG_AXIS2 ? -py : py;
            px = EspConfig.ENEMY_AXIS_SWAP ? q2 : q1;
            py = EspConfig.ENEMY_AXIS_SWAP ? q1 : q2;
        }
        if (!sane(px) || !sane(py) || !sane(pz)) return;
        ownX = (float) px;
        ownY = (float) pz;   // height
        ownZ = (float) py;
        eyeValid = false;

        // Orientation: decoded direction vector primary, folded fallback.
        // ONE snapshot read for the whole basis, so the forward vector and its
        // lead come from the same sample (see EspEngine.CamSample).
        final EspEngine.CamSample cs = esp.camSnapshot();
        full.update(esp);
        if (full.yawKnown() && cs != null) {
            if (EspConfig.VIEW_AUTOCYCLE) {
                EspConfig.VIEW_MODE = (int) ((now / Math.max(500L, EspConfig.VIEW_CYCLE_MS)) % 8);
            }
            int m = EspConfig.VIEW_MODE & 7;

            // ---- CAMERA-FORWARD ANGULAR EXTRAPOLATION (raw axes) ----
            // Lead the packet forward to the present frame using its measured
            // angular velocity, so the whole basis tracks the camera through a
            // turn instead of trailing it. Done on the RAW (dirX,dirY,dirZ)
            // axes, BEFORE the EspConfig.VIEW_MODE swap/negate below: that remap is
            // orthogonal, so it commutes with the rotation and either order
            // gives the same basis. At EspConfig.CAM_EXTRAP_MS == 0 (or a steady view)
            // this path is skipped and erx/ery/erz are the raw packet forward,
            // reducing exactly to the previous maths.
            //
            // GATED ON EspConfig.EXTRAPOLATE. This is the SECOND predictor in the
            // renderer, and it used to answer only to its own CAM EXTRAP knob
            // in a different panel section -- so turning EspConfig.EXTRAPOLATE off, LEAD
            // to 0 and AUTO LEAD off still left the basis being led 90 ms by
            // default, and boxes still slid ahead of a tracked player. One
            // switch now means what it says: prediction off is prediction off,
            // both for the target and for the camera.
            float erx = cs.x, ery = cs.y, erz = cs.z;
            lastCamExtrapDeg = 0f;
            //
            // EspConfig.CAM_LEAD_ALWAYS decouples the two again, deliberately and with a
            // switch of its own rather than by quietly reverting the above.
            // They correct different errors: EspConfig.EXTRAPOLATE carries a TARGET
            // forward along its velocity and overshoots when it stops; camera
            // lead corrects OUR OWN view angle being ~99 ms stale, which is a
            // rigid rotation of the basis and has no target to overshoot. A
            // scope multiplies the second by the zoom -- at 3.27x a 1 deg lag
            // is 53 px, about one box width -- which is why it only became
            // worth separating once the scoped projection was right.
            // A: adaptive camera lead — stronger while panning, still never
            // touches entity LEAD/EXTRAPOLATE.
            float camLeadMs = EspConfig.CAM_EXTRAP_MS;
            if (EspConfig.CAM_LEAD_ALWAYS || EspConfig.EXTRAPOLATE) {
                try {
                    float panDps = esp.camPanDegPerSec();
                    if (panDps > 15f) {
                        // Extra lead scales with turn rate, capped.
                        // Capped by the same CAM DELAY knob: one setting, all the extra lead.
                        float extra = Math.min(EspConfig.CAM_EXTRAP_MS, (panDps - 15f) * 0.6f);
                        camLeadMs = EspConfig.CAM_EXTRAP_MS + extra;
                    }
                } catch (Throwable ignored) { }
            }
            if ((EspConfig.EXTRAPOLATE || EspConfig.CAM_LEAD_ALWAYS) && camLeadMs > 0f && EspEngine.extrapolatedDir(cs,
                    now, camLeadMs, EspConfig.CAM_EXTRAP_MAX_DEG, dirScratch)) {
                float rawx = erx, rawy = ery, rawz = erz;
                erx = dirScratch[0]; ery = dirScratch[1]; erz = dirScratch[2];
                double dp = (double) rawx * erx + (double) rawy * ery
                        + (double) rawz * erz;
                if (dp > 1.0) dp = 1.0; else if (dp < -1.0) dp = -1.0;
                lastCamExtrapDeg = (float) Math.toDegrees(Math.acos(dp));
            }

            float a = ((m & 4) != 0) ? erz : erx;
            float b = ((m & 4) != 0) ? erx : erz;
            if ((m & 2) != 0) a = -a;
            if ((m & 1) != 0) b = -b;
            theta = (float) Math.atan2(a, b);
            float vy = EspConfig.PITCH_SIGN * ery;
            if (vy > 1f) vy = 1f;
            if (vy < -1f) vy = -1f;
            pitch = (float) Math.asin(vy);
            thetaKnown = true;
            // Table lookup, not a concat: this ran every frame purely to
            // label a debug row.
            oriSource = (m >= 0 && m < MODE_NAMES.length) ? MODE_NAMES[m] : "m?";

            // Keep the forward vector itself. The decoded packet already IS a
            // unit forward vector, so converting it to yaw/pitch and then
            // rebuilding it with sin/cos is a pure round-trip that can only
            // lose precision and introduce quadrant/sign mistakes. Static
            // analysis of a competing cheat (captures/REPORT_netcheat_apk.md)
            // showed its ESP libraries import NO libm symbol at all — no
            // sinf/cosf/atan2f/tanf anywhere — proving its world-to-screen is
            // dot products and one divide against a basis taken directly from
            // the stream. We now do the same; theta/pitch remain only for the
            // debug readout.
            float lx = erx, ly = ery, lz = erz;
            if ((m & 4) != 0) { float t2 = lx; lx = lz; lz = t2; }
            if ((m & 2) != 0) lx = -lx;
            if ((m & 1) != 0) lz = -lz;
            ly *= EspConfig.PITCH_SIGN;
            float ln = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (ln > 1e-6f) {
                fwdX = lx / ln; fwdY = ly / ln; fwdZ = lz / ln;
                fwdValid = true;
            } else {
                fwdValid = false;
            }
        } else {
            folded.update(esp);
            theta = (float) folded.yaw();
            pitch = (float) EspConfig.PITCH_FALLBACK_RAD;
            thetaKnown = folded.yawKnown();
            oriSource = "fold:" + folded.source();
            // The folded fallback is a different estimator with no angular
            // velocity of its own, so the camera-forward extrapolation does not
            // apply here.
            lastCamExtrapDeg = 0f;
        }
        // Folded view of the direction magnitudes (debug/calibration readout).
        double adx = Math.abs(esp.dirX());
        double adz = Math.abs(esp.dirZ());
        if (adx + adz > 1e-6) foldedDeg = Math.toDegrees(Math.atan2(adx, adz));
        hasState = true;
        // NOTE: the drew-box / drew-nothing counters USED to be here, reading
        // enemyCount before the entity loop below had assigned it -- and
        // enemyCount is zeroed at the top of this method. So DREW_NOTHING
        // fired unconditionally on every frame, DREW_BOX could never fire at
        // all, and the RENDER_BLIND invariant that keys on them produced 79
        // false alarms in a single capture. They now sit after the loop.

        // Lead for this frame: measured if available, else the manual knob.
        double leadMs = EspConfig.LEAD_MS;
        if (EspConfig.AUTO_LEAD) {
            double m = esp.measuredLagMs();
            if (m > 0) leadMs = m;
        }
        lastLeadMs = (float) leadMs;
        lastLeadEcho = esp.lagFromEcho();
        // The REAL staleness, independent of what the lead knob is set to.
        // With prediction off this is exactly how far behind the box sits, so
        // the HUD can state the cost instead of implying it is zero.
        lastMeasLagMs = (float) esp.measuredLagMs();

        int n = 0;
        // keySet() + get(), NOT entrySet().
        //
        // ConcurrentHashMap's EntryIterator ALLOCATES A Map.Entry PER STEP. At
        // 64 entities and 120 Hz that is ~1300 objects/sec, ~48 MB over a
        // match, and every one of them is garbage created on the thread with
        // the 8.3 ms frame budget. onDraw itself is carefully allocation-free;
        // this loop one level up was quietly undoing that.
        //
        // The Integer keys are NOT a problem and must not be "fixed": entity
        // ids are capped at 127, so every one is inside the Integer cache and
        // boxing allocates nothing.
        for (Integer key : esp.entities().keySet()) {
            if (n >= MAX_DRAW) break;
            Track t = esp.entities().get(key);
            if (t == null) continue;
            if (now - t.updateMs >= STALE_MS) {
                com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_ENTITY_STALE);
                continue;
            }
            double e1 = t.x;   // engine "X" (own posX-axis candidate)
            double e2 = t.y;    // engine "Y" (own posY-axis candidate)
            double eZ = t.z;    // height
            if (!sane(e1) || !sane(e2)) continue;

            // ---- DEAD RECKONING ----
            // Advance the last packet position to NOW: the record's own age
            // plus EspConfig.LEAD_MS for the latency that had already elapsed before it
            // reached us. Constant-velocity prediction was measured against
            // simply holding the last position and wins by a wide margin -
            // RMS ratio 0.573 one tick ahead, 0.622 at two, 0.679 at three,
            // and 0.60 end-to-end; median error while moving falls from about
            // one player-width to a few centimetres.
            long age = now - t.updateMs;
            // TWO DIFFERENT CORRECTIONS, AND THEY USED TO SHARE ONE FLAG.
            //
            // `age` carries the record to NOW -- it undoes staleness we can
            // measure, and it is arithmetic on a timestamp we hold. `leadMs`
            // guesses the FUTURE. Gating both on EspConfig.EXTRAPOLATE meant switching
            // off the guess also switched off the catch-up, so a box sat frozen
            // at the packet for a whole 198 ms tick and then snapped: a 5 Hz
            // sawtooth reaching 1.19 m at 6 m/s, which reads exactly as "the
            // box is sometimes ahead, sometimes off to the side".
            //
            // Same shape as the EspConfig.CAM_EXTRAP_MS coupling in section 13.4. Third
            // time this codebase has shipped one switch over two corrections,
            // so it gets its own flag rather than a silent re-coupling.
            final boolean carry = EspConfig.EXTRAPOLATE || EspConfig.TARGET_AGE_CARRY;
            // v3: standing still for STILL_SKIP_MS means the stored position is already right.
            final boolean stillSkip = t.lastMoveMs != 0L && (now - t.lastMoveMs) > STILL_SKIP_MS;
            if (carry && !stillSkip && t.velValid && age >= 0 && age < EXTRAP_STOP_MS) {
                double lead = age + (EspConfig.EXTRAPOLATE ? leadMs : 0.0);
                double dx1 = t.vx * lead;
                double dy1 = t.vy * lead;
                double dz1 = t.vz * lead;
                // Absurdity guard only - see EspConfig.EXTRAP_MAX_M.
                //
                // THE `> 0` IS LOAD-BEARING. At EspConfig.EXTRAP_MAX_M == 0 this read
                // `mag > 0`, so k = 0/mag = 0 and all three axes were zeroed --
                // a cap of zero clamps to nothing, it does not mean "no cap".
                // Prediction could be switched on and produce identically no
                // movement, silently, which is what shipped.
                double maxU = EspConfig.EXTRAP_MAX_M * UNITS_PER_METER;
                double mag = Math.sqrt(dx1 * dx1 + dy1 * dy1);
                if (EspConfig.EXTRAP_MAX_M > 0f && mag > maxU && mag > 1e-6) {
                    double k = maxU / mag;
                    dx1 *= k; dy1 *= k; dz1 *= k;
                }
                e1 += dx1;
                e2 += dy1;
                eZ += dz1;
            }

            // ---- ENEMY AXIS MAP (the ONLY place it is applied) ----
            double m1 = EspConfig.ENEMY_NEG_AXIS1 ? -e1 : e1;
            double m2 = EspConfig.ENEMY_NEG_AXIS2 ? -e2 : e2;
            double ax = EspConfig.ENEMY_AXIS_SWAP ? m2 : m1;   // own posX-axis component
            double az = EspConfig.ENEMY_AXIS_SWAP ? m1 : m2;   // own posY-axis component

            // Role filter. Draw ONLY confirmed enemies: the self-echo and
            // teammates must never be drawn, and neither must an entity that
            // has not yet been classified — ROLE_UNKNOWN used to fall through
            // and get drawn, which is why boxes appeared on teammates and on
            // our own character during the first seconds of a match (the
            // classifier needs 40 samples before it commits).
            int role = t.role;
            if (role == EspEngine.ROLE_SELF) { com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_ROLE_FILTERED); continue; }
            // Teammates are never drawn. Skipping here also keeps them out of the
            // aim pick and the radar, which both read this same list.
            if (role == EspEngine.ROLE_TEAM) continue;
            if (role == EspEngine.ROLE_UNKNOWN && !EspConfig.SHOW_UNCLASSIFIED) continue;
            // Killed: the damage feed took this entity to 0 HP. The server
            // keeps streaming position records for a dead player, which is
            // why boxes used to linger after everyone was down.
            if (t.dead) continue;

            double dx = ax - px;
            double dz = az - py;
            float distM = (float) (Math.sqrt(dx * dx + dz * dz) / UNITS_PER_METER);
            if (distM < MIN_DIST_M) continue;   // belt-and-braces self filter

            // Decoded enemy height when plausible, else own ground level.
            double eh = (sane(eZ) && Math.abs(eZ - pz) < HEIGHT_REL_MAX) ? eZ : ownY;

            // Captured BEFORE the smoothing filter, for the aim.
            final double rawAx = ax, rawAz = az, rawEh = eh;

            // ---- RENDER SMOOTHING ----
            // Follow the predicted position exponentially instead of
            // snapping, so each 198 ms correction does not show up as a jump.
            // Frame-rate independent: the coefficient is derived from the
            // real elapsed time, so it behaves identically at 30 and 120 fps.
            int id = key;
            if (EspConfig.SMOOTH_MS > 1f && id >= 0 && id < SMOOTH_SLOTS) {
                long dtMs = now - smMs[id];
                double snap = SMOOTH_SNAP_M * UNITS_PER_METER;
                if (smMs[id] == 0 || dtMs > 1000
                        || Math.abs(ax - smX[id]) > snap
                        || Math.abs(az - smY[id]) > snap) {
                    smX[id] = ax; smY[id] = az; smZ[id] = eh;
                } else if (dtMs > 0) {
                    double k = 1.0 - Math.exp(-dtMs / (double) EspConfig.SMOOTH_MS);
                    smX[id] += (ax - smX[id]) * k;
                    smY[id] += (az - smY[id]) * k;
                    smZ[id] += (eh - smZ[id]) * k;
                }
                smMs[id] = now;
                ax = smX[id]; az = smY[id]; eh = smZ[id];
            }

            // The aim's base: mapped like the box, but pre-smoothing. Written
            // from the locals captured before the EspConfig.SMOOTH_MS filter overwrote
            // ax/az/eh -- see enemyAimBaseX.
            enemyAimBaseX[n] = (float) rawAx;
            enemyAimBaseZ[n] = (float) rawAz;
            enemyAimBaseH[n] = (float) rawEh;
            // Velocity through the SAME axis map as the position. Mapping the
            // position and not the velocity would make the lead point in a
            // different direction from the target's motion on 4 of the 8
            // EspConfig.VIEW_MODE settings, and be silently correct on the other 4.
            {
                double mv1 = EspConfig.ENEMY_NEG_AXIS1 ? -t.vx : t.vx;
                double mv2 = EspConfig.ENEMY_NEG_AXIS2 ? -t.vy : t.vy;
                enemyVX[n] = (float) (EspConfig.ENEMY_AXIS_SWAP ? mv2 : mv1);
                enemyVZ[n] = (float) (EspConfig.ENEMY_AXIS_SWAP ? mv1 : mv2);
                enemyVY[n] = (float) t.vz;
                enemyVelValid[n] = t.velValid;
            }
            enemyWX[n] = (float) ax;             // world x
            enemyWZ[n] = (float) az;             // world z (Unity Y-up)
            enemyH[n] = (float) eh;
            enemyDistM[n] = distM;
            enemyId[n] = key;
            enemyRawX[n] = t.x;
            enemyRawUp[n] = t.z;
            enemyRawY[n] = t.y;
            enemyRole[n] = role;
            enemyTeamKnown[n] = t.teamKnown;
            enemyStillMs[n] = (t.lastMoveMs == 0L) ? 0L : (now - t.lastMoveMs);
            enemyPoseEnum[n] = t.state;
            enemyModeEnum[n] = t.mode;
            enemyInVehicle[n] = t.inVehicle;
            enemyAnchorU[n] = (float) (com.nepdon.wire.ui.aim.Posture
                    .anchorM(t.state) * UNITS_PER_METER);
            enemyHp[n] = t.hp;
            enemyName[n] = esp.roster().name(key);
            enemyRealHp[n] = esp.pri().hpFor(key, now);
            enemyMaxHp[n] = esp.pri().maxHpFor(key);
            enemyAgeMs[n] = age;
            enemyPose[n] = t.poseValid;
            enemyBodyYaw[n] = t.bodyYawDeg;
            enemyAimYaw[n] = t.aimYawDeg;
            enemyAimPitch[n] = t.aimPitchDeg;
            n++;
        }
        enemyCount = n;
        // Now that n is known. Both the boxes and the radar dots are driven
        // from this one array, which is why the two always fail together.
        if (n > 0) com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_DREW_BOX, n);
        else com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_DREW_NOTHING);

        // Auto-scale the radar to contain every drawn entity (plus a 15%
        // margin), clamped to [EspConfig.RADAR_MIN_RANGE_M, EspConfig.RADAR_MAX_RANGE_M].
        float far = 0f;
        for (int i = 0; i < n; i++) far = Math.max(far, enemyDistM[i]);
        float want = far * 1.15f;
        if (want < EspConfig.RADAR_MIN_RANGE_M) want = EspConfig.RADAR_MIN_RANGE_M;
        if (want > EspConfig.RADAR_MAX_RANGE_M) want = EspConfig.RADAR_MAX_RANGE_M;
        // Ease toward the target so the scale does not jitter frame to frame.
        radarRangeM += (want - radarRangeM) * 0.15f;
    }

    private static boolean sane(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) < 1e9;
    }

    /**
     * ONE compact line for the trimmed debug panel: how many boxes are being
     * drawn, the <b>effective</b> horizontal FOV at the real viewport aspect,
     * and which own-state source the camera basis is running on.
     *
     * <p>The hFOV printed here is the one the projection actually uses, not the
     * raw {@code EspConfig.FOV_X_DEG} setting — {@code EspConfig.FOV_X_DEG} is interpreted as the
     * hFOV at a 16:9 reference and re-derived from the true aspect (90° becomes
     * ~103° on this 2.17:1 panel). Any aim math that converts screen pixels to
     * degrees needs <i>this</i> number; printing the setting instead is how you
     * end up calibrating a swipe against a FOV the renderer never used.</p>
     *
     * <p>The long calibration readout is still {@link #statusLine()} — it was
     * not deleted, just unhooked from the panel. Re-add it there when doing
     * camera calibration work.</p>
     */
    public String briefLine() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("\nESP   draw=").append(enemyCount);
        if (vpW > 0 && vpH > 0) {
            // FROM THE LIVE TANGENTS, not recomputed from a subset of the
            // inputs. This line used to ignore the zoom entirely and print an
            // hFOV the renderer was not using.
            float tx = LIVE_TANX > 1e-6f ? LIVE_TANX : 1e-6f;
            float ty = LIVE_TANY > 1e-6f ? LIVE_TANY : 1e-6f;
            sb.append(String.format(" fov=%.1f/%.1f°",
                    2.0 * Math.toDegrees(Math.atan(tx)),
                    2.0 * Math.toDegrees(Math.atan(ty))));
            if (Math.abs(LIVE_ZOOMX - 1f) > 0.01f
                    || Math.abs(LIVE_ZOOMY - 1f) > 0.01f) {
                sb.append(String.format(" x%.2f/%.2f", LIVE_ZOOMX, LIVE_ZOOMY));
            }
            if (SEP_ID1 >= 0 && SEP_ID2 >= 0) {
                // The measurement: compare this gap with the gap between the
                // game's own two markers for the same pair.
                sb.append(String.format(" sep=%.0fpx(e%d-e%d)",
                        Math.abs(SEP_X2 - SEP_X1), SEP_ID1, SEP_ID2));
            }
            // PAN RATE AND SAMPLE AGE -- the instrument that makes a screenshot
            // certify itself. Every static calibration here (zoom, boom,
            // shoulder) is only valid on a SETTLED view, because a stale basis
            // slides every box together by rate x age, and a scope multiplies
            // that by the zoom. If pan= is non-zero the frame is not a rig
            // measurement, and without this row there is no way to tell that
            // afterwards -- which is how every previous reading of this symptom
            // ended up ambiguous.
            float pan = lastPanDegSec;
            if (pan >= 0f) {
                long age = lastCamAgeMs < 0L ? 0L : lastCamAgeMs;
                sb.append(String.format(" pan=%.0f°/s age=%dms lag=%.0fpx",
                        pan, age,
                        (double) LIVE_PXDEG * pan * (age * 0.001)));
            }
            if (CAL_BOX_ID >= 0) {
                // The zoom measurement that assumes nothing about where the
                // centre is: box height against the character's own height in
                // the same screenshot. trueZoom = zoom * charPx / boxPx.
                sb.append(String.format(" box=%.0fpx@%.0fm(e%d)",
                        CAL_BOX_H_PX, CAL_BOX_DIST_M, CAL_BOX_ID));
            }
            if (!vpExact) sb.append(" INSET");
        } else {
            sb.append(" vp?");
        }
        if (!hasState) return sb.append("  (no view state)").toString();
        return sb.append("  ").append(lastOwnSource).toString();
    }

    /** Short status lines for the debug panel (cheap; called ~2 Hz). */
    public String statusLine() {
        StringBuilder sb = new StringBuilder(96);
        if (hasState) {
            sb.append(String.format("\nVIEW fold=%.1f\u00b0 full=%.1f\u00b0%s src=%s",
                    foldedDeg, Math.toDegrees(theta), thetaKnown ? "" : "?", oriSource))
              .append(String.format(" pitch=%.1f\u00b0", Math.toDegrees(pitch)))
              .append(String.format(" FOV=%.0f\u00b0 x%.0f map=%s",
                      EspConfig.FOV_X_DEG, EspConfig.FOV_ZOOM, axisMapCode()))
              .append(String.format("\nCAL boom=%.1f piv=%.2f sh=%.2f fovY=%.2f off=%.0f,%.0f",
                      EspConfig.TPS_BOOM_M, EspConfig.TPS_PIVOT_H_M, EspConfig.TPS_SHOULDER_M,
                      EspConfig.FOV_Y_SCALE, EspConfig.OFFSET_X_PX, EspConfig.OFFSET_Y_PX))
              // Loud by design. An overlay inset from the game's viewport is
              // indistinguishable from a wrong FOV on screen, so the two are
              // printed side by side: "exact" means our window IS the game's
              // surface, anything else means the projection is compensating.
              // Mounted runs on a degraded own-state source; say so rather
              // than let a quantised, stale basis look like a calibration bug.
              .append('\n').append(lastOwnSource)
              .append('\n').append(lastLayoutSource)
              .append('\n').append(viewportHint())
              .append(com.nepdon.wire.config.EspTuning.cameraWasMigrated()
                      ? "  [cam recalibrated]" : "")
              // Say plainly whether ANYTHING is predicting. "LEAD 0ms" alone
              // was misleading twice over: the target still advances by the
              // sample's own age, and the camera basis had its own lead.
              .append(EspConfig.EXTRAPOLATE
                      ? String.format("\nLEAD %.0fms %s smooth=%.0fms",
                              lastLeadMs,
                              EspConfig.AUTO_LEAD ? (lastLeadEcho ? "(echo)" : "(rtt)") : "(manual)",
                              EspConfig.SMOOTH_MS)
                      : String.format("\nPREDICT OFF (stick) smooth=%.0fms"
                              + " — box sits ~%.0fms behind", EspConfig.SMOOTH_MS, lastMeasLagMs))
              .append(EspConfig.EXTRAPOLATE
                      ? String.format("\nCAMX lead=%.0fms applied=%.1f° cap=%.0f°",
                              EspConfig.CAM_EXTRAP_MS, lastCamExtrapDeg, EspConfig.CAM_EXTRAP_MAX_DEG)
                      : "\nCAMX off (follows EXTRAPOLATE)")
              .append(String.format("\nESP draw=%d radar=%s",
                      enemyCount, EspConfig.SHOW_RADAR ? "on" : "off"));
        } else {
            sb.append("\nVIEW -- (no view state)");
        }
        return sb.toString();
    }

    /** Human-readable name of a view mapping mode. */
    public static String viewModeName(int m) {
        String a = ((m & 4) != 0) ? "dz" : "dx";
        String b = ((m & 4) != 0) ? "dx" : "dz";
        return "(" + (((m & 2) != 0) ? "-" : "+") + a + ","
                   + (((m & 1) != 0) ? "-" : "+") + b + ")";
    }

    /** Compact code for the current enemy-axis mapping, for the debug panel. */
    public static String axisMapCode() {
        return (EspConfig.ENEMY_AXIS_SWAP ? "SWAP" : "ID")
                + (EspConfig.ENEMY_NEG_AXIS1 ? ",-A1" : "")
                + (EspConfig.ENEMY_NEG_AXIS2 ? ",-A2" : "")
                + (EspConfig.MIRROR_X ? ",mir" : "");
    }

    // ====================================================================
    // Drawing
    // ====================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasState) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;


        // ---- view basis (approved design; see class javadoc) ----
        // f = (sin(theta)cos(phi), sin(phi), cos(theta)cos(phi))
        // r = f x up_world, u = r x f      (up_world = (0,1,0))
        if (fwdValid) {
            fX = fwdX; fY = fwdY; fZ = fwdZ;
        } else {
            float st = (float) Math.sin(theta);
            float ct = (float) Math.cos(theta);
            float sp = (float) Math.sin(pitch);
            float cp = (float) Math.cos(pitch);
            fX = st * cp; fY = sp; fZ = ct * cp;
        }
        // SIGN TRAP -- READ BEFORE "FIXING" EITHER HALF.
        //
        // Unity's right vector is cross(up, f) = (fZ, 0, -fX). What is written
        // here is its NEGATION, and EspConfig.MIRROR_X (default true, xSign = -1 in the
        // projection) negates it back. The pair is self-cancelling and the net
        // result is correct -- "correcting" one without the other silently
        // mirrors the whole ESP.
        //
        // Confirmed from the binary 2026-09-13: the outbound Cmd 2001 packer
        // (KANJBNIANHC::JMELIEOFFHJ @ 0x0577E1D8) is a plain float32 copy of
        // Quaternion * Vector3.forward -- stp s10,s9 / str s8, nothing else. No
        // negation, no axis swap, no scale. The wire vector IS Unity world
        // space (left-handed, Y-up, x/y/z), so the identity is the right
        // answer and the 8-way EspConfig.VIEW_MODE search was only ever compensating for
        // this local sign convention.
        rX = -fZ;     rY = 0f;  rZ = fX;
        uX = -fX * fY; uY = fX * fX + fZ * fZ; uZ = -fZ * fY;
        // Normalize r and u (already unit for phi=0).
        float rLen = (float) Math.sqrt(rX * rX + rY * rY + rZ * rZ);
        float uLen = (float) Math.sqrt(uX * uX + uY * uY + uZ * uZ);
        if (rLen < 1e-6f || uLen < 1e-6f) return;
        rX /= rLen; rY /= rLen; rZ /= rLen;
        uX /= uLen; uY /= uLen; uZ /= uLen;

        // Third-person boom: move the projection origin from the character to
        // where the camera actually sits — up to the pivot, then back along
        // the view direction. Without this the whole scene shears whenever the
        // view rotates, because the real camera is orbiting the character.
        // Aiming down sights plausibly pulls the camera off the third-person
        // boom and onto the weapon. We do not know that it does, so this is a
        // switch that is OFF by default and changes nothing until thrown --
        // measuring the zoom and the rig in the same session would make both
        // unreadable.
        // Scoped Free Fire is FIRST PERSON -- the view sits at the optic, not on
        // the third-person boom. The rig is therefore read from the scoped
        // overrides while the scope is up, and they are separate knobs because
        // the two terms have opposite signatures: the boom is zero on-axis and
        // grows with angle, the shoulder is constant across the whole screen.
        // ADS REPLACES THE PIVOT; IT DOES NOT MOVE THE BOOM.
        //
        // FollowCamera::AHCBLOCABDP @ 0x0722519c branches on IsSighting and
        // jumps straight to `pivot = Player::get_AimStartPostion` (a plain
        // Entity.Position + up*k), skipping the whole shoulder/height block.
        // The boom is the SAME 2.50 m in both states -- the only ADS-specific
        // boom change is an extra wall linecast that shortens it near geometry
        // and is a no-op in the open.
        //
        // So: pivot and shoulder change on ADS, boom does not. An earlier guess
        // that ADS pulls the camera onto the weapon (boom 0) is refuted.
        // The scope rig (boom/pivot/shoulder) was calibrated together with the scope
        // zoom, so it only applies when that zoom is on. With zoom off the scoped
        // view uses the hip rig: a rig with no zoom behind it shifts every box.
        final boolean adsRig = EspConfig.AUTO_SCOPE_ZOOM
                && com.nepdon.wire.protocol.ScopeState.isScoped();
        float boom = (adsRig ? EspConfig.SCOPE_BOOM_M : EspConfig.TPS_BOOM_M) * (float) UNITS_PER_METER;
        float pivot = (adsRig ? EspConfig.SCOPE_PIVOT_M : EspConfig.TPS_PIVOT_H_M)
                * (float) UNITS_PER_METER;
        float shoulder = (adsRig ? EspConfig.SCOPE_SHOULDER_M : EspConfig.TPS_SHOULDER_M)
                * (float) UNITS_PER_METER;
        eyeX = ownX - fX * boom + rX * shoulder;
        eyeY = ownY + pivot - fY * boom + rY * shoulder;
        eyeZ = ownZ - fZ * boom + rZ * shoulder;
        eyeValid = true;

        // ---- projection constants (P from FOV + aspect) ----
        // Built from the GAME's viewport (the display), then translated into
        // this view's coordinates -- see refreshViewport(). Using the view's
        // own w/h here is what let a nav-bar or cutout inset masquerade as an
        // FOV error: zero at the crosshair, growing with angle from it.
        refreshViewport(w, h);
        float halfW = vpW * 0.5f;
        float halfH = vpH * 0.5f;
        // SCOPE IS READ ONCE PER FRAME, here, and reused by the optic centre,
        // the zoom, the box scale, the aim gate and the reticle. The answer
        // cannot change inside a frame and a second read of a volatile is pure
        // cost.
        final boolean scoped = com.nepdon.wire.protocol.ScopeState.isScoped();
        float cx = halfW - vpOriginX;
        float cy = halfH - vpOriginY;
        // A PURE TRANSLATION, not a centre of expansion. scratchX is
        // cx + (a term with no cx in it), so this moves every point by the same
        // number of pixels at any zoom and any angle -- it cannot fix an error
        // that is zero at the crosshair and grows off-axis, and trying will
        // break the crosshair. See the EspConfig.SCOPE_OFF_X_PX javadoc.
        if (scoped && EspConfig.AUTO_SCOPE_ZOOM) {
            cx += EspConfig.SCOPE_OFF_X_PX;
            cy += EspConfig.SCOPE_OFF_Y_PX;
        }
        // Zoom divides the tangent, which is the same as multiplying the
        // projection scale K = 1/tan(fov/2) by the zoom factor.
        //
        // SCOPE IS READ ONCE PER FRAME, here, and reused below for the aim gate
        // and the reticle: the answer cannot change inside a frame and a second
        // read of a volatile is pure cost.
        //
        // The scoped zoom is DERIVED, never stored. Writing EspConfig.FOV_ZOOM on a scope
        // edge would leave it latched past the scope that set it — it persists
        // under esp_fov_zoom, survives match end and app restart, and is not in
        // CAM_KEYS or reset(). That is the "flag that latches once and is never
        // cleared" shape this codebase keeps relearning. Deriving it costs one
        // branch and cannot be left in the wrong state.
        // One switch (AUTO_SCOPE_ZOOM) decides whether the scope changes the projection.
        // Off: scoped is the hip projection. On: zoom and rig are applied together.
        float zoomWant = (EspConfig.AUTO_SCOPE_ZOOM && scoped) ? EspConfig.SCOPED_ZOOM : EspConfig.FOV_ZOOM;
        float zoom = (zoomWant > 0.05f) ? zoomWant : 1f;
        // HOR+ aspect model. Free Fire (like most mobile shooters) keeps the
        // VERTICAL FOV fixed and widens the horizontal FOV as the screen gets
        // wider — so a fixed horizontal FOV is only correct at one aspect and
        // drifts off-centre on every other. EspConfig.FOV_X_DEG is therefore interpreted
        // as the horizontal FOV at a 16:9 reference: derive the implied (aspect-
        // independent) vertical FOV from it, then re-derive the ACTUAL horizontal
        // FOV from the real screen aspect. On 16:9 this is byte-identical to the
        // old fixed-90 maths; on an ultrawide (e.g. 2.17:1) it correctly widens
        // horizontal (~90 -> ~103 deg), which is what fixes the right-drift of
        // off-centre / distant targets.
        float tanHalfV = tanHalfV();                            // implied vertical half-tan @16:9
        // ---- HORIZONTAL AND VERTICAL ARE ZOOMED SEPARATELY WHILE SCOPED ----
        //
        // A single zoom assumes the scope magnifies BOTH axes by the same
        // factor, which is true only if the scoped view is still HOR+. A
        // rendered optic is round: it magnifies isotropically about its own
        // axis, and the "vertical FOV fixed, horizontal widens with the screen
        // aspect" rule need not survive it. If it does not, our horizontal
        // tangent carries an extra factor of aspect (~2.17 here) that the game
        // does not, and no single zoom can correct both axes at once.
        //
        // THE MEASUREMENT: set SCOPED ZOOM until the box sits at the right
        // HEIGHT, then set SCOPE X until it sits at the right SIDEWAYS
        // position. If SCOPE X lands on 1.0 the scoped view is HOR+ after all.
        // If it lands near the aspect ratio, it is not, and that number is the
        // answer.
        float zoomY = zoom;
        float zoomX = (EspConfig.AUTO_SCOPE_ZOOM && scoped) ? zoom * EspConfig.SCOPE_X_EXTRA : zoom;
        float aspect = (float) vpW / (float) vpH;
        if (EspConfig.AUTO_SCOPE_ZOOM && scoped && EspConfig.SCOPE_SQUARE) {
            // The non-HOR+ case: the optic is square, so the horizontal tangent
            // is the vertical one, not the vertical times the screen aspect.
            aspect = 1f;
        }
        float tanHalfY = (tanHalfV * EspConfig.FOV_Y_SCALE) / zoomY;      // vertical, trimmed + zoomed
        // Aspect comes from the GAME viewport, never from our window bounds.
        float tanHalfX = (tanHalfV * aspect) / zoomX;
        // Publish what is ACTUALLY in use. The HUD used to derive its hFOV from
        // EspConfig.FOV_X_DEG alone and to print only EspConfig.FOV_ZOOM -- so while scoped it
        // displayed a field of view the renderer was not using, and EspConfig.SCOPED_ZOOM
        // never appeared at all. A readout that cannot disagree with reality is
        // the one you end up calibrating against, which is this project's
        // oldest failure mode wearing a different hat.
        LIVE_TANX = tanHalfX;
        LIVE_TANY = tanHalfY;
        LIVE_ZOOMX = zoomX;
        LIVE_ZOOMY = zoomY;
        LIVE_PXDEG = (float) (halfH / tanHalfY * Math.PI / 180.0);
        float xSign = EspConfig.MIRROR_X ? -1f : 1f;

        float boxHUnits = (float) (BOX_H_M * UNITS_PER_METER);
        float distScale = (float) (BOX_W_M / BOX_H_M) * 0.5f;

        // ---- AIM TARGET PICK (shares this exact projection; see ui/aim/AimTarget) ----
        com.nepdon.wire.ui.aim.AimTarget.beginFrame();
        SEP_ID1 = -1; SEP_ID2 = -1; CAL_BOX_ID = -1;
        float sepD1 = Float.MAX_VALUE, sepD2 = Float.MAX_VALUE;
        // Scoped-only gate, evaluated ONCE per frame rather than per entity: the
        // answer cannot change inside a frame and a per-entity read of a
        // volatile is pure cost.
        // Self-mounted overrides everything: no target is eligible while our
        // own basis is frozen.
        // The self-mounted refusal is skipped in TEST TRACK too: it fired 1.1
        // times a second on a player walking around on foot, and a gate that
        // noisy makes the swipe impossible to judge.
        final boolean aimScoped = (!EspConfig.AIM_SCOPE_ONLY || scoped)
                && (com.nepdon.wire.ui.aim.AimConfig.AIM_TEST_TRACK
                    || !com.nepdon.wire.ui.aim.AimTarget.selfMounted);
        int aimBest = -1;
        float aimBestErr = Float.MAX_VALUE;
        float aimBestHx = 0f, aimBestHy = 0f, aimBestYaw = 0f, aimBestPitch = 0f;
        final long aimNow = System.currentTimeMillis();

        // NOT gated on EspConfig.SHOW_BOXES. The aim target pick lives in this loop, and
        // gating the loop on a DISPLAY toggle meant turning the boxes off also
        // turned off acquisition -- silently, and indistinguishably from "no
        // enemies nearby", because AimTarget.id simply stayed -1. Drawing is
        // gated further down instead; everything above that point is
        // computation the aim and the calibration readouts need.
        for (int i = 0; i < enemyCount; i++) {
            float ex = enemyWX[i];
            float ez = enemyWZ[i];
            float groundY = enemyH[i];

            // Project head FIRST, because the head decides the glass branch for
            // the whole entity: it is the anchor the box is drawn from and the
            // point the aim steers at, so if one end has to be right it is that
            // one. Everything else for this entity is then pinned to it.
            boolean headVis = project(ex, groundY + boxHUnits, ez, tanHalfX, tanHalfY,
                    cx, cy, halfW, halfH, xSign);
            float headX = scratchX, headY = scratchY;
            // Every scratch value belonging to the HEAD is copied out here,
            // before the next project() call overwrites all of them. The
            // bearing is what the aim steers on; taking it one line later takes
            // the FEET's bearing instead, silently, and the boxes still look
            // right.
            final float headYawDeg = scratchYawDeg, headPitchDeg = scratchPitchDeg;
            boolean feetVis = project(ex, groundY, ez, tanHalfX, tanHalfY,
                    cx, cy, halfW, halfH, xSign);
            float feetX = scratchX, feetY = scratchY;
            if (!feetVis || !headVis) {
                // Was a bare continue -- the exact shape of the mounted-ESP
                // blackout, where one `continue` placed a line early made a
                // whole feature vanish in silence. The slot existed and had
                // never been written.
                com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_BEHIND_CAMERA);
                continue;
            }

            // ---- THE AIM ANCHOR: THE DRAWN BOX, AND NOTHING ELSE ----
            //
            // Same ex, ez, groundY and boxHUnits the box above is drawn from,
            // so "aim at the box" is literally true rather than approximately
            // true. If the aim looks off, the box is off -- there is no second
            // pipeline that can disagree with what is on screen.
            //
            // This replaced an independent anchor that differed from the box in
            // three ways at once: a posture-modelled capsule height, a velocity
            // lead, and its own projection. Each was individually defensible
            // and together they put the aim point somewhere the player could
            // not see, with no way to tell which of the three was wrong. A
            // target you can see is worth more than a target you modelled.
            //
            // The lead is GONE, not merely zeroed. It was carrying the target
            // forward by AIM_PIPELINE_MS on a velocity that the same capture
            // shows is not what it was assumed to be: a stationary player on
            // the ground reports vel.y = -374 (gravity, continuously
            // integrated) with vel.x = vel.z = 0, so "velocity" is not a clean
            // is-moving signal and leading on it displaces a standing target
            // downward for no reason.
            float aimAncX = 0f, aimAncY = 0f, aimAncYaw = 0f, aimAncPitch = 0f;
            final int poseVerdict = com.nepdon.wire.ui.aim.Posture.verdict(
                    enemyPoseEnum[i], enemyModeEnum[i], enemyInVehicle[i]);
            if (project(ex, groundY + boxHUnits * EspConfig.AIM_BOX_FRAC, ez,
                    tanHalfX, tanHalfY, cx, cy, halfW, halfH, xSign)) {
                aimAncX = scratchX; aimAncY = scratchY;
                aimAncYaw = scratchYawDeg; aimAncPitch = scratchPitchDeg;
            } else {
                aimAncX = -1f; aimAncY = -1f;   // fails the on-screen gate
            }

            // ---- aim eligibility, evaluated on the SAME projected head ----
            // Head anchor: box top (ground + 1.8 m). Chosen over the pose-ray's
            // 0.92x because the box top is what the user actually sees and aims
            // at, and it is the anchor the ESP box is drawn from -- so "aim at
            // the box" means exactly that. (The renderer has four anchors: 1.0
            // box top, 0.92 pose head, 0.55 pose chest, 0.9 MarkTarget chest.)
            com.nepdon.wire.ui.aim.AimTarget.drawn++;
            if (enemyRole[i] == com.nepdon.wire.esp.EspEngine.ROLE_ENEMY) {
                com.nepdon.wire.ui.aim.AimTarget.enemies++;
                boolean onScr = !(aimAncX < 0f || aimAncY < 0f
                        || aimAncX > w || aimAncY > h);
                if (onScr) com.nepdon.wire.ui.aim.AimTarget.onScreen++;
                float angAll = Math.abs(aimAncYaw) + Math.abs(aimAncPitch);
                if (onScr && angAll <= EspConfig.AIM_CONE_DEG) com.nepdon.wire.ui.aim.AimTarget.inCone++;
                if (com.nepdon.wire.ui.aim.AimTarget.bestAngDeg < 0f || angAll < com.nepdon.wire.ui.aim.AimTarget.bestAngDeg) {
                    com.nepdon.wire.ui.aim.AimTarget.bestAngDeg = angAll;
                }
                com.nepdon.wire.ui.aim.AimTarget.seen++;
                if (!aimScoped) {
                    com.nepdon.wire.ui.aim.AimTarget.rejectedNoScope++;
                } else if (com.nepdon.wire.ui.aim.AimConfig.AIM_TEST_TRACK) {
                    // TEST TRACK: straight to the cone check. Deliberately no
                    // team / still / age / posture gate -- see AIM_TEST_TRACK.
                    float ang = Math.abs(aimAncYaw) + Math.abs(aimAncPitch);
                    if (aimAncX < 0f || aimAncY < 0f || aimAncX > w || aimAncY > h) {
                        com.nepdon.wire.ui.aim.AimTarget.rejectedOffScreen++;
                    } else if (enemyDistM[i] > EspConfig.AIM_MAX_RANGE_M) {
                        com.nepdon.wire.ui.aim.AimTarget.rejectedRange++;
                    } else if (ang > EspConfig.AIM_CONE_DEG) {
                        com.nepdon.wire.ui.aim.AimTarget.rejectedCone++;
                    } else if (ang < aimBestErr) {
                        aimBestErr = ang; aimBest = i;
                        aimBestHx = aimAncX; aimBestHy = aimAncY;
                        aimBestYaw = aimAncYaw; aimBestPitch = aimAncPitch;
                    }
                } else if (!enemyTeamKnown[i]) {
                    // THE GATE. "ENEMY" is also what "no team data" looks like,
                    // because the only rung that may say TEAM needs Cmd 118/101,
                    // both reliable and therefore encrypted. Refuse rather than
                    // risk a teammate. See AimTarget's class comment.
                    com.nepdon.wire.ui.aim.AimTarget.rejectedNoTeam++;
                } else if (enemyAgeMs[i] > com.nepdon.wire.ui.aim.AimConfig.AIM_MAX_SRC_AGE_MS) {
                    // The record is too old to predict through. ~37% of
                    // wall-clock time an entity has no fresh record at all and
                    // the p99 dropout is 1782 ms, over which a moving player
                    // travels a median 7.2 m.
                    com.nepdon.wire.ui.aim.AimTarget.rejectedStaleSrc++;
                    com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_STALE_SRC);
                } else if (poseVerdict != com.nepdon.wire.ui.aim.Posture.OK) {
                    // POSTURE. An allow-list, so an unknown byte refuses rather
                    // than falling through to STANDING -- the cost of a wrong
                    // refusal is a missed shot, the cost of a wrong aim is
                    // swinging the view at nothing.
                    switch (poseVerdict) {
                        case com.nepdon.wire.ui.aim.Posture.REFUSE_KNOCKED:
                            com.nepdon.wire.ui.aim.AimTarget.rejectedKnocked++;
                            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_KNOCKED); break;
                        case com.nepdon.wire.ui.aim.Posture.REFUSE_AIRBORNE:
                            com.nepdon.wire.ui.aim.AimTarget.rejectedAirborne++;
                            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_AIRBORNE); break;
                        case com.nepdon.wire.ui.aim.Posture.REFUSE_SWIM:
                            com.nepdon.wire.ui.aim.AimTarget.rejectedSwim++;
                            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_SWIM); break;
                        case com.nepdon.wire.ui.aim.Posture.REFUSE_MOUNTED:
                            com.nepdon.wire.ui.aim.AimTarget.rejectedMounted++;
                            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_TARGET_MOUNTED); break;
                        default:
                            com.nepdon.wire.ui.aim.AimTarget.rejectedPose++;
                            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AIM_REFUSE_POSE); break;
                    }
                } else if (enemyStillMs[i] < EspConfig.AIM_STILL_MS) {
                    com.nepdon.wire.ui.aim.AimTarget.rejectedMoving++;
                } else if (enemyDistM[i] > EspConfig.AIM_MAX_RANGE_M) {
                    com.nepdon.wire.ui.aim.AimTarget.rejectedRange++;
                } else if (aimAncX < 0f || aimAncY < 0f || aimAncX > w || aimAncY > h) {
                    com.nepdon.wire.ui.aim.AimTarget.rejectedOffScreen++;
                } else {
                    // Angular error, not screen distance: screen distance lies
                    // under a scope, where the same pixel is a much smaller
                    // angle. Taken straight from the camera basis rather than
                    // recovered by inverting the screen position -- see
                    // scratchYawDeg. The old form was numerically identical
                    // AND depended on the drawing maths to stay that way.
                    // THE AIM ANCHOR, not the box top. The box is drawn from
                    // ground+1.8 m because that is what the user sees; the aim
                    // steers at the neck base of a posture-aware capsule, which
                    // is a different point and must not silently inherit a
                    // cosmetic change to BOX_H_M.
                    float yawE = aimAncYaw;
                    float pitchE = aimAncPitch;
                    float ang = Math.abs(yawE) + Math.abs(pitchE);
                    if (ang > EspConfig.AIM_CONE_DEG) {
                        // Was unnamed: a live panel read "seen=4 moving=1" and
                        // left 3 unexplained. Every rejection gets a counter.
                        com.nepdon.wire.ui.aim.AimTarget.rejectedCone++;
                    } else if (ang < aimBestErr) {
                        aimBestErr = ang; aimBest = i;
                        aimBestHx = aimAncX; aimBestHy = aimAncY;
                        aimBestYaw = yawE; aimBestPitch = pitchE;
                    }
                }
            }

            // Two nearest drawn entities, for the centre-free zoom measurement.
            // Cheap: two compares on a loop that is already running.
            //
            // The branch is recorded with each: a pair with one target inside
            // the glass and one outside spans a magnification step, so its gap
            // answers no question at all. Without the tag that pair is
            // indistinguishable from a good one and quietly yields a wrong zoom.
            if (SEP_ID1 < 0 || enemyDistM[i] < sepD1) {
                sepD2 = sepD1; SEP_X2 = SEP_X1; SEP_ID2 = SEP_ID1;
                sepD1 = enemyDistM[i]; SEP_X1 = headX; SEP_ID1 = enemyId[i];
            } else if (SEP_ID2 < 0 || enemyDistM[i] < sepD2) {
                sepD2 = enemyDistM[i]; SEP_X2 = headX; SEP_ID2 = enemyId[i];
            }

            float boxH = feetY - headY;              // > 0 (feet below head)
            // SIZE IS SCALED SEPARATELY FROM POSITION.
            //
            // boxH is the gap between two PROJECTED points, so it carries the
            // zoom divide exactly as the position does -- at 8x a box is 8x
            // taller. That is only correct if the scope genuinely magnifies the
            // whole camera. If it does not (see EspConfig.SCOPED_ZOOM), size and position
            // need different factors, and one knob cannot express that. This
            // one exists to find out which: set SCOPED ZOOM by where the box
            // SITS, then set this by how BIG it is. If they converge on the
            // same number the scope is a camera zoom; if they do not, it is
            // not, and the difference is the measurement.
            if (scoped) boxH *= EspConfig.SCOPE_BOX_SCALE;
            if (boxH < 2f) boxH = 2f;                  // far away: keep visible
            // Publish the nearest box's height and range for the zoom
            // measurement. Before the 2f floor would matter, and before the
            // clamp below, so the number printed is the number drawn.
            if (enemyId[i] == SEP_ID1) {
                CAL_BOX_H_PX = boxH;
                CAL_BOX_DIST_M = enemyDistM[i];
                CAL_BOX_ID = enemyId[i];
            }
            // ---- DRAWING FROM HERE. Everything above is computation. ----
            if (!EspConfig.SHOW_BOXES) continue;

            float centerX = (feetX + headX) * 0.5f;
            float halfBoxW = Math.max(2f, boxH * distScale);

            float left = centerX - halfBoxW;
            float top = headY;
            float right = centerX + halfBoxW;
            float bottom = headY + boxH;

            // No screen-space smoothing here on purpose. A box is the projection and
            // nothing else: a filter on the rect held it stale for up to 350 ms during
            // a pan and then snapped it, and its EMA trailed the projection by up to
            // ~110 ms, which is also what the aim cursor reads.

            // Refresh the enemy-box paints if the user changed the colour
            // picker since the last frame. setColor is a plain int write with
            // no allocation; the guard avoids even that cost on every entity.
            int currentBoxColor = EspConfig.BOX_COLOR_ARGB;
            if (currentBoxColor != lastBoxColor) {
                lastBoxColor = currentBoxColor;
                int r = Color.red(currentBoxColor);
                int g = Color.green(currentBoxColor);
                int b = Color.blue(currentBoxColor);
                boxFill.setColor(Color.argb(26, r, g, b));
            }

            int role = enemyRole[i];
            if (role == EspEngine.ROLE_TEAM) {
                boxStroke.setColor(TEAM_STROKE_COLOR);
                labelPaint.setColor(TEAM_LABEL_COLOR);
            } else if (role == EspEngine.ROLE_UNKNOWN) {
                boxStroke.setColor(UNK_STROKE_COLOR);
                labelPaint.setColor(UNK_LABEL_COLOR);
            } else {
                boxStroke.setColor(currentBoxColor);
                labelPaint.setColor(currentBoxColor);
            }
            // Confidence fade. A box whose data is stale is still worth
            // drawing, but it should not look as certain as a fresh one -
            // past EXTRAP_STOP_MS it is frozen at the last known position
            // rather than predicted, and the target may be metres away.
            int alpha = 255;
            long a = enemyAgeMs[i];
            if (a > EXTRAP_STOP_MS) alpha = 90;
            else if (a > EXTRAP_FADE_MS) alpha = 160;
            if (alpha != 255) {
                boxStroke.setAlpha(alpha);
                labelPaint.setAlpha(alpha);
            }

            // ---- ESP LINE ----
            // Drawn BEFORE the box so the box sits on top.
            if (EspConfig.SHOW_ESP_LINE) {
                espLinePaint.setColor(currentBoxColor);
                espLinePaint.setAlpha(alpha < 255 ? (int)(alpha * 0.7f) : 160);
                canvas.drawLine(cx, h, feetX, feetY, espLinePaint);
            }

            // ---- CORNER-BRACKET BOX (EspBox) ----
            espBox.draw(canvas, left, top, right, bottom, role, alpha, currentBoxColor);

            // ---- HEALTH BAR ----
            // Drawn only from REAL health (Cmd 1900). The estimate is a lower
            // bound built from our own hits, so a bar fed by it would read
            // "full" for every enemy we happen not to have shot -- a bar that
            // lies is worse than no bar, because you cannot tell the two apart
            // at a glance the way you can with a number.
            int realHp = enemyRealHp[i];
            if (EspConfig.HP_BAR && realHp >= 0) {
                int maxHp = enemyMaxHp[i];
                if (maxHp < realHp) maxHp = (realHp > EspEngine.MAX_HP)
                        ? realHp : EspEngine.MAX_HP;
                float frac = maxHp > 0 ? (float) realHp / maxHp : 0f;
                if (frac < 0f) frac = 0f;
                else if (frac > 1f) frac = 1f;

                float barH = Math.max(2f, EspConfig.HP_BAR_H_PX * densityScale);
                float barTop = bottom + 2f * densityScale;
                float barBot = barTop + barH;
                canvas.drawRect(left, barTop, right, barBot, hpBarBg);
                if (frac > 0f) {
                    // Green through amber to red. Hue alone carries the reading,
                    // so it survives being 3 px tall at 80 m.
                    int r = (frac > 0.5f) ? (int) (255 * (1f - frac) * 2f) : 235;
                    int g = (frac > 0.5f) ? 220 : (int) (220 * frac * 2f);
                    hpBarFill.setColor(Color.argb(235, clamp255(r), clamp255(g), 70));
                    canvas.drawRect(left, barTop, left + (right - left) * frac,
                            barBot, hpBarFill);
                }
                canvas.drawRect(left, barTop, right, barBot, hpBarEdge);
            }

            // Label: distance in metres, entity id, and height delta.
            labelBuf.setLength(0);
            labelBuf.append(Math.round(enemyDistM[i])).append("m e")
                    .append(enemyId[i]);
            // Real health reads plain; the estimate is marked with a '~' so a
            // guess is never mistaken for a measurement.
            if (realHp >= 0) {
                labelBuf.append(' ').append(realHp).append("hp");
            } else if (enemyHp[i] < EspEngine.MAX_HP) {
                labelBuf.append(" ~").append(enemyHp[i]).append("hp");
            }
            float dh = (float) ((groundY - ownY) / UNITS_PER_METER);
            if (dh > 1.5f) labelBuf.append(" ↑").append(Math.round(dh));
            else if (dh < -1.5f) labelBuf.append(" ↓").append(Math.round(-dh));
            canvas.drawText(labelBuf, 0, labelBuf.length(),
                    centerX, top - 3f, labelPaint);

            // ---- MOCO NAME TAG (EspMoco) ----
            // Purple diamond-flanked badge above the box, drawn after the
            // distance label so it sits on top. Dist is also shown inside the
            // sub-badge so the label line stays tidy.
            if (EspConfig.SHOW_NAMES) {
                espMoco.draw(canvas, centerX, top,
                        enemyName[i], enemyDistM[i], alpha);
            }

            // ---- POSE ----
            // Yaw 0 points along +Y and increases toward +X, i.e. the same
            // atan2(x, y) convention the velocity check confirmed. Drawn as
            // world-space rays projected like everything else, so they inherit
            // the same calibration rather than needing their own.
            if (EspConfig.SHOW_POSE && enemyPose[i]) {
                float rayU = EspConfig.AIM_RAY_M * (float) UNITS_PER_METER;
                double bRad = Math.toRadians(enemyBodyYaw[i]);
                double aRad = Math.toRadians(enemyAimYaw[i]);
                double pRad = Math.toRadians(enemyAimPitch[i]);
                float chest = groundY + (float) (BOX_H_M * 0.55 * UNITS_PER_METER);
                float head = groundY + (float) (BOX_H_M * 0.92 * UNITS_PER_METER);

                // Every ray point is pinned to the entity's glass branch. A ray
                // is a straight line in the world and must stay one on screen;
                // letting each endpoint pick its own branch puts a kink at the
                // glass edge that looks exactly like a pose decode error.
                // Legs / hips: where the body is actually facing.
                boolean o = project(ex, chest, ez, tanHalfX, tanHalfY,
                        cx, cy, halfW, halfH, xSign);
                float ox = scratchX, oy = scratchY;
                boolean b = project(ex + (float) (Math.sin(bRad) * rayU * 0.5), chest,
                        ez + (float) (Math.cos(bRad) * rayU * 0.5),
                        tanHalfX, tanHalfY, cx, cy, halfW, halfH, xSign);
                if (o && b) canvas.drawLine(ox, oy, scratchX, scratchY, bodyPaint);

                // Head / aim: where the weapon is pointing, pitch included.
                boolean headVis2 = project(ex, head, ez, tanHalfX, tanHalfY,
                        cx, cy, halfW, halfH, xSign);
                float hx = scratchX, hy = scratchY;
                double cp = Math.cos(pRad);
                boolean aimVis = project(
                        ex + (float) (Math.sin(aRad) * cp * rayU),
                        head + (float) (Math.sin(pRad) * rayU),
                        ez + (float) (Math.cos(aRad) * cp * rayU),
                        tanHalfX, tanHalfY, cx, cy, halfW, halfH, xSign);
                if (headVis2 && aimVis) canvas.drawLine(hx, hy, scratchX, scratchY, aimPaint);
            }
        }

        // ---- SCOPE RETICLE + ACQUISITION CONE ----
        // Drawn ONLY while scoped, because that is the only state the aim acts
        // in. The swipe-origin cross and travel ring that used to live here are
        // gone: they marked the numeric fallback origin, which the placed layout
        // replaces, and they sat over the weapon cluster looking like a game
        // control the player could press.
        //
        // The cone is drawn through the SAME tangents the projection uses, so
        // what you see is exactly what acquires -- including under magnification,
        // where tanHalfX/Y already carry EspConfig.FOV_ZOOM and the cone therefore grows
        // on screen as the scope narrows the real field of view.
        if (EspConfig.SHOW_AIM_EDITOR && scoped) {
            float coneTan = (float) Math.tan(Math.toRadians(EspConfig.AIM_CONE_DEG));
            // The cone is small and therefore lives inside the glass, so it is
            // drawn through the glass magnification too. Omitting this draws a
            // cone smaller than the region that actually acquires, which is the
            // worst kind of wrong for an instrument whose whole job is to show
            // what acquires.
            float rx = (coneTan / tanHalfX) * halfW;
            float ry = (coneTan / tanHalfY) * halfH;
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, aimConePaint);
            // The scope's own centre. A real dot, not a cross: the scope already
            // draws a reticle and a second cross on top of it reads as blur.
            // This is the point every angular error in AimTarget is measured
            // from, so if it does not sit on the scope's own centre the
            // projection is off and that is visible at a glance.
            canvas.drawCircle(cx, cy, Math.max(2.5f, densityScale * 2f), aimDotPaint);
        }

        // Placed layout: the two swipe paths and the fire button, drawn where
        // the editor put them. OFF by default and deliberately separate from
        // EspConfig.SHOW_AIM_EDITOR above -- that one draws the numeric fallback origin,
        // this one draws the placed geometry, and showing both at once is how
        // you end up tuning the one that is not in use.
        if (com.nepdon.wire.ui.edit.Layout.visible) {
            canvas.drawLine(
                    com.nepdon.wire.ui.edit.Layout.lineXx1 * w,
                    com.nepdon.wire.ui.edit.Layout.lineXy1 * h,
                    com.nepdon.wire.ui.edit.Layout.lineXx2 * w,
                    com.nepdon.wire.ui.edit.Layout.lineXy2 * h, aimZonePaint);
            canvas.drawLine(
                    com.nepdon.wire.ui.edit.Layout.lineYx1 * w,
                    com.nepdon.wire.ui.edit.Layout.lineYy1 * h,
                    com.nepdon.wire.ui.edit.Layout.lineYx2 * w,
                    com.nepdon.wire.ui.edit.Layout.lineYy2 * h, aimZonePaint);
            canvas.drawCircle(
                    com.nepdon.wire.ui.edit.Layout.fireCx * w,
                    com.nepdon.wire.ui.edit.Layout.fireCy * h,
                    com.nepdon.wire.ui.edit.Layout.fireR * w, aimConePaint);
        }

        // ---- commit the aim pick for this frame ----
        if (aimBest >= 0) {
            com.nepdon.wire.ui.aim.AimTarget.set(enemyId[aimBest], aimBestHx, aimBestHy,
                    cx, cy, aimBestYaw, aimBestPitch, enemyDistM[aimBest],
                    enemyStillMs[aimBest], aimNow,
                    enemyTeamKnown[aimBest] || com.nepdon.wire.ui.aim.AimConfig.AIM_TEST_TRACK,
                    enemyPoseEnum[aimBest],
                    // The anchor the aim ACTUALLY used: a fraction of the drawn
                    // box, not Posture's capsule. Reporting the capsule here
                    // would put a number on the HUD that nothing acts on -- the
                    // dump has to describe the live path or it misleads.
                    (float) BOX_H_M * EspConfig.AIM_BOX_FRAC);
        } else {
            com.nepdon.wire.ui.aim.AimTarget.clear();
        }

        // ---- nearest-target readout for the calibration panel ----
        // Independent of EspConfig.SHOW_BOXES: the readout is how the projection is
        // tuned, so it has to work with the boxes switched off.
        nearValid = false;
        int ni = -1;
        float nd = Float.MAX_VALUE;
        for (int i = 0; i < enemyCount; i++) {
            if (enemyDistM[i] < nd) { nd = enemyDistM[i]; ni = i; }
        }
        if (ni >= 0) {
            float chest = enemyH[ni] + (float) (BOX_H_M * 0.9 * UNITS_PER_METER);
            boolean vis = project(enemyWX[ni], chest, enemyWZ[ni],
                    tanHalfX, tanHalfY, cx, cy, halfW, halfH, xSign);
            nearId = enemyId[ni];
            nearDistM = nd;
            nearOnScreen = vis;
            if (vis) {
                nearNdcX = (scratchX - cx) / halfW;
                nearNdcY = (scratchY - cy) / halfH;
            }
            nearValid = true;
        }

        // Publish the mark target for the Cmd 148 rewrite. Prefer the locked
        // aim target when there is one -- that is the enemy the player is
        // actually dealing with -- and fall back to the nearest. RAW wire
        // coordinates only (see enemyRawX): this value goes back on the wire,
        // so the display axis map must not touch it.
        // Mark target for the Cmd 148 rewrite: the nearest enemy. (The old
        // aim-lock preference was removed with the aim system on 2026-09-18.)
        int mi = ni;
        if (mi >= 0) {
            com.nepdon.wire.esp.MarkTarget.set(
                    enemyId[mi], enemyRawX[mi], enemyRawUp[mi], enemyRawY[mi],
                    enemyDistM[mi]);
        } else {
            com.nepdon.wire.esp.MarkTarget.clear();
        }

        if (com.nepdon.wire.config.FeatureGate.RADAR && EspConfig.SHOW_RADAR) drawRadar(canvas, w, h);
    }

    /**
     * One-line calibration readout for the tune panel: where the projection
     * currently puts the nearest enemy, in normalised screen coordinates
     * (0,0 = crosshair, ±1 = screen edge).
     *
     * <p>Aim directly at that enemy and the pair should read ~0.00, 0.00. What
     * it reads instead identifies the wrong knob: a constant offset that does
     * not change when you look around is OFF X/Y; an offset that grows with
     * angle from centre is FOV; one that grows as you close the distance is
     * BOOM; a sign flip on X is handedness (VIEW MODE / MIRROR X).</p>
     */
    public String calibHint() {
        if (!hasState) return "aim at an enemy — no view state yet";
        if (!nearValid) return "aim at an enemy — no target tracked";
        if (!nearOnScreen) {
            return String.format("near e%d %.0fm — BEHIND camera", nearId, nearDistM);
        }
        return String.format("near e%d %.0fm  scr %+.2f,%+.2f%s",
                nearId, nearDistM, nearNdcX, nearNdcY,
                (Math.abs(nearNdcX) < 0.06f && Math.abs(nearNdcY) < 0.10f) ? "  ✓" : "");
    }

    /**
     * Projects one world point to screen; the result lands in
     * {@link #scratchX}/{@link #scratchY} (copy it out before the next
     * call). Equivalent to s = P * V * e with the view/projection matrices
     * of the approved design, evaluated in factored form: camera coords
     * (d.r, d.u, d.f), d = e - p; drawable iff clip w = d.f &gt; near;
     * screen = center + ndc * half-extent.
     *
     * @return true if the point is in front of the camera (drawable).
     */
    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private boolean project(float x, float y, float z,
                            float tanHalfX, float tanHalfY,
                            float cx, float cy, float halfW, float halfH,
                            float xSign) {
        // Measure from the CAMERA, not the character (third-person boom).
        float ex = eyeValid ? eyeX : ownX;
        float ey = eyeValid ? eyeY : ownY;
        float ez = eyeValid ? eyeZ : ownZ;
        float dx = x - ex;
        float dy = y - ey;
        float dz = z - ez;
        float depth = dx * fX + dy * fY + dz * fZ;   // clip w (d.f)
        if (depth < NEAR_UNITS) return false;
        float camX = dx * rX + dy * rY + dz * rZ;
        float camY = dx * uX + dy * uY + dz * uZ;
        // Camera-space bearing, published alongside the screen point.
        //
        // The aim used to recover this by INVERTING the screen position:
        // ndcX*tanHalfX, which happens to equal camX/depth exactly, so the
        // tangents cancelled and the angle came out zoom-invariant by accident
        // of algebra rather than by intent. Anything that changes the screen
        // mapping -- a zoom, an optic nudge, a ring remap -- breaks that
        // cancellation silently: the boxes move, the swipe scales with them,
        // and no counter says so. Publishing the bearing at its source makes
        // the aim independent of how we choose to DRAW the point.
        scratchYawDeg = (float) Math.toDegrees(Math.atan2(xSign * camX, depth));
        scratchPitchDeg = (float) Math.toDegrees(Math.atan2(camY, depth));
        scratchX = cx + xSign * (camX / depth / tanHalfX) * halfW + EspConfig.OFFSET_X_PX;
        scratchY = cy - (camY / depth / tanHalfY) * halfH + EspConfig.OFFSET_Y_PX;
        return true;
    }

    /**
     * Radar-lite: bottom-left square, self at center, world-up orientation
     * (world x -> radar right, world z (own posY axis) -> radar up), dots
     * for enemies within {@link #radarRangeM}, own facing as a line using
     * the best-known theta. Doubles as the live axis-mapping calibrator:
     * if the dots track the in-game minimap, the mapping is right.
     */
    private void drawRadar(Canvas canvas, int w, int h) {
        float left = RADAR_MARGIN_PX;
        float top = h - RADAR_MARGIN_PX - RADAR_PX;
        float right = left + RADAR_PX;
        float bottom = top + RADAR_PX;
        float cx = left + RADAR_PX * 0.5f;
        float cy = top + RADAR_PX * 0.5f;

        canvas.drawRect(left, top, right, bottom, radarBg);
        canvas.drawRect(left, top, right, bottom, radarBorder);

        // Range ring at 50m (half range) for scale.
        float halfPx = RADAR_PX * 0.5f - 6f;
        canvas.drawCircle(cx, cy, halfPx * 0.5f, radarBorder);

        // Own facing line (world (sin(theta), cos(theta)) over (x, z)).
        float fx = (float) Math.sin(theta);
        float fz = (float) Math.cos(theta);
        canvas.drawLine(cx, cy, cx + fx * halfPx, cy - fz * halfPx, radarFacing);

        // Own dot.
        canvas.drawCircle(cx, cy, 2.5f, radarSelf);

        // Enemy dots within range.
        float scale = halfPx / (radarRangeM * (float) UNITS_PER_METER);
        for (int i = 0; i < enemyCount; i++) {
            float dxm = (enemyWX[i] - ownX) * scale;
            float dzm = (enemyWZ[i] - ownZ) * scale;
            if (dxm * dxm + dzm * dzm > halfPx * halfPx) continue;
            int r = enemyRole[i];
            radarDot.setColor(r == EspEngine.ROLE_TEAM ? TEAM_STROKE_COLOR
                    : (r == EspEngine.ROLE_UNKNOWN ? UNK_STROKE_COLOR : RADAR_DOT));
            canvas.drawCircle(cx + dxm, cy - dzm, 3f, radarDot);
        }

        // Range label.
        radarBuf.setLength(0);
        radarBuf.append(Math.round(radarRangeM)).append('m');
        canvas.drawText(radarBuf, 0, radarBuf.length(), right - 4f, top + 12f, radarText);
    }
}
