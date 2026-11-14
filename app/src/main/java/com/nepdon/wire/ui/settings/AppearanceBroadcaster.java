package com.nepdon.wire.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.nepdon.wire.BuildConfig;
import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.VpnActions;

/**
 * Builds and sends the floating-overlay appearance broadcast. Owns the min/max
 * bounds and the clamped getters for every appearance value so the UI
 * bindings and the engine start flow read from a single source of truth.
 */
public final class AppearanceBroadcaster {

    public static final int FLOAT_ALPHA_MIN = 35;
    public static final int FLOAT_ALPHA_MAX = 100;
    public static final int FLOAT_SIZE_MIN = 80;
    public static final int FLOAT_SIZE_MAX = 260;
    public static final int TELE_REPLAY_DELAY_MIN = 1;
    public static final int TELE_REPLAY_DELAY_MAX = 50;

    private final Context context;

    public AppearanceBroadcaster(Context context) {
        this.context = context;
    }

    private SharedPreferences prefs() {
        return Prefs.get(context);
    }

    /**
     * Default-on unless listed. The list is SHORTER than the one in
     * {@code UiBindings.setupFloatingToggle} and that is not a bug: only six
     * keys ever reach this method (GHOST, FREEZE, TELE, MASK_BAR,
     * DEV_MODE — see {@link #send()}), so the ESP_TUNE / AIM_* / ZOOM_BUTTON
     * entries the other list carries would be dead weight here. Any key you
     * start passing to this method must be checked against that list, because
     * the two disagreeing about a LIVE key is precisely how a switch ends up
     * reading ON for something that is actually off.
     *
     * <p>TELE is listed because teleport is now off by default; it pairs with
     * {@code Registry}'s FilterFeature declaration, {@code showTele}'s field
     * initialiser and the {@code KEY_TELE} read in
     * {@code FloatingOverlayService.applyPrefs}. All four must say false.</p>
     */
    public boolean isFloatEnabled(String key) {
        boolean defaultOn = !Prefs.KEY_DEV_MODE.equals(key)
                && !Prefs.KEY_TELE.equals(key);
        return prefs().getBoolean(key, defaultOn);
    }

    public int getFloatSizePercent() {
        int saved = prefs().getInt(Prefs.KEY_FLOAT_SIZE, 100);
        if (saved < FLOAT_SIZE_MIN) saved = FLOAT_SIZE_MIN;
        if (saved > FLOAT_SIZE_MAX) saved = FLOAT_SIZE_MAX;
        return saved;
    }

    public int getFloatAlphaPercent() {
        int saved = prefs().getInt(Prefs.KEY_FLOAT_ALPHA, 92);
        if (saved < FLOAT_ALPHA_MIN) saved = FLOAT_ALPHA_MIN;
        if (saved > FLOAT_ALPHA_MAX) saved = FLOAT_ALPHA_MAX;
        return saved;
    }

    public int getTeleReplayDelayMs() {
        int saved = prefs().getInt(Prefs.KEY_TELE_REPLAY_DELAY, TELE_REPLAY_DELAY_MIN);
        if (saved < TELE_REPLAY_DELAY_MIN) saved = TELE_REPLAY_DELAY_MIN;
        if (saved > TELE_REPLAY_DELAY_MAX) saved = TELE_REPLAY_DELAY_MAX;
        return saved;
    }

    /** Builds and sends the appearance broadcast using the VpnActions contract keys. */
    public void send() {
        Intent intent = new Intent(VpnActions.ACTION_APPLY_FLOAT_APPEARANCE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(VpnActions.EXTRA_FLOAT_SIZE, getFloatSizePercent());
        intent.putExtra(VpnActions.EXTRA_FLOAT_ALPHA, getFloatAlphaPercent());
        intent.putExtra(VpnActions.EXTRA_SHOW_GHOST, isFloatEnabled(Prefs.KEY_GHOST));
        intent.putExtra(VpnActions.EXTRA_SHOW_FREEZE, isFloatEnabled(Prefs.KEY_FREEZE));
        intent.putExtra(VpnActions.EXTRA_SHOW_TELE, isFloatEnabled(Prefs.KEY_TELE));
        intent.putExtra(VpnActions.EXTRA_DEV_MODE,
                BuildConfig.DEBUG && isFloatEnabled(Prefs.KEY_DEV_MODE));
        intent.putExtra(VpnActions.EXTRA_ESP_BOXES, prefs().getBoolean(Prefs.KEY_ESP_BOXES, true));
        intent.putExtra(VpnActions.EXTRA_ESP_RADAR, prefs().getBoolean(Prefs.KEY_ESP_RADAR, true));
        intent.putExtra(VpnActions.EXTRA_ESP_LINE,  prefs().getBoolean(Prefs.KEY_ESP_LINE,  false));
        intent.putExtra(VpnActions.EXTRA_ESP_DEBUG,
                BuildConfig.DEBUG && prefs().getBoolean(Prefs.KEY_ESP_DEBUG, false));
        intent.putExtra(VpnActions.EXTRA_ESP_TUNE, prefs().getBoolean(Prefs.KEY_ESP_TUNE, false));
        intent.putExtra(VpnActions.EXTRA_MOD_MENU, prefs().getBoolean(Prefs.KEY_MOD_MENU, false));
        intent.putExtra(VpnActions.EXTRA_AIM_SETUP, prefs().getBoolean(Prefs.KEY_AIM_SETUP, false));
        intent.putExtra(VpnActions.EXTRA_AIM_ENABLED, prefs().getBoolean(Prefs.KEY_AIM_ENABLED, false));
        intent.putExtra(VpnActions.EXTRA_AIM_BUTTON, prefs().getBoolean(Prefs.KEY_AIM_BUTTON, false));
        intent.putExtra(VpnActions.EXTRA_ZOOM_BUTTON, prefs().getBoolean(Prefs.KEY_ZOOM_BUTTON, false));
        intent.putExtra(VpnActions.EXTRA_TELE_REPLAY_DELAY, getTeleReplayDelayMs());
        intent.putExtra(VpnActions.EXTRA_ESP_BOX_COLOR,
                prefs().getInt(Prefs.KEY_ESP_BOX_COLOR,
                        android.graphics.Color.argb(235, 255, 64, 64)));
        intent.putExtra(VpnActions.EXTRA_LOBBY_ROUTE, true);
        // setPackage makes this an EXPLICIT broadcast: only this app can
        // receive it. That is the sender half of closing the exported-
        // receiver hole -- on API 21-32 there is no RECEIVER_NOT_EXPORTED
        // flag, so this is the only defence there. See
        // PacketVpnService.registerReceiverCompat.
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
