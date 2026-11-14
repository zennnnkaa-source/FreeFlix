package com.nepdon.wire.ui.button;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.nepdon.wire.R;

/**
 * Creates, paints, and lays out the floating round buttons, and owns their
 * touch listener (drag + click).
 *
 * <p>There used to be a second, private {@code BtnTouchListener} here — a copy
 * of {@code OverlayTouchListener.MaskBarTouchListener} adapted for buttons,
 * carrying its own 2 s long-press position lock. <b>Nothing ever constructed
 * it</b>: a tree-wide search found only its own declaration and constructor,
 * and this project uses no reflection for binding. Deleted 2026-09-16.</p>
 *
 * <p>It mattered more than its 98 lines. It was a near-identical second copy of
 * the click dispatch, so an edit applied to the wrong one would compile, read
 * correctly, and do nothing — this project's documented recurring failure mode.
 * The freeze-latency fix that moved the click to {@code ACTION_DOWN} was one
 * wrong scroll position away from exactly that.</p>
 *
 * <p>The long-press position lock is <b>not</b> lost with it: the live copy in
 * {@code OverlayTouchListener.MaskBarTouchListener} still backs the mask bar
 * and the dev popup ({@code DevPopup}), and still uses
 * {@code R.string.pos_locked} / {@code pos_unlocked}. Note that the floating
 * buttons never had that lock — the listener below has no {@code isLocked} —
 * so they remain draggable with no way to pin them.</p>
 */
public class OverlayButton {

    // NUVOLA, OVERLAY SIDE: minimal.
    //
    // These sit ON TOP OF LIVE GAMEPLAY, which is the whole reason they do not
    // look like the app. No gradient, no glow, no saturated fill: a flat scrim
    // over the ground colour and one accent, so a button never competes with
    // an enemy for your attention and never hides one behind a bloom.
    //
    // The old pair was a saturated red circle for OFF and a saturated green
    // one for ON - two of the four unrelated accents the redesign removed, and
    // red is reserved for enemy boxes.  docs/NUVOLA_DESIGN.md.
    private static final String FLOAT_BUTTON_OFF_FILL   = "#D9070A18";  // nv_scrim
    private static final String FLOAT_BUTTON_OFF_STROKE = "#7F1D1D";    // nv_line_strong
    private static final String FLOAT_BUTTON_ON_FILL    = "#47EF4444";  // nv_teal_28
    private static final String FLOAT_BUTTON_ON_STROKE  = "#EF4444";    // nv_teal

    /** Callback fired when a button is tapped (not dragged). */
    public interface ClickListener {
        void onClick(int id);
    }

    /**
     * Press/release notifications for hold-style buttons.
     *
     * <p>AIM is held rather than toggled, so it needs the down and up edges,
     * not a click. Kept as a separate optional listener so the four existing
     * toggle buttons are untouched.</p>
     */
    public interface HoldListener {
        void onPress(int id);
        void onRelease(int id);
    }

    /** Ids that report press/release instead of click. */
    private final java.util.HashSet<Integer> holdIds = new java.util.HashSet<>();
    private HoldListener holdListener;

