package com.nepdon.wire.ui.settings;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.nepdon.wire.config.EspConfig;
import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.R;

/**
 * Owns every {@code findViewById} for the settings screen plus the toggle and
 * seekbar wiring. Keeps the raw view references out of {@code MainActivity}.
 */
public final class UiBindings {

    private final Activity activity;
    private final AppearanceBroadcaster broadcaster;

    private final TextView cbFloatGhost, cbFloatFreeze, cbFloatTele;
    private final TextView tvFloatGhostStatus, tvFloatFreezeStatus, tvFloatTeleStatus;
    private final TextView cbDevMode, tvDevModeStatus;
    private TextView cbEsp, tvEspStatus, cbRadar, tvRadarStatus, cbEspLine, tvEspLineStatus, cbEspDebug, tvEspDebugStatus, cbEspTune, tvEspTuneStatus, cbAimSetup, tvAimSetupStatus, cbAimBtn, tvAimBtnStatus, cbZoomBtn, tvZoomBtnStatus, cbModMenu, tvModMenuStatus;
    private final TextView tvFloatSizeValue, tvFloatAlphaValue;
    private final SeekBar seekFloatSize, seekFloatAlpha;
    private final ViewGroup mainContent;
    private final View groupTeleOptions;

    // ---- ESP box colour picker ----
    private View btnColorBlack, btnColorWhite, btnColorRed;
    private View btnColorYellow, btnColorBlue, btnColorGreen, btnColorPurple;
    private TextView tvEspColorLabel;

    /** Packed ARGB values for each colour swatch (stroke alpha 235). */
    private static final int[] COLOR_VALUES = {
            Color.argb(235,   0,   0,   0),  // BLACK
            Color.argb(235, 255, 255, 255),  // WHITE
            Color.argb(235, 255,  64,  64),  // RED
            Color.argb(235, 255, 220,   0),  // YELLOW
            Color.argb(235,  60, 130, 255),  // BLUE
            Color.argb(235,  60, 220,  90),  // GREEN
            Color.argb(235, 180,  80, 255),  // PURPLE
    };
    private static final String[] COLOR_NAMES = {
            "Black", "White", "Red", "Yellow", "Blue", "Green", "Purple"
    };

    public UiBindings(Activity activity, AppearanceBroadcaster broadcaster) {
        this.activity = activity;
        this.broadcaster = broadcaster;

        cbFloatGhost      = (TextView) activity.findViewById(R.id.cb_float_ghost);
        cbFloatFreeze     = (TextView) activity.findViewById(R.id.cb_float_freeze);
        cbFloatTele       = (TextView) activity.findViewById(R.id.cb_float_tele);
        tvFloatGhostStatus   = (TextView) activity.findViewById(R.id.tv_float_ghost_status);
        tvFloatFreezeStatus  = (TextView) activity.findViewById(R.id.tv_float_freeze_status);
        tvFloatTeleStatus    = (TextView) activity.findViewById(R.id.tv_float_tele_status);
        cbDevMode            = (TextView) activity.findViewById(R.id.cb_dev_mode);
        tvDevModeStatus      = (TextView) activity.findViewById(R.id.tv_dev_mode_status);

        tvFloatSizeValue = (TextView) activity.findViewById(R.id.tv_float_size_value);
        tvFloatAlphaValue = (TextView) activity.findViewById(R.id.tv_float_alpha_value);
        seekFloatSize = (SeekBar) activity.findViewById(R.id.seek_float_size);
        seekFloatAlpha = (SeekBar) activity.findViewById(R.id.seek_float_alpha);

        cbEsp = (TextView) activity.findViewById(R.id.cb_esp);
        tvEspStatus = (TextView) activity.findViewById(R.id.tv_esp_status);
        cbRadar = (TextView) activity.findViewById(R.id.cb_radar);
        tvRadarStatus = (TextView) activity.findViewById(R.id.tv_radar_status);
        cbEspLine = (TextView) activity.findViewById(R.id.cb_esp_line);
        tvEspLineStatus = (TextView) activity.findViewById(R.id.tv_esp_line_status);
        cbEspDebug = (TextView) activity.findViewById(R.id.cb_espdebug);
        tvEspDebugStatus = (TextView) activity.findViewById(R.id.tv_espdebug_status);
        cbEspTune = (TextView) activity.findViewById(R.id.cb_esptune);
        tvEspTuneStatus = (TextView) activity.findViewById(R.id.tv_esptune_status);
        cbModMenu = (TextView) activity.findViewById(R.id.cb_modmenu);
        tvModMenuStatus = (TextView) activity.findViewById(R.id.tv_modmenu_status);
        cbAimSetup = (TextView) activity.findViewById(R.id.cb_aimsetup);
        tvAimSetupStatus = (TextView) activity.findViewById(R.id.tv_aimsetup_status);
        cbAimBtn = (TextView) activity.findViewById(R.id.cb_aimbtn);
        tvAimBtnStatus = (TextView) activity.findViewById(R.id.tv_aimbtn_status);
        cbZoomBtn = (TextView) activity.findViewById(R.id.cb_zoombtn);
        tvZoomBtnStatus = (TextView) activity.findViewById(R.id.tv_zoombtn_status);

        mainContent = (ViewGroup) activity.findViewById(R.id.main_content);
        groupTeleOptions = activity.findViewById(R.id.group_tele_options);

        // Colour picker
        btnColorBlack  = activity.findViewById(R.id.btn_color_black);
        btnColorWhite  = activity.findViewById(R.id.btn_color_white);
        btnColorRed    = activity.findViewById(R.id.btn_color_red);
        btnColorYellow = activity.findViewById(R.id.btn_color_yellow);
        btnColorBlue   = activity.findViewById(R.id.btn_color_blue);
        btnColorGreen  = activity.findViewById(R.id.btn_color_green);
        btnColorPurple = activity.findViewById(R.id.btn_color_purple);
        tvEspColorLabel = (TextView) activity.findViewById(R.id.tv_esp_color_label);
    }

