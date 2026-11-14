package com.nepdon.wire.ui.button;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * Animated ring drawable used behind the floating buttons. Ported verbatim from
 * the original {@code FloatingOverlayService.AnimatedCircleDrawable} inner class.
 */
public class AnimatedCircleDrawable extends Drawable {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baseRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fillRect = new RectF();
    private final RectF ringRect = new RectF();
    private final Matrix ringMatrix = new Matrix();
    private final float hueOffset;
    private SweepGradient sweepGradient;
    private float pulse;
    private float rotationDegrees;
    private boolean active;

    public AnimatedCircleDrawable(float hueOffset) {
        this.hueOffset = hueOffset;
        baseRingPaint.setStyle(Paint.Style.STROKE);
        baseRingPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setActive(boolean active) {
        this.active = active;
        updatePaints(getBounds());
        invalidateSelf();
    }

    public void setPulse(float pulse) {
        this.pulse = pulse;
        updatePaints(getBounds());
        invalidateSelf();
    }

    public void setRotationDegrees(float rotationDegrees) {
        this.rotationDegrees = rotationDegrees;
        Rect bounds = getBounds();
        if (sweepGradient != null && !bounds.isEmpty()) {
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            ringMatrix.reset();
            ringMatrix.preRotate(rotationDegrees + hueOffset, cx, cy);
            sweepGradient.setLocalMatrix(ringMatrix);
        }
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        updatePaints(bounds);
    }

    private void updatePaints(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return;

        float stroke = Math.max(2.4f, Math.min(bounds.width(), bounds.height()) * (active ? 0.038f : 0.032f));
        float inset = stroke * 1.8f;
        fillRect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        ringRect.set(bounds.left + stroke, bounds.top + stroke, bounds.right - stroke, bounds.bottom - stroke);

        float activePulse = active ? (0.78f + (0.22f * pulse)) : (0.55f + (0.12f * pulse));
        int glowColor = active
                ? Color.argb((int) (110 * activePulse), 5, 242, 5)
                : Color.argb((int) (96 * activePulse), 181, 67, 247);
        glowPaint.setColor(glowColor);

        fillPaint.setShader(null);
        fillPaint.setColor(active ? Color.parseColor("#05F205") : Color.parseColor("#B543F7"));

        highlightPaint.setShader(new RadialGradient(
                fillRect.left + (fillRect.width() * 0.32f),
                fillRect.top + (fillRect.height() * 0.28f),
                Math.max(fillRect.width(), fillRect.height()) * 0.5f,
                new int[]{
                        active
                                ? Color.argb((int) (52 + (12 * pulse)), 236, 255, 236)
                                : Color.argb((int) (48 + (10 * pulse)), 241, 225, 255),
                        Color.argb(0, 255, 255, 255)
                },
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP
        ));

        baseRingPaint.setColor(Color.argb(active ? 72 : 58, 255, 255, 255));
        baseRingPaint.setStrokeWidth(Math.max(1.1f, stroke * 0.58f));

        sweepGradient = new SweepGradient(
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                new int[]{
                        Color.parseColor("#FFFF3B3B"),
                        Color.parseColor("#FFFF8C3B"),
                        Color.parseColor("#FFFFE83B"),
                        Color.parseColor("#FF52FF7A"),
                        Color.parseColor("#FF32F0FF"),
                        Color.parseColor("#FF5A78FF"),
                        Color.parseColor("#FFC04CFF"),
                        Color.parseColor("#FFFF4FD4"),
                        Color.parseColor("#FFFF3B3B")
                },
                new float[]{
                        0.00f,
                        0.14f,
                        0.28f,
                        0.42f,
                        0.56f,
                        0.70f,
                        0.84f,
                        0.93f,
                        1.00f
                }
        );
        ringPaint.setShader(sweepGradient);
        ringPaint.setStrokeWidth(stroke);
        setRotationDegrees(rotationDegrees);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawOval(ringRect, glowPaint);
        canvas.drawOval(fillRect, fillPaint);
        canvas.drawOval(fillRect, highlightPaint);
        canvas.drawOval(ringRect, baseRingPaint);
        canvas.drawOval(ringRect, ringPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        glowPaint.setAlpha(alpha);
        highlightPaint.setAlpha(alpha);
        baseRingPaint.setAlpha(alpha);
        ringPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        glowPaint.setColorFilter(colorFilter);
        highlightPaint.setColorFilter(colorFilter);
        baseRingPaint.setColorFilter(colorFilter);
        ringPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
