package com.nepdon.wire.ui.panel;

import com.nepdon.wire.config.EspConfig;

import android.content.Context;
import android.view.WindowManager;

import com.nepdon.wire.config.EspTuning;
import com.nepdon.wire.ui.render.EspRenderView;

/**
 * Projection and prediction setup — the panel formerly called ESP TUNE.
 *
 * <h3>Why these values are knobs and not constants</h3>
 *
 * <p>The world-to-screen transform needs two things the server never sends: the
 * client's field of view and the handedness of the engine's horizontal axes. No
 * amount of packet analysis recovers them — a capture contains positions, not a
 * projection matrix. The honest way to solve them is to put the knobs in front
 * of the eyes that can see the error, which is what this panel does: every
 * change writes straight into the renderer's volatile statics, so the boxes move
 * on the next frame with the game still running underneath.</p>
 *
 * <h3>Prediction, which is what is actually left to tune</h3>
 *
 * <p>The projection itself is solved and its rows were retired. What remains is
 * dead reckoning: the server ticks at 198 ms, so a box drawn at the last known
 * position is up to a fifth of a second stale on a moving target. Every row here
 * trades staleness against overshoot, and the right answer depends on the ping
 * of the match being played.</p>
 */
public final class EspSetupPanel extends SetupPanel {

    public EspSetupPanel(Context context, WindowManager wm) {
        super(context, wm);
    }

    @Override protected String title()      { return "⚙ ESP SETUP"; }
    @Override protected String shortTitle() { return "⚙ ESP"; }
    @Override protected String openSectionKey() { return "esp_tune_open_section"; }

    @Override
    protected void buildRows() {

        section("PREDICTION (lag)");
        note("The server broadcasts world state every ~198 ms. Everything in "
                + "this section decides what to draw during the gap.");

        bool("EXTRAPOLATE",
                "Off draws the last packet exactly — correct, but behind. On "
                + "carries the target forward along its last velocity.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.EXTRAPOLATE; }
                    public void set(boolean b) { EspTuning.setExtrapolate(b); }
                });

