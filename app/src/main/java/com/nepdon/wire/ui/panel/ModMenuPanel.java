package com.nepdon.wire.ui.panel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.nepdon.wire.R;
import com.nepdon.wire.config.EspConfig;
import com.nepdon.wire.config.EspTuning;
import com.nepdon.wire.mod.ModConfig;

/** Mod menu: auto freeze switches and the ESP display delay sliders. */
public final class ModMenuPanel extends SetupPanel {

    // app palette (res/values/colors.xml, fl_*)
    private static final int BG        = Color.parseColor("#FF160707");
    private static final int SURFACE   = Color.parseColor("#FF241010");
    private static final int HIGH      = Color.parseColor("#FF321515");
    private static final int OUTLINE   = Color.parseColor("#FF4C1D1D");
    private static final int ACCENT    = Color.parseColor("#FFEF4444");
    private static final int TEXT      = Color.parseColor("#FFFFFFFF");
    private static final int TEXT_SOFT = Color.parseColor("#FFFEE2E2");
    private static final int TEXT_DIM  = Color.parseColor("#FFFCA5A5");

    private static final int STEP_MS = 5;

    private interface Flag {
        boolean get();
        void set(boolean v);
    }

    private SeekBar camBar;
    private SeekBar boxBar;

    public ModMenuPanel(Context context, WindowManager wm) {
        super(context, wm);
    }

    @Override protected String title()          { return "MOD"; }
    @Override protected String shortTitle()     { return "MOD"; }
    @Override protected String openSectionKey() { return "mod_menu_open"; }
    @Override protected int bodyHeightDp()      { return 440; }

