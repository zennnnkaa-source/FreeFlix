package com.nepdon.wire.ui.panel;

import com.nepdon.wire.config.EspConfig;

import android.content.Context;
import android.view.WindowManager;

import com.nepdon.wire.config.EspTuning;
import com.nepdon.wire.ui.aim.AimConfig;
import com.nepdon.wire.ui.aim.AimController;
import com.nepdon.wire.ui.aim.Daemon;
import com.nepdon.wire.ui.aim.AimTarget;
import com.nepdon.wire.ui.edit.Layout;
import com.nepdon.wire.ui.render.EspRenderView;

/**
 * Aim setup — acquisition rules, swipe placement, calibration and live state.
 *
 * <h3>What the aim is, precisely</h3>
 *
 * <p>It moves the player's own crosshair by planting a synthetic finger through
 * the {@code nvmux} uinput clone and dragging it. <b>It touches no packet.</b>
 * It does not forge hits, does not claim damage, and the deleted Cmd-106
 * {@code AimFilter} stays deleted. Everything below is geometry and touch.</p>
 *
 * <h3>Why this panel is verbose where the old one was terse</h3>
 *
 * <p>Two of these values are genuinely underivable and one of them — px per
 * degree — is the last unknown standing between the aim and working. A bare
 * number with a −/+ pair gives the user no way to know which direction to move
 * it, and the answer ("overshoot → lower") is one sentence. Every row that has
 * such a sentence carries it.</p>
 *
 * <h3>The five-tap</h3>
 *
 * <p>Tapping the header five times opens the full-screen layout editor, which is
 * where the swipe paths and the fire button are actually placed. The same editor
 * is reachable from the EDIT LAYOUT row, because a gesture nobody is told about
 * is a feature nobody uses — the gesture is the shortcut, not the only door.</p>
 */
public final class AimSetupPanel extends SetupPanel {

    /** Opens the layout editor. Injected so this panel owns no window. */
    public interface EditorHook {
        void openEditor();
    }

    private final EditorHook editor;

    public AimSetupPanel(Context context, WindowManager wm, EditorHook editor) {
        super(context, wm);
        this.editor = editor;
    }

    @Override protected String title()      { return "◎ AIM SETUP"; }
    @Override protected String shortTitle() { return "◎ AIM"; }
    @Override protected String openSectionKey() { return "aim_setup_open_section"; }
    @Override protected int startXDp() { return 12; }
    @Override protected int startYDp() { return 96; }
    @Override protected int bodyHeightDp() { return 380; }

    @Override
    protected void onSecretTap() {
        if (editor != null) editor.openEditor();
    }