    // FIRING ON ACTION_DOWN WAS TRIED AND REVERTED. Do not reintroduce it.
    //
    // The motive was real: a freeze tap costs the wait for the next inbound
    // world-state packet (0-198 ms, mean 61, over 222 toggles) PLUS everything
    // upstream of the filter flip, and the capture cannot see that upstream
    // part -- EV_FREEZE_ON is stamped inside noteFilterToggled(), after
    // filterEngine.toggle() has already returned. Firing on UP puts the whole
    // finger dwell, typically 40-120 ms, in front of the flip on every press.
    //
    // But a click on ACTION_DOWN has to be undone when the gesture turns out
    // to be a drag, and NOTHING CAN KNOW AT DOWN TIME WHICH IT IS. So every
    // drag toggled the filter on, then off again as the finger moved -- the
    // user saw freeze and ghost visibly flicker while repositioning a button,
    // and a filter that was already on flashed off and back on. Reported as
    // "it turns on for a split second before I'm done dragging".
    //
    // There is no threshold that fixes this, only one that makes it rarer:
    // the undo is required by the eager fire, not by the threshold. The
    // latency it bought is not worth a toggle the user cannot trust.
    //
    // What DID survive from that work, and is worth keeping:
    //   - PacketVpnService.applyFilterTap, which skips the AMS round trip
    //     (same process, so the intent went to system_server and came back to
    //     this very looper). Pure win, no behavioural change.
    //   - touchSlopPx below, replacing an 8-RAW-PIXEL literal that was
    //     dropping ordinary taps outright.
    //
    // If the dwell is ever worth attacking again, the shape that does NOT
    // flicker is: start a short settle timer on DOWN, fire when it expires
    // with the finger still inside slop, and cancel it if the finger moves
    // first. That fires early without ever having to take a toggle back.

    /** Marks an id as hold-style and registers the listener. */
    public void setHold(int id, HoldListener l) {
        holdIds.add(id);
        holdListener = l;
    }

    /** Result of {@link #makeBtn}: the created view, its layout params, and whether it was added. */
    public static final class ButtonResult {
        public final TextView view;
        public final WindowManager.LayoutParams params;
        public final boolean added;
        public final int id;

        ButtonResult(TextView view, WindowManager.LayoutParams params, boolean added, int id) {
            this.view = view;
            this.params = params;
            this.added = added;
            this.id = id;
        }
    }

    private final Context context;
    private final WindowManager wm;
    private final Typeface font;
    private final ClickListener clickListener;

    /**
     * Distance a finger may wander before the gesture counts as a drag.
     *
     * <p><b>This used to be the literal {@code 8f}, in RAW PIXELS.</b> At this
     * device's density that is 3.2dp — far below the platform's own touch slop
     * and below ordinary finger jitter on a 183px button pressed mid-match. So
     * a perfectly normal tap was routinely classified as a drag.</p>
     *
     * <p>While the click fired on ACTION_UP that only meant a tap was silently
     * ignored now and then. Once the click moved to ACTION_DOWN it became much
     * worse: the press toggled the filter, the wobble crossed 8px, the undo
     * fired, and the toggle landed back where it started — reported as
     * <i>"I turned it off but it is still on"</i>.</p>
     *
     * <p>{@code getScaledTouchSlop()} is the value every other Android control
     * uses, so the boundary now matches the user's muscle memory instead of
     * being 2.5x tighter than the rest of the system.</p>
     */
    private final int touchSlopPx;

    public OverlayButton(Context context, WindowManager wm, Typeface font, ClickListener clickListener) {
        this.context = context;
        this.wm = wm;
        this.font = font;
        this.clickListener = clickListener;
        this.touchSlopPx = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public ButtonResult makeBtn(String label, int x, int y, int id, int floatAlphaPercent, int floatSizePercent) {
        return makeBtn(label, x, y, id, floatAlphaPercent, floatSizePercent, 0);
    }

    /**
     * As above, with a glyph above the label.
     *
     * <p>{@code iconRes} of 0 means text only, which is what the buttons that
     * have no icon yet (AIM, the zoom step, CAPTURE) still pass. The icon is a
     * compound drawable rather than a second view: one overlay window per
     * button is already the budget, and {@code WindowManager.addView} is the
     * call that has failed silently here before.</p>
     */
    public ButtonResult makeBtn(String label, int x, int y, int id, int floatAlphaPercent,
                                int floatSizePercent, int iconRes) {
        Button tv = new Button(context);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setAlpha(floatAlphaPercent / 100f);

        float scale = floatSizePercent / 100f;
        // THESE ARE PIXELS, AND THE FLOOR WAS DENSITY-BLIND.
        // Math.max(72, ...) is 72 PHYSICAL pixels, which on this 3x screen is a
        // 24dp button and on a 4x screen 18dp -- half the 48dp minimum touch
        // target, on a control pressed mid-match. Both the floor and the base
        // size are now expressed in dp and converted once.
        final float dp = context.getResources().getDisplayMetrics().density;
        final int minPx = Math.round(52f * dp);          // overlay-grade floor
        final int basePx = Math.round(56f * dp);
        int sizePx = Math.max(minPx, Math.round(basePx * scale));
        tv.setTextSize(11f * Math.max(0.86f, scale));

        tv.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(false);
        tv.setMaxLines(2);
        tv.setClickable(true);
        tv.setLongClickable(true);
        tv.setAllCaps(false);
        tv.setIncludeFontPadding(false);
        int innerPadding = Math.max(6, Math.round(10 * scale));
        tv.setPadding(innerPadding, innerPadding, innerPadding, innerPadding);
        tv.setBackground(getButtonStyle(false));
        applyIcon(tv, iconRes, sizePx, label);

        int type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                type,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT
        );

        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = dpToPx(x);
        p.y = dpToPx(y);

        boolean added = false;
        try {
            wm.addView(tv, p);
            added = true;
        } catch (Exception e) {
            added = false;
        }

        if (added) {
            tv.setOnTouchListener(new SrcVipButtonTouchListener(p, tv, id));
        }

        return new ButtonResult(tv, added ? p : null, added, id);
    }

