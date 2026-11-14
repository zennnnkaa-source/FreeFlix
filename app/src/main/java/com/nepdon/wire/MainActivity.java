package com.nepdon.wire;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.dev.FileWatchLog;
import com.nepdon.wire.dev.StorageAccess;
import com.nepdon.wire.config.VpnActions;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.nepdon.wire.ui.settings.AppPicker;
import com.nepdon.wire.ui.settings.AppearanceBroadcaster;
import com.nepdon.wire.ui.settings.PermissionController;
import com.nepdon.wire.ui.settings.UiBindings;
import com.nepdon.wire.tunnel.VpnEngineState;
import com.nepdon.wire.ui.aim.AimController;
import com.nepdon.wire.ui.aim.Privilege;

/**
 * Thin settings shell: lifecycle, engine start/stop, and the permission flow.
 * View wiring lives in {@link UiBindings}, permission state in
 * {@link PermissionController}, appearance broadcast in
 * {@link AppearanceBroadcaster}, and multi-app selection in {@link AppPicker}.
 *
 * <p><b>State contract.</b> The power button paints from engine reality:
 * {@link #onResume()} reads {@link PacketVpnService#snapshot()} synchronously
 * (fixes "reopen app shows OFF while the VPN is running"), and a live
 * {@code ACTION_STATE} receiver keeps it current while the activity is open.
 * No optimistic flips.</p>
 */
public class MainActivity extends Activity {

    private static final long START_TIMEOUT_MS = 4000L;

    private View btnEngine;
    private ImageView ivEnginePower;
    private TextView tvApp;
    private TextView tvStatus;
    private Button btnShizuku;
    private View dotEngine;
    private View dotApp;
    // The strip pinned above the tab bar, on every page.
    private View dotStrip;
    private TextView tvStripStatus;
    private TextView btnStripEngine;
    private boolean engineOn = false;
    private boolean waiting  = false;

    private AppearanceBroadcaster broadcaster;
    private UiBindings uiBindings;
    private PermissionController permissionController;
    private com.nepdon.wire.ui.settings.Pager pager;
    private AppPicker appPicker;

    private BroadcastReceiver stateReceiver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startTimeout = new Runnable() {
        @Override
        public void run() {
            if (waiting) {
                waiting = false;
                VpnEngineState s = PacketVpnService.snapshot();
                setUI(s != null && s.engineRun);
                if (s == null || !s.engineRun) toast("Engine failed to start");
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StorageAccess.ensure(this);

        // Auth gate (defense in depth — LoginActivity is the normal entry point).
        // In non-enforcing debug builds isAuthorized() is always true.
        if (!NepdonGate.isLoggedIn(this) && !com.nepdon.wire.auth.SessionManager.isAuthorized()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Load the Rust packet core (verified downloaded copy, else the bundled
        // fallback) and check for a live update — off the main thread, non-fatal.
        com.nepdon.wire.net.NativeLoader.init(getApplicationContext());

        try {
            if (getActionBar() != null) getActionBar().hide();
        } catch (Exception e) { }

        btnEngine   = findViewById(R.id.btn_engine);
        ivEnginePower = (ImageView) findViewById(R.id.iv_engine_power);
        tvApp       = (TextView) findViewById(R.id.tv_selected_app);
        tvStatus    = (TextView) findViewById(R.id.tv_status);
        dotEngine   = findViewById(R.id.dot_engine);
        dotApp      = findViewById(R.id.dot_app);
        dotStrip      = findViewById(R.id.dot_strip);
        tvStripStatus = (TextView) findViewById(R.id.tv_strip_status);
        btnStripEngine = (TextView) findViewById(R.id.btn_strip_engine);
        final Button btnShowFloating = (Button) findViewById(R.id.btn_show_floating);

        broadcaster = new AppearanceBroadcaster(this);
        uiBindings = new UiBindings(this, broadcaster);
        permissionController = new PermissionController(this);
        appPicker = new AppPicker(this, new AppPicker.Listener() {
            @Override
            public void onSelectionChanged(int selectedCount) {
                updateAppSummary(selectedCount);
            }
        });

        uiBindings.setupAll();
        permissionController.setup();
        // Four pages instead of one ~30-row scroll. Every view stays in the
        // tree and pages are shown/hidden, because ~60 ids are bound once at
        // startup and lazily-inflated pages would hand those bindings null.
        pager = new com.nepdon.wire.ui.settings.Pager(this);
        pager.show(com.nepdon.wire.ui.settings.Pager.PAGE_HOME);
        // The tunnel is the whole product, so offer the VPN consent dialog
        // straight away rather than making the user find a row and press it.
        // Once per process - see askOnFreshLaunch.
        permissionController.askOnFreshLaunch();
        setUI(false);
        appPicker.loadApps();
        updateAppSummary(Prefs.getTargetPackages(this).size());

        btnEngine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (waiting) return;
                if (!engineOn) {
                    if (Prefs.getTargetPackages(MainActivity.this).isEmpty()) {
                        toast(getString(R.string.toast_choose_app));
                        return;
                    }
                    checkOverlay();
                } else {
                    stopEngine();
                }
            }
        });

        if (btnStripEngine != null) {
            btnStripEngine.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { btnEngine.performClick(); }
            });
        }

