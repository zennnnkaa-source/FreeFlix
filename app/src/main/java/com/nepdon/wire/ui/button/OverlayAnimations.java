package com.nepdon.wire.ui.button;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Owns the top banner view and the rainbow/pulse/banner animators that drive the
 * ring drawables and the banner text.
 */
public class OverlayAnimations {

    private final Context context;
    private final WindowManager wm;
    private final ArrayList<AnimatedCircleDrawable> ringDrawables = new ArrayList<AnimatedCircleDrawable>();

    private TextView topBanner;
    private boolean bannerAdded;
    private ValueAnimator shineAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator bannerAnimator;

    public OverlayAnimations(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    /**
     * The status title.
     *
     * <p>Replaces the decorative banner that used to sit here. That one was
     * branding with a status word appended, and it drove an infinite
     * ValueAnimator whose per-frame listener varied {@code setShadowLayer}'s
     * blur RADIUS -- which misses HWUI's blurred-text cache on every frame, at
     * 120 Hz, in a window composited over the game, forever. This one is a
     * static TextView whose text changes only when the state does.</p>
     *
     * <p>What it answers, in one glance, without opening the debug panel:
     * whether the engine can work yet, and how many players it can see. Those
     * are the two questions asked every time something looks wrong.</p>
     */
    public TextView makeTopBanner(int floatSizePercent) {
        TextView tv = new TextView(context);
        tv.setText("ENGINE OFF");
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setAlpha(0.97f);
        int padH = scalePx(14, floatSizePercent);
        int padV = scalePx(6, floatSizePercent);
        tv.setPadding(padH, padV, padH, padV);
        tv.setTextColor(COL_IDLE);
        // A 95% scrim, not a glow: this has to stay readable over a bright
        // gameplay frame, where the old translucent style fell to 3.46:1.
        tv.setBackground(pill(0xF20B0F14, 0xFF2C333D));

        int type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT
        );

        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.x = 0;
        p.y = scalePx(10, floatSizePercent);

        try {
            wm.addView(tv, p);
            bannerAdded = true;
        } catch (Exception e) { }

        topBanner = tv;
        return tv;
    }

    /** Rounded pill background, built in code so no drawable lookup is needed. */
    private static android.graphics.drawable.Drawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(999f);
        d.setStroke(2, stroke);
        return d;
    }

    // Design-system colours, matching res/values/colors.xml.
    private static final int COL_IDLE    = 0xFF7D8899;   // fl_text_tertiary
    private static final int COL_LEARN   = 0xFFFFB020;   // fl_warning
    private static final int COL_READY   = 0xFF22D3EE;   // fl_accent
    private static final int COL_ACTIVE  = 0xFF3DDC84;   // fl_success

    public void startRainbowAnimation() {
        if (ringDrawables.isEmpty()) return;
        if (shineAnimator != null) shineAnimator.cancel();
        shineAnimator = ValueAnimator.ofFloat(0f, 360f);
        shineAnimator.setDuration(7200L);
        shineAnimator.setInterpolator(new LinearInterpolator());
        shineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shineAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                float rotation = ((Float) animator.getAnimatedValue()).floatValue();
                for (int i = 0; i < ringDrawables.size(); i++) {
                    ringDrawables.get(i).setRotationDegrees(rotation + (i * 22f));
                }
            }
        });
        shineAnimator.start();
    }

    public void startPulseAnimation() {
        if (ringDrawables.isEmpty()) return;
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1800L);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                float pulse = ((Float) animator.getAnimatedValue()).floatValue();
                for (int i = 0; i < ringDrawables.size(); i++) {
                    ringDrawables.get(i).setPulse(Math.min(1f, pulse + (i * 0.08f)));
                }
            }
        });
        pulseAnimator.start();
    }

    // startBannerAnimation() DELETED.
    //
    // It drove an infinite ValueAnimator whose per-frame listener called
    // Color.parseColor twice and varied setShadowLayer's blur RADIUS. A
    // changing blur radius misses HWUI's blurred-text cache on every single
    // frame -- 120 times a second, in a window composited over the game, for
    // as long as the overlay existed. It was the most expensive thing this app
    // did, and it was decorating a word.
    //
    // The title now repaints only when its text actually changes.


    /** Cancels all running animators (used on service destroy). */
    public void cancelAll() {
        if (shineAnimator != null) shineAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (bannerAnimator != null) bannerAnimator.cancel();
    }

    /** Removes the top banner view (used on service destroy). */
    public void removeBanner() {
        if (bannerAdded && topBanner != null) {
            try {
                wm.removeView(topBanner);
            } catch (Exception e) { }
        }
        topBanner = null;
        bannerAdded = false;
    }

    // ---- Live engine status on the banner ----

    /** Banner status codes (drive setBannerStatus). */
    public static final int STATUS_IDLE = 0;        // engine off / no match
    public static final int STATUS_LEARNING = 1;    // engine on, stream learning
    public static final int STATUS_READY = 2;       // match learned: effects armed
    public static final int STATUS_ACTIVE = 3;      // an effect is running


    /**
     * Updates the banner text + color to the engine's live state, so the
     * user can SEE whether the packet engine is armed before trusting a
     * toggle. The rainbow animation only runs when READY/ACTIVE; LEARNING
     * shows an amber pulse, IDLE a dim gray.
     */
    /**
     * Sets the title.
     *
     * <p>Counts are passed in rather than pulled, because this class has no
     * reference to the engine and should not grow one -- the service already
     * polls state on a timer and is the right place to own that.</p>
     *
     * @param status  one of the STATUS_ constants
     * @param enemies live enemies seen, or -1 when the ESP is off
     * @param mates   live teammates seen
     * @param unknown live entities whose side is not yet decided
     * @param mounted true while riding anything -- shown because the ESP runs
     *                on a different, weaker set of inputs in that state, so
     *                "it went odd in a car" should be visibly a mode and not
     *                a mystery
     */
    public void setBannerStatus(int status, int enemies, int mates, int unknown,
                                boolean mounted) {
        if (topBanner == null) return;
        String text;
        int colour;
        switch (status) {
            case STATUS_ACTIVE:
                text = "ACTIVE";  colour = COL_ACTIVE;  break;
            case STATUS_READY:
                text = "READY";   colour = COL_READY;   break;
            case STATUS_LEARNING:
                // The honest answer to "why is nothing happening yet". The
                // movement stream has not been confirmed, so no effect can
                // bite and the ESP has no frame of reference.
                text = "LEARNING…"; colour = COL_LEARN; break;
            default:
                text = "ENGINE OFF"; colour = COL_IDLE;  break;
        }
        if (enemies >= 0) {
            StringBuilder sb = new StringBuilder(48);
            sb.append(text).append("  •  ").append(enemies).append('E');
            if (mates > 0) sb.append(" / ").append(mates).append('T');
            // Only shown while it is non-zero: a live unclassified count means
            // friend/foe has not settled and the colours are still moving,
            // which is worth seeing. At zero it is noise.
            if (unknown > 0) sb.append(" / ").append(unknown).append('?');
            if (mounted) sb.append("  •  RIDING");
            text = sb.toString();
        }
        if (text.equals(lastBannerText)) return;   // no needless relayout
        lastBannerText = text;
        topBanner.setText(text);
        topBanner.setTextColor(colour);
    }

    private String lastBannerText = "";

    private int scalePx(int basePx, int floatSizePercent) {
        return Math.max(1, Math.round(basePx * (floatSizePercent / 100f)));
    }
}
