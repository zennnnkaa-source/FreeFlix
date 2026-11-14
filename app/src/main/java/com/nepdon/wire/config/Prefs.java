package com.nepdon.wire.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Central accessor for the app's {@link SharedPreferences}. Every key lives here
 * so the UI, the VPN engine, and the overlay never hard-code a preference name.
 *
 * <p>Target-app selection supports both the legacy single-package key
 * ({@link #KEY_TARGET_PACKAGE}) and the new multi-package set
 * ({@link #KEY_TARGET_PACKAGES}). {@link #getTargetPackages(Context)} falls back
 * to the legacy key so existing installs keep working.</p>
 */
public final class Prefs {

    public static final String NAME = "xevpn_prefs";

    // ---- Target app selection ----
    public static final String KEY_TARGET_PACKAGE  = "target_package";
    public static final String KEY_TARGET_PACKAGES = "target_packages";

    // ---- Floating overlay feature toggles ----
    public static final String KEY_GHOST      = "show_ghost";
    public static final String KEY_FREEZE     = "show_freeze";
    public static final String KEY_TELE       = "show_tele";
    /** Draw the ESP boxes over enemies. */
    public static final String KEY_ESP_BOXES  = "esp_boxes";
    /** Draw the ESP radar. */
    public static final String KEY_ESP_RADAR  = "esp_radar";
    /** Draw an ESP line from screen bottom-centre to each enemy's feet. */
    public static final String KEY_ESP_LINE   = "esp_line";
    /** Use the vehicle heading as the camera while mounted. */
    public static final String KEY_ESP_MOUNT_CAM = "esp_mount_cam";
    /** Show the ESP text debug panel (mask/pos/entity readout). */
    public static final String KEY_ESP_DEBUG  = "esp_debug";
    /** Show the draggable in-game ESP calibration panel. */
    public static final String KEY_ESP_TUNE    = "esp_tune_panel";
    public static final String KEY_MOD_MENU         = "mod_menu_panel";
    public static final String KEY_MOD_AUTO_FREEZE  = "mod_auto_freeze";
    public static final String KEY_MOD_FREEZE_FIRE  = "mod_freeze_fire";
    public static final String KEY_MOD_FREEZE_SCOPE = "mod_freeze_scope";
    public static final String KEY_MOD_FIREWALL     = "mod_firewall";
    public static final String KEY_MOD_DOMAIN       = "mod_domain";
    /** Local UDP port the VPN relay binds. 0 = the game's own source port. */
    public static final String KEY_MOD_RELAY_PORT   = "mod_relay_port";
    /** Show the aim setup panel overlay. Separate switch from the ESP one. */
    public static final String KEY_AIM_SETUP   = "aim_setup_panel";

    // ---- ESP projection calibration (see esp/EspTuning) ----
    // The camera FOV and the axis handedness are not in the packet stream, so
    // they are tuned by eye in game and persisted here.
    //
    // Schema version for the CAMERA keys only. A stored value always beats a
    // default, so correcting a default in code does nothing on a device that
    // has already saved one. Worse, when the projection maths changed (the
    // HOR+ aspect model) the MEANING of KEY_ESP_FOV_X changed with it -- from
    // "the horizontal FOV" to "the horizontal FOV at a 16:9 reference" -- and
    // an old hand-tuned number was then silently reinterpreted under maths it
    // was never tuned for. Bump this whenever a camera default or its meaning
    // changes; EspTuning.load() re-seeds the camera keys once and no one has
    // to know to press RESET.
    public static final String KEY_ESP_CAM_SCHEMA  = "esp_cam_schema";
    public static final String KEY_ESP_FOV_X       = "esp_fov_x";
    public static final String KEY_ESP_FOV_Y_SCALE = "esp_fov_y_scale";
    public static final String KEY_ESP_BOOM_M      = "esp_boom_m";
    public static final String KEY_ESP_PIVOT_M     = "esp_pivot_m";
    public static final String KEY_ESP_SHOULDER_M  = "esp_shoulder_m";
    public static final String KEY_ESP_LEAD_MS     = "esp_lead_ms";
    public static final String KEY_ESP_EXTRAP_MAX  = "esp_extrap_max_m";
    public static final String KEY_ESP_EXTRAPOLATE = "esp_extrapolate";
    public static final String KEY_ESP_AUTO_LEAD   = "esp_auto_lead";
    public static final String KEY_ESP_SMOOTH_MS   = "esp_smooth_ms";
    public static final String KEY_ESP_CAM_EXTRAP_MS  = "esp_cam_extrap_ms";
    public static final String KEY_ESP_CAM_EXTRAP_DEG = "esp_cam_extrap_max_deg";
    public static final String KEY_ESP_POSE        = "esp_pose";
    public static final String KEY_ESP_PITCH_LSB   = "esp_pitch_lsb";
    public static final String KEY_ESP_ZOOM        = "esp_fov_zoom";
    /** Projection zoom applied while the scope is up. Empirical, tuned by eye. */
    public static final String KEY_ESP_SCOPE_ZOOM  = "esp_scope_zoom";
    /** Apply KEY_ESP_SCOPE_ZOOM automatically on the scope latch. */
    public static final String KEY_ESP_SCOPE_AUTO  = "esp_scope_auto_zoom";
    /** Extra size-only multiplier while scoped; separates size from position. */
    public static final String KEY_ESP_SCOPE_BOX   = "esp_scope_box_scale";
    /** Optic-centre nudge while scoped, px. */
    public static final String KEY_ESP_SCOPE_OFF_X = "esp_scope_off_x";
    public static final String KEY_ESP_SCOPE_OFF_Y = "esp_scope_off_y";
    /** Horizontal-only extra zoom while scoped; tests the non-HOR+ optic. */
    public static final String KEY_ESP_SCOPE_XEX   = "esp_scope_x_extra";
    /** Treat the scoped optic as square (drop the screen aspect term). */
    public static final String KEY_ESP_SCOPE_SQ    = "esp_scope_square";
    /** Camera boom while scoped, metres (first-person ADS: 0). */
    public static final String KEY_ESP_SCOPE_BOOM  = "esp_scope_boom_m";
    /** Camera shoulder offset while scoped, metres. */
    public static final String KEY_ESP_SCOPE_SHLD  = "esp_scope_shoulder_m";
    /** Camera pivot height while scoped, metres (AimStartPostion). */
    public static final String KEY_ESP_SCOPE_PIVOT = "esp_scope_pivot_m";
    /** Lead the camera basis even when target prediction is off. */
    public static final String KEY_ESP_CAM_LEAD_ALWAYS = "esp_cam_lead_always";
    /** Carry targets forward by record age with prediction still off. */
    public static final String KEY_ESP_AGE_CARRY   = "esp_target_age_carry";
    public static final String KEY_ZOOM_BUTTON     = "zoom_button";

    // ---- Aim ----
    public static final String KEY_AIM_ENABLED   = "aim_enabled";
    public static final String KEY_AIM_BUTTON    = "aim_button";
    public static final String KEY_AIM_FOV       = "aim_fov_deg";
    public static final String KEY_AIM_HEIGHT    = "aim_height_m";
    public static final String KEY_AIM_STICKY    = "aim_sticky";
    public static final String KEY_AIM_HYST      = "aim_hysteresis";
    public static final String KEY_AIM_CIRCLE    = "aim_show_circle";
    public static final String KEY_AIM_TEAMCHECK = "aim_team_check";
    /** Retarget the squad map pin (Cmd 148) at the ESP target. Default off. */
    public static final String KEY_MARK_RETARGET = "mark_retarget";
    public static final String KEY_AIM_UNKNOWN   = "aim_target_unknown";
    public static final String KEY_AIM_CX        = "aim_circle_x_off";
    public static final String KEY_AIM_CY        = "aim_circle_y_off";
    public static final String KEY_AIM_DOT       = "aim_dot_px";
    public static final String KEY_AIM_LOCK      = "aim_lock_px";
    public static final String KEY_AIM_SWIPE     = "aim_swipe_px_per_deg";
    public static final String KEY_AIM_INJECT    = "aim_inject";
    public static final String KEY_AIM_SWIPE_INV = "aim_swipe_invert";
    public static final String KEY_AIM_SWIPE_X   = "aim_swipe_x_frac";
    public static final String KEY_AIM_SWIPE_Y   = "aim_swipe_y_frac";
    public static final String KEY_AIM_SPEED     = "aim_swipe_speed_pxs";
    public static final String KEY_AIM_GAIN      = "aim_swipe_gain";
    public static final String KEY_AIM_DEADZONE  = "aim_swipe_deadzone_px";
    public static final String KEY_AIM_LAG       = "aim_swipe_lag_ms";
    public static final String KEY_AIM_HOLD      = "aim_swipe_hold_ms";
    public static final String KEY_AIM_YIELD     = "aim_yield_to_touch";
    public static final String KEY_AIM_REGRIP    = "aim_swipe_regrip_ms";
    public static final String KEY_AIM_SENS      = "aim_game_sensitivity";
    public static final String KEY_AIM_TRAVEL    = "aim_max_travel_px";
    public static final String KEY_AIM_GAIN_X    = "aim_swipe_gain_x";
    public static final String KEY_AIM_DZ_X      = "aim_swipe_deadzone_x_px";
    public static final String KEY_AIM_X_ON      = "aim_swipe_x_enabled";

    // ---- Setup editor layout (ui/edit/Layout) ----
    // Normalised element geometry placed by the on-screen editor. New keys,
    // never aliases of the KEY_AIM_* above: the old swipe fractions stay the
    // fallback until KEY_SETUP_PLACED is true, so an old reader keeps its old
    // answer.
    public static final String KEY_SETUP_LX1    = "setup_line_x_x1";
    public static final String KEY_SETUP_LY1    = "setup_line_x_y1";
    public static final String KEY_SETUP_LX2    = "setup_line_x_x2";
    public static final String KEY_SETUP_LY2    = "setup_line_x_y2";
    public static final String KEY_SETUP_VX1    = "setup_line_y_x1";
    public static final String KEY_SETUP_VY1    = "setup_line_y_y1";
    public static final String KEY_SETUP_VX2    = "setup_line_y_x2";
    public static final String KEY_SETUP_VY2    = "setup_line_y_y2";
    public static final String KEY_SETUP_FCX    = "setup_fire_cx";
    public static final String KEY_SETUP_FCY    = "setup_fire_cy";
    public static final String KEY_SETUP_FR     = "setup_fire_r";
    public static final String KEY_SETUP_PLACED = "setup_layout_placed";
    /** Plant the synthetic finger on the placed fire button. */
    public static final String KEY_SETUP_PLANT_FIRE = "setup_plant_on_fire";
    /** Acquire only while scoped. */
    public static final String KEY_AIM_SCOPE_ONLY   = "aim_scope_only";
    /** Fire the test spin automatically on every scope-in. */
    public static final String KEY_AIM_SPIN_SCOPE   = "aim_spin_on_scope";
    /** Arm the fire-press steer test. */
    public static final String KEY_AIM_TEST_MODE    = "aim_test_mode";
    /** How long the scope-in steer runs, ms. */
    public static final String KEY_AIM_SPIN_MS      = "aim_spin_ms";
    /** Modelled standing capsule height, metres. */
    public static final String KEY_AIM_BODY_H    = "aim_body_height_m";
    /** Crouched height as a fraction of standing. */
    public static final String KEY_AIM_CROUCH_R  = "aim_crouch_ratio";
    /** Aim anchor as a fraction of capsule height (0.82 = neck base). */
    public static final String KEY_AIM_ANCHOR    = "aim_anchor_frac";
    /** Client-side pipeline lead applied to the aim point, ms. */
    public static final String KEY_AIM_PIPELINE  = "aim_pipeline_ms";
    /** Refuse a target whose freshest record is older than this, ms. */
    public static final String KEY_AIM_SRC_AGE   = "aim_max_src_age_ms";
    /** Refuse targets by posture (unverified enum; off by default). */
    public static final String KEY_AIM_POSE_GATE = "aim_posture_gate";
    /** Acquire any enemy in the cone, skipping the optional gates. */
    public static final String KEY_AIM_TEST_TRACK = "aim_test_track";
    /** Extra px-per-degree multiplier while scoped. */
    public static final String KEY_AIM_SCOPED_GAIN = "aim_scoped_gain";
    /** Chase time constant for the steer, ms. */
    public static final String KEY_AIM_STEER_TAU = "aim_steer_tau";
    /** Where on the drawn box the aim points, fraction of box height. */
    public static final String KEY_AIM_BOX_FRAC = "aim_box_frac";
    /** Own-keepalive silence before the aim treats the basis as unusable, ms. */
    public static final String KEY_AIM_BASIS_AGE = "aim_basis_stale_ms";

    // ---- Hit rewrite (Cmd 106, AimFilter) ----
    public static final String KEY_HIT_MOVE      = "hit_move_point";
    public static final String KEY_HIT_HEAD_H    = "hit_head_h_m";
    public static final String KEY_HIT_IDX15     = "hit_idx15_mode";
    public static final String KEY_HIT_ZONE      = "hit_target_zone";
    public static final String KEY_HIT_UB_ONLY   = "hit_upper_body_only";
    public static final String KEY_HIT_UB_MIN_H  = "hit_ub_min_h_m";
    public static final String KEY_HIT_CP_PATCH  = "hit_cp_patch";
    public static final String KEY_HIT_HARVEST   = "hit_harvest_head";
    public static final String KEY_ESP_OFF_X       = "esp_off_x";
    public static final String KEY_ESP_OFF_Y       = "esp_off_y";
    public static final String KEY_ESP_MIRROR_X    = "esp_mirror_x";
    public static final String KEY_ESP_AXIS_SWAP   = "esp_axis_swap";
    public static final String KEY_ESP_NEG_A1      = "esp_neg_a1";
    public static final String KEY_ESP_NEG_A2      = "esp_neg_a2";
    public static final String KEY_ESP_PITCH_NEG   = "esp_pitch_neg";
    public static final String KEY_ESP_VIEW_MODE   = "esp_view_mode";
    public static final String KEY_ESP_AUTOCYCLE   = "esp_autocycle";
    public static final String KEY_ESP_SHOW_TEAM   = "esp_show_team";
    public static final String KEY_ESP_SHOW_UNK    = "esp_show_unk";
    public static final String KEY_ESP_HP_BAR      = "esp_hp_bar";
    public static final String KEY_ESP_NAMES       = "esp_show_names";
    public static final String KEY_ESP_HP_BAR_H    = "esp_hp_bar_h";
    public static final String KEY_ESP_RADAR_MIN   = "esp_radar_min_m";
    public static final String KEY_ESP_RADAR_MAX   = "esp_radar_max_m";

    // ---- Overlay appearance ----
    public static final String KEY_FLOAT_ALPHA          = "float_alpha_percent";
    public static final String KEY_FLOAT_SIZE           = "float_size_percent";
    /** Packed ARGB int for the enemy ESP box stroke colour. */
    public static final String KEY_ESP_BOX_COLOR        = "esp_box_color";

    // ---- Login / licensing (docs/LOGIN_AND_SERVER.md) ----
    /** Stable device public key (base64 X.509), for the server fingerprint. */
    public static final String KEY_DEVICE_PUBKEY = "device_pubkey_b64";
    /** Tink-AEAD-encrypted refresh token. */
    public static final String KEY_AUTH_REFRESH  = "auth_refresh_enc";
    /** Server-signed offline lease (base64 json + "." + hex sig); self-verifying. */
    public static final String KEY_AUTH_LEASE    = "auth_lease";
    /** Cached tier label for UI (non-authoritative). */
    public static final String KEY_AUTH_TIER     = "auth_tier";
    /**
     * Tink-AEAD-encrypted license key. Persisted so the client can SILENTLY
     * re-activate after a benign session loss (a dropped-rotation refresh that the
     * server rejected) instead of dumping the user back to the key form. Same
     * at-rest protection as the refresh token; the key is server-revocable.
     */
    public static final String KEY_AUTH_KEY      = "auth_key_enc";

    // ---- Native-lib live update (docs/NATIVE_LIVE_UPDATE.md) ----
    /** Currently-active downloaded native lib version (0 = none, use bundled). */
    public static final String KEY_NATIVE_ACTIVE     = "native_active_ver";
    /** A downloaded-but-not-yet-promoted version (promoted after one clean load). */
    public static final String KEY_NATIVE_CANDIDATE  = "native_candidate_ver";
    /** Set right before System.load(); a value that survives a launch bricked us. */
    public static final String KEY_NATIVE_ATTEMPTING = "native_attempting_ver";
    /** Monotonic high-water mark; a manifest at/below this is refused (downgrade guard). */
    public static final String KEY_NATIVE_HIGHWATER  = "native_high_water";
    /** Comma-separated versions that failed load/self-test and must never be retried. */
    public static final String KEY_NATIVE_QUARANTINE = "native_quarantine";
    /** Prefix (+version) for the cached signed manifest JSON of a downloaded lib. */
    public static final String KEY_NATIVE_MANIFEST   = "native_manifest_";
    /** Stable per-install id for the staged-rollout bucket. */
    public static final String KEY_NATIVE_INSTALL_ID = "native_install_id";

    // ---- Engine tuning ----
    public static final String KEY_DEV_MODE                 = "dev_mode";
    public static final String KEY_TELE_REPLAY_DELAY        = "tele_replay_delay_ms";
    public static final String KEY_TUN_IP_CURSOR            = "tun_ip_cursor";
    public static final String KEY_DEV_LAST_LOG_FILE         = "dev_last_log_file";
    public static final String KEY_CAPTURE_LAST_REPORT       = "capture_last_report";
    public static final String KEY_CAPTURE_LAST_RAW          = "capture_last_raw";
    public static final String KEY_TELEMETRY_LAST_FILE      = "telemetry_last_file";
    public static final String KEY_CAPTURE_MODE             = "capture_mode";
    public static final String KEY_CAPTURE_RETRO_SEC        = "capture_retro_sec";

    private Prefs() { }

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---- Target packages (multi-app) ----

    /** Returns the ordered set of selected target packages, never null. */
    public static Set<String> getTargetPackages(Context ctx) {
        SharedPreferences p = get(ctx);
        Set<String> set = p.getStringSet(KEY_TARGET_PACKAGES, null);
        if (set != null && !set.isEmpty()) {
            return new LinkedHashSet<>(set);
        }
        // Legacy single-package fallback.
        String legacy = p.getString(KEY_TARGET_PACKAGE, "");
        if (legacy != null && !legacy.isEmpty()) {
            LinkedHashSet<String> single = new LinkedHashSet<>();
            single.add(legacy);
            return single;
        }
        return Collections.emptySet();
    }

    /** Persists the selected target packages (also mirrors the first into the legacy key). */
    public static void setTargetPackages(Context ctx, Set<String> packages) {
        SharedPreferences.Editor e = get(ctx).edit();
        if (packages == null || packages.isEmpty()) {
            e.remove(KEY_TARGET_PACKAGES);
            e.remove(KEY_TARGET_PACKAGE);
        } else {
            e.putStringSet(KEY_TARGET_PACKAGES, new LinkedHashSet<>(packages));
            e.putString(KEY_TARGET_PACKAGE, packages.iterator().next());
        }
        e.commit();
    }
}