        btnShowFloating.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!appPicker.isLoaded()) {
                    toast(getString(R.string.checking));
                    return;
                }
                appPicker.show();
            }
        });

        AimController.init();
        btnShizuku = (Button) findViewById(R.id.btn_shizuku);
        btnShizuku.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShizukuButton();
            }
        });
        updateShizukuButton();

        registerStateReceiver();
    }

    /** Walk the user through the Shizuku setup based on the current state. */
    private void onShizukuButton() {
        int st = Privilege.state(this);
        switch (st) {
            case Privilege.STATE_NOT_INSTALLED:
                toast("Install Shizuku, then return");
                openShizukuInstall();
                break;
            case Privilege.STATE_NOT_RUNNING:
                toast("Start Shizuku via Wireless Debugging, then tap again");
                launchShizuku();
                break;
            case Privilege.STATE_NO_PERMISSION:
                Privilege.requestPermission();
                break;
            case Privilege.STATE_READY:
                toast("Shizuku ready — aim can inject");
                break;
        }
        // Reflect the new state shortly after (permission/launch is async).
        handler.postDelayed(new Runnable() {
            @Override public void run() { updateShizukuButton(); }
        }, 400L);
    }

    private void updateShizukuButton() {
        if (btnShizuku != null) btnShizuku.setText(Privilege.stateLabel(this));
    }

    private void openShizukuInstall() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + Privilege.SHIZUKU_PKG)));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/download/")));
            } catch (Throwable ignored) {
            }
        }
    }

    private void launchShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(Privilege.SHIZUKU_PKG);
            if (i != null) startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissionController.refresh();
        Privilege.refresh();
        updateShizukuButton();
        // Synchronous truth read: if the VPN service is alive (user reopened
        // the app with the engine running), show ON — no restart churn.
        VpnEngineState s = PacketVpnService.snapshot();
        setUI(s != null && s.engineRun);
        waiting = false;
        handler.removeCallbacks(startTimeout);
    }

    @Override
    protected void onDestroy() {
        if (appPicker != null) appPicker.cancel();
        handler.removeCallbacks(startTimeout);
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Exception e) { }
            stateReceiver = null;
        }
        super.onDestroy();
    }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                boolean engineRun = intent.getBooleanExtra("engine_run", false);
                if (waiting && engineRun) {
                    waiting = false;
                    handler.removeCallbacks(startTimeout);
                    toast("Engine ON!");
                }
                if (!engineRun && waiting) return;   // restart blip; keep waiting
                setUI(engineRun);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(VpnActions.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                // API 21-32: gate with the signature permission (audit M1).
                registerReceiver(stateReceiver, filter, "com.nepdon.wire.permission.INTERNAL_BROADCAST", null);
            }
        } catch (Exception e) {
            // Without ACTION_STATE the start/stop button never leaves its
            // optimistic state - it looks stuck, with nothing logged.
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.UI_RECEIVER_REGISTER_FAILED);
        }
    }

    private void updateAppSummary(int count) {
        if (count <= 0) {
            tvApp.setText(getString(R.string.no_apps_selected));
            if (dotApp != null) dotApp.setBackgroundResource(R.drawable.dot_off);
        } else {
            // B17: the card used to read "1 app(s) selected" - a count, for a
            // choice between two nearly identically named apps. Say WHICH.
            java.util.Set<String> pkgs = Prefs.getTargetPackages(this);
            String label = null;
            if (pkgs.size() == 1) {
                String pkg = pkgs.iterator().next();
                try {
                    label = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (Throwable t) {
                    label = pkg;
                }
            }
            tvApp.setText(label != null ? label
                    : getString(R.string.selected_apps_count, count));
            if (dotApp != null) dotApp.setBackgroundResource(R.drawable.dot_on);
        }
    }

    private void setUI(boolean on) {
        engineOn = on;
        if (dotEngine != null)
            dotEngine.setBackgroundResource(on ? R.drawable.dot_on : R.drawable.dot_off);
        // Colours come from the Nepdon Wire tokens, not from literals. The status
        // line sits on the gradient header, where the old #FF6D7286 grey fell
        // below 3:1 and was effectively unreadable when disconnected - which
        // is exactly the state you most need to be able to read.
        // The card's own label. Without this it read "ACTIVATE ENGINE" while
        // the engine was running, so the one control on the screen described
        // the state it was NOT in.
        TextView engineLabel = (TextView) findViewById(R.id.tv_engine_label);
        if (engineLabel != null) {
            engineLabel.setText(getString(on ? R.string.deactivate_engine
                                             : R.string.activate_engine));
            engineLabel.setTextColor(getResources().getColor(R.color.nv_on_brand));
        }
        // The pinned strip paints from the same call, so the two can never
        // disagree about whether the engine is running.
        if (dotStrip != null) {
            dotStrip.setBackgroundResource(on ? R.drawable.dot_on : R.drawable.dot_off);
        }
        if (tvStripStatus != null) {
            tvStripStatus.setText(getString(on ? R.string.engine_status_connected
                                               : R.string.engine_status_disconnected));
        }
        if (btnStripEngine != null) {
            btnStripEngine.setText(getString(on ? R.string.nv_stop : R.string.nv_start));
            btnStripEngine.setBackgroundResource(
                    on ? R.drawable.nv_btn_secondary : R.drawable.nv_btn_primary);
            btnStripEngine.setTextColor(getResources().getColor(
                    on ? R.color.nv_text : R.color.nv_on_brand));
        }
        if (on) {
            btnEngine.setBackgroundResource(R.drawable.bg_power_circle_on);
            if (ivEnginePower != null)
                ivEnginePower.setColorFilter(getResources().getColor(R.color.nv_on_brand));
            tvStatus.setText(getString(R.string.engine_status_connected));
            tvStatus.setTextColor(getResources().getColor(R.color.nv_on_brand));
        } else {
            btnEngine.setBackgroundResource(R.drawable.bg_power_circle);
            if (ivEnginePower != null)
                ivEnginePower.setColorFilter(getResources().getColor(R.color.nv_on_brand));
            tvStatus.setText(getString(R.string.engine_status_disconnected));
            tvStatus.setTextColor(getResources().getColor(R.color.nv_on_brand));
        }
    }

    private void checkOverlay() {
        waiting = true;
        handler.postDelayed(startTimeout, START_TIMEOUT_MS);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, PermissionController.REQ_OVERLAY);
            } catch (Exception e) {
                checkVpn();
            }
        } else {
            checkVpn();
        }
    }

    private void checkVpn() {
        try {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, PermissionController.REQ_VPN);
            } else {
                startEngine();
            }
        } catch (Exception e) {
            waiting = false;
            setUI(false);
            toast("VPN: " + e.getMessage());
        }
    }

    private void startEngine() {
        VpnEngineState s = PacketVpnService.snapshot();
        if (s != null && s.engineRun) {
            // Already running (relaunch while engine on): nothing to restart.
            waiting = false;
            setUI(true);
            toast("Engine already running");
            ensureOverlay();
            return;
        }
        try {
            Intent i = new Intent(this, PacketVpnService.class);
            i.setAction(VpnActions.ACTION_SET_APP);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            // UI flips to ON when the engine's ACTION_STATE broadcast lands
            // (or the timeout fires). Overlay follows the engine.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { ensureOverlay(); }
            }, 500L);
        } catch (Exception e) {
            waiting = false;
            setUI(false);
            toast("XM: " + e.getMessage());
        }
    }

    private void ensureOverlay() {
        try {
            Intent si = new Intent(MainActivity.this, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(si);
            } else {
                startService(si);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopEngine() {
        waiting = false;
        handler.removeCallbacks(startTimeout);
        try {
            stopService(new Intent(this, FloatingOverlayService.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            Intent i = new Intent(this, PacketVpnService.class);
            i.setAction(VpnActions.ACTION_STOP);
            startService(i);
            setUI(false);
            toast("Engine OFF");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PermissionController.REQ_OVERLAY) {
            permissionController.refresh();
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                // Overlay denied: do not start a VPN the user cannot control.
                waiting = false;
                handler.removeCallbacks(startTimeout);
                setUI(false);
                toast("Overlay permission is required to control the engine");
                return;
            }
            checkVpn();
        } else if (requestCode == PermissionController.REQ_VPN) {
            permissionController.refresh();
            if (resultCode == RESULT_OK) {
                startEngine();
            } else {
                waiting = false;
                handler.removeCallbacks(startTimeout);
                setUI(false);
                toast("VPN Permission denied!");
            }
        } else if (requestCode == PermissionController.REQ_VPN_GRANT
                || requestCode == PermissionController.REQ_OVERLAY_GRANT) {
            // A GRANT, not an activation. Repaint the row and stop there:
            // granting a permission must never start the tunnel by itself.
            permissionController.refresh();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        permissionController.refresh();
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
