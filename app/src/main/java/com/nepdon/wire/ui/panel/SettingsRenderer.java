package com.nepdon.wire.ui.panel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nepdon.wire.config.plugin.Feature;
import com.nepdon.wire.config.plugin.Features;
import com.nepdon.wire.config.plugin.Setting;

/**
 * Turns a {@link Feature}'s declared settings into rows. Nobody writes a
 * settings screen by hand again.
 *
 * <h3>Plain widgets, on purpose</h3>
 *
 * <p>No {@code androidx.preference}. The module currently has <b>no AndroidX
 * UI dependency at all</b> — only Shizuku and hiddenapibypass — and pulling
 * that in would drag appcompat, recyclerview and fragment into an APK that
 * ships one screen and an overlay. This is ~300 lines of {@code LinearLayout}
 * and it owes nothing to anybody.</p>
 *
 * <h3>Dependent rows are resolved imperatively</h3>
 *
 * <p>{@code Setting.dependsOn} names one boolean key, and {@link #refresh}
 * walks the rows and dims the ones whose gate is off. This is the part every
 * declarative settings system gets wrong: a schema rich enough to express real
 * conditions is not a schema a UI can draw, and the ones that tried it end up
 * with fields that stay visible and editable while doing nothing. Anything
 * harder than one gate belongs in {@code Feature.validate}, in code.</p>
 *
 * <h3>Not on a hot path</h3>
 *
 * <p>Every allocation here happens when a panel is built or a value changes,
 * never per frame and never per packet.</p>
 */
public final class SettingsRenderer {

    // Nepdon Wire tokens. Duplicated as ints because this view tree is built in
    // Java, not inflated, so it cannot reference @color without a Resources
    // lookup per row. Kept in one block so they are greppable against
    // res/values/nv_colors.xml.
    private static final int C_TEXT     = 0xFFEEF1FB;
    private static final int C_TEXT_2   = 0xFFA3AECF;
    private static final int C_TEXT_3   = 0xFF7B87B0;
    private static final int C_SURFACE  = 0xFF0F1430;
    private static final int C_RAISED   = 0xFF161D3F;
    private static final int C_LINE     = 0xFF232B52;
    private static final int C_TEAL     = 0xFF16C5C3;
    private static final int C_WARN     = 0xFFF5A524;

    private final Context ctx;
    private final Feature feature;
    private final LinearLayout root;
    private final java.util.ArrayList<Row> rows = new java.util.ArrayList<Row>();
    private TextView problem;

    /** One rendered setting, kept so {@link #refresh} can re-dim it. */
    private static final class Row {
        Setting setting;
        View view;
        TextView value;
        TextView label;
    }

    public SettingsRenderer(Context ctx, Feature feature) {
        this.ctx = ctx;
        this.feature = feature;
        this.root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        build();
    }

    /** The rendered tree. Add it to whatever container you like. */
    public View view() { return root; }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private void build() {
        Setting[] all = Features.settingsOf(feature);
        String section = null;

        for (int i = 0; i < all.length; i++) {
            Setting s = all[i];
            if (!s.section.equals(section)) {
                section = s.section;
                root.addView(sectionHeader(section));
            }
            Row r = row(s);
            rows.add(r);
            root.addView(r.view);
        }

        problem = new TextView(ctx);
        problem.setTextColor(C_WARN);
        problem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        problem.setPadding(dp(16), dp(8), dp(16), dp(12));
        problem.setVisibility(View.GONE);
        root.addView(problem);

        refresh();
    }

