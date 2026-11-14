package com.nepdon.wire.ui.edit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The layout editor's canvas: a scrim over the live game, the three placeable
 * elements, their handles, and the chips that leave.
 *
 * <h3>Three elements, three different handle sets</h3>
 *
 * <p>Not everything gets a bounding box. A <b>line</b> is edited by its two
 * endpoints — moving one is move, resize and rotate at once, with no rotation
 * handle to fight the drag and no box whose corners mean nothing for a segment.
 * The <b>fire button</b> is a circle, so it gets exactly one radius handle:
 * four corners on a circle would imply non-uniform scale, which is meaningless.
 * This is the model diagramming tools use for links versus nodes, and it is
 * also what every shipped Android on-screen-control editor converges on once
 * the corner handles turn out to be unusable with a thumb.</p>
 *
 * <h3>The drag anchor is a grab offset, never a recentre</h3>
 *
 * <p>On touch-down the offset between the finger and the element's anchor is
 * recorded, and every move re-derives the anchor from the live finger plus that
 * offset. The two tempting alternatives are both visibly wrong: setting the
 * anchor to the finger snaps the element under the thumb on frame one, and
 * accumulating {@code += (x - prevX)} drifts and loses the anchor across a snap.
 * Touch slop only <i>classifies</i> the gesture — once it is a drag, the element
 * has moved exactly as far as the finger has, never 8dp more.</p>
 *
 * <h3>One pointer owns a gesture</h3>
 *
 * <p>A second finger arriving mid-drag is ignored outright. There is no pinch:
 * mixing pinch with handles means a second pointer has to either steal or be
 * dropped, and both surprise the user. Tracking is by pointer <b>id</b>, never
 * index — indices are reordered between events, and the classic symptom is an
 * element teleporting the moment the first of two fingers lifts.</p>
 *
 * <h3>Everything is normalised on commit</h3>
 *
 * <p>Pixels live here and only here, derived in {@link #onSizeChanged} from the
 * view's own size — never from a {@code Display} query, which has been observed
 * returning the pre-rotation size to one overlay while its siblings got the new
 * one. {@link Layout} stores fractions, so a placement survives a density
 * change. This device reports 480 dpi and actually runs 398.</p>
 *
 * <h3>onDraw allocates nothing</h3>
 *
 * <p>Every {@code Paint}, {@code RectF} and scratch array is a field. The status
 * string is rebuilt when a transform commits, not per frame.</p>
 */
final class EditSurface extends View {

    /** Told when the user asks to leave. */
    interface Listener {
        void onExit();
    }

    // ---- element ids ----
    private static final int EL_NONE = -1;
    private static final int EL_LINE_X = 0;
    private static final int EL_LINE_Y = 1;
    private static final int EL_FIRE = 2;

    // ---- drag modes ----
    private static final int DRAG_NONE = 0;
    private static final int DRAG_BODY = 1;
    private static final int DRAG_P1 = 2;
    private static final int DRAG_P2 = 3;
    private static final int DRAG_RADIUS = 4;

    private static final int INVALID_POINTER = -1;

    // ---- dp constants (converted once, in onSizeChanged) ----
    /** Drawn size of a handle. Small enough not to hide what it sits on. */
    private static final float HANDLE_VISUAL_DP = 14f;
    /** Grabbable size of a handle. The accessibility floor is 48dp. */
    private static final float HANDLE_TOUCH_DP = 48f;
    /** How far off a line still counts as grabbing its body. */
    private static final float LINE_CORRIDOR_DP = 24f;
    /**
     * Shortest a line may be dragged.
     *
     * <p>64dp, so the two endpoint handles' centres stay at least 56dp apart —
     * 48dp of target plus 8dp of separation. Below that the handles overlap and
     * whichever one you meant, you get the other.</p>
     */
    private static final float MIN_LINE_DP = 64f;
    /** Pick radius for an element that is not currently selected. */
    private static final float PICK_DP = 28f;

    private final Paint scrim = new Paint();
    private final Paint lineXPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineYPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint firePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF exitChip = new RectF();
    private final RectF resetChip = new RectF();
    private final RectF scratchOval = new RectF();

    private final Listener listener;

    private float handleVisual, handleTouch, lineCorridor, minLine, pickSlop;
    private float textPad, labelLift;
    private int touchSlop;

    private int selected = EL_NONE;
    private int dragMode = DRAG_NONE;
    private int activePointer = INVALID_POINTER;
    private boolean dragLatched;
    private float downX, downY;
    private float grabDx, grabDy;

    /** Tap-to-cycle state: where the last pick landed and how deep we are. */
    private float lastPickX = -1e9f, lastPickY = -1e9f;
    private int cycleCount;
    private final int[] cand = new int[3];
    private final float[] candDist = new float[3];

    /** Rebuilt on commit, never in onDraw. */
    private String status = "";

    /**
     * Scrim alpha. Lower when the system is really blurring behind us, because
     * blur plus a heavy wash reads as fog rather than glass.
     */
    private int scrimAlpha = 0x99;

    EditSurface(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;

        scrim.setStyle(Paint.Style.FILL);

        lineXPaint.setStyle(Paint.Style.STROKE);
        lineXPaint.setColor(Color.parseColor("#FFFF6B6B"));
        lineXPaint.setStrokeCap(Paint.Cap.ROUND);

        lineYPaint.setStyle(Paint.Style.STROKE);
        lineYPaint.setColor(Color.parseColor("#FFFFA3A3"));
        lineYPaint.setStrokeCap(Paint.Cap.ROUND);

        firePaint.setStyle(Paint.Style.STROKE);
        firePaint.setColor(Color.parseColor("#FFF5A524"));

        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setColor(Color.parseColor("#FFFFFFFF"));

        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(Color.parseColor("#FF241010"));
        handleEdge.setStyle(Paint.Style.STROKE);
        handleEdge.setColor(Color.parseColor("#FFFFFFFF"));

        chipFill.setStyle(Paint.Style.FILL);
        chipFill.setColor(Color.argb(240, 24, 36, 58));
        chipText.setColor(Color.WHITE);
        chipText.setTextAlign(Paint.Align.CENTER);
        chipText.setFakeBoldText(true);

        label.setColor(Color.parseColor("#FFFEE2E2"));
        label.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(Color.parseColor("#FFFCA5A5"));

        setFocusable(false);
    }

    /** Called by the owner once it knows whether the system is blurring. */
    void setBlurring(boolean blurring) {
        scrimAlpha = blurring ? 0x40 : 0x99;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);

        // The ONE place dp becomes px. density is read live, so the device's
        // 398 override is used rather than the 480 its panel advertises.
        float d = getResources().getDisplayMetrics().density;
        handleVisual = HANDLE_VISUAL_DP * d * 0.5f;      // radius, not diameter
        handleTouch = HANDLE_TOUCH_DP * d * 0.5f;
        lineCorridor = LINE_CORRIDOR_DP * d;
        minLine = MIN_LINE_DP * d;
        pickSlop = PICK_DP * d;
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        float stroke = Math.max(2f, 1.6f * d);
        lineXPaint.setStrokeWidth(stroke);
        lineYPaint.setStrokeWidth(stroke);
        firePaint.setStrokeWidth(stroke);
        selPaint.setStrokeWidth(Math.max(1f, d));
        handleEdge.setStrokeWidth(Math.max(2f, 1.4f * d));

        chipText.setTextSize(13f * d);
        label.setTextSize(11f * d);
        hintPaint.setTextSize(11f * d);
        textPad = 16f * d;
        labelLift = 12f * d;

        float cw = 108f * d, ch = 52f * d, pad = 14f * d;
        exitChip.set(w - pad - cw, pad, w - pad, pad + ch);
        resetChip.set(exitChip.left - pad - cw, pad, exitChip.left - pad, pad + ch);

        // A layout authored on another surface can put an element's only handle
        // off-screen, and there is then no gesture that recovers it.
        Layout.clampAll();
        rebuildStatus();
    }

    // ==================================================================
    // Drawing
    // ==================================================================

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        scrim.setColor((scrimAlpha << 24) | 0x000A0E14);
        c.drawRect(0f, 0f, w, h, scrim);

        drawLine(c, EL_LINE_X, w, h, lineXPaint, "SWIPE X");
        drawLine(c, EL_LINE_Y, w, h, lineYPaint, "SWIPE Y");

        float fx = Layout.fireCxPx(w), fy = Layout.fireCyPx(h), fr = Layout.fireRPx(w);
        c.drawCircle(fx, fy, fr, firePaint);
        c.drawText("FIRE", fx, fy - fr - labelLift, label);
        if (selected == EL_FIRE) {
            scratchOval.set(fx - fr, fy - fr, fx + fr, fy + fr);
            c.drawOval(scratchOval, selPaint);
            float k = 0.70710678f;
            handle(c, fx + fr * k, fy + fr * k);
        }

        chip(c, exitChip, "EXIT");
        chip(c, resetChip, "RESET");

        float baseY = exitChip.bottom + hintPaint.getTextSize() * 1.8f;
        c.drawText(status, textPad, baseY, hintPaint);
        c.drawText("Drag a line's ENDS to aim and size it. Drag its middle to move.",
                textPad, baseY + hintPaint.getTextSize() * 1.4f, hintPaint);
    }

    private void drawLine(Canvas c, int el, int w, int h, Paint p, String name) {
        float x1, y1, x2, y2;
        if (el == EL_LINE_X) {
            x1 = Layout.lineXx1 * w; y1 = Layout.lineXy1 * h;
            x2 = Layout.lineXx2 * w; y2 = Layout.lineXy2 * h;
        } else {
            x1 = Layout.lineYx1 * w; y1 = Layout.lineYy1 * h;
            x2 = Layout.lineYx2 * w; y2 = Layout.lineYy2 * h;
        }
        c.drawLine(x1, y1, x2, y2, p);
        c.drawText(name, (x1 + x2) * 0.5f, (y1 + y2) * 0.5f - labelLift, label);
        if (selected == el) {
            handle(c, x1, y1);
            handle(c, x2, y2);
        }
    }

    private void handle(Canvas c, float x, float y) {
        c.drawCircle(x, y, handleVisual, handleFill);
        c.drawCircle(x, y, handleVisual, handleEdge);
    }

    private void chip(Canvas c, RectF r, String text) {
        c.drawRoundRect(r, 10f, 10f, chipFill);
        c.drawRoundRect(r, 10f, 10f, selPaint);
        c.drawText(text, r.centerX(),
                r.centerY() - (chipText.ascent() + chipText.descent()) * 0.5f, chipText);
    }

    // ==================================================================
    // Input
    // ==================================================================

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return true;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                activePointer = ev.getPointerId(0);
                downX = ev.getX();
                downY = ev.getY();
                dragLatched = false;
                dragMode = DRAG_NONE;

                // Chips are hit-tested BEFORE any element, so an element dragged
                // over EXIT can never trap the user inside the editor.
                if (exitChip.contains(downX, downY) || resetChip.contains(downX, downY)) {
                    return true;
                }
                pick(downX, downY, w, h);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                // Deliberately ignored. One pointer owns a gesture; there is no
                // pinch to hand it to.
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (activePointer == INVALID_POINTER || dragMode == DRAG_NONE) return true;
                int idx = ev.findPointerIndex(activePointer);
                if (idx < 0) return true;          // getX(-1) throws
                float x = ev.getX(idx), y = ev.getY(idx);

                if (!dragLatched) {
                    if (Math.abs(x - downX) <= touchSlop
                            && Math.abs(y - downY) <= touchSlop) {
                        return true;
                    }
                    dragLatched = true;
                }
                apply(x, y, w, h);
                rebuildStatus();
                postInvalidateOnAnimation();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                // If the OWNING finger lifted while another is down, do not
                // silently adopt the survivor -- that is how an element
                // teleports. End the gesture instead.
                int upId = ev.getPointerId(ev.getActionIndex());
                if (upId == activePointer) endGesture();
                return true;
            }

            case MotionEvent.ACTION_UP: {
                float x = ev.getX(), y = ev.getY();
                if (!dragLatched) {
                    if (exitChip.contains(x, y) && exitChip.contains(downX, downY)) {
                        endGesture();
                        if (listener != null) listener.onExit();
                        return true;
                    }
                    if (resetChip.contains(x, y) && resetChip.contains(downX, downY)) {
                        Layout.reset(getContext());
                        selected = EL_NONE;
                        rebuildStatus();
                        invalidate();
                        endGesture();
                        return true;
                    }
                }
                endGesture();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                // Treated exactly like UP. A missed CANCEL leaves an element
                // latched to a finger that no longer exists.
                endGesture();
                invalidate();
                return true;
        }
        return true;
    }

    private void endGesture() {
        activePointer = INVALID_POINTER;
        dragMode = DRAG_NONE;
        dragLatched = false;
    }

    /**
     * Chooses what this touch grabs.
     *
     * <p>Priority: a handle of the selected element, then its body, then the
     * <b>nearest-centre</b> candidate among the others. Nearest-centre rather
     * than first-that-contains is the fix for the oldest bug in this class of
     * editor: with two overlapping elements, "first one added wins" means one of
     * them can never be picked.</p>
     *
     * <p>Nearest-centre alone is still not enough, and the live build proved it:
     * drag a swipe path across the fire button and the path wins every touch in
     * that area, so the button underneath becomes unselectable and cannot be
     * moved out from under it. <b>Tapping the same spot again cycles</b> to the
     * next overlapping element, which is the standard escape and the only one
     * that needs no extra chrome on a screen that is already busy. The cycle
     * order is by centre distance, so the first tap still lands on the obvious
     * candidate and repeats reach the buried ones.</p>
     */
    private void pick(float x, float y, int w, int h) {
        boolean repeat = cycleCount > 0
                && dist(x, y, lastPickX, lastPickY) <= handleTouch;

        // A fresh touch on the selected element grabs it, so a drag starts on
        // the first frame. A repeat tap deliberately skips that: otherwise the
        // element on top consumes every tap and nothing beneath it is reachable.
        if (!repeat && selected != EL_NONE && grabSelected(x, y, w, h)) {
            lastPickX = x;
            lastPickY = y;
            cycleCount = 0;
            return;
        }

        int n = 0;
        for (int el = EL_LINE_X; el <= EL_FIRE; el++) {
            if (distanceTo(el, x, y, w, h) > pickSlop) continue;
            // Insertion sort by centre distance; three elements, so this is
            // shorter and cheaper than anything with a comparator.
            float dc = centreDistance(el, x, y, w, h);
            int at = n;
            while (at > 0 && candDist[at - 1] > dc) {
                candDist[at] = candDist[at - 1];
                cand[at] = cand[at - 1];
                at--;
            }
            candDist[at] = dc;
            cand[at] = el;
            n++;
        }

        lastPickX = x;
        lastPickY = y;

        if (n == 0) {
            selected = EL_NONE;
            dragMode = DRAG_NONE;
            cycleCount = 0;
            rebuildStatus();
            return;
        }

        cycleCount = repeat ? cycleCount + 1 : 0;
        selected = cand[cycleCount % n];
        if (!grabSelected(x, y, w, h)) {
            // Selected but not grabbed: the touch is inside the pick slop yet
            // outside the element's own hit shape. Selecting without dragging is
            // the right answer -- the handles are now on screen to aim at.
            dragMode = DRAG_NONE;
        }
        rebuildStatus();
    }

    /** Sets {@link #dragMode} and the grab offset for the selected element. */
    private boolean grabSelected(float x, float y, int w, int h) {
        if (selected == EL_FIRE) {
            float fx = Layout.fireCxPx(w), fy = Layout.fireCyPx(h), fr = Layout.fireRPx(w);
            float k = 0.70710678f;
            float hx = fx + fr * k, hy = fy + fr * k;
            if (dist(x, y, hx, hy) <= handleTouch) {
                dragMode = DRAG_RADIUS;
                grabDx = fr - dist(x, y, fx, fy);   // finger-to-rim error
                grabDy = 0f;
                return true;
            }
            if (dist(x, y, fx, fy) <= fr + pickSlop) {
                dragMode = DRAG_BODY;
                grabDx = fx - x;
                grabDy = fy - y;
                return true;
            }
            return false;
        }

        float x1, y1, x2, y2;
        if (selected == EL_LINE_X) {
            x1 = Layout.lineXx1 * w; y1 = Layout.lineXy1 * h;
            x2 = Layout.lineXx2 * w; y2 = Layout.lineXy2 * h;
        } else {
            x1 = Layout.lineYx1 * w; y1 = Layout.lineYy1 * h;
            x2 = Layout.lineYx2 * w; y2 = Layout.lineYy2 * h;
        }
        float d1 = dist(x, y, x1, y1), d2 = dist(x, y, x2, y2);
        if (d1 <= handleTouch || d2 <= handleTouch) {
            if (d1 <= d2) {
                dragMode = DRAG_P1;
                grabDx = x1 - x;
                grabDy = y1 - y;
            } else {
                dragMode = DRAG_P2;
                grabDx = x2 - x;
                grabDy = y2 - y;
            }
            return true;
        }
        if (segDistance(x, y, x1, y1, x2, y2) <= lineCorridor) {
            dragMode = DRAG_BODY;
            grabDx = (x1 + x2) * 0.5f - x;
            grabDy = (y1 + y2) * 0.5f - y;
            return true;
        }
        return false;
    }

    private void apply(float x, float y, int w, int h) {
        if (selected == EL_FIRE) {
            if (dragMode == DRAG_RADIUS) {
                float fx = Layout.fireCxPx(w), fy = Layout.fireCyPx(h);
                // The handle sits at 45 degrees on the rim, so its distance from
                // the centre IS the radius: hypot(r/root2, r/root2) == r.
                // grabDx carries the down-time error between finger and rim, so
                // the circle does not jump to the finger on the first frame.
                float r = dist(x, y, fx, fy) + grabDx;
                Layout.fireR = clamp(r / w, Layout.MIN_FIRE_R, Layout.MAX_FIRE_R);
            } else if (dragMode == DRAG_BODY) {
                Layout.fireCx = clamp((x + grabDx) / w, 0f, 1f);
                Layout.fireCy = clamp((y + grabDy) / h, 0f, 1f);
            }
            Layout.clampAll();
            return;
        }

        boolean isX = (selected == EL_LINE_X);
        float x1 = (isX ? Layout.lineXx1 : Layout.lineYx1) * w;
        float y1 = (isX ? Layout.lineXy1 : Layout.lineYy1) * h;
        float x2 = (isX ? Layout.lineXx2 : Layout.lineYx2) * w;
        float y2 = (isX ? Layout.lineXy2 : Layout.lineYy2) * h;

        if (dragMode == DRAG_BODY) {
            float ccx = (x1 + x2) * 0.5f, ccy = (y1 + y2) * 0.5f;
            float dx = (x + grabDx) - ccx, dy = (y + grabDy) - ccy;
            x1 += dx; x2 += dx; y1 += dy; y2 += dy;
        } else if (dragMode == DRAG_P1 || dragMode == DRAG_P2) {
            float ax, ay;                                 // the fixed end
            if (dragMode == DRAG_P1) { ax = x2; ay = y2; } else { ax = x1; ay = y1; }
            float mx = x + grabDx, my = y + grabDy;

            float vx = mx - ax, vy = my - ay;
            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 1e-3f) return;                      // no direction to keep

            float ang = snapAngle((float) Math.toDegrees(Math.atan2(vy, vx)));
            if (len < minLine) len = minLine;             // handles must stay apart
            double r = Math.toRadians(ang);
            float nx = ax + (float) Math.cos(r) * len;
            float ny = ay + (float) Math.sin(r) * len;

            if (dragMode == DRAG_P1) { x1 = nx; y1 = ny; } else { x2 = nx; y2 = ny; }
        }

        if (isX) {
            Layout.lineXx1 = clamp(x1 / w, 0f, 1f); Layout.lineXy1 = clamp(y1 / h, 0f, 1f);
            Layout.lineXx2 = clamp(x2 / w, 0f, 1f); Layout.lineXy2 = clamp(y2 / h, 0f, 1f);
        } else {
            Layout.lineYx1 = clamp(x1 / w, 0f, 1f); Layout.lineYy1 = clamp(y1 / h, 0f, 1f);
            Layout.lineYx2 = clamp(x2 / w, 0f, 1f); Layout.lineYy2 = clamp(y2 / h, 0f, 1f);
        }
    }

    /**
     * Snaps a line's bearing to something deliberate.
     *
     * <p>A hard pull to the axes within 5°, because "exactly horizontal" and
     * "exactly vertical" are the overwhelmingly common intent for a swipe-X and
     * a swipe-Y path and are the two angles a thumb cannot hit by hand. A softer
     * 15° detent elsewhere, for the oblique case. 15° rather than the 45° some
     * drawing tools use: 45° is too coarse when near-axis is the normal answer.</p>
     */
    private static float snapAngle(float deg) {
        float a = deg;
        while (a <= -180f) a += 360f;
        while (a > 180f) a -= 360f;

        float[] axes = AXES;
        for (int i = 0; i < axes.length; i++) {
            if (Math.abs(a - axes[i]) <= 5f) return axes[i];
        }
        float k = Math.round(a / 15f) * 15f;
        return (Math.abs(a - k) <= 3f) ? k : a;
    }

    private static final float[] AXES = { 0f, 90f, 180f, -90f, -180f };

    // ==================================================================
    // Geometry helpers
    // ==================================================================

    private float distanceTo(int el, float x, float y, int w, int h) {
        if (el == EL_FIRE) {
            float d = dist(x, y, Layout.fireCxPx(w), Layout.fireCyPx(h));
            float r = Layout.fireRPx(w);
            return d <= r ? 0f : d - r;
        }
        if (el == EL_LINE_X) {
            return segDistance(x, y, Layout.lineXx1 * w, Layout.lineXy1 * h,
                    Layout.lineXx2 * w, Layout.lineXy2 * h);
        }
        return segDistance(x, y, Layout.lineYx1 * w, Layout.lineYy1 * h,
                Layout.lineYx2 * w, Layout.lineYy2 * h);
    }

    private float centreDistance(int el, float x, float y, int w, int h) {
        if (el == EL_FIRE) return dist(x, y, Layout.fireCxPx(w), Layout.fireCyPx(h));
        if (el == EL_LINE_X) return dist(x, y, Layout.lineXcx(w), Layout.lineXcy(h));
        return dist(x, y, Layout.lineYcx(w), Layout.lineYcy(h));
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float segDistance(float px, float py,
                                     float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float len2 = vx * vx + vy * vy;
        if (len2 < 1e-6f) return dist(px, py, ax, ay);
        float t = ((px - ax) * vx + (py - ay) * vy) / len2;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return dist(px, py, ax + vx * t, ay + vy * t);
    }

    private static float clamp(float v, float lo, float hi) {
        if (v != v) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Rebuilt on commit so {@code onDraw} only reads it. */
    private void rebuildStatus() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) { status = ""; return; }
        String who = (selected == EL_LINE_X) ? "SWIPE X"
                : (selected == EL_LINE_Y) ? "SWIPE Y"
                : (selected == EL_FIRE) ? "FIRE" : "nothing selected";
        if (selected == EL_FIRE) {
            status = who + "   r=" + Math.round(Layout.fireRPx(w)) + "px";
        } else if (selected == EL_LINE_X) {
            status = who + "   len=" + Math.round(Layout.lineXlenPx(w, h)) + "px";
        } else if (selected == EL_LINE_Y) {
            status = who + "   len=" + Math.round(Layout.lineYlenPx(w, h)) + "px";
        } else {
            status = "Tap a line or the fire circle to select it";
        }
    }
}