    @Override
    protected void buildRows() {

        // ---- live state first: the panel's job is to answer "is it working" ----
        section("STATE");
        note("Read this before changing anything. Most 'the aim is broken' is "
                + "a gate refusing to acquire, not a bad number.");

        info("TARGET", new TextProvider() {
            public String text() {
                return AimTarget.dumpLine();
            }
        });
        info("INJECT", new TextProvider() {
            public String text() {
                return AimController.aimLine();
            }
        });
        // Shizuku + daemon liveness. Without this the panel could say the aim
        // was on while the input path had never come up at all.
        info("DAEMON", new TextProvider() {
            public String text() {
                return Daemon.statusLine();
            }
        });
        info("LAYOUT", new TextProvider() {
            public String text() {
                return Layout.isPlaced() ? "placed" : "defaults (not placed)";
            }
        });
        // The scope latch, printed because SCOPE ONLY below depends on it and a
        // stuck latch is otherwise indistinguishable from a broken aim.
        info("SCOPE", new TextProvider() {
            public String text() {
                return com.nepdon.wire.protocol.ScopeState.debugLine();
            }
        });

        // ---- the editor ----
        section("LAYOUT");
        note("Where the synthetic finger plants and drags, and where your fire "
                + "button is. Placed on screen, not typed in — a fraction is "
                + "impossible to judge from a number.");
        action("EDIT LAYOUT", "OPEN", new Runnable() {
            public void run() {
                if (editor != null) editor.openEditor();
            }
        });
        note("Shortcut: tap this panel's header five times.");
        bool("SHOW ELEMENTS",
                "Draw the swipe paths and fire button during play. Off by "
                + "default — they are input geometry, not decoration, and a "
                + "permanent line across the look area is a tell.",
                new BoolKnob() {
                    public boolean get() { return Layout.visible; }
                    public void set(boolean b) { Layout.visible = b; }
                });
        action("RESET LAYOUT", "RESET", new Runnable() {
            public void run() {
                Layout.reset(context);
                refreshAll();
            }
        });

        // ---- WHO the aim may pick ----
        section("ACQUISITION");
        note("Only a server-published team byte may mark an enemy. The aim "
                + "never guesses from geometry, so an unclassified entity is "
                + "never a target.");

        bool("SCOPE ONLY",
                "Acquire only while scoped. On is the intended mode: scoped, "
                + "the reticle is a point and the correction is the whole shot; "
                + "hip-fire spread is usually wider than the correction, so a "
                + "swipe there costs more than it buys. Turn OFF only to test "
                + "without a scope — the SCOPE row above says whether the "
                + "latch is tracking.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.AIM_SCOPE_ONLY; }
                    public void set(boolean v) { EspTuning.setAimScopeOnly(v); }
                });

        num("CONE", "Half-angle around the crosshair inside which a target is "
                        + "eligible. Wider acquires sooner and swipes further; "
                        + "narrower only helps shots you were nearly taking.",
                0.5f, 5f, "%.1f°", new FloatKnob() {
                    public float get() { return EspConfig.AIM_CONE_DEG; }
                    public void set(float v) { EspTuning.setAimCone(v); }
                });

        num("RANGE", "Beyond this, ignore. Distant targets need a tiny swipe "
                        + "that the game's own aim friction eats.",
                5f, 25f, "%.0fm", new FloatKnob() {
                    public float get() { return EspConfig.AIM_MAX_RANGE_M; }
                    public void set(float v) { EspTuning.setAimRange(v); }
                });

        num("STILL FOR", "The thesis in one number. Dead reckoning's RMS error "
                        + "is 0.57/0.62/0.68 m over one/two/three ticks and a "
                        + "swipe takes 100-200 ms, so a moving target outruns "
                        + "its own correction. Raising this makes the aim "
                        + "pickier and more accurate, not weaker.",
                50f, 200f, "%.0fms", new FloatKnob() {
                    public float get() { return EspConfig.AIM_STILL_MS; }
                    public void set(float v) { EspTuning.setAimStillMs(v); }
                });

        // ---- HOW FAR a degree moves the finger ----
        section("TARGET VOLUME");
        note("There is NO per-bone data on the wire -- an exhaustive 4.96M "
                + "sweep found body offsets 24x BELOW chance. The hittable "
                + "body is MODELLED from the entity root plus the posture byte "
                + "(record field 11), so these three numbers ARE the target.");

        num("BODY HEIGHT", "Standing capsule height. 1.75, not 1.8 — two "
                        + "independent residuals from the scoped-zoom work "
                        + "disagree with each other but agree 1.8 is too big. "
                        + "The real value is serialized Unity data AND scaled "
                        + "per match by the server, so there may be no single "
                        + "right answer.",
                0.01f, 0.05f, "%.2fm", new FloatKnob() {
                    public float get() { return AimConfig.AIM_H_STANDING_M; }
                    public void set(float v) { EspTuning.setAimBodyH(v); }
                });

        num("CROUCH RATIO", "Crouched height as a fraction of standing. 0.60 "
                        + "is an ASSUMPTION taken from the ADS pivot table "
                        + "(0.77/1.28) because no collider table exists in the "
                        + "binary. The least-supported number in the aim path "
                        + "— calibrate it against a crouched target.",
                0.01f, 0.05f, "%.2f", new FloatKnob() {
                    public float get() { return AimConfig.AIM_CROUCH_RATIO; }
                    public void set(float v) { EspTuning.setAimCrouchRatio(v); }
                });

