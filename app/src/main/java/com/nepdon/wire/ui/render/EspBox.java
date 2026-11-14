package com.nepdon.wire.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Draws the corner-bracket ESP box for one enemy entity.
 *
 * <p>Self-contained: owns its own {@link Paint} instances, initialised once in
 * the constructor, so {@link EspRenderView} stays allocation-free on
 * {@code onDraw}. Call {@link #draw} once per visible enemy per frame.</p>
 *
 * <h3>Box geometry</h3>
 * <pre>
 *   left, top ──────── right, top
 *       |                   |
 *       |                   |
 *   left, bottom ─── right, bottom
 * </pre>
 * <p>Only L-shaped corner arms are drawn (not a full outline), so the box
 * stays readable when several enemies overlap.</p>
 */
public final class EspBox {

    // ---- role stroke colours (match EspRenderView's palette) ----
    private static final int COLOR_ENEMY   = Color.argb(235, 255,  64,  64);
    private static final int COLOR_TEAM    = Color.argb(225,  70, 190, 255);
    private static final int COLOR_UNKNOWN = Color.argb(215, 235, 205,  90);
    private static final int FILL_ALPHA    = 26;   // translucent fill

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill   = new Paint();

    /** Cached last colour so setColor is only called when it changes. */
    private int lastStrokeColor = 0;

    /**
     * @param density {@code DisplayMetrics.density} for stroke width scaling
     */
    public EspBox(float density) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5f, 1.5f * density));
        stroke.setColor(COLOR_ENEMY);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.argb(FILL_ALPHA,
                Color.red(COLOR_ENEMY),
                Color.green(COLOR_ENEMY),
                Color.blue(COLOR_ENEMY)));
    }

    /**
     * Draws the box for one enemy.
     *
     * @param canvas     target canvas
     * @param left       box left edge, px
     * @param top        box top edge (head), px
     * @param right      box right edge, px
     * @param bottom     box bottom edge (feet), px
     * @param role       {@code EspEngine.ROLE_ENEMY / ROLE_TEAM / ROLE_UNKNOWN}
     * @param alpha      255 = fully opaque; lower for stale data
     * @param boxColor   custom ARGB from {@code EspConfig.BOX_COLOR_ARGB}
     *                   (used for ENEMY role; TEAM and UNKNOWN ignore it)
     */
    public void draw(Canvas canvas,
                     float left, float top, float right, float bottom,
                     int role, int alpha, int boxColor) {

        // ---- pick colour by role ----
        int color;
        switch (role) {
            case 1:  color = COLOR_TEAM;    break;   // ROLE_TEAM
            case 0:  color = COLOR_UNKNOWN; break;   // ROLE_UNKNOWN
            default: color = boxColor;      break;   // ROLE_ENEMY
        }

        if (color != lastStrokeColor) {
            lastStrokeColor = color;
            stroke.setColor(color);
            fill.setColor(Color.argb(FILL_ALPHA,
                    Color.red(color), Color.green(color), Color.blue(color)));
        }

        if (alpha != 255) {
            stroke.setAlpha(alpha);
        } else {
            stroke.setAlpha(255);
        }

        // ---- translucent fill ----
        canvas.drawRect(left, top, right, bottom, fill);

        // ---- four L-shaped corner brackets ----
        float w  = right  - left;
        float h  = bottom - top;
        float cw = Math.max(4f, w * 0.28f);   // horizontal arm length
        float ch = Math.max(4f, h * 0.28f);   // vertical   arm length

        // top-left
        canvas.drawLine(left,       top,        left + cw,  top,        stroke);
        canvas.drawLine(left,       top,        left,       top + ch,   stroke);
        // top-right
        canvas.drawLine(right - cw, top,        right,      top,        stroke);
        canvas.drawLine(right,      top,        right,      top + ch,   stroke);
        // bottom-left
        canvas.drawLine(left,       bottom,     left + cw,  bottom,     stroke);
        canvas.drawLine(left,       bottom - ch, left,      bottom,     stroke);
        // bottom-right
        canvas.drawLine(right - cw, bottom,     right,      bottom,     stroke);
        canvas.drawLine(right,      bottom - ch, right,     bottom,     stroke);
    }
}
