package com.nepdon.wire.config;

/**
 * Single source of truth for every Intent action and extra key used to talk to
 * {@link PacketVpnService} and {@link FloatingOverlayService}. Keeping these in
 * one place (instead of duplicated string literals across three services) is
 * the contract that lets the transport core, the filter engine, and the UI evolve
 * independently.
 */
public final class VpnActions {

    private VpnActions() { }

    // ---- Service control actions (sent to PacketVpnService) ----
    public static final String ACTION_ACTIVATE   = "com.nepdon.wire.ACTIVATE";
    public static final String ACTION_DEACTIVATE = "com.nepdon.wire.DEACTIVATE";
    public static final String ACTION_FREEZE     = "com.nepdon.wire.FREEZE";
    public static final String ACTION_GHOST      = "com.nepdon.wire.GHOST";
    public static final String ACTION_JITTER     = "com.nepdon.wire.JITTER";
    /** Toggles the aim / hit-zone rewrite (outbound Cmd 106 Body/Limb -> Head). */
    public static final String ACTION_AIM        = "com.nepdon.wire.AIM";
    public static final String ACTION_SET_APP    = "com.nepdon.wire.SET_APP";
    public static final String ACTION_STATS      = "com.nepdon.wire.STATS";
    public static final String ACTION_STOP       = "com.nepdon.wire.STOP";

    // ---- Packet capture (dev) ----
    public static final String ACTION_CAPTURE_START = "com.nepdon.wire.CAPTURE_START";
    public static final String ACTION_CAPTURE_STOP  = "com.nepdon.wire.CAPTURE_STOP";
    public static final String ACTION_CAPTURE_DUMP  = "com.nepdon.wire.CAPTURE_DUMP";
    public static final String ACTION_TELEMETRY_DUMP = "com.nepdon.wire.TELEMETRY_DUMP";

    // ---- Broadcast action (MainActivity -> services) ----
    public static final String ACTION_APPLY_FLOAT_APPEARANCE = "com.nepdon.wire.APPLY_FLOAT_APPEARANCE";

    // ---- State broadcast (engine -> UI, on every change + stats tick) ----
    public static final String ACTION_STATE = "com.nepdon.wire.STATE";

    // ---- Extra keys ----
    public static final String EXTRA_BYTES_IN        = "bytes_in";
    public static final String EXTRA_BYTES_OUT       = "bytes_out";
    public static final String EXTRA_FILTER_LOG      = "filter_log";
    public static final String EXTRA_FILTER_ENABLED  = "filter_enabled";
    public static final String EXTRA_LOSS            = "loss";
    public static final String EXTRA_MODE            = "mode";
    public static final String EXTRA_PING            = "ping";
    public static final String EXTRA_PACKAGE         = "PACKAGE";
    public static final String EXTRA_PACKAGE_ALT     = "PKG";
    public static final String EXTRA_PACKAGES        = "PACKAGES";

    public static final String EXTRA_DEV_MODE        = "dev_mode";
    public static final String EXTRA_TELE_REPLAY_DELAY = "tele_replay_delay_ms";
    public static final String EXTRA_LOBBY_ROUTE     = "vpn_lobby_route";

    public static final String EXTRA_FLOAT_SIZE              = "float_size_percent";
    public static final String EXTRA_FLOAT_ALPHA             = "float_alpha_percent";

    // ESP switches ride the appearance broadcast so they apply without an
    // overlay restart.
    public static final String EXTRA_ESP_BOXES               = "esp_boxes";
    public static final String EXTRA_ESP_RADAR               = "esp_radar";
    public static final String EXTRA_ESP_LINE                = "esp_line";
    public static final String EXTRA_ESP_DEBUG               = "esp_debug";
    public static final String EXTRA_ESP_TUNE                = "esp_tune_panel";
    public static final String EXTRA_MOD_MENU                = "mod_menu_panel";
    public static final String EXTRA_AIM_SETUP               = "aim_setup_panel";
    public static final String EXTRA_AIM_ENABLED             = "aim_enabled";
    public static final String EXTRA_AIM_BUTTON              = "aim_button";
    public static final String EXTRA_ZOOM_BUTTON             = "zoom_button";

    public static final String EXTRA_SHOW_GHOST      = "SHOW_GHOST";
    public static final String EXTRA_SHOW_FREEZE     = "SHOW_FREEZE";
    public static final String EXTRA_SHOW_TELE       = "SHOW_TELE";

    /** Packed ARGB int for the enemy ESP box stroke colour. */
    public static final String EXTRA_ESP_BOX_COLOR   = "esp_box_color";

    public static final String EXTRA_CAPTURE_HEX_LIMIT = "capture_hex_limit";
    public static final String EXTRA_CAPTURE_MODE      = "capture_mode";
}