    @Override
    protected GradientDrawable card(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color == COL_HEADER ? SURFACE : BG);
        d.setCornerRadius(dp(14));
        d.setStroke(1, OUTLINE);
        return d;
    }

    @Override
    protected void buildRows() {
        add(label("NETWORK"), 2);
        add(switchRow("Firewall", new Flag() {
            public boolean get() { return ModConfig.FIREWALL; }
            public void set(boolean v) { ModConfig.setFirewall(context, v); }
        }), 12);

        add(switchRow("Domain", new Flag() {
            public boolean get() { return ModConfig.DOMAIN; }
            public void set(boolean v) { ModConfig.setDomain(context, v); }
        }), 12);

        add(relayPortRow(), 12);

        add(label("FREEZE"), 2);
        add(switchRow("Auto freeze", new Flag() {
            public boolean get() { return ModConfig.AUTO_FREEZE; }
            public void set(boolean v) { ModConfig.setAutoFreeze(context, v); }
        }), 6);
        add(switchRow("While scoped", new Flag() {
            public boolean get() { return ModConfig.FREEZE_ON_SCOPE; }
            public void set(boolean v) { ModConfig.setFreezeOnScope(context, v); }
        }), 6);
        add(switchRow("While firing", new Flag() {
            public boolean get() { return ModConfig.FREEZE_ON_FIRE; }
            public void set(boolean v) { ModConfig.setFreezeOnFire(context, v); }
        }), 12);

        add(label("ESP DELAY"), 2);
        add(sliderRow("Camera", true), 6);
        add(sliderRow("Boxes", false), 10);
        add(resetButton(), 0);
    }

    private void add(View v, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        addRaw(v, lp);
    }

    private TextView label(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(10f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(TEXT_DIM);
        t.setPadding(dp(6), dp(6), dp(6), dp(4));
        return t;
    }

    private GradientDrawable rowBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE);
        d.setCornerRadius(dp(10));
        d.setStroke(1, OUTLINE);
        return d;
    }

    private GradientDrawable pillBg(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        if (on) {
            d.setColor(ACCENT);
        } else {
            d.setColor(HIGH);
            d.setStroke(1, OUTLINE);
        }
        return d;
    }

    private GradientDrawable knobBg() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(TEXT);
        return d;
    }

    private View switchRow(String name, final Flag flag) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rowBg());
        row.setPadding(dp(12), 0, dp(10), 0);
        row.setMinimumHeight(dp(46));

        TextView text = new TextView(context);
        text.setText(name);
        text.setTextSize(13f);
        text.setTextColor(TEXT);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean startOn = flag.get();
        final int travel = dp(18);
        final FrameLayout sw = new FrameLayout(context);
        sw.setBackground(pillBg(startOn));
        final View knob = new View(context);
        knob.setBackground(knobBg());
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(18), dp(18),
                Gravity.CENTER_VERTICAL | Gravity.START);
        kp.leftMargin = dp(3);
        sw.addView(knob, kp);
        knob.setTranslationX(startOn ? travel : 0);
        row.addView(sw, new LinearLayout.LayoutParams(dp(42), dp(24)));

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !flag.get();
                flag.set(on);
                sw.setBackground(pillBg(on));
                knob.animate().translationX(on ? travel : 0).setDuration(160).start();
            }
        });
        return row;
    }

    private View sliderRow(String name, final boolean isCam) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(rowBg());
        col.setPadding(dp(12), dp(8), dp(12), dp(4));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView text = new TextView(context);
        text.setText(name);
        text.setTextSize(13f);
        text.setTextColor(TEXT);
        final TextView value = new TextView(context);
        value.setTextSize(12f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextColor(TEXT_DIM);
        head.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(head);

        int max = (int) (isCam ? EspTuning.CAM_EXTRAP_MS_MAX : EspTuning.SMOOTH_MAX);
        float cur = isCam ? EspConfig.CAM_EXTRAP_MS : EspConfig.SMOOTH_MS;
        final SeekBar bar = new SeekBar(context);
        bar.setMax(max / STEP_MS);
        bar.setProgress(Math.round(cur / STEP_MS));
        bar.setProgressDrawable(context.getResources().getDrawable(R.drawable.nv_seek_track));
        bar.setThumb(context.getResources().getDrawable(R.drawable.nv_seek_thumb));
        value.setText((bar.getProgress() * STEP_MS) + " ms");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int ms = p * STEP_MS;
                value.setText(ms + " ms");
                if (!fromUser) return;
                if (isCam) EspTuning.setCamExtrapMs(ms);
                else EspTuning.setSmoothMs(ms);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));

        if (isCam) camBar = bar;
        else boxBar = bar;
        return col;
    }

    private View relayPortRow() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(rowBg());
        col.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView text = new TextView(context);
        text.setText("Relay port");
        text.setTextSize(13f);
        text.setTextColor(TEXT);
        final TextView value = new TextView(context);
        value.setTextSize(12f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextColor(TEXT_DIM);
        head.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(head);

        final Runnable refresh = new Runnable() {
            public void run() {
                value.setText(ModConfig.RELAY_PORT == 0 ? "AUTO" : String.valueOf(ModConfig.RELAY_PORT));
            }
        };
        refresh.run();

        LinearLayout btns = new LinearLayout(context);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final String[] labels = { "-100", "-1", "+1", "+100", "AUTO" };
        final int[] deltas = { -100, -1, 1, 100, 0 };
        for (int i = 0; i < labels.length; i++) {
            final int d = deltas[i];
            TextView b = new TextView(context);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(11f);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(TEXT_SOFT);
            GradientDrawable bd = new GradientDrawable();
            bd.setColor(HIGH);
            bd.setCornerRadius(dp(8));
            bd.setStroke(1, OUTLINE);
            b.setBackground(bd);
            b.setPadding(0, dp(8), 0, dp(8));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    int next = 0;
                    if (d != 0) {
                        int base = ModConfig.RELAY_PORT == 0 ? 1024 : ModConfig.RELAY_PORT;
                        next = Math.max(1024, Math.min(65535, base + d));
                    }
                    ModConfig.setRelayPort(context, next);
                    refresh.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) lp.rightMargin = dp(4);
            btns.addView(b, lp);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        col.addView(btns, blp);

        TextView note = new TextView(context);
        note.setText("AUTO = the game's own port, so the server sees the same flow as without VPN.");
        note.setTextSize(10f);
        note.setTextColor(TEXT_DIM);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(4);
        col.addView(note, np);
        return col;
    }

    private View resetButton() {
        TextView b = new TextView(context);
        b.setText("RESET TO " + Math.round(EspTuning.REC_CAM_EXTRAP_MS) + " / "
                + Math.round(EspTuning.REC_SMOOTH_MS) + " ms");
        b.setGravity(Gravity.CENTER);
        b.setTextSize(12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(TEXT_SOFT);
        GradientDrawable d = new GradientDrawable();
        d.setColor(HIGH);
        d.setCornerRadius(dp(10));
        d.setStroke(1, OUTLINE);
        b.setBackground(d);
        b.setPadding(0, dp(11), 0, dp(11));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                EspTuning.resetDelay();
                if (camBar != null) camBar.setProgress(Math.round(EspTuning.REC_CAM_EXTRAP_MS) / STEP_MS);
                if (boxBar != null) boxBar.setProgress(Math.round(EspTuning.REC_SMOOTH_MS) / STEP_MS);
            }
        });
        return b;
    }
}