        num("ANCHOR", "Where in the capsule to aim, as a fraction of height. "
                        + "0.82 is the neck base. Our HORIZONTAL error is the "
                        + "staleness error; the VERTICAL is near zero (the root "
                        + "is exact and crouch/prone provably do not move it), "
                        + "so height is the axis we can spend precision in. "
                        + "1.00 = head top, 0.55 = centre of mass.",
                0.01f, 0.05f, "%.2f", new FloatKnob() {
                    public float get() { return AimConfig.AIM_ANCHOR_FRAC; }
                    public void set(float v) { EspTuning.setAimAnchorFrac(v); }
                });

        section("LEAD");
        note("Bullets are HITSCAN and the server rewinds to our own tick (the "
                + "Cmd 106 claim carries our sim timer and our ping). So every "
                + "transport term cancels and the lead is client-side only — "
                + "not the ~650ms a ballistic model would need.");

        num("PIPELINE", "How far ahead the aim point is carried: swipe (~160) "
                        + "+ pipe (~8) + the game applying the rotation (~17). "
                        + "Applied to the AIM POINT ONLY — the boxes are "
                        + "carried by record age alone. Too high: boxes right, "
                        + "shots land in front, and the error grows with target "
                        + "speed. Too low: shots land behind.",
                5f, 25f, "%.0fms", new FloatKnob() {
                    public float get() { return AimConfig.AIM_PIPELINE_MS; }
                    public void set(float v) { EspTuning.setAimPipelineMs(v); }
                });

        num("MAX RECORD AGE", "Refuse a target whose freshest packet is older "
                        + "than this. ~37% of wall-clock time an entity has no "
                        + "fresh record at all and the p99 dropout is 1782ms, "
                        + "over which a player travels a median 7.2m. "
                        + "Predicting through that is not prediction.",
                10f, 50f, "%.0fms", new FloatKnob() {
                    public float get() { return AimConfig.AIM_MAX_SRC_AGE_MS; }
                    public void set(float v) { EspTuning.setAimMaxSrcAge(v); }
                });

        note("REFUSALS are an allow-list: an unknown posture refuses rather "
                + "than falling through to STANDING. Knocked, swimming, "
                + "falling and mounted targets are all refused, and so is "
                + "EVERY target while YOU are mounted — Cmd 2001 stops in "
                + "vehicles, so the camera basis freezes and the aim would "
                + "swing at an arbitrary bearing. Watch the STATE row: every "
                + "refusal has its own counter.");

        num("AIM POINT", "Where on the ESP box the aim points. 0 = feet, "
                        + "1 = box top, 0.55 = upper chest. The aim projects "
                        + "the SAME box the outline is drawn from, so this is "
                        + "the only thing between the aim point and the "
                        + "outline -- if they still disagree, the box itself "
                        + "is miscalibrated, not the aim.",
                0.05f, 0.1f, "%.2f", new FloatKnob() {
                    public float get() { return EspConfig.AIM_BOX_FRAC; }
                    public void set(float v) { EspTuning.setAimBoxFrac(v); }
                });

        num("SMOOTHNESS", "Chase time constant, ms -- how hard the steer pulls "
                        + "toward the aim point. The contact is always moving; "
                        + "this only sets how fast. Low = snappy and "
                        + "eventually robotic. High = smooth but lags behind a "
                        + "moving target. Keep it under ~120ms, the aim's "
                        + "re-solve period.",
                5f, 20f, "%.0fms", new FloatKnob() {
                    public float get() { return AimConfig.AIM_STEER_TAU_MS; }
                    public void set(float v) { EspTuning.setAimSteerTau(v); }
                });

