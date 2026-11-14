package com.nepdon.wire.ui.overlay;

import com.nepdon.wire.R;

import com.nepdon.wire.ui.aim.AimController;
import com.nepdon.wire.ui.aim.Daemon;
import com.nepdon.wire.ui.render.EspRenderView;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.nepdon.wire.esp.EspEngine;

/**
 * ESP overlay: a full-screen {@link EspRenderView} (Canvas box/radar
 * renderer) plus a small toggleable text debug panel — the calibration
 * tool.
 *
 * <p>Both windows are {@code TYPE_APPLICATION_OVERLAY} with
 * {@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE} (+
 * {@code FLAG_LAYOUT_IN_SCREEN}): they never take input, never dim or
 * obscure touches — the game below keeps receiving everything.</p>
 *
 * <p>The render loop is driven by {@link Choreographer}, so it runs once per
 * real display frame (120 Hz on a modern handset) rather than on a fixed
 * timer. The previous 33 ms {@link Handler} chain both capped the overlay at
 * 30 fps and beat against the refresh rate, adding up to a frame of latency
 * at random — visible as boxes that stutter against a smoothly moving target.
 * It also under-serves dead reckoning, which is only as smooth as the rate it
 * is evaluated at. When the engine is off / no mask yet the snapshot is empty
 * and the renderer draws nothing while the debug panel says why.</p>
 */
public class EspOverlay {

    private static final long DEBUG_PERIOD_MS = 500L;    // text poll

    private final Context context;
    private final WindowManager wm;

    private EspRenderView renderView;
    private TextView debugPanel;
    private boolean renderAdded = false;
    private boolean debugAdded = false;
    private boolean debugVisible = false;

    private Handler handler;
    private Choreographer.FrameCallback frameCallback;
    private Runnable debugTicker;

    public EspOverlay(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
        // Bind Shizuku early so the touch-injection aim is ready once the user
        // has started Shizuku + granted permission. Safe no-op otherwise.
        try {
            AimController.init();
        } catch (Throwable ignored) {
        }
    }

    private static int overlayType() {
        return (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** Supplies the live ESP state to the overlay. */
    public interface EspStateProvider {
        /** Multi-line status text for the debug panel; null = hide. */
        String stateText();

        /** The live ESP engine; null when the engine is off. */
        EspEngine engine();
    }

    /**
     * Shows the renderer + debug panel; the renderer runs at display
     * refresh via Choreographer, the debug text polls at 500 ms.
     */
    public void show(final EspStateProvider provider) {
        if (renderAdded || debugAdded) return;

        // ---- full-screen renderer window ----
        EspRenderView rv = new EspRenderView(context);
        // MUST cover the SAME surface the game renders into, edge to edge.
        //
        // FLAG_LAYOUT_IN_SCREEN alone is not enough: without
        // FLAG_LAYOUT_NO_LIMITS and an explicit cutout mode, Android keeps an
        // overlay clear of the display cutout and the nav bar. The game runs
        // immersive and renders into both, so our window came out smaller than
        // its viewport and offset inside it -- and because the projection was
        // built from our own getWidth()/getHeight(), that inset behaved
        // exactly like a wrong FOV: no error at the crosshair, growing error
        // with angle from it. EspRenderView now projects in display space and
        // corrects for any residual inset, but the window should still be the
        // full display so there is nothing to correct and nothing is clipped.
        WindowManager.LayoutParams rp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        rp.gravity = Gravity.TOP | Gravity.START;
        rp.x = 0;
        rp.y = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            rp.layoutInDisplayCutoutMode = (Build.VERSION.SDK_INT >= 30)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(rv, rp);
            renderView = rv;
            renderAdded = true;
        } catch (Exception e) {
            renderView = null;
            com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.RENDER_WINDOW_ADD_FAILED,
                    "addView failed: " + e.getClass().getSimpleName());
        }

        // ---- small debug panel window (calibration readout) ----
        TextView tv = new TextView(context);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.LEFT);
        tv.setAlpha(0.92f);
        // setPadding takes PIXELS. 12px is 4dp on a 3x screen -- effectively
        // no padding, which is why the readout sat flush against its own edge.
        final float dpu = context.getResources().getDisplayMetrics().density;
        final int padH = Math.round(10f * dpu);
        final int padV = Math.round(6f * dpu);
        tv.setPadding(padH, padV, padH, padV);
        // THE OLD SCRIM WAS LEGIBLE EXACTLY WHEN YOU DID NOT NEED IT.
        //
        // argb(150, ...) is 59% opacity. Measured against the WCAG formula:
        // over a dark scene this text read at 14.47:1, but over a bright one
        // the background composites to ~#6D737A and the same text collapsed to
        // 3.46:1 -- below the 4.5:1 body-text floor, on a readout whose whole
        // job is to be readable over live gameplay of unknown brightness.
        //
        // 95% measures 15.37:1 worst-case, composited against pure white.
        tv.setTextColor(context.getResources().getColor(R.color.fl_mono_value));
        tv.setBackground(context.getResources().getDrawable(R.drawable.fl_bg_panel));