    public void paintBtn(TextView tv, String label, boolean on, int floatAlphaPercent) {
        paintBtn(tv, label, on, floatAlphaPercent, 0);
    }

    /**
     * Repaint, re-attaching the glyph.
     *
     * <p>The icon has to be re-applied here and not just at creation:
     * {@code setText} does not disturb a compound drawable, but this method is
     * also the one the state broadcast calls, and an earlier version of the
     * overlay lost a button because a repaint path quietly dropped what the
     * create path had set.</p>
     */
    public void paintBtn(TextView tv, String label, boolean on, int floatAlphaPercent, int iconRes) {
        if (tv == null) return;
        tv.setAlpha(floatAlphaPercent / 100f);
        tv.setTextColor(Color.WHITE);
        tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        tv.setBackground(getButtonStyle(on));
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        if (iconRes != 0) applyIcon(tv, iconRes, tv.getWidth() > 0 ? tv.getWidth() : 0, label);
    }

    /**
     * Replaces the label with {@code iconRes}, centred and sized to the button.
     *
     * <p><b>Icon only.</b> A snowflake and the word FREEZE inside one circle
     * said the same thing twice, and paying for both left the glyph too small
     * to read at a glance — the only way this control is ever read.</p>
     *
     * <h3>A top compound drawable is NOT centred vertically</h3>
     *
     * <p>This is the part I got wrong twice. {@code TextView} draws the top
     * compound drawable at {@code scrollY + mPaddingTop} — <b>top-aligned</b> —
     * and only centres it horizontally; the text is then laid out below it.
     * Setting the text size to 0 removes the label's line box but does nothing
     * about that, so the glyph sat about 35px high in a 183px button, which is
     * exactly what "the centering is off" looked like.</p>
     *
     * <p>So the PADDING does the centring: top and bottom are set to half the
     * slack, which puts the drawable's own box in the middle. Horizontal
     * padding is left alone because TextView already centres across it.</p>
     *
     * <p>{@code setBounds} is explicit because {@code
     * setCompoundDrawablesWithIntrinsicBounds} would pin the icon at the 24dp
     * it was authored at and ignore the size slider, which goes to 260%.</p>
     */
    private void applyIcon(TextView tv, int iconRes, int buttonPx, String label) {
        if (iconRes == 0) return;
        android.graphics.drawable.Drawable d;
        try {
            d = context.getResources().getDrawable(iconRes);
        } catch (Exception e) {
            // A missing or unparsable vector must not cost us the button. Leave
            // the label in place so the control is still identifiable, and say
            // so rather than showing a blank circle.
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.UI_BUTTON_ICON_MISSING);
            return;
        }
        if (d == null) return;