        num("SCOPED GAIN", "Extra px-per-degree multiplier while scoped. "
                        + "PX PER DEGREE is the HIP-FIRE figure; scoped, the "
                        + "game damps the look ~0.2x, so the same drag turns a "
                        + "fifth as far. Too low: the aim crawls toward the "
                        + "target. Too high: it overshoots and oscillates.",
                0.25f, 1f, "%.2fx", new FloatKnob() {
                    public float get() { return AimConfig.AIM_SCOPED_GAIN; }
                    public void set(float v) { EspTuning.setAimScopedGain(v); }
                });

        bool("TEST TRACK",
                "Acquire ANY enemy in the cone. Skips team-known, still-time, "
                + "record age and posture. ON while the aim is being brought "
                + "up: you cannot debug a steering problem through an "
                + "acquisition problem. Turn OFF once the swipe is confirmed "
                + "to land.",
                new BoolKnob() {
                    public boolean get() { return AimConfig.AIM_TEST_TRACK; }
                    public void set(boolean b) { EspTuning.setAimTestTrack(b); }
                });

        bool("POSTURE GATE",
                "Refuse targets by posture (knocked / falling / swimming / "
                + "creeping). OFF because the posture enum is UNVERIFIED and a "
                + "live capture contradicted it: value 11 was 15% of all "
                + "samples, and players are not airborne 15% of the time. "
                + "Turn on only after confirming the mapping with a deliberate "
                + "crouch/knockdown capture.",
                new BoolKnob() {
                    public boolean get() { return AimConfig.AIM_POSTURE_GATE; }
                    public void set(boolean b) { EspTuning.setAimPostureGate(b); }
                });

        num("BASIS STALE", "How long our own keepalive may be silent before the "
                        + "aim refuses everything. The camera basis comes from "
                        + "our own Cmd 2001, so a frozen basis makes every "
                        + "bearing wrong. 400ms (the vehicle test) is only two "
                        + "missed ticks and refused 1.1x/sec on foot.",
                50f, 200f, "%.0fms", new FloatKnob() {
                    public float get() { return AimConfig.AIM_BASIS_STALE_MS; }
                    public void set(float v) { EspTuning.setAimBasisStale(v); }
                });

        section("CALIBRATION");
        note("The one measurement that is still missing. 10.0 is the binary's "
                + "value at GAME SENS 75, but Free Fire moved to a 0-200 scale "
                + "with six per-scope sliders, so the compiled constant is a "
                + "fallback, not your sensitivity.");
        num("PX / DEG", "Watch one swipe land. OVERSHOOTS the target → lower "
                        + "this. UNDERSHOOTS → raise it. That is the whole "
                        + "procedure.",
                0.1f, 1f, "%.1f", new FloatKnob() {
                    public float get() { return AimConfig.PX_PER_DEG; }
                    public void set(float v) { EspTuning.setAimPxPerDeg(v); }
                });

        num("MAX TRAVEL", "Longest single drag before the finger lifts and "
                        + "re-plants. The game's look ACCELERATES with drag "
                        + "distance (~3.4× at 300 px), so one long drag "
                        + "overshoots and blames its own gain. Lower this "
                        + "before you touch PX / DEG.",
                5f, 25f, "%.0fpx", new FloatKnob() {
                    public float get() { return AimConfig.MAX_TRAVEL_PX; }
                    public void set(float v) { EspTuning.setAimMaxTravel(v); }
                });

        section("SWIPE ORIGIN");
        bool("PLANT ON FIRE",
                "Plant the synthetic finger on the placed fire button. Scoped, "
                + "that is the ONLY place a drag turns the view — the look "
                + "region is the reticle, and a swipe starting anywhere else "
                + "runs perfectly and moves nothing, which looks exactly like a "
                + "bad PX / DEG.",
                new BoolKnob() {
                    public boolean get() { return AimConfig.PLANT_ON_FIRE; }
                    public void set(boolean v) { EspTuning.setAimPlantOnFire(v); }
                });
        note("The two placed lines set the DIRECTION of each axis, not the "
                + "travel limit. A swipe may run from the plant point to the "
                + "edge of the screen.");

