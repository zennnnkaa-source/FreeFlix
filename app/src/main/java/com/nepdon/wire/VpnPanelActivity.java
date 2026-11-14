package com.nepdon.wire;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nepdon.wire.config.VpnActions;
import com.nepdon.wire.tunnel.VpnEngineState;
import com.nepdon.wire.web.ControlServer;

/**
 * The only screen. One switch for the VPN and the localhost link. Every other
 * switch lives on the control page, so nothing here changes what gets blocked.
 */
public final class VpnPanelActivity extends Activity {

    private static final int REQ_OVERLAY = 7101;
    private static final int REQ_VPN = 7102;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };
    private final Runnable overlayTask = new Runnable() {
        @Override
        public void run() {
            startOverlay();
        }
    };

    private Button toggle;
    private TextView link;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        link = new TextView(this);
        link.setTextIsSelectable(true);
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, dp(16), 0, 0);

        root.addView(toggle, new LinearLayout.LayoutParams(dp(200), dp(56)));
        root.addView(link, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_OVERLAY) {
            askVpn();
        } else if (req == REQ_VPN && result == RESULT_OK) {
            startEngine();
        }
    }

    private boolean engineOn() {
        VpnEngineState s = PacketVpnService.snapshot();
        return s != null && s.engineRun;
    }

    private void refresh() {
        boolean on = engineOn();
        toggle.setText(on ? "OFF" : "ON");
        link.setText(on ? "http://127.0.0.1:" + ControlServer.PORT + "/?t=" + ControlServer.token() : "");
    }

    private void onToggle() {
        if (engineOn()) {
            stopEngine();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        askVpn();
    }

    /** prepare() returns null once consent was granted, so no dialog after the first time. */
    private void askVpn() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN);
        } else {
            startEngine();
        }
    }

    private void startEngine() {
        try {
            Intent i = new Intent(this, PacketVpnService.class);
            i.setAction(VpnActions.ACTION_SET_APP);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            link.setText("start failed: " + e.getMessage());
            return;
        }
        handler.postDelayed(overlayTask, 500L);
        handler.postDelayed(refreshTask, 800L);
        handler.postDelayed(refreshTask, 2500L);
    }

    private void startOverlay() {
        try {
            Intent si = new Intent(this, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(si);
            } else {
                startService(si);
            }
        } catch (Exception e) {
            link.setText("overlay failed: " + e.getMessage());
        }
    }

    private void stopEngine() {
        try {
            stopService(new Intent(this, FloatingOverlayService.class));
            Intent i = new Intent(this, PacketVpnService.class);
            i.setAction(VpnActions.ACTION_STOP);
            startService(i);
        } catch (Exception e) {
            link.setText("stop failed: " + e.getMessage());
            return;
        }
        handler.postDelayed(refreshTask, 800L);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