    private TextView sectionHeader(String title) {
        TextView t = new TextView(ctx);
        t.setText(title.toUpperCase(java.util.Locale.US));
        t.setTextColor(C_TEXT_3);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(16), dp(18), dp(16), dp(8));
        return t;
    }

    private Row row(final Setting s) {
        final Row r = new Row();
        r.setting = s;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(3), dp(12), dp(3));
        box.setLayoutParams(lp);
        box.setBackground(card());
        box.setMinimumHeight(dp(48));           // touch target floor

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        r.label = new TextView(ctx);
        r.label.setText(s.title);
        r.label.setTextColor(C_TEXT);
        r.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        text.addView(r.label);

        if (s.summary != null) {
            TextView sub = new TextView(ctx);
            sub.setText(s.summary);
            sub.setTextColor(C_TEXT_3);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            sub.setPadding(0, dp(2), 0, 0);
            text.addView(sub);
        }
        if (s.applies == Setting.APPLIES_RESTART) {
            TextView warn = new TextView(ctx);
            warn.setText("Takes effect after the engine restarts");
            warn.setTextColor(C_WARN);
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            warn.setPadding(0, dp(2), 0, 0);
            text.addView(warn);
        }
        box.addView(text);

        r.value = new TextView(ctx);
        r.value.setTextColor(C_TEAL);
        r.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        r.value.setGravity(Gravity.END);
        r.value.setMinWidth(dp(56));

        if (s instanceof Setting.Bool) {
            box.addView(r.value);
            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ((Setting.Bool) s).set(!((Setting.Bool) s).get());
                    commit(s);
                }
            });
        } else {
            box.addView(stepper(s, r, false));
            box.addView(r.value);
            box.addView(stepper(s, r, true));
        }

        r.view = box;
        return r;
    }

    private View stepper(final Setting s, final Row r, final boolean up) {
        TextView b = new TextView(ctx);
        b.setText(up ? "+" : "−");
        b.setTextColor(C_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        b.setGravity(Gravity.CENTER);
        // 52dp, not 48: these are used mid-match, one-handed, while moving.
        int side = dp(52);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setBackground(pill());
        b.setContentDescription((up ? "Increase " : "Decrease ") + s.title);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (s instanceof Setting.Num) {
                    Setting.Num n = (Setting.Num) s;
                    n.nudge(up ? n.step : -n.step);
                } else if (s instanceof Setting.Int) {
                    Setting.Int n = (Setting.Int) s;
                    n.nudge(up ? n.step : -n.step);
                } else if (s instanceof Setting.Choice) {
                    ((Setting.Choice) s).cycle();
                }
                commit(s);
            }
        });
        return b;
    }

    /**
     * Writes one setting through, then re-renders.
     *
     * <p>A refusal from {@code Feature.validate} rolls the field back and
     * shows why. It is deliberately not silent: a settings screen that
     * ignores what you pressed, with no explanation, is the same
     * indistinguishable-from-working failure this codebase keeps hitting.</p>
     */
    private void commit(Setting s) {
        String bad = Features.commit(feature, s);
        if (problem != null) {
            if (bad == null) {
                problem.setVisibility(View.GONE);
            } else {
                problem.setText(bad);
                problem.setVisibility(View.VISIBLE);
            }
        }
        refresh();
    }

    /** Repaints every row's value and dims the ones whose gate is off. */
    public void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.value.setText(r.setting.display());

            boolean live = gateOpen(r.setting);
            r.view.setEnabled(live);
            r.view.setAlpha(live ? 1f : 0.38f);
            r.label.setTextColor(live ? C_TEXT : C_TEXT_2);

            // A value the user has never touched is shown in the muted colour,
            // so "this is the default" and "I chose this" are distinguishable
            // at a glance - which is also what makes Reset meaningful.
            boolean own = Features.isOverridden(r.setting);
            r.value.setTextColor(!live ? C_TEXT_3 : (own ? C_TEAL : C_TEXT_2));
        }
    }

    /** True when this setting's {@code dependsOn} gate is satisfied. */
    private boolean gateOpen(Setting s) {
        if (s.dependsOn == null) return true;
        for (int i = 0; i < rows.size(); i++) {
            Setting o = rows.get(i).setting;
            if (o.key.equals(s.dependsOn) && o instanceof Setting.Bool) {
                return ((Setting.Bool) o).get();
            }
        }
        // The gate names a key this feature does not declare. Treat it as open
        // rather than hiding the row: a row that vanished for an unfindable
        // reason is worse than one that does nothing.
        return true;
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_SURFACE);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    private GradientDrawable pill() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_RAISED);
        g.setCornerRadius(dp(13));
        g.setStroke(dp(1), Color.argb(0x47, 0x16, 0xC5, 0xC3));
        return g;
    }
}