        WindowManager.LayoutParams dp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        dp.gravity = Gravity.TOP | Gravity.START;
        dp.x = 8;
        dp.y = 120;

        try {
            wm.addView(tv, dp);
            debugPanel = tv;
            debugAdded = true;
        } catch (Exception e) {
            debugPanel = null;
        }

        if (!renderAdded && !debugAdded) return;

        handler = new Handler(Looper.getMainLooper());

        // Render loop, VSYNC-LOCKED. The old fixed 33 ms postDelayed chain
        // capped the overlay at ~30 fps and, worse, beat against the display
        // refresh — a frame's worth of extra latency at random. Choreographer
        // fires once per actual display frame (120 Hz on this device), so the
        // boxes are recomputed and redrawn in step with the game's own
        // frames. This matters far more now that positions are extrapolated:
        // dead reckoning is only as smooth as the rate it is evaluated at.
        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                // RE-POST FIRST, ALWAYS.
                //
                // The old `if (!renderAdded) return;` returned WITHOUT
                // re-posting, so the 120 Hz loop died permanently the first
                // time renderAdded was momentarily false -- and nothing
                // restarts it. The overlay then stays frozen for the rest of
                // the session with every subsystem beneath it working fine.
                Choreographer.getInstance().postFrameCallback(this);
                if (!renderAdded) {
                    com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.RENDER_LOOP_DIED);
                    return;
                }
                if (renderView != null) {
                    renderView.update(provider.engine());
                    renderView.invalidate();
                }
            }
        };
        Choreographer.getInstance().postFrameCallback(frameCallback);

        // Debug loop: calibration text + renderer status line.
        debugTicker = new Runnable() {
            @Override
            public void run() {
                if (!debugAdded) return;
                if (debugVisible && debugPanel != null) {
                    debugPanel.setText(buildDebugText(provider));
                } else if (debugPanel != null) {
                    debugPanel.setText("");
                }
                handler.postDelayed(this, DEBUG_PERIOD_MS);
            }
        };
        handler.post(debugTicker);
    }

    /**
     * The dev panel — <b>five lines, scoped to the aim work in flight.</b>
     *
     * <p>This used to print about twenty-eight lines (key hex, endpoint, server
     * IP, ghost, teleport, visibility mask, HP owners, the full camera
     * calibration readout and an eight-line view/lead/camx block). All of it is
     * still available; none of it was deleted. It was answering questions that
     * are closed, and at 10sp it buried the four or five values that are
     * actually being iterated on.</p>
     *
     * <p><b>What is here now, and why:</b></p>
     * <ul>
     *   <li>{@code LINK} — the three "nothing below this can be right" facts,
     *       folded onto one row: is the tunnel pointed at the app being played,
     *       did the lobby sniffer capture the session key, and has the header
     *       solver converged. A wrong target measured 1 outbound packet against
     *       10 886 inbound with no other symptom, so it stays first.</li>
     *   <li>{@code SCOPE} — the Cmd 137/138 latch (see
     *       {@code protocol.ScopeState}). {@code n=starts/stops} should stay
     *       balanced; {@code desync} climbing means the solver is missing edges.
     *       Reads {@code hip (no edge yet)} until the first scope of a match —
     *       which is NOT the same as "not scoped", and says so.</li>
     *   <li>{@code FRZ} — freeze is the force multiplier for the one-shot aim,
     *       and {@code dmgthru} climbing during a fight is the combat-feedback
     *       pass working.</li>
     *   <li>{@code AIM} — the nvmux daemon. Without it a dead aim and a working
     *       aim look identical, which cost a full session once already.</li>
     *   <li>{@code ESP} — box count, the EFFECTIVE hFOV the projection uses, and
     *       the own-state source.</li>
     * </ul>
     *
     * <p><b>To bring a row back</b> for a different piece of work: every one was
     * a single {@code sb.append(X.debugLine())}. The long camera readout is
     * {@code renderView.statusLine()} + {@code provider.stateText()}; ghost,
     * teleport, visibility and HP are {@code GhostFilter.debugLine()},
     * {@code TeleportFilter.debugLine()}, {@code VisibilityMask.debugLine()} and
     * {@code engine().pri().debugLine()}. Nothing needs re-deriving.</p>
     */
    private String buildDebugText(EspStateProvider provider) {
        StringBuilder sb = new StringBuilder(256);

        // LINK: target app / session key / header solver, in that order of
        // "if this is wrong, stop reading". The key is printed as a yes-no --
        // the hex value itself told us nothing once the sniffer was proven.
        sb.append("LINK  ")
          .append(com.nepdon.wire.config.GameTargets.debugLine(context));
        sb.append(com.nepdon.wire.protocol.LobbyKeySniffer.lastKeyHex() == null
                ? "  key=WAIT" : "  key=ok");

        com.nepdon.wire.protocol.PacketPipeline pipe =
                com.nepdon.wire.protocol.PacketPipeline.current();
        sb.append("  ").append(pipe == null ? "no tunnel" : pipe.describe()).append('\n');

        // SCOPE: the wire answer to "is the player scoped" -- Cmd 137/138, two
        // integer compares on the outbound hot path. NOTE the latch is blind
        // until the header solver converges and after every key rotation, which
        // is why the line distinguishes "hip" from "hip (no edge yet)" instead
        // of reporting a bare boolean.
        sb.append("SCOPE ")
          .append(com.nepdon.wire.protocol.ScopeState.debugLine()).append('\n');

        // TIER: the varId-12 probe. Raw numbers on purpose — nothing has
        // measured what EPRI_PLAYER_SIGHTING_ID means yet, so nothing here
        // pretends to. Scope in with a 2x, a 4x and an 8x and compare @in=.
        sb.append("TIER  ")
          .append(com.nepdon.wire.protocol.ScopeState.probeLine()).append('\n');

        // FREEZE: drop counts world-state packets withheld; dmgthru counts
        // damage/death/HP messages passed that the old 21-450 byte window
        // would have eaten. dmgthru climbing during a fight is the fix working.
        sb.append("FRZ   ")
          .append(com.nepdon.wire.filter.FreezeFilter.debugLine()).append("\n");

        // AIM: the nvmux grab-merge daemon. Shows whether Shizuku is up and
        // permitted, whether the daemon's stdin pipe is alive, how many
        // commands actually went out, and why the last attempt bailed. Without
        // this line an aim that is silently dead looks identical to one that is
        // working — which cost a full session when Shizuku's server was
        // crash-looping on this ROM and nothing said so.
        sb.append("AIM   ")
          .append(Daemon.statusLine()).append("\n");

        // TGT: what the aim WOULD shoot at, and when it refuses, why.
        // noTeam climbing means the reliable channel is not decrypting, so the
        // server team byte is absent — the aim refuses rather than risk a
        // teammate, because "ENEMY" is also what "no team data" looks like.
        sb.append("TGT   ")
          .append(com.nepdon.wire.ui.aim.AimTarget.debugLine()).append("\n");

        if (renderView != null) sb.append(renderView.briefLine());
        return sb.toString();
    }

    /** Toggles the debug text panel (the renderer keeps running). */
    public void setDebugVisible(boolean visible) {
        debugVisible = visible;
        if (debugPanel != null && !visible) debugPanel.setText("");
    }

    public boolean isDebugVisible() {
        return debugVisible;
    }

    /** Live projection readout for the calibration panel; never null. */
    public String calibHint() {
        EspRenderView rv = renderView;
        return (rv == null) ? "ESP renderer not attached" : rv.calibHint();
    }

    /** Hides and tears down both windows. */
    public void hide() {
        if (frameCallback != null) {
            try {
                Choreographer.getInstance().removeFrameCallback(frameCallback);
            } catch (Exception e) { }
            frameCallback = null;
        }
        if (handler != null && debugTicker != null) {
            handler.removeCallbacks(debugTicker);
        }
        if (renderAdded && renderView != null) {
            try {
                wm.removeView(renderView);
            } catch (Exception e) { }
        }
        if (debugAdded && debugPanel != null) {
            try {
                wm.removeView(debugPanel);
            } catch (Exception e) { }
        }
        renderAdded = false;
        debugAdded = false;
        renderView = null;
        debugPanel = null;
    }

    public boolean isShown() {
        return renderAdded || debugAdded;
    }
}