    /** Wires every toggle and seekbar exactly as the original onCreate did. */
    public void setupAll() {
        setupFloatingToggle(cbFloatFreeze,  tvFloatFreezeStatus,  Prefs.KEY_FREEZE);
        setupFloatingToggle(cbFloatGhost,   tvFloatGhostStatus,   Prefs.KEY_GHOST);
        setupFloatingToggle(cbFloatTele,    tvFloatTeleStatus,    Prefs.KEY_TELE);
        setupFloatSizeSeek();
        setupFloatAlphaSeek();
        setupFloatingToggle(cbDevMode, tvDevModeStatus, Prefs.KEY_DEV_MODE);
        setupFloatingToggle(cbEsp,      tvEspStatus,      Prefs.KEY_ESP_BOXES);
        setupFloatingToggle(cbRadar,    tvRadarStatus,    Prefs.KEY_ESP_RADAR);
        setupFloatingToggle(cbEspLine,  tvEspLineStatus,  Prefs.KEY_ESP_LINE);
        setupFloatingToggle(cbEspDebug, tvEspDebugStatus, Prefs.KEY_ESP_DEBUG);
        setupFloatingToggle(cbEspTune,  tvEspTuneStatus,  Prefs.KEY_ESP_TUNE);
        setupFloatingToggle(cbModMenu, tvModMenuStatus, Prefs.KEY_MOD_MENU);
        setupFloatingToggle(cbAimSetup, tvAimSetupStatus, Prefs.KEY_AIM_SETUP);
        setupFloatingToggle(cbAimBtn,   tvAimBtnStatus,   Prefs.KEY_AIM_BUTTON);
        setupFloatingToggle(cbZoomBtn,  tvZoomBtnStatus,  Prefs.KEY_ZOOM_BUTTON);
        setupColorPicker();
        updateConditionalGroups(false);
        animateMainEntrance();
    }

    // ---- Colour picker ----