        // ---- the old numeric placement, kept reachable ----
        section("SWIPE ORIGIN (numeric fallback)");
        note("Used only while the layout above is unplaced. Fractions of screen "
                + "width and height for a single plant point. The editor "
                + "replaces both with two placed paths.");
        num("SWIPE X", 0.01f, 0.05f, "%.2f", new FloatKnob() {
            public float get() { return AimConfig.SWIPE_X_FRAC; }
            public void set(float v) { EspTuning.setAimSwipeX(v); }
        });
        num("SWIPE Y", 0.01f, 0.05f, "%.2f", new FloatKnob() {
            public float get() { return AimConfig.SWIPE_Y_FRAC; }
            public void set(float v) { EspTuning.setAimSwipeY(v); }
        });

        // ---- proving the swipe lands at all ----
        section("TEST MODE");
        note("Hold your real finger on the fire button. The daemon TAKES OVER "
                + "that finger and moves it — your own contact is what "
                + "travels, so the game steers exactly as if you had dragged.");
        note("A second synthetic finger beside yours does NOT work scoped: the "
                + "scoped look is driven by the contact already down, so an "
                + "extra one is ignored. That is why the old test only ever "
                + "moved anything once you had come back out of the scope.");
        info("TEST", new TextProvider() {
            public String text() {
                return AimController.steerLine();
            }
        });
        // The temp dumper. GEOM is what the app thinks; LAST PRESS is what the
        // daemon actually saw. When those two disagree the press is landing
        // outside a circle placed in the wrong coordinate space, which is
        // invisible from either side alone.
        info("GEOM", new TextProvider() {
            public String text() {
                return AimController.geomLine();
            }
        });
        info("LAST PRESS", new TextProvider() {
            public String text() {
                return Daemon.lastDown();
            }
        });
        info("TRACE", new TextProvider() {
            public String text() {
                return Daemon.lastLine();
            }
        });
        // The instrument of last resort: no target, no team byte, no scope,
        // no stillness, no real press. If the camera does not move for THIS,
        // nothing further up the stack is worth tuning.
        action("SPIN NOW (3s)", "GO", new Runnable() {
            public void run() {
                AimController.steerNow(3000);
                refreshAll();
            }
        });
        note("Steers whatever finger is currently down. Hold the fire button, "
                + "then press GO. It refuses if no finger is on the glass — "
                + "there is nothing to steer.");
        note("The daemon now grabs the panel ONLY while you are scoped, and "
                + "gives it back the moment the scope closes.");
        num("SPIN FOR", "How long the automatic scope-in steer runs. It stops "
                        + "early if you unscope or lift the finger.",
                250f, 1000f, "%.0fms", new FloatKnob() {
                    public float get() { return AimConfig.TEST_HOLD_MS; }
                    public void set(float v) { EspTuning.setAimSpinMs(v); }
                });
        bool("HOLD-FIRE STEER",
                "Arms the daemon to watch the placed fire circle. On by "
                + "default. Scoping in now steers the finger nearest your fire "
                + "button automatically — no button to press. Turn it off to "
                + "play normally.",
                new BoolKnob() {
                    public boolean get() { return AimConfig.TEST_MODE; }
                    public void set(boolean v) {
                        EspTuning.setAimTestMode(v);
                        refreshAll();
                    }
                });

        section("DEBUG OVERLAY");
        bool("AIM OVERLAY",
                "While SCOPED, draws the acquisition cone and a dot at the "
                + "scope's centre. The cone uses the SAME tangents the "
                + "projection does, so what you see is what acquires; if the "
                + "dot does not sit on the scope's own centre, the projection "
                + "is off.",
                new BoolKnob() {
                    public boolean get() { return EspConfig.SHOW_AIM_EDITOR; }
                    public void set(boolean v) { EspTuning.setAimEditor(v); }
                });
    }
}