        tv.setText("");
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 0f);
        // It still needs a NAME. A button with no text and no content
        // description is unlabelled to TalkBack and anonymous in the layout
        // dumps this project uses to inspect the overlay.
        tv.setContentDescription(label);

        int side = buttonSide(tv, buttonPx);
        int px = iconPx(side);
        d.setBounds(0, 0, px, px);
        tv.setCompoundDrawablePadding(0);
        tv.setCompoundDrawables(null, d, null, null);
        centreIcon(tv, side, px);
    }

    /** Pads a text-free, top-drawable button so the glyph lands in the middle. */
    private static void centreIcon(TextView tv, int side, int iconPx) {
        int v = Math.max(0, (side - iconPx) / 2);
        int h = Math.max(0, (side - iconPx) / 2);
        tv.setPadding(h, v, h, v);
    }

    /** The button's edge length, from the best source available to the caller. */
    private static int buttonSide(TextView tv, int buttonPx) {
        if (buttonPx > 0) return buttonPx;
        if (tv.getWidth() > 0) return tv.getWidth();
        // An overlay view's layout params ARE the WindowManager ones, so this
        // is the real window width even before the first layout pass.
        android.view.ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp != null && lp.width > 0) return lp.width;
        return Math.round(56f * tv.getResources().getDisplayMetrics().density);
    }

    /**
     * The glyph's edge length inside a button {@code side} wide.
     *
     * <p>0.70, arrived at by measuring rather than by eye. The artwork does not
     * reach the edges of its own 24dp viewport, so the visible ink is well
     * under the box: at 0.58 it measured 39–42% of the button and read small,
     * at 0.70 it measures about 52%, which fills the ring without crowding
     * it.</p>
     *
     * <p>A square inscribed in a circle can reach 0.707 before its corners
     * touch the rim, and none of these three glyphs has ink in its corners —
     * the snowflake's diagonal arms stop at 60 degrees, not 45 — so 0.70 is
     * safe. Go higher and the corners start to matter.</p>
     */
    private static int iconPx(int side) {
        return Math.round(side * 0.70f);
    }

    public GradientDrawable getButtonStyle(boolean isOn) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(isOn ? FLOAT_BUTTON_ON_FILL : FLOAT_BUTTON_OFF_FILL));
        // 2dp, not 3: a thinner ring reads as UI rather than as a target, and
        // every pixel of stroke here is a pixel of gameplay it covers.
        g.setStroke(2, Color.parseColor(isOn ? FLOAT_BUTTON_ON_STROKE : FLOAT_BUTTON_OFF_STROKE));
        return g;
    }

    public void updateButtonLayout(TextView tv, WindowManager.LayoutParams params, int id, boolean isOn,
                                   int floatAlphaPercent, int floatSizePercent) {
        if (tv == null || params == null) return;

        // THE SAME px/dp BUG AS makeBtn, AND I ONLY FIXED IT THERE.
        //
        // This is the resize path: change the size in settings and the button
        // reverted to the density-blind maths, so a 3x screen got a 24dp touch
        // target again. Fixing one of two call sites is how a fixed bug comes
        // back looking like a new one.
        float scale = floatSizePercent / 100f;
        final float dp = tv.getResources().getDisplayMetrics().density;
        final int minPx = Math.round(52f * dp);
        final int basePx = Math.round(56f * dp);
        int sizePx = Math.max(minPx, Math.round(basePx * scale));
        params.width = sizePx;
        params.height = sizePx;
        // The glyph tracks the slider too. Resizing the window and leaving a
        // 24dp icon inside a 145dp button is the same class of half-fix as the
        // px/dp bug above: correct in one of two paths.
        android.graphics.drawable.Drawable[] cur = tv.getCompoundDrawables();
        boolean iconned = cur != null && cur[1] != null;
        if (iconned) {
            int ipx = iconPx(sizePx);
            cur[1].setBounds(0, 0, ipx, ipx);
            tv.setCompoundDrawables(null, cur[1], null, null);
            centreIcon(tv, sizePx, ipx);
        } else {
            // Text buttons only. Giving an iconned button a text size back
            // restores the empty line box applyIcon collapsed, and re-padding
            // it below would undo the centring every time the slider moves.
            tv.setTextSize(11f * Math.max(0.86f, scale));
        }
        if (!iconned) {
            int innerPadding = Math.max(Math.round(4f * dp), Math.round(6f * dp * scale));
            tv.setPadding(innerPadding, innerPadding, innerPadding, innerPadding);
        }
        tv.setAlpha(floatAlphaPercent / 100f);

        try {
            wm.updateViewLayout(tv, params);
        } catch (Exception e) {
            // THIS SWALLOW HID A REAL BUG FOR A WHOLE SESSION.
            //
            // A stray rm() detached the zoom button's window while leaving its
            // fields set, so this threw "View not attached to window manager"
            // on every later layout pass -- silently. The button simply
            // vanished, with nothing anywhere to say why.
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.UI_BUTTON_LAYOUT_THREW);
        }

        paintBtn(tv, labelForId(id), isOn, floatAlphaPercent);
    }

    private String labelForId(int id) {
        switch (id) {
            case 2: return context.getString(R.string.freeze);
            case 3: return context.getString(R.string.ghost);
            case 4: return context.getString(R.string.tele);
            case 9: return context.getString(R.string.aim);
            case 10: return zoomLabel();
            default: return "";
        }
    }

    /** Live label for the zoom button, so it shows the current level. */
    private String zoomLabel() {
        float z = com.nepdon.wire.config.EspConfig.FOV_ZOOM;
        return String.format(java.util.Locale.US, "%.0fX", z);
    }

    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(dp * density));
    }

    private class SrcVipButtonTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams p;
        private final TextView tv;
        private final int id;

        private int startX, startY;
        private float startRawX, startRawY;
        private int activePointerId = -1;
        private boolean drag = false;

        SrcVipButtonTouchListener(WindowManager.LayoutParams p, TextView tv, int id) {
            this.p = p;
            this.tv = tv;
            this.id = id;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int action = e.getActionMasked();
            int actionIndex = e.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (activePointerId != -1) return true;
                    activePointerId = e.getPointerId(actionIndex);
                    startX = p.x;
                    startY = p.y;
                    startRawX = e.getRawX() - e.getX() + e.getX(actionIndex);
                    startRawY = e.getRawY() - e.getY() + e.getY(actionIndex);
                    drag = false;
                    tv.animate().cancel();
                    tv.animate().scaleX(0.94f).scaleY(0.94f).setDuration(40L).start();
                    if (holdListener != null && holdIds.contains(id)) {
                        holdListener.onPress(id);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (activePointerId == -1) return false;
                    int idx = e.findPointerIndex(activePointerId);
                    if (idx < 0) return false;
                    float currentRawX = e.getRawX() - e.getX() + e.getX(idx);
                    float currentRawY = e.getRawY() - e.getY() + e.getY(idx);
                    float dx = currentRawX - startRawX;
                    float dy = currentRawY - startRawY;
                    if (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx) {
                        drag = true;
                        p.x = startX + (int) dx;
                        p.y = startY + (int) dy;
                        try {
                            wm.updateViewLayout(tv, p);
                        } catch (Exception ex) { }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    int pointerId = e.getPointerId(actionIndex);
                    if (pointerId != activePointerId) return true;
                    tv.animate().cancel();
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(40L).start();
                    if (holdListener != null && holdIds.contains(id)) {
                        holdListener.onRelease(id);
                    } else if (!drag) {
                        clickListener.onClick(id);
                    }
                    activePointerId = -1;
                    drag = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    tv.animate().cancel();
                    tv.animate().scaleX(1f).scaleY(1f).setDuration(40L).start();
                    // Release on cancel too, or a lost gesture leaves aim latched on.
                    if (holdListener != null && holdIds.contains(id)) {
                        holdListener.onRelease(id);
                    }
                    activePointerId = -1;
                    drag = false;
                    return true;
            }

            return false;
        }
    }
}
