package com.nepdon.wire.ui.button;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.nepdon.wire.R;

/**
 * Shared drag touch listeners for the overlay. Both listeners move a view by
 * updating its {@link WindowManager.LayoutParams} as the finger drags.
 */
public final class OverlayTouchListener {

    private OverlayTouchListener() { }

    /**
     * Drag listener with a long-press (>2s) position lock. Used by the mask bar
     * and the dev popup.
     */
    public static class MaskBarTouchListener implements View.OnTouchListener {
        private final WindowManager wm;
        private final Context context;
        private final WindowManager.LayoutParams p;
        private final View view;

        private int ox, oy;
        private float startTouchRawX, startTouchRawY;
        private int activePointerId = -1;
        private boolean drag = false;
        private boolean isLocked = false;
        private long downTime;

        public MaskBarTouchListener(WindowManager wm, Context context, WindowManager.LayoutParams p, View view) {
            this.wm = wm;
            this.context = context;
            this.p = p;
            this.view = view;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            int actionIndex = e.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (activePointerId != -1) return true;

                    activePointerId = e.getPointerId(actionIndex);
                    ox = p.x;
                    oy = p.y;
                    startTouchRawX = e.getRawX() - e.getX() + e.getX(actionIndex);
                    startTouchRawY = e.getRawY() - e.getY() + e.getY(actionIndex);
                    drag = false;
                    downTime = System.currentTimeMillis();
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (activePointerId == -1) return false;
                    int idx = e.findPointerIndex(activePointerId);
                    if (idx < 0) return false;
                    if (isLocked) return true;

                    float currentRawX = e.getRawX() - e.getX() + e.getX(idx);
                    float currentRawY = e.getRawY() - e.getY() + e.getY(idx);
                    float dx = currentRawX - startTouchRawX;
                    float dy = currentRawY - startTouchRawY;

                    // Platform touch slop, not a raw-pixel literal. This was
                    // 8f PIXELS = 3.2dp at this device's density, well under
                    // normal finger jitter, so ordinary taps were classified
                    // as drags and their click silently dropped. Same bug as
                    // the floating buttons had; here it only loses taps,
                    // because nothing fires on ACTION_DOWN.
                    int slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        drag = true;
                        p.x = ox + (int) dx;
                        p.y = oy + (int) dy;
                        try {
                            wm.updateViewLayout(view, p);
                        } catch (Exception ex) { }
                    }
                    return true;
                }

                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP: {
                    int pointerId = e.getPointerId(actionIndex);
                    if (pointerId != activePointerId) return true;

                    long held = System.currentTimeMillis() - downTime;
                    if (held > 2000) {
                        isLocked = !isLocked;
                        Toast.makeText(
                                context,
                                isLocked ? context.getString(R.string.pos_locked) : context.getString(R.string.pos_unlocked),
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    activePointerId = -1;
                    drag = false;
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    activePointerId = -1;
                    drag = false;
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Plain drag listener (no click, no lock). Used by the stats text view.
     */
    public static class StatTouchListener implements View.OnTouchListener {
        private final WindowManager wm;
        private final WindowManager.LayoutParams p;
        private final View view;

        private int ox, oy;
        private float startTouchRawX, startTouchRawY;
        private int activePointerId = -1;

        public StatTouchListener(WindowManager wm, WindowManager.LayoutParams p, View view) {
            this.wm = wm;
            this.p = p;
            this.view = view;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            int actionIndex = e.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (activePointerId != -1) return true;

                    activePointerId = e.getPointerId(actionIndex);
                    ox = p.x;
                    oy = p.y;

                    startTouchRawX = e.getRawX() - e.getX() + e.getX(actionIndex);
                    startTouchRawY = e.getRawY() - e.getY() + e.getY(actionIndex);
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (activePointerId == -1) return false;

                    int idx = e.findPointerIndex(activePointerId);
                    if (idx < 0) return false;

                    float currentRawX = e.getRawX() - e.getX() + e.getX(idx);
                    float currentRawY = e.getRawY() - e.getY() + e.getY(idx);

                    p.x = ox + (int) (currentRawX - startTouchRawX);
                    p.y = oy + (int) (currentRawY - startTouchRawY);

                    try {
                        wm.updateViewLayout(view, p);
                    } catch (Exception ex) { }

                    return true;
                }

                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP: {
                    int pointerId = e.getPointerId(actionIndex);
                    if (pointerId == activePointerId) {
                        activePointerId = -1;
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    activePointerId = -1;
                    return true;
                }
            }

            return false;
        }
    }
}
