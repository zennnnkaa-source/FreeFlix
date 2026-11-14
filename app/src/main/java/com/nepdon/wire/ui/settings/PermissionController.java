package com.nepdon.wire.ui.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.nepdon.wire.R;

/**
 * The two permissions this app genuinely cannot work without.
 *
 * <h3>What was removed, and why</h3>
 *
 * <p><b>Notifications and Storage are gone.</b> Neither gated anything: the
 * foreground-service notification posts whether or not the user has allowed
 * notifications to be <i>shown</i>, and captures are written to the app's own
 * external files directory, which has needed no storage permission since
 * API 19. They were two rows that could sit permanently red while every
 * feature worked, which teaches people to ignore the whole section.</p>
 *
 * <p><b>Display-over-other-apps stays</b>, even though the brief said "VPN
 * only". Without it there is no floating overlay at all — no ESP, no radar, no
 * buttons — so removing its row would not simplify the screen, it would hide
 * the reason the app appears to do nothing. It is kept and stated plainly.</p>
 *
 * <h3>A permission is not a toggle</h3>
 *
 * <p>These used to render as switches, which is a lie in two directions: you
 * cannot revoke a permission by tapping it, and a switch that is "off" looks
 * like a feature you chose not to use rather than something blocking you. The
 * control is now a <b>GRANT button that disappears once granted</b>, leaving
 * the row quiet. Nothing to press means nothing is wrong.</p>
 */
public final class PermissionController {

    /**
     * Activation flow. MainActivity owns these: a result here means "the user
     * pressed the power button and consented", and it starts the engine.
     */
    public static final int REQ_VPN = 1001;
    public static final int REQ_OVERLAY = 1002;

    /**
     * GRANT flow. This class owns these, and a result only repaints a row.
     *
     * <p>Separate codes because sharing them was a real bug: the permission
     * rows and the fresh-launch prompt both used REQ_VPN, and
     * MainActivity.onActivityResult reads REQ_VPN as "consent for an
     * activation the user just asked for" and calls startEngine(). So on the
     * FIRST LAUNCH OF EVERY INSTALL, tapping OK on the system VPN dialog
     * started the tunnel with no target app selected -- the empty-target
     * guard lives in the power button's click handler, which was never run --
     * and an empty scope installs the UNSCOPED routes, relaying every app's
     * traffic through the userspace NAT.</p>
     */
    public static final int REQ_VPN_GRANT = 1003;
    public static final int REQ_OVERLAY_GRANT = 1004;

    private final Activity activity;
    private final View dotVpn, dotDisplay;
    private final TextView tvVpnStatus, tvDisplayStatus;
    private final TextView cbVpn, cbDisplay;

    /**
     * True once this process has offered the VPN consent dialog, so a fresh
     * launch asks exactly once rather than every time the Activity is
     * recreated by a rotation or a return from the picker.
     */
    private static boolean askedVpnThisLaunch = false;

    public PermissionController(Activity activity) {
        this.activity = activity;
        // Every one of these may be null: the notification and storage rows
        // were deleted from the layout, and a future layout may drop more.
        // Binding defensively is what lets the UI change without this class
        // needing to.
        dotVpn          = activity.findViewById(R.id.dot_vpn);
        dotDisplay      = activity.findViewById(R.id.dot_display);
        tvVpnStatus     = (TextView) activity.findViewById(R.id.tv_vpn_status);
        tvDisplayStatus = (TextView) activity.findViewById(R.id.tv_display_status);
        cbVpn           = (TextView) activity.findViewById(R.id.cb_vpn);
        cbDisplay       = (TextView) activity.findViewById(R.id.cb_display);
    }

    public void setup() {
        if (cbVpn != null) {
            cbVpn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    requestVpn();
                }
            });
        }
        if (cbDisplay != null) {
            cbDisplay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity)) {
                        try {
                            activity.startActivityForResult(
                                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                                    REQ_OVERLAY_GRANT);
                        } catch (Exception e) { }
                    }
                    refresh();
                }
            });
        }
    }

    /**
     * Offers the VPN consent dialog once per launch if it has not been given.
     *
     * <p>The tunnel is the entire product, so making the user find a row and
     * press it before anything can work is a step with one correct answer.
     * Android shows its own system dialog here and the user can still decline;
     * this only removes the hunting.</p>
     *
     * <p>Once per PROCESS, not once per {@code onCreate} — otherwise returning
     * from the app picker or rotating re-prompts someone who just declined.</p>
     */
    public void askOnFreshLaunch() {
        if (askedVpnThisLaunch) return;
        askedVpnThisLaunch = true;
        if (!vpnGranted()) requestVpn();
    }

    private void requestVpn() {
        try {
            Intent i = VpnService.prepare(activity);
            if (i != null) activity.startActivityForResult(i, REQ_VPN_GRANT);
        } catch (Exception e) {
            // Some OEM builds throw here when another VPN owns the slot.
            // Refresh below still reports the real state.
        }
        refresh();
    }

    private boolean vpnGranted() {
        try {
            return VpnService.prepare(activity) == null;
        } catch (Exception e) {
            return false;
        }
    }

    public void refresh() {
        setPermissionState(dotVpn, tvVpnStatus, cbVpn,
                "Required for the tunnel", vpnGranted());

        boolean displayOk = (Build.VERSION.SDK_INT < 23) || Settings.canDrawOverlays(activity);
        setPermissionState(dotDisplay, tvDisplayStatus, cbDisplay,
                "Required for the in-game overlay", displayOk);
    }

    /**
     * Granted: a teal dot, a quiet subtitle, and <b>no button at all</b>.
     * Missing: an amber dot and a GRANT button.
     */
    private void setPermissionState(View dot, TextView label, TextView action,
                                    String why, boolean granted) {
        if (dot != null) {
            dot.setBackgroundResource(granted ? R.drawable.dot_on : R.drawable.dot_warn);
        }
        if (label != null) {
            label.setText(granted
                    ? activity.getString(R.string.permission_granted)
                    : why);
            label.setTextColor(activity.getResources().getColor(
                    granted ? R.color.nv_text_3 : R.color.nv_learning));
        }
        if (action != null) {
            action.setTag(granted);
            if (granted) {
                // Nothing to do, so offer nothing to press. A control that
                // does nothing when tapped is worse than no control.
                action.setVisibility(View.GONE);
            } else {
                action.setVisibility(View.VISIBLE);
                action.setText(activity.getString(R.string.nv_grant));
                action.setBackgroundResource(R.drawable.nv_btn_primary);
                action.setTextColor(activity.getResources().getColor(R.color.nv_on_brand));
            }
        }
    }
}