    private void setupColorPicker() {
        final View[] swatches = {
                btnColorBlack, btnColorWhite, btnColorRed,
                btnColorYellow, btnColorBlue, btnColorGreen, btnColorPurple
        };

        // Restore previously saved colour and update label.
        int saved = Prefs.get(activity).getInt(
                Prefs.KEY_ESP_BOX_COLOR, Color.argb(235, 255, 64, 64));
        EspConfig.BOX_COLOR_ARGB = saved;
        updateColorLabel(saved);

        for (int i = 0; i < swatches.length; i++) {
            final View swatch = swatches[i];
            final int colorValue = COLOR_VALUES[i];
            final String colorName = COLOR_NAMES[i];
            if (swatch == null) continue;
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Persist, apply live, notify overlay.
                    Prefs.get(activity).edit()
                            .putInt(Prefs.KEY_ESP_BOX_COLOR, colorValue)
                            .commit();
                    EspConfig.BOX_COLOR_ARGB = colorValue;
                    updateColorLabel(colorValue);
                    broadcaster.send();
                    // Subtle scale-pop feedback on the tapped swatch.
                    v.animate().cancel();
                    v.setScaleX(0.85f);
                    v.setScaleY(0.85f);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                }
            });
        }
    }

    private void updateColorLabel(int argb) {
        if (tvEspColorLabel == null) return;
        String name = "Custom";
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            if (COLOR_VALUES[i] == argb) { name = COLOR_NAMES[i]; break; }
        }
        tvEspColorLabel.setText("Selected: " + name);
    }

    // ---- Seekbars ----

    private void setupFloatSizeSeek() {
        bindSeek(seekFloatSize, tvFloatSizeValue,
                AppearanceBroadcaster.FLOAT_SIZE_MIN, AppearanceBroadcaster.FLOAT_SIZE_MAX,
                Prefs.KEY_FLOAT_SIZE, "%", broadcaster.getFloatSizePercent(), null);
    }

    private void setupFloatAlphaSeek() {
        bindSeek(seekFloatAlpha, tvFloatAlphaValue,
                AppearanceBroadcaster.FLOAT_ALPHA_MIN, AppearanceBroadcaster.FLOAT_ALPHA_MAX,
                Prefs.KEY_FLOAT_ALPHA, "%", broadcaster.getFloatAlphaPercent(), null);
    }

    private void bindSeek(final SeekBar seek, final TextView value, final int min, final int max,
                          final String key, final String suffix, final int saved, final Runnable onChanged) {
        if (seek == null || value == null) return;
        seek.setMax(max - min);
        seek.setProgress(saved - min);
        value.setText(saved + suffix);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                Prefs.get(activity).edit().putInt(key, v).commit();
                value.setText(v + suffix);
                if (onChanged != null) onChanged.run();
                broadcaster.send();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    // ---- Toggles ----

    private void setupFloatingToggle(final TextView toggle, final TextView status, final String key) {
        if (toggle == null) return;
        // Default-off keys. These must match AppearanceBroadcaster and the
        // service, or the switch shows ON for something that is actually off
        // on a fresh install (which is what KEY_DEV_MODE did).
        boolean defaultOn = !Prefs.KEY_DEV_MODE.equals(key)
                && !Prefs.KEY_ESP_TUNE.equals(key)
                && !Prefs.KEY_MOD_MENU.equals(key)
                && !Prefs.KEY_AIM_SETUP.equals(key)
                && !Prefs.KEY_ESP_DEBUG.equals(key)
                && !Prefs.KEY_TELE.equals(key)
                && !Prefs.KEY_AIM_BUTTON.equals(key)
                && !Prefs.KEY_ZOOM_BUTTON.equals(key)
                && !Prefs.KEY_ESP_LINE.equals(key);
        boolean isOn = Prefs.get(activity).getBoolean(key, defaultOn);
        applyToggleState(toggle, status, isOn);
        View.OnClickListener flip = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean current = toggle.getTag() != null && (Boolean) toggle.getTag();
                boolean next = !current;
                applyToggleState(toggle, status, next);
                animateToggle(toggle);
                Prefs.get(activity).edit().putBoolean(key, next).commit();
                broadcaster.send();
                updateConditionalGroups(true);
            }
        };
        toggle.setOnClickListener(flip);
        // THE WHOLE ROW IS THE TARGET, not just the switch.
        //
        // Measured on device: the switch graphic is 52x28dp -- 28dp is 58% of
        // the 48dp floor this design set itself -- and the row was not
        // clickable at all, so ~314dp of row width was dead and the only live
        // area sat at the far right, the hardest place to reach with a left
        // thumb. The row is the parent LinearLayout; the layout gives it
        // minHeight 56dp and a ripple, and the switch carries
        // duplicateParentState so it lights with the row.
        View row = (toggle.getParent() instanceof View) ? (View) toggle.getParent() : null;
        if (row != null) row.setOnClickListener(flip);
    }

    private void applyToggleState(TextView toggle, TextView status, boolean isOn) {
        if (toggle == null) return;
        toggle.setTag(isOn);
        toggle.setText("");
        toggle.setBackgroundResource(isOn ? R.drawable.bg_switch_custom_on : R.drawable.bg_switch_custom_off);
        // THE SUBLABEL IS NOT A STATE ECHO.
        //
        // It used to be overwritten with "Showing"/"Hidden" on every row,
        // which (a) restated what the switch already says, costing ~15dp of
        // scroll per row for nothing, and (b) was simply wrong for anything
        // non-visual: "Aim assist - Hidden", "Teleport - Hidden",
        // "Developer mode - Hidden". Aim assist is not hidden; it is off.
        //
        // The layout now sets a permanent description of what each setting
        // DOES, and nothing here touches it.
    }

    private void animateToggle(final TextView toggle) {
        if (toggle == null) return;
        toggle.animate().cancel();
        toggle.setScaleX(0.96f);
        toggle.setScaleY(0.96f);
        toggle.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start();
    }

    // ---- Conditional groups + entrance ----

    private void updateConditionalGroups(boolean animate) {
        // The mask-bar group was the only conditional group and it is gone
        // (the renderer it fed, overlay/MaskBar, was deleted long ago and the
        // two sliders kept rendering and persisting values nothing read).
        // groupTeleOptions is still bound but has never been shown or hidden.
    }

    private void setOptionalGroupVisible(final View group, boolean visible, boolean animate) {
        if (group == null) return;
        if (!animate) {
            group.setVisibility(visible ? View.VISIBLE : View.GONE);
            group.setAlpha(visible ? 1f : 0f);
            group.setTranslationY(0f);
            return;
        }
        group.animate().cancel();
        if (visible) {
            group.setVisibility(View.VISIBLE);
            group.setAlpha(0f);
            group.setTranslationY(18f);
            group.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start();
        } else {
            group.animate()
                    .alpha(0f)
                    .translationY(-10f)
                    .setDuration(130L)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            group.setVisibility(View.GONE);
                            group.setTranslationY(0f);
                        }
                    })
                    .start();
        }
    }

    private void animateMainEntrance() {
        if (mainContent == null) return;
        for (int i = 0; i < mainContent.getChildCount(); i++) {
            View child = mainContent.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(28f);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(45L * i)
                    .setDuration(260L)
                    .start();
        }
    }
}