        bool("AUTO LEAD (ping)",
                "Sets LEAD from the measured round trip instead of the fixed "
                + "value below. Leave on unless you are chasing a specific "
                + "number.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.AUTO_LEAD; }
                    public void set(boolean b) { EspTuning.setAutoLead(b); }
                });

        num("LEAD", "How far ahead of the last packet to draw. Boxes trailing "
                        + "behind a running target: raise. Boxes running ahead "
                        + "of it: lower.",
                5f, 20f, "%.0fms", new FloatKnob() {
                    public float get() { return EspConfig.LEAD_MS; }
                    public void set(float v) { EspTuning.setLeadMs(v); }
                });

        num("MAX PREDICT", "Hard cap on how far a box may be carried. Bounds "
                        + "the damage when a target stops dead and the "
                        + "extrapolation is still running.",
                0.5f, 2f, "%.1fm", new FloatKnob() {
                    public float get() { return EspConfig.EXTRAP_MAX_M; }
                    public void set(float v) { EspTuning.setExtrapMax(v); }
                });

        section("CAMERA LEAD");
        note("Your own view angle is published on the same slow tick as "
                + "everyone's position, so turning the view drags every box "
                + "together. The Camera slider in the MOD menu corrects for that; this only caps it.");

        num("CAM EXT CAP", "Ceiling on the applied angle, so a fast flick can "
                        + "never spin the whole basis.",
                5f, 15f, "%.0f°", new FloatKnob() {
                    public float get() { return EspConfig.CAM_EXTRAP_MAX_DEG; }
                    public void set(float v) { EspTuning.setCamExtrapMaxDeg(v); }
                });

        section("SCOPE");
        note("The scope latch is read from your own outbound packets (Cmd "
                + "137/138), so the ESP knows you are scoped without you "
                + "telling it. These decide what it does about it.");

        // The two prior causes of "offset, and it grows with angle from the
        // crosshair" -- both of which a zoom then multiplies. Reported rather
        // than assumed: every previous instance of this symptom in this project
        // turned out to be multiplicative or basis-level, and NOT ONE of them
        // was ever fixed by an additive nudge.
        info("VIEWPORT", new TextProvider() {
            public String text() {
                if (EspRenderView.VP_W <= 0) return "unknown";
                return EspRenderView.VP_W + "x" + EspRenderView.VP_H
                        + " org " + EspRenderView.VP_OX + "," + EspRenderView.VP_OY
                        + (EspRenderView.VP_EXACT ? " exact" : " INSET-CORRECTED");
            }
        });
        info("PREDICT", new TextProvider() {
            public String text() {
                if (!EspConfig.EXTRAPOLATE) return "off";
                return "ON lead=" + Math.round(EspConfig.LEAD_MS)
                        + " cam=" + Math.round(EspConfig.CAM_EXTRAP_MS)
                        + "ms (a zoom multiplies both)";
            }
        });

        info("SCOPE", new TextProvider() {
            public String text() {
                return com.nepdon.wire.protocol.ScopeState.debugLine();
            }
        });

        bool("AUTO SCOPE ZOOM",
                "Switch the projection to SCOPED ZOOM whenever the scope is up, "
                + "and straight back when it closes. Off leaves the manual "
                + "zoom button in charge.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.AUTO_SCOPE_ZOOM; }
                    public void set(boolean b) { EspTuning.setAutoScopeZoom(b); }
                });

        num("SCOPED ZOOM", "Whole-screen magnification while scoped, and NOT "
                        + "the advertised tier. 3.27 = a scoped vertical FOV "
                        + "of exactly 20°, which is 60/3 — the game computes "
                        + "its scoped field as C_FOV_NORMAL divided by the "
                        + "sight's fov_amplify_ratio, and for the '8X' that "
                        + "divisor is 3, not 8. The tier and the FOV divisor "
                        + "are different CSV columns. Get this wrong and boxes "
                        + "are perfect at the crosshair and worse the further "
                        + "out you look: the error is (zoom error) x (distance "
                        + "from centre).",
                0.05f, 0.5f, "%.2fx", new FloatKnob() {
                    public float get() { return EspConfig.SCOPED_ZOOM; }
                    public void set(float v) { EspTuning.setScopeZoom(v); }
                });

        info("BOX", new TextProvider() {
            public String text() {
                if (EspRenderView.CAL_BOX_ID < 0) return "no target drawn";
                return String.format("e%d %.0fm drawn %.0fpx tall",
                        EspRenderView.CAL_BOX_ID, EspRenderView.CAL_BOX_DIST_M,
                        EspRenderView.CAL_BOX_H_PX);
            }
        });
        note("QUICK CHECK: screenshot a target with a box on it, measure the "
                + "character's own height in pixels, read the row above. "
                + "trueZoom = SCOPED ZOOM x charPx / boxPx. Careful — the box "
                + "height is computed FROM our range, so this inherits any "
                + "range error one for one.");
        note("THE REAL MEASUREMENT is sep= on the HUD, with TWO targets. "
                + "trueZoom = SCOPED ZOOM x (pixel gap between the two bodies) "
                + "/ sep. Both are differences of two screen positions, so the "
                + "optic centre, the character height, the range and the box "
                + "height all cancel. It is the only reading with nothing "
                + "assumed in it — and assuming was what made every earlier "
                + "attempt at this number wrong, twice, in opposite directions.");

        note("The scope ring is a UI sprite over an already-magnified "
                + "full-screen render — not a magnified inset. Every caller of "
                + "set_fieldOfView (58), set_targetTexture (56), set_rect (6) "
                + "and set_projectionMatrix (3) was enumerated in the binary "
                + "and not one is a sighting path. So boxes outside the ring "
                + "are legitimately visible; nothing is clipped to it.");

        num("SCOPE BOOM", "Camera boom while scoped, metres. A boom that is too "
                        + "long computes a bearing that is too small: EXACTLY "
                        + "zero at the crosshair, growing off-axis, pulling "
                        + "boxes inward. BUT it also changes DEPTH, so setting "
                        + "it to 0 makes boxes +25% taller at 10m and +7.8% at "
                        + "32m. TEST IT at 8-12m: if the box gets visibly too "
                        + "tall, 2.50 was right.",
                0.1f, 0.5f, "%.2fm", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_BOOM_M; }
                    public void set(float v) { EspTuning.setScopeBoom(v); }
                });

        num("SCOPE SHOULDER", "Sideways camera offset while scoped, metres. "
                        + "MEASURED 0 — while sighting, AHCBLOCABDP jumps past "
                        + "the whole shoulder block, so the game applies none. "
                        + "Was 0.40, worth 38px of sideways bias at 32m and "
                        + "122px at 10m, constant across the screen and present "
                        + "AT the crosshair.",
                0.05f, 0.2f, "%.2fm", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_SHOULDER_M; }
                    public void set(float v) { EspTuning.setScopeShoulder(v); }
                });

        num("SCOPE PIVOT", "Camera pivot height while scoped, metres above the "
                        + "entity origin. MEASURED 1.28 — ADS does not adjust "
                        + "the third-person pivot, it REPLACES it with "
                        + "get_AimStartPostion = Position + up*1.28 standing. "
                        + "Hip fire uses the capsule top (~1.7-1.8), so the "
                        + "scope drops the eye ~0.4m. Crouch is 0.77 and is NOT "
                        + "modelled yet. Pure vertical error: 30px at 32m.",
                0.02f, 0.1f, "%.2fm", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_PIVOT_M; }
                    public void set(float v) { EspTuning.setScopePivot(v); }
                });

        bool("CAM LEAD ALWAYS",
                "Run the camera lead even with EXTRAPOLATE off. They are "
                + "different corrections: EXTRAPOLATE guesses where a PLAYER "
                + "will be; this corrects YOUR OWN view angle being ~99ms "
                + "stale. Its signature is DYNAMIC — boxes slide together in "
                + "the direction you turn and settle when you stop. Turn on "
                + "only if the offset vanishes when you hold still.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.CAM_LEAD_ALWAYS; }
                    public void set(boolean b) { EspTuning.setCamLeadAlways(b); }
                });

        note("TEST 0, BEFORE ANYTHING ELSE: put a target off to one side, take "
                + "your finger OFF the look control, and look at pan= on the "
                + "HUD. It must read 0. If the offset is there while panning "
                + "and gone when settled, it was camera lag and nothing below "
                + "needs touching.");
        note("IF IT SURVIVES HOLDING STILL: the zoom and the boom are only "
                + "separable by RANGE — at 25-40m their predictions differ by "
                + "under 2%, which is why measuring there never settled it. Set "
                + "SCOPED ZOOM on a target at 60m+ (boom barely matters), then "
                + "SCOPE BOOM at 8-12m (where it is 25% of the box height). One "
                + "iteration converges.");

        num("SCOPE X", "Horizontal-only zoom, on top of SCOPED ZOOM. One "
                        + "factor cannot fix both axes if the scoped view is "
                        + "not HOR+. Set SCOPED ZOOM by the box's HEIGHT, then "
                        + "set this by its SIDEWAYS position. Lands on 1.0 = "
                        + "HOR+ after all; lands near 2.17 (the screen aspect) "
                        + "= the optic is square, and SQUARE OPTIC below is the "
                        + "real fix.",
                0.05f, 0.5f, "%.2fx", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_X_EXTRA; }
                    public void set(float v) { EspTuning.setScopeXExtra(v); }
                });

        bool("SQUARE OPTIC",
                "While scoped, drop the screen-aspect term from the horizontal "
                + "tangent. A circular optic magnifies about its own axis "
                + "equally both ways; the screen aspect is then a property of "
                + "the frame around it, not of the magnified image. Same "
                + "hypothesis as SCOPE X = 2.17, as one tap.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.SCOPE_SQUARE; }
                    public void set(boolean b) { EspTuning.setScopeSquare(b); }
                });

        num("BOX SIZE x", "Size only, on top of SCOPED ZOOM. A box's height is "
                        + "the gap between two projected points, so it already "
                        + "carries the zoom — at 8x it is 8x taller. Set "
                        + "SCOPED ZOOM by where the box SITS, then set this by "
                        + "how BIG it is.",
                0.05f, 0.25f, "%.2fx", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_BOX_SCALE; }
                    public void set(float v) { EspTuning.setScopeBoxScale(v); }
                });

        num("OPTIC X", "Moves the point the zoom expands ABOUT, while scoped "
                        + "only. If the scope's optic is not centred on the "
                        + "screen, magnifying about the screen centre misplaces "
                        + "every box by an amount that GROWS with the zoom — "
                        + "which is the 'offset, wrong way, and by a lot' "
                        + "signature exactly.",
                2f, 20f, "%.0fpx", new FloatKnob() {
                    public float get() { return EspConfig.SCOPE_OFF_X_PX; }
                    public void set(float v) { EspTuning.setScopeOffX(v); }
                });
        num("OPTIC Y", 2f, 20f, "%.0fpx", new FloatKnob() {
            public float get() { return EspConfig.SCOPE_OFF_Y_PX; }
            public void set(float v) { EspTuning.setScopeOffY(v); }
        });

        note("Turn on AIM OVERLAY (in AIM SETUP) while you tune: the cone is "
                + "drawn through these exact tangents, so it grows as the value "
                + "narrows the field, and the dot marks the centre every "
                + "angular error is measured from.");
        warn("Tier is NOT detected. The app cannot yet tell 2x from 8x, so this "
                + "is one value — set it for the scope you actually use.");
        warn("If SCOPED ZOOM and BOX SIZE will not agree on one number, the "
                + "scope is NOT a camera zoom — and the gap between them is "
                + "the measurement that proves it.");

        section("PACKET EFFECTS");
        warn("This section changes packets other players act on. Everything "
                + "above only changes what YOU see.");

        bool("MARK → ESP TARGET",
                "Rewrites your squad map pin at the ESP target when YOU tap the "
                + "game's own mark button. It never injects — if you never tap, "
                + "it never fires.",
                new BoolKnob() {
                    public boolean get() {
                        return com.nepdon.wire.filter.MarkFilter.ENABLED;
                    }
                    public void set(boolean b) { EspTuning.setMarkRetarget(b); }
                });

        section("RESET");
        wideAction("RESET ALL", new Runnable() {
            public void run() {
                EspTuning.reset();
                refreshAll();
            }
        });
    }
}
