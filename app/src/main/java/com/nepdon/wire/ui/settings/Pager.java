package com.nepdon.wire.ui.settings;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.nepdon.wire.R;
import com.nepdon.wire.diag.Diag;
import com.nepdon.wire.diag.Slot;

/**
 * Turns the one long settings scroll into four reachable pages.
 *
 * <h3>Why pages</h3>
 *
 * <p>The screen had ~30 rows in a single column. On a 2340 px phone that is
 * six full swipes from the engine button to the permissions, every setting
 * looks equally important because nothing is grouped by anything but a small
 * heading, and the thing you open the app for most — starting the tunnel — is
 * the thing you scroll away from first.</p>
 *
 * <h3>Why a BOTTOM bar, not tabs at the top</h3>
 *
 * <p>This is a tool used one-handed, usually in the thirty seconds before a
 * match. On a 2340 px display the top of the screen cannot be reached with a
 * thumb without shifting grip. Navigation that lives at the top is navigation
 * you need two hands for. The bar is at the bottom, above the gesture inset.</p>
 *
 * <h3>Pages are shown and hidden, never created or destroyed</h3>
 *
 * <p>Every view stays in the tree. That is not laziness: {@code MainActivity},
 * {@code UiBindings} and {@code PermissionController} bind ~60 ids by
 * {@code findViewById} at startup and hold the references. A pager that
 * inflated pages lazily would hand them null for anything not yet created,
 * and the crash would land on whichever screen the user opened second. Toggling
 * visibility costs one measure pass on switch and nothing at all afterwards.</p>
 */
public final class Pager {

    /** Page ids, in bar order. */
    public static final int PAGE_HOME = 0;
    public static final int PAGE_OVERLAY = 1;
    public static final int PAGE_AIM = 2;
    public static final int PAGE_SYSTEM = 3;
    private static final int COUNT = 4;

    private final Activity activity;
    private final View[] pages = new View[COUNT];
    private final TextView[] tabs = new TextView[COUNT];
    /**
     * The tab labels' ORIGINAL typeface, captured before anything bolds them.
     *
     * <p>{@code TextView.setTypeface(tf, style)} only calls
     * {@code Typeface.create(tf, style)} when {@code style > 0}; with
     * {@code NORMAL} (0) it applies {@code tf} unchanged. Passing
     * {@code getTypeface()} therefore bolds fine and <b>can never un-bold</b> —
     * after visiting all four tabs every one of them was permanently bold.
     * Caching the original is the fix.</p>
     */
    private final android.graphics.Typeface[] baseFace =
            new android.graphics.Typeface[COUNT];
    private final View scroll;
    private final View[] indicators = new View[COUNT];
    private final View strip;

    private int current = -1;

    public Pager(Activity activity) {
        this.activity = activity;
        scroll = activity.findViewById(R.id.nv_scroll);
        strip  = activity.findViewById(R.id.engine_strip);

        pages[PAGE_HOME]    = activity.findViewById(R.id.page_home);
        pages[PAGE_OVERLAY] = activity.findViewById(R.id.page_overlay);
        pages[PAGE_AIM]     = activity.findViewById(R.id.page_aim);
        pages[PAGE_SYSTEM]  = activity.findViewById(R.id.page_system);

        tabs[PAGE_HOME]    = (TextView) activity.findViewById(R.id.tab_home);
        tabs[PAGE_OVERLAY] = (TextView) activity.findViewById(R.id.tab_overlay);
        tabs[PAGE_AIM]     = (TextView) activity.findViewById(R.id.tab_aim);
        tabs[PAGE_SYSTEM]  = (TextView) activity.findViewById(R.id.tab_system);

        indicators[PAGE_HOME]    = activity.findViewById(R.id.ind_home);
        indicators[PAGE_OVERLAY] = activity.findViewById(R.id.ind_overlay);
        indicators[PAGE_AIM]     = activity.findViewById(R.id.ind_aim);
        indicators[PAGE_SYSTEM]  = activity.findViewById(R.id.ind_system);

        for (int i = 0; i < COUNT; i++) {
            final int idx = i;
            if (tabs[i] == null) continue;
            baseFace[i] = tabs[i].getTypeface();
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { show(idx); }
            });
        }
    }

    /** Opens a page. Safe to call with a page this layout does not have. */
    public void show(int page) {
        if (page < 0 || page >= COUNT) return;
        if (page == current) {
            // Tapping the page you are on scrolls it back to the top, which is
            // the one thing that gesture reliably means everywhere else.
            if (scroll != null) scroll.scrollTo(0, 0);
            return;
        }
        current = page;
        for (int i = 0; i < COUNT; i++) {
            if (pages[i] != null) {
                pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            }
            paintTab(i, i == page);
        }
        if (scroll != null) scroll.scrollTo(0, 0);
        // The pinned strip exists so the primary action is reachable from the
        // three pages that do not have the engine card. On the Engine page it
        // would be the same control twice, 200dp apart, both live -- so it
        // stands down there.
        if (strip != null) {
            strip.setVisibility(page == PAGE_HOME ? View.GONE : View.VISIBLE);
        }
        Diag.hit(Slot.UI_PAGE_SHOWN);
    }

    /** Which page is open. */
    public int current() { return current; }

    private void paintTab(int i, boolean on) {
        TextView t = tabs[i];
        if (t == null) return;
        int colour = activity.getResources().getColor(
                on ? R.color.nv_teal : R.color.nv_text_3);
        t.setTextColor(colour);
        // A compound drawable does NOT follow setTextColor. Without this the
        // four icons would sit at their authored white while the labels moved,
        // so the selected tab would be saying two different things at once.
        //
        // setColorFilter, not setCompoundDrawableTintList: the tint list needs
        // API 23 and this app's minSdk is 21, where it is a NoSuchMethodError
        // rather than a no-op.
        android.graphics.drawable.Drawable[] d = t.getCompoundDrawables();
        if (d != null && d[1] != null) {
            d[1].mutate().setColorFilter(colour, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        // The selected tab is named by colour and weight only. A filled pill
        // behind it would be a fifth kind of container on a screen that
        // already has cards, rows, chips and buttons.
        t.setTypeface(android.graphics.Typeface.create(
                baseFace[i], on ? android.graphics.Typeface.BOLD
                                : android.graphics.Typeface.NORMAL));
        // NO alpha on the inactive tab. nv_text_3 at 75% over nv_ground is
        // about 3.5:1, under the 4.5:1 floor; the colour difference alone is
        // already enough to say which tab is live.
        t.setAlpha(1f);
        View ind = indicators[i];
        if (ind != null) ind.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }
}
