package com.nepdon.wire.ui.panel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shared in-game setup panel: a draggable, collapsible overlay card of
 * live-editable knobs, with a hidden five-tap gesture on its title that opens
 * the full-screen layout editor.
 *
 * <h3>Why a base class instead of two panels</h3>
 *
 * <p>This is the generalisation of the old {@code EspTunePanel}. There are now
 * two panels — projection setup and aim setup — and they differ only in which
 * rows they declare. Everything else (the window, the drag, the collapse, the
 * repaint tick, the five-tap, the remembered open section) is identical, and
 * two copies of it would drift. Subclasses implement {@link #title()} and
 * {@link #buildRows()} and nothing else is required.</p>
 *
 * <h3>Verbose by design</h3>
 *
 * <p>The old panel was a label, a number and two buttons. That is fine for a
 * knob you tuned last week and forgot, and useless for one you have never
 * touched: the whole reason these values are settings rather than constants is
 * that they cannot be derived, so the panel has to carry the reason with it.
 * Every row may declare a {@link #note(String) note} — one line saying what the
 * knob does and, where it matters, <i>which direction to move it</i> when the
 * symptom is X. Notes are drawn dimmer and smaller than the row itself, so a
 * user who already knows can read past them.</p>
 *
 * <h3>Window flags</h3>
 *
 * <p>{@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SPLIT_TOUCH}: the
 * panel takes the touches that land on it and lets every other touch through to
 * the game, so it can be adjusted mid-match. It deliberately does <b>not</b>
 * take focus — a focusable overlay steals the IME and the back key from the
 * game. The layout editor is the one place that needs to capture everything,
 * and it is a separate window that exists only while it is open.</p>
 *
 * <h3>Nothing here runs per frame</h3>
 *
 * <p>Rows repaint on a 250 ms tick and only while expanded. The renderer's
 * {@code onDraw} still reads plain volatile statics; this panel writes them.</p>
 */
public abstract class SetupPanel {

    // ====================================================================
    // Knob interfaces — a knob owns no value, it binds to the live static
    // ====================================================================

    /** A continuous knob: read the live value, write a new one. */
    public interface FloatKnob {
        float get();
        void set(float v);
    }

    /** A discrete on/off knob. */
    public interface BoolKnob {
        boolean get();
        void set(boolean v);
    }

    /** A discrete multi-value knob (stepper over an explicit value set). */
    public interface IntKnob {
        int get();
        void set(int v);
    }

    /** Supplies a live string for a read-only status row. */
    public interface TextProvider {
        String text();
    }

    /** Supplies the live readout shown at the top of the panel. */
    public interface HintProvider {
        String hint();
    }

    // ====================================================================
    // Palette — matches res/values/colors.xml fl_accent
    // ====================================================================

    protected static final int COL_BG      = Color.argb(238, 12, 18, 30);
    protected static final int COL_HEADER  = Color.argb(245, 18, 30, 48);
    protected static final int COL_TEXT    = Color.parseColor("#FFC4CAE0");
    protected static final int COL_NOTE    = Color.parseColor("#FFC98A8A");
    protected static final int COL_VALUE   = Color.parseColor("#FFFFA3A3");
    protected static final int COL_HINT    = Color.parseColor("#FFFCA5A5");
    protected static final int COL_BTN     = Color.argb(255, 32, 48, 72);
    protected static final int COL_ACCENT  = Color.parseColor("#FFFF6B6B");
    protected static final int COL_BTN_ON  = Color.argb(255, 26, 120, 62);
    protected static final int COL_BTN_OFF = Color.argb(255, 62, 30, 38);
    protected static final int COL_BORDER  = Color.argb(200, 90, 200, 255);
    protected static final int COL_WARN    = Color.parseColor("#FFF5A524");

    private static final long HINT_PERIOD_MS = 250L;
    private static final long REPEAT_DELAY_MS = 340L;
    private static final long REPEAT_PERIOD_MS = 70L;
    /** Ticks held before the step size is multiplied (coarse drag-through). */
    private static final int ACCEL_AFTER = 12;

    /**
     * Taps on the title that open the editor, and the total time all five have.
     *
     * <p>The budget is a single 3 s window opened by the FIRST tap, not a
     * per-gap timeout. Chaining {@code ViewConfiguration.getDoubleTapTimeout()}
     * (300 ms) between each of five taps is a 1.2 s sprint that users lose, and
     * a lost secret is indistinguishable from a dead title. It still has to be
     * bounded: with no expiry at all, five ordinary expand/collapse taps spread
     * over a minute would open the editor by accident.</p>
     *
     * <p>From {@link #SECRET_FEEDBACK_AT} taps the header shows a countdown.
     * Android's own seven-tap build-number gesture starts its toast at four, for
     * the same reason.</p>
     */
    private static final int SECRET_TAPS = 5;
    private static final long SECRET_WINDOW_MS = 3000L;
    private static final int SECRET_FEEDBACK_AT = 3;

    protected final Context context;
    protected final WindowManager wm;
    protected final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Runnable> refreshers = new ArrayList<Runnable>();
    private final List<String> sectionNames = new ArrayList<String>();
    private final List<TextView> sectionHeaders = new ArrayList<TextView>();
    private final List<LinearLayout> sectionBodies = new ArrayList<LinearLayout>();

    private View root;
    private LinearLayout body;
    private ScrollView bodyScroll;
    private TextView headerView;
    private TextView hintView;
    private WindowManager.LayoutParams params;
    private boolean added;
    private boolean expanded;
    private HintProvider hintProvider;
    private Runnable hintTicker;

    /** The section rows are appended to; null before the first section(). */
    private LinearLayout current;
    private String openSection = "";

    private int tapCount;
    private long firstTapMs;
    private boolean expandedBeforeBurst;

    protected SetupPanel(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    // ====================================================================
    // Subclass contract
    // ====================================================================

    /** Header text when expanded, e.g. {@code "⚙ AIM SETUP"}. */
    protected abstract String title();

    /** Header text when collapsed — short, it is a chip over gameplay. */
    protected abstract String shortTitle();

    /** Declares the rows. Called once per {@link #show}. */
    protected abstract void buildRows();

    /** Distinct per panel, so two panels do not share one open section. */
    protected abstract String openSectionKey();

    /** Initial window position, in dp from the top-left. */
    protected int startXDp() { return 12; }
    protected int startYDp() { return 300; }

    /** Expanded body size, in dp. */
    protected int bodyWidthDp()  { return 292; }
    protected int bodyHeightDp() { return 360; }

    /**
     * Invoked when the title is tapped {@value #SECRET_TAPS} times.
     *
     * <p>Default does nothing, so a panel with no editable layout simply has no
     * secret. The aim panel overrides it to open the layout editor.</p>
     */
    protected void onSecretTap() { }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** Creates and adds the panel (idempotent). */
    public void show(HintProvider provider) {
        this.hintProvider = provider;
        if (added) return;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(card(COL_BG));

        headerView = new TextView(context);
        headerView.setText(shortTitle());
        headerView.setTextSize(11f);
        headerView.setTypeface(Typeface.DEFAULT_BOLD);
        headerView.setTextColor(COL_HINT);
        headerView.setBackground(card(COL_HEADER));
        headerView.setPadding(dp(10), dp(7), dp(10), dp(7));
        headerView.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(headerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(6), dp(4), dp(6), dp(6));

        hintView = new TextView(context);
        hintView.setTextSize(9f);
        hintView.setTypeface(Typeface.MONOSPACE);
        hintView.setTextColor(COL_HINT);
        hintView.setPadding(dp(4), dp(2), dp(4), dp(4));
        body.addView(hintView);

        loadOpenSection();
        sectionNames.clear();
        sectionHeaders.clear();
        sectionBodies.clear();
        current = null;
        buildRows();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        container.addView(scroll, new LinearLayout.LayoutParams(
                dp(bodyWidthDp()), dp(bodyHeightDp())));
        bodyScroll = scroll;
        scroll.setVisibility(View.GONE);

        int type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // Touchable (no FLAG_NOT_TOUCHABLE) but NOT_TOUCH_MODAL, so touches
        // outside the panel still reach the game underneath.
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(startXDp());
        p.y = dp(startYDp());

        try {
            wm.addView(container, p);
            added = true;
            root = container;
            params = p;
        } catch (Exception e) {
            added = false;
            root = null;
            return;
        }

        headerView.setOnTouchListener(new DragTapListener());
        setExpanded(false);
        startHintTicker();
    }

    /** Shows or hides the panel to match {@code visible}. */
    public void update(boolean visible, HintProvider provider) {
        if (visible) show(provider);
        else hide();
    }

    /** Removes the panel. */
    public void hide() {
        if (hintTicker != null) handler.removeCallbacks(hintTicker);
        if (added && root != null) {
            try {
                wm.removeView(root);
            } catch (Exception e) { }
        }
        added = false;
        root = null;
        body = null;
        bodyScroll = null;
        params = null;
        current = null;
        refreshers.clear();
        sectionNames.clear();
        sectionHeaders.clear();
        sectionBodies.clear();
    }

    public boolean isShown() {
        return added;
    }

    /** Repaints every row now. Safe to call from the UI thread only. */
    public void refreshAll() {
        for (int i = 0; i < refreshers.size(); i++) refreshers.get(i).run();
    }

    private void startHintTicker() {
        hintTicker = new Runnable() {
            @Override
            public void run() {
                if (!added) return;
                if (expanded && hintView != null && hintProvider != null) {
                    hintView.setText(hintProvider.hint());
                }
                handler.postDelayed(this, HINT_PERIOD_MS);
            }
        };
        handler.post(hintTicker);
    }

    private void setExpanded(boolean want) {
        expanded = want;
        if (bodyScroll != null) {
            bodyScroll.setVisibility(want ? View.VISIBLE : View.GONE);
        }
        if (headerView != null) {
            headerView.setText(want ? (title() + "  ▼") : shortTitle());
        }
        if (want) refreshAll();
    }

    // ====================================================================
    // Row builders
    // ====================================================================

    /**
     * Opens a COLLAPSIBLE section. Rows added after this call belong to it.
     *
     * <p>One section open at a time, and which one is remembered across
     * restarts. The old panel packed 83 rows into a 330dp window — about seven
     * screens of scrolling to reach a knob, mid-match.</p>
     */
    protected void section(String title) {
        final String name = title;

        final TextView header = new TextView(context);
        header.setTextSize(11f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(COL_ACCENT);
        header.setPadding(dp(10), dp(12), dp(10), dp(12));
        header.setBackground(card(COL_BTN));
        header.setMinHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        boolean open = name.equals(openSection);
        content.setVisibility(open ? View.VISIBLE : View.GONE);
        header.setText((open ? "▾  " : "▸  ") + name);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nowOpen = content.getVisibility() != View.VISIBLE;
                for (int i = 0; i < sectionBodies.size(); i++) {
                    sectionBodies.get(i).setVisibility(View.GONE);
                    sectionHeaders.get(i).setText("▸  " + sectionNames.get(i));
                }
                if (nowOpen) {
                    content.setVisibility(View.VISIBLE);
                    header.setText("▾  " + name);
                    openSection = name;
                } else {
                    openSection = "";
                }
                saveOpenSection();
            }
        });

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        header.setLayoutParams(hp);

        body.addView(header);
        body.addView(content);
        sectionNames.add(name);
        sectionHeaders.add(header);
        sectionBodies.add(content);
        current = content;
    }

    /**
     * A wrapped explanatory line. This is what makes the panel verbose: say
     * what the knob does and which way to move it when the symptom appears.
     */
    protected void note(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(9f);
        tv.setTextColor(COL_NOTE);
        tv.setLineSpacing(dp(1), 1f);
        tv.setPadding(dp(6), dp(3), dp(6), dp(5));
        addRow(tv);
    }

    /** A note drawn in the warning colour, for a knob with a real downside. */
    protected void warn(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(9f);
        tv.setTextColor(COL_WARN);
        tv.setLineSpacing(dp(1), 1f);
        tv.setPadding(dp(6), dp(3), dp(6), dp(5));
        addRow(tv);
    }

    /** A continuous knob row: label, live value, hold-to-repeat −/+ pair. */
    protected void num(String name, final float step, final float bigStep,
                       final String fmt, final FloatKnob knob) {
        LinearLayout row = rowLayout();

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(COL_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(context);
        value.setTextSize(10f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextColor(COL_VALUE);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(52));
        row.addView(value);

        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                value.setText(String.format(Locale.US, fmt, knob.get()));
            }
        };
        refreshers.add(paint);

        row.addView(stepButton("−", knob, -step, -bigStep, paint));
        row.addView(stepButton("+", knob, step, bigStep, paint));

        addRow(row);
    }

    /** A continuous knob with its explanation attached. */
    protected void num(String name, String why, float step, float bigStep,
                       String fmt, FloatKnob knob) {
        num(name, step, bigStep, fmt, knob);
        note(why);
    }

    /** An on/off row; the whole row is the hit target. */
    protected void bool(String name, final BoolKnob knob) {
        LinearLayout row = rowLayout();

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(COL_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView state = new TextView(context);
        state.setTextSize(9f);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setTextColor(Color.WHITE);
        state.setGravity(Gravity.CENTER);
        state.setMinWidth(dp(54));
        state.setMinHeight(dp(40));
        state.setPadding(dp(6), dp(4), dp(6), dp(4));
        row.addView(state);

        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                boolean on = knob.get();
                state.setText(on ? "ON" : "OFF");
                state.setBackground(card(on ? COL_BTN_ON : COL_BTN_OFF));
            }
        };
        refreshers.add(paint);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                knob.set(!knob.get());
                paint.run();
            }
        });

        addRow(row);
    }

    /** An on/off row with its explanation attached. */
    protected void bool(String name, String why, BoolKnob knob) {
        bool(name, knob);
        note(why);
    }

    /**
     * A read-only status row: a label and a live string.
     *
     * <p>Exists because the aim has one failure mode no ON/OFF chip can express
     * — "armed but not injecting". Shizuku's server was dying at startup on this
     * ROM and the panel had no way to report it, so the toggle simply refused to
     * latch and looked broken.</p>
     */
    protected void info(String name, final TextProvider provider) {
        LinearLayout row = rowLayout();

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(COL_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(context);
        value.setTextSize(9f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextColor(COL_HINT);
        value.setGravity(Gravity.END);
        value.setMaxWidth(dp(170));
        row.addView(value);

        Runnable paint = new Runnable() {
            @Override
            public void run() {
                String s = provider.text();
                value.setText(s == null ? "-" : s);
            }
        };
        refreshers.add(paint);
        paint.run();
        addRow(row);
    }

    /** A one-shot action row: tapping runs {@code onTap}, nothing latches. */
    protected void action(String name, String button, final Runnable onTap) {
        LinearLayout row = rowLayout();

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(COL_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = new TextView(context);
        btn.setText(button);
        btn.setTextSize(9f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(70));
        btn.setMinHeight(dp(40));
        btn.setPadding(dp(8), dp(6), dp(8), dp(6));
        btn.setBackground(card(COL_BTN));
        row.addView(btn);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    onTap.run();
                } catch (Throwable ignored) {
                }
            }
        });

        addRow(row);
    }

    /**
     * A discrete stepper over an explicit value set (‹ previous, › next,
     * wrapping). For enum-style knobs whose useful values are a short set
     * rather than a continuous range.
     */
    protected void step(String name, final int[] values, final String[] labels,
                        final IntKnob knob) {
        LinearLayout row = rowLayout();

        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(10f);
        label.setTextColor(COL_TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(context);
        value.setTextSize(9f);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextColor(COL_VALUE);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(70));
        row.addView(value);

        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                int cur = knob.get();
                String txt = String.valueOf(cur);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == cur) { txt = labels[i]; break; }
                }
                value.setText(txt);
            }
        };
        refreshers.add(paint);

        row.addView(tapButton("‹", new Runnable() {
            @Override
            public void run() {
                int i = indexOf(values, knob.get());
                knob.set(values[(i + values.length - 1) % values.length]);
                paint.run();
            }
        }));
        row.addView(tapButton("›", new Runnable() {
            @Override
            public void run() {
                int i = indexOf(values, knob.get());
                knob.set(values[(i + 1) % values.length]);
                paint.run();
            }
        }));

        addRow(row);
    }

    /** A full-width button, for a panel-level action such as RESET. */
    protected void wideAction(String text, final Runnable onTap) {
        LinearLayout row = rowLayout();
        row.addView(new TextView(context), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tapButtonWide(text, onTap));
        addRow(row);
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    /** For panels that build their own layout. Appends like addRow, with explicit params. */
    protected void addRaw(View v, LinearLayout.LayoutParams lp) {
        if (current != null) current.addView(v, lp);
        else body.addView(v, lp);
    }

    /** Appends to the open section, or to the body before any section. */
    private void addRow(View v) {
        if (current != null) current.addView(v);
        else body.addView(v);
    }

    private LinearLayout rowLayout() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        row.setLayoutParams(lp);
        row.setPadding(dp(4), dp(2), dp(2), dp(2));
        return row;
    }

    // ====================================================================
    // Buttons
    // ====================================================================

    /**
     * A −/+ button that fires once on press then auto-repeats while held,
     * switching to {@code bigStep} after {@link #ACCEL_AFTER} ticks so a knob
     * can be swept across its range without a hundred taps.
     */
    private TextView stepButton(String glyph, final FloatKnob knob,
                                final float step, final float bigStep,
                                final Runnable paint) {
        final TextView b = miniButton(glyph);
        b.setOnTouchListener(new View.OnTouchListener() {
            private Runnable repeat;
            private int ticks;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setAlpha(0.6f);
                        ticks = 0;
                        knob.set(knob.get() + step);
                        paint.run();
                        repeat = new Runnable() {
                            @Override
                            public void run() {
                                ticks++;
                                knob.set(knob.get()
                                        + (ticks > ACCEL_AFTER ? bigStep : step));
                                paint.run();
                                handler.postDelayed(this, REPEAT_PERIOD_MS);
                            }
                        };
                        handler.postDelayed(repeat, REPEAT_DELAY_MS);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        if (repeat != null) handler.removeCallbacks(repeat);
                        repeat = null;
                        return true;
                }
                return false;
            }
        });
        return b;
    }

    private TextView tapButton(String glyph, final Runnable action) {
        TextView b = miniButton(glyph);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private TextView tapButtonWide(String text, final Runnable action) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextSize(9f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(card(COL_BTN));
        b.setMinHeight(dp(44));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    /**
     * A −/+ stepper, 52dp square.
     *
     * <p>Was 34×28dp — 58% of the 48dp minimum on the short axis, on a control
     * used mid-match with a thumb while the other hand is aiming. 48dp assumes a
     * stationary target and a spare hand, and neither holds here.</p>
     */
    private TextView miniButton(String glyph) {
        TextView b = new TextView(context);
        b.setText(glyph);
        b.setTextSize(18f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(COL_ACCENT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(card(COL_BTN));
        b.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    protected GradientDrawable card(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        d.setStroke(1, COL_BORDER);
        return d;
    }

    protected int dp(int v) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(v * density));
    }

    // ====================================================================
    // Open-section memory
    // ====================================================================

    private void saveOpenSection() {
        try {
            com.nepdon.wire.config.Prefs.get(context).edit()
                    .putString(openSectionKey(), openSection).apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadOpenSection() {
        try {
            openSection = com.nepdon.wire.config.Prefs.get(context)
                    .getString(openSectionKey(), "");
        } catch (Throwable ignored) {
            openSection = "";
        }
    }

    // ====================================================================
    // Header gesture
    // ====================================================================

    /**
     * Header listener: drags the window while the finger moves, treats a short
     * stationary press as an expand/collapse tap, and counts those taps toward
     * the five-tap secret. One view has to do all three because the collapsed
     * state IS the header.
     *
     * <p>The toggle still happens on every tap — a gesture that waits 900 ms to
     * find out whether more taps are coming feels broken. On the fifth the panel
     * is restored to the state it was in before the burst started, so the secret
     * leaves no visible side effect.</p>
     */
    private final class DragTapListener implements View.OnTouchListener {
        private int ox, oy;
        private float downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (params == null) return false;
                    ox = params.x;
                    oy = params.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragged = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (params == null || root == null) return false;
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragged && Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                        return true;
                    }
                    // A drag is not a tap: abandon any secret burst in progress,
                    // or moving the panel five times would open the editor.
                    dragged = true;
                    tapCount = 0;
                    params.x = ox + (int) dx;
                    params.y = oy + (int) dy;
                    try {
                        wm.updateViewLayout(root, params);
                    } catch (Exception ex) { }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    // getEventTime() is on the uptime clock. currentTimeMillis
                    // is wall-clock and jumps on an NTP sync, which would either
                    // expire a burst mid-gesture or never expire one.
                    if (!dragged && e.getEventTime() - e.getDownTime()
                            < android.view.ViewConfiguration.getLongPressTimeout()) {
                        countTap(e.getEventTime());
                        setExpanded(!expanded);
                        if (tapCount >= SECRET_TAPS) {
                            tapCount = 0;
                            setExpanded(expandedBeforeBurst);
                            try {
                                onSecretTap();
                            } catch (Throwable ignored) {
                            }
                        } else if (tapCount >= SECRET_FEEDBACK_AT) {
                            showCountdown(SECRET_TAPS - tapCount);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    tapCount = 0;
                    return true;
            }
            return false;
        }

        private void countTap(long eventTimeMs) {
            if (tapCount == 0 || eventTimeMs - firstTapMs > SECRET_WINDOW_MS) {
                if (tapCount > 0) {
                    com.nepdon.wire.diag.Diag.hit(
                            com.nepdon.wire.diag.Slot.SETUP_TAP_RESET);
                }
                tapCount = 1;
                firstTapMs = eventTimeMs;
                expandedBeforeBurst = expanded;
            } else {
                tapCount++;
            }
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.SETUP_TAP_COUNTED);
        }
    }

    /**
     * Replaces the header text with "N more" for a moment.
     *
     * <p>Posted rather than latched: {@link #setExpanded} rewrites the header on
     * the very next tap, and the delayed restore only runs if nothing else has
     * touched it, so the two cannot fight over the same TextView.</p>
     */
    private void showCountdown(int remaining) {
        if (headerView == null) return;
        headerView.setText(remaining + " MORE → EDITOR");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!added || headerView == null) return;
                setExpanded(expanded);
            }
        }, 700L);
    }
}
