package com.nepdon.wire;

import android.app.Activity;
import android.content.Context;

/** Local access gate. Telegram redirect removed; key fixed to 666. */
public final class NepdonGate {
    public static final int MAX_DEVICES = 10000;
    private static final String PREFS = "nepdon_wire_access";
    private static final String LOGGED_IN = "logged_in";
    private static boolean noticeShown;

    // Accept key "666" only.
    private static final String VALID_KEY = "666";

    private NepdonGate() { }

    public static boolean isUsernameValid(String value) {
        if (value == null) return false;
        return VALID_KEY.equals(value.trim());
    }

    public static boolean isLoggedIn(Context context) {
        return context != null
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(LOGGED_IN, false);
    }

    public static void markLoggedIn(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(LOGGED_IN, true).apply();
    }

    public static boolean isExpired() {
        // Never expire locally.
        return false;
    }

    /** No-op: expiry dialog and telegram path removed. */
    public static void showExpiryDialog(final Activity activity) {
        // intentionally empty
    }

    /** No-op: telegram redirect removed. */
    public static void openTelegram(Context context) {
        // intentionally empty
    }
}
