package com.nepdon.wire.ui.overlay;

import com.nepdon.wire.ui.button.OverlayTouchListener;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The draggable "DEV LOG" popup shown when dev mode is enabled. Self-contained
 * overlay view; owns its own {@link WindowManager}
 * layout params and add/remove lifecycle.
 */
public class DevPopup {

    private final Context context;
    private final WindowManager wm;

    private TextView view;
    private WindowManager.LayoutParams params;
    private boolean added;

    public DevPopup(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    /** Creates and adds the popup (idempotent). */
    public void make(int floatSizePercent) {
        if (view != null) return;
        TextView tv = new TextView(context);
        tv.setText("DEV\nLOG");
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.parseColor("#FF071007"));
        tv.setBackgroundColor(Color.parseColor("#FFFF6B6B"));
        tv.setAlpha(0.92f);
        tv.setPadding(scalePx(8, floatSizePercent), scalePx(8, floatSizePercent),
                scalePx(8, floatSizePercent), scalePx(8, floatSizePercent));

        int type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;

        int size = dpToPx(112);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size, size, type, flags, PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = scalePx(230, floatSizePercent);
        p.y = scalePx(80, floatSizePercent);

        try {
            wm.addView(tv, p);
            added = true;
            params = p;
            tv.setOnTouchListener(new OverlayTouchListener.MaskBarTouchListener(wm, context, p, tv));
        } catch (Exception e) {
            // ignore
        }

        view = tv;
    }

    /** Shows or hides the popup to match {@code show}. */
    public void update(boolean show, int floatSizePercent) {
        if (show) {
            make(floatSizePercent);
            if (view != null && params != null) {
                view.setText("DEV\nLOG");
                view.setAlpha(0.92f);
                try {
                    wm.updateViewLayout(view, params);
                } catch (Exception e) {
                    // ignore
                }
            }
        } else {
            remove();
        }
    }

    /** Removes the popup from the window manager. */
    public void remove() {
        if (!added || view == null) return;
        try {
            wm.removeView(view);
        } catch (Exception e) {
            // ignore
        }
        view = null;
        params = null;
        added = false;
    }

    private int scalePx(int basePx, int floatSizePercent) {
        return Math.max(1, Math.round(basePx * (floatSizePercent / 100f)));
    }

    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(dp * density));
    }
}
