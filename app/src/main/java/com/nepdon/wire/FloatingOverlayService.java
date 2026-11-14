package com.nepdon.wire;

import com.nepdon.wire.util.Humanize;
import com.nepdon.wire.ui.aim.AimConfig;
import com.nepdon.wire.ui.aim.AimController;
import com.nepdon.wire.ui.aim.Daemon;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.VpnActions;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.nepdon.wire.ui.overlay.DevPopup;
import com.nepdon.wire.ui.button.OverlayAnimations;
import com.nepdon.wire.ui.button.OverlayButton;

public class FloatingOverlayService extends Service {

    private static final String CH_ID = "gbf";
    private static final int NOTIF_ID = 2;

    private boolean onFreeze = false;
    private boolean onGhost = false;
    private boolean onJitter = false;
    private boolean addedF = false;
    private boolean addedG = false;
    private boolean addedJ = false;
    private boolean addedAim = false;
    private boolean addedZoom = false;
    /**
     * Aim is held, and it is CLIENT-SIDE ONLY.
     *
     * <p>FREEZE / GHOST / TELE deliberately avoid optimistic flips: they send an
     * intent and wait for the engine's ACTION_STATE broadcast to repaint, because
     * the engine owns that state. Aim is different — the packet engine has no aim
     * state to be the truth about, it lives entirely in the renderer. So this is
     * flipped locally and painted immediately.</p>
     */
    private boolean onAim = false;
    /**
     * Capture state, kept for the status broadcast only.
     *
     * <p>THE BUTTON IS GONE. Capture is always-on: {@code VpnEngine} opens a
     * fresh dump on every engine start and the writer streams to disk in real
     * time. A button could therefore only ever turn OFF the recording the
     * diagnostics depend on -- and a dump that stops when you most need it is
     * worse than none.</p>
     */
    /** Reused by the title poll; never allocate on a repeating callback. */
    private final int[] roleScratch = new int[3];


    private TextView btnFreeze;
    private TextView btnGhost;
    private TextView btnJitter;
    private TextView btnAim;
    private TextView btnZoom;
    private WindowManager wm;
    private WindowManager.LayoutParams freezeParams;
    private WindowManager.LayoutParams ghostParams;
    private WindowManager.LayoutParams teleParams;
    private WindowManager.LayoutParams aimParams;
    private WindowManager.LayoutParams zoomParams;
    private BroadcastReceiver rcv;
    private ToneGenerator toneGenerator;
    private Typeface floatingButtonFont;

    private OverlayButton button;
    private OverlayAnimations animations;
    private DevPopup devPopup;
    private com.nepdon.wire.ui.overlay.EspOverlay espOverlay;
    private com.nepdon.wire.ui.panel.EspSetupPanel espTunePanel;
    private com.nepdon.wire.ui.panel.AimSetupPanel aimSetupPanel;
    private com.nepdon.wire.ui.panel.ModMenuPanel modMenuPanel;
    private final com.nepdon.wire.mod.AutoFreeze autoFreeze = new com.nepdon.wire.mod.AutoFreeze();
    private static final long AUTO_FREEZE_TICK_MS = 50L;
    private long lastGhostReleaseMs = 0L;
    private final Runnable autoFreezeLoop = new Runnable() {
        @Override public void run() {
            try {
                com.nepdon.wire.tunnel.VpnEngineState s = PacketVpnService.snapshot();
                boolean tunnelOn = s != null && s.tunnelActive;
                boolean freezeOn = s != null && s.freezeActive;
                long nowMs = System.currentTimeMillis();
                if (autoFreeze.tick(tunnelOn, freezeOn, nowMs)) {
                    send(VpnActions.ACTION_FREEZE);
                }
                // v3: the server resent our reliable traffic. Ghost is the layer that
                // withholds ACKs, so release it once per fresh alarm. The 1 s debounce
                // stops a second toggle while the snapshot is still catching up.
                long alarmMs = com.nepdon.wire.protocol.ServerSignal.retransmitAlarmMs();
                if (com.nepdon.wire.filter.HoldExperiments.RELEASE_ON_OWN_CORRECTION) {
                    alarmMs = Math.max(alarmMs,
                            com.nepdon.wire.protocol.ServerSignal.ownCorrectionMs());
                }
                boolean ghostOn = s != null && s.ghostActive;
                if (ghostOn && alarmMs > lastGhostReleaseMs
                        && nowMs - alarmMs < 1500L && nowMs - lastGhostReleaseMs > 1000L) {
                    lastGhostReleaseMs = nowMs;
                    send(VpnActions.ACTION_GHOST);
                }
            } catch (Throwable t) {
                android.util.Log.w("Fakelag", "auto freeze tick failed", t);
            }
            if (handler != null) handler.postDelayed(this, AUTO_FREEZE_TICK_MS);
        }
    };
    /**
     * The full-screen layout editor. Owned here rather than by a panel so both
     * panels can open the same one and there is exactly one window to remove
     * when the service dies -- an editor left attached would hold every touch
     * on the device with nothing on screen to dismiss it.
     */
    private com.nepdon.wire.ui.edit.LayoutEditor layoutEditor;

    private int floatAlphaPercent = 92;
    private int floatSizePercent = 100;

    private boolean showGhost = false;
    private boolean showFreeze = false;
    private boolean showTele = false;
    private boolean showDevMode = false;
    private boolean showEspTune = false;
    private boolean showAimSetup = false;
    private boolean showModMenu = false;
    private boolean showAimButton = false;
    private boolean showZoomButton = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        doForeground();
        wm = (WindowManager) getSystemService("window");
        initTone();
        // assets/fonts/chudep.ttf was a ZERO-BYTE FILE, so this always threw
        // and every overlay button silently ran on the catch branch. The asset
        // is deleted and the fallback is now the actual, stated intent rather
        // than an accident that looked deliberate.
        floatingButtonFont = Typeface.DEFAULT_BOLD;

        button = new OverlayButton(this, wm, floatingButtonFont, new OverlayButton.ClickListener() {
            @Override
            public void onClick(int id) {
                click(id);
            }
        });
        // AIM is now a plain toggle (id 9), handled in click() like freeze —
        // not a hold. So no setHold() wiring; the button just flips the endless
        // test-swipe on and off.
        AimController.init();
        // Where the nvmux daemon binary is extracted to (see extractNativeLibs);
        // the injector spawns it from here via Shizuku when the swipe starts.
        Daemon.setLibDir(getApplicationInfo().nativeLibraryDir);
        // MASK BAR REMOVED (watermark over gameplay).
        animations = new OverlayAnimations(this, wm);
        devPopup = new DevPopup(this, wm);
        espOverlay = new com.nepdon.wire.ui.overlay.EspOverlay(this, wm);
        espTunePanel = new com.nepdon.wire.ui.panel.EspSetupPanel(this, wm);
        modMenuPanel = new com.nepdon.wire.ui.panel.ModMenuPanel(this, wm);
        handler.removeCallbacks(autoFreezeLoop);
        handler.post(autoFreezeLoop);
        layoutEditor = new com.nepdon.wire.ui.edit.LayoutEditor(this, wm);
        aimSetupPanel = new com.nepdon.wire.ui.panel.AimSetupPanel(this, wm,
                new com.nepdon.wire.ui.panel.AimSetupPanel.EditorHook() {
                    @Override
                    public void openEditor() {
                        if (layoutEditor != null) layoutEditor.open();
                    }
                });
        // Push the saved projection calibration into the renderer BEFORE the
        // overlay starts drawing, so the first frame already uses it.
        // Guarded: nothing below this line may be skipped because a tuning
        // value failed to load. espOverlay.show() is what puts the ESP, the
        // tune panel and the crosshair on screen, and it used to sit behind an
        // unguarded load() that could throw via the Shizuku-backed injector.
        try {
            com.nepdon.wire.config.EspTuning.load(this);
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "EspTuning.load failed; using defaults", t);
        }
        try {
            com.nepdon.wire.mod.ModConfig.load(this);
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "ModConfig.load failed; using defaults", t);
        }
        // The placed swipe/fire geometry. Guarded for the same reason: a bad
        // stored fraction must not stop the overlay being shown.
        try {
            com.nepdon.wire.ui.edit.Layout.load(this);
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "Layout.load failed; using defaults", t);
        }
        // Arm the scope-in watcher from the loaded preference. Must come AFTER
        // EspTuning.load, which is what reads that preference, and after
        // AimController.init(), which creates the worker thread it runs on.
        try {
            // ALWAYS on: this watcher is what arms and releases the daemon,
            // not an optional extra. The panel is grabbed on scope-in and
            // given back on scope-out.
            
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "scope watcher failed to arm", t);
        }
        // Arm the fire-press steer. The trigger has to be the press itself:
        // the scope latch comes off the wire, so it lands well after the scope
        // is actually up -- which is why the spin kept firing once the player
        // had already come out of it.
        try {
            pushRealScreenSize();
            // Take the grab NOW, at service start, while nothing is touching the
            // screen. The daemon defers it if a finger happens to be down. It
            // must not be taken later, mid-gesture: EVIOCGRAB during a touch
            // strands the framework's pointer on the real device permanently.
            if (AimConfig.TEST_MODE) AimController.arm();
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "aim input path failed to arm", t);
        }
        // The feature registry, AFTER EspTuning so that a setting which has
        // been migrated to a Feature wins over its old hand-written load line.
        // See EspTuning.load's note: during the migration a key may briefly
        // appear in both, and the registry is the one that should decide.
        // Guarded for the same reason the call above is: a bad stored value
        // must not stop the overlay being shown.
        try {
            com.nepdon.wire.features.Registry.install(this);
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "Feature registry failed; using defaults", t);
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.CFG_NO_PREFS);
        }
        // ESP overlay: full-screen box/radar renderer + calibration readout,
        // live while the VPN engine is alive.
        espOverlay.show(new com.nepdon.wire.ui.overlay.EspOverlay.EspStateProvider() {
            @Override
            public String stateText() {
                com.nepdon.wire.tunnel.VpnEngineState s = PacketVpnService.snapshot();
                if (s == null || !s.engineRun) return "ESP: engine off";
                com.nepdon.wire.esp.EspEngine esp = PacketVpnService.espEngine();
                if (esp == null) return "ESP: not running";
                return esp.calibrationReadout();
            }

            @Override
            public com.nepdon.wire.esp.EspEngine engine() {
                com.nepdon.wire.tunnel.VpnEngineState s = PacketVpnService.snapshot();
                if (s == null || !s.engineRun) return null;
                return PacketVpnService.espEngine();
            }
        });

        android.content.SharedPreferences prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        showGhost = prefs.getBoolean(Prefs.KEY_GHOST, false);
        showFreeze = prefs.getBoolean(Prefs.KEY_FREEZE, false);
        showTele = com.nepdon.wire.config.FeatureGate.TELE && prefs.getBoolean(Prefs.KEY_TELE, false);
        showDevMode = BuildConfig.DEBUG && prefs.getBoolean(Prefs.KEY_DEV_MODE, false);
        // Shipped in all variants now (slimmed prediction-only panel is a user
        // feature). Debug-readout and dev-mode stay BuildConfig.DEBUG-gated.
        showEspTune = prefs.getBoolean(Prefs.KEY_ESP_TUNE, false);
        showModMenu = prefs.getBoolean(Prefs.KEY_MOD_MENU, false);
        showAimSetup = com.nepdon.wire.config.FeatureGate.AIM && prefs.getBoolean(Prefs.KEY_AIM_SETUP, false);
        showAimButton = com.nepdon.wire.config.FeatureGate.AIM && prefs.getBoolean(Prefs.KEY_AIM_BUTTON, true);
        showZoomButton = prefs.getBoolean(Prefs.KEY_ZOOM_BUTTON, false);
        // ESP controls. Boxes and radar are independent (radar is useful on
        // its own — it needs no camera calibration), and the debug readout
        // can be switched off without losing the ESP itself.
        com.nepdon.wire.config.EspConfig.SHOW_BOXES =
                prefs.getBoolean(Prefs.KEY_ESP_BOXES, true);
        com.nepdon.wire.config.EspConfig.SHOW_RADAR =
                prefs.getBoolean(Prefs.KEY_ESP_RADAR, true);
        com.nepdon.wire.config.EspConfig.SHOW_ESP_LINE =
                prefs.getBoolean(Prefs.KEY_ESP_LINE, false);
        com.nepdon.wire.config.EspConfig.BOX_COLOR_ARGB =
                prefs.getInt(Prefs.KEY_ESP_BOX_COLOR,
                        android.graphics.Color.argb(235, 255, 64, 64));
        if (espOverlay != null) {
            espOverlay.setDebugVisible(
                    BuildConfig.DEBUG && prefs.getBoolean(Prefs.KEY_ESP_DEBUG, false));
        }
        if (showEspTune) espTunePanel.show(tuneHint);
        if (showModMenu && modMenuPanel != null) modMenuPanel.show(tuneHint);
        if (showAimSetup) aimSetupPanel.show(tuneHint);
        floatAlphaPercent = prefs.getInt(Prefs.KEY_FLOAT_ALPHA, 92);
        floatSizePercent = prefs.getInt(Prefs.KEY_FLOAT_SIZE, 100);
        if (floatAlphaPercent < 35) floatAlphaPercent = 35;
        if (floatAlphaPercent > 100) floatAlphaPercent = 100;
        if (floatSizePercent < 80) floatSizePercent = 80;
        if (floatSizePercent > 260) floatSizePercent = 260;

        // The status title. Not branding this time: it answers the two
        // questions actually asked when something looks wrong -- can the
        // engine work yet, and how many players can it see. The old banner
        // drove an infinite 120 Hz shadow-blur animator; this one repaints
        // only when its text changes.
        animations.makeTopBanner(floatSizePercent);
        if (showDevMode) devPopup.make(floatSizePercent);
        if (showFreeze) storeButton(button.makeBtn(getString(R.string.freeze), 18, 110, 2, floatAlphaPercent, floatSizePercent, R.drawable.ic_freeze));
        if (showGhost) storeButton(button.makeBtn(getString(R.string.ghost), 156, 110, 3, floatAlphaPercent, floatSizePercent, R.drawable.ic_ghost));
        if (showTele) storeButton(button.makeBtn(getString(R.string.tele), 18, 248, 4, floatAlphaPercent, floatSizePercent, R.drawable.ic_tele));
        // NO CAPTURE BUTTON. Capture is always-on -- VpnEngine opens a fresh
        // dump on every engine start -- so a button could only ever turn OFF
        // the recording the diagnostics depend on.
        //
        // This line was creating the view unconditionally while its storeButton
        // case had already been deleted, so the returned view went nowhere: it
        // was drawn, never painted, never clickable, and impossible to remove.
        // Deleting the wiring without deleting the construction is how you get
        // a button that exists and does nothing.
        if (showAimButton) {
            storeButton(button.makeBtn(getString(R.string.aim), 18, 386, 9,
                    floatAlphaPercent, floatSizePercent));
        }
        if (showZoomButton) {
            storeButton(button.makeBtn("1X", 156, 386, 10,
                    floatAlphaPercent, floatSizePercent));
        }

        doRegister();

        // Paint from engine reality: if the VPN service is already running
        // (overlay started after engine, or both sticky-restored), the
        // buttons must show the live filter states — not hardcoded off.
        com.nepdon.wire.tunnel.VpnEngineState s = PacketVpnService.snapshot();
        if (s != null) {
            onFreeze = s.freezeActive;
            onGhost = s.ghostActive;
            onJitter = s.jitterActive;
        }
        if (btnFreeze != null) button.paintBtn(btnFreeze, getString(R.string.freeze), onFreeze, floatAlphaPercent, R.drawable.ic_freeze);
        if (btnGhost != null) button.paintBtn(btnGhost, getString(R.string.ghost), onGhost, floatAlphaPercent, R.drawable.ic_ghost);
        if (btnJitter != null) button.paintBtn(btnJitter, getString(R.string.tele), onJitter, floatAlphaPercent, R.drawable.ic_tele);
        animations.startRainbowAnimation();
        animations.startPulseAnimation();
    }

    /** Feeds the tune panel the renderer's live nearest-target readout. */
    private final com.nepdon.wire.ui.panel.SetupPanel.HintProvider tuneHint =
            new com.nepdon.wire.ui.panel.SetupPanel.HintProvider() {
                @Override
                public String hint() {
                    return (espOverlay == null) ? "ESP overlay off" : espOverlay.calibHint();
                }
            };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(autoFreezeLoop);
        try {
            if (rcv != null) unregisterReceiver(rcv);
        } catch (Exception e) { }
        rm(btnFreeze, addedF);
        rm(btnGhost, addedG);
        rm(btnJitter, addedJ);
        rm(btnAim, addedAim);
        rm(btnZoom, addedZoom);
        if (espOverlay != null) espOverlay.hide();
        if (espTunePanel != null) espTunePanel.hide();
        if (modMenuPanel != null) modMenuPanel.hide();
        if (aimSetupPanel != null) aimSetupPanel.hide();
        if (layoutEditor != null) layoutEditor.close();
        animations.removeBanner();
        devPopup.remove();
        animations.cancelAll();
        // Stop the swipe and kill the nvmux daemon so it does not outlive the
        // overlay holding the panel grabbed.
        try {
            AimController.shutdown();
        } catch (Throwable ignored) {
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    private void doForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService("notification");
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(CH_ID, "GB", NotificationManager.IMPORTANCE_MIN);
                    ch.setSound(null, null);
                    nm.createNotificationChannel(ch);
                }
                Notification n = new Notification.Builder(this, CH_ID)
                        .setContentTitle("Chơi Game - Ping Thấp")
                        .setContentText("Máy chủ giảm độ trễ đã kích hoạt")
                        .setSmallIcon(0x1080039)
                        .build();
                startForeground(NOTIF_ID, n);
            } else {
                Notification n = new Notification.Builder(this)
                        .setContentTitle("Chơi Game - Ping Thấp")
                        .setContentText("Máy chủ giảm độ trễ đã kích hoạt")
                        .setSmallIcon(0x1080039)
                        .build();
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            try {
                Notification n = new Notification();
                n.icon = 0x1080039;
                startForeground(NOTIF_ID, n);
            } catch (Exception e2) { }
        }
    }

    private void doRegister() {
        rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (VpnActions.ACTION_APPLY_FLOAT_APPEARANCE.equals(action)) {
                    applyAppearanceIntent(intent);
                    return;
                }
                if (VpnActions.ACTION_STATE.equals(action)) {
                    applyEngineState(intent);
                    return;
                }
                if (!VpnActions.ACTION_STATS.equals(action)) return;

                String mode = intent.getStringExtra(VpnActions.EXTRA_MODE);
                if (mode == null) mode = "STANDBY";
            }
        };

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(VpnActions.ACTION_STATS);
            filter.addAction(VpnActions.ACTION_STATE);
            filter.addAction(VpnActions.ACTION_APPLY_FLOAT_APPEARANCE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                // API 21-32: gate with the signature permission so a co-installed
                // app can't spoof appearance/state or flip dev-mode (audit M1).
                registerReceiver(rcv, filter, "com.nepdon.wire.permission.INTERNAL_BROADCAST", null);
            }
        } catch (Exception e) {
            // A dead receiver means every button freezes at its last paint and
            // the overlay silently stops following the engine.
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.UI_RECEIVER_REGISTER_FAILED);
        }
    }

    /** Repaints every button from the engine's broadcast truth. */
    private void applyEngineState(Intent intent) {
        onFreeze = intent.getBooleanExtra("freeze_active", false);
        onGhost = intent.getBooleanExtra("ghost_active", false);
        onJitter = intent.getBooleanExtra("jitter_active", false);
        boolean engineRun = intent.getBooleanExtra("engine_run", false);
        boolean matchReady = intent.getBooleanExtra("match_ready", false);
        if (btnFreeze != null) button.paintBtn(btnFreeze, getString(R.string.freeze), onFreeze, floatAlphaPercent, R.drawable.ic_freeze);
        if (btnGhost != null) button.paintBtn(btnGhost, getString(R.string.ghost), onGhost, floatAlphaPercent, R.drawable.ic_ghost);
        if (btnJitter != null) button.paintBtn(btnJitter, getString(R.string.tele), onJitter, floatAlphaPercent, R.drawable.ic_tele);
        // The banner is the live engine-status indicator: IDLE (engine off),
        // LEARNING (movement stream not yet confirmed — effects won't bite
        // yet), READY (armed), ACTIVE (an effect is running).
        if (animations != null) {
            // Player counts, but only while the engine is live AND boxes are
            // on -- a count shown when nothing is drawing is a number with no
            // relationship to the screen.
            int en = -1, tm = 0, uk = 0;
            boolean riding = false;
            if (engineRun
                    && com.nepdon.wire.config.EspConfig.SHOW_BOXES) {
                com.nepdon.wire.esp.EspEngine esp = PacketVpnService.espEngine();
                if (esp != null) {
                    // 3 s: long enough to ride out the 198 ms entity tick and a
                    // dropped burst, short enough that someone who has stopped
                    // being streamed drops off while you are still looking.
                    esp.roleCounts(3000L, roleScratch);
                    en = roleScratch[0];
                    tm = roleScratch[1];
                    uk = roleScratch[2];
                    riding = esp.mounted();
                }
            }
            int status;
            if (!engineRun) {
                status = com.nepdon.wire.ui.button.OverlayAnimations.STATUS_IDLE;
            } else if (!matchReady) {
                status = com.nepdon.wire.ui.button.OverlayAnimations.STATUS_LEARNING;
            } else if (onFreeze || onGhost || onJitter) {
                status = com.nepdon.wire.ui.button.OverlayAnimations.STATUS_ACTIVE;
            } else {
                status = com.nepdon.wire.ui.button.OverlayAnimations.STATUS_READY;
            }
            animations.setBannerStatus(status, en, tm, uk, riding);
        }
    }

    private void storeButton(OverlayButton.ButtonResult r) {
        switch (r.id) {
            case 2: btnFreeze = r.view; freezeParams = r.params; addedF = r.added; break;
            case 3: btnGhost = r.view; ghostParams = r.params; addedG = r.added; break;
            case 4: btnJitter = r.view; teleParams = r.params; addedJ = r.added; break;
            case 9: btnAim = r.view; aimParams = r.params; addedAim = r.added; break;
            case 10: btnZoom = r.view; zoomParams = r.params; addedZoom = r.added; break;
        }
    }

    private void initTone() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 95);
        } catch (Exception e) {
            toneGenerator = null;
        }
    }

    private void playToggleBeep(boolean isOn) {
        if (toneGenerator == null) return;
        try {
            toneGenerator.startTone(
                    isOn ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_ACK,
                    isOn ? 120 : 105
            );
        } catch (Exception e) { }
    }

    private void applyAppearanceIntent(Intent intent) {
        if (intent == null) return;
        int nextSize = intent.getIntExtra(VpnActions.EXTRA_FLOAT_SIZE, floatSizePercent);
        int nextAlpha = intent.getIntExtra(VpnActions.EXTRA_FLOAT_ALPHA, floatAlphaPercent);
        if (nextSize < 80) nextSize = 80;
        if (nextSize > 260) nextSize = 260;
        if (nextAlpha < 35) nextAlpha = 35;
        if (nextAlpha > 100) nextAlpha = 100;
        floatSizePercent = nextSize;
        floatAlphaPercent = nextAlpha;
        showGhost = intent.getBooleanExtra(VpnActions.EXTRA_SHOW_GHOST, showGhost);
        showFreeze = intent.getBooleanExtra(VpnActions.EXTRA_SHOW_FREEZE, showFreeze);
        showTele = com.nepdon.wire.config.FeatureGate.TELE && intent.getBooleanExtra(VpnActions.EXTRA_SHOW_TELE, showTele);
        showDevMode = intent.getBooleanExtra(VpnActions.EXTRA_DEV_MODE, showDevMode);
        // ESP switches used to be read only in onCreate, so flipping one in the
        // app did nothing until the overlay was restarted. They ride the same
        // appearance broadcast as everything else now.
        com.nepdon.wire.config.EspConfig.SHOW_BOXES =
                intent.getBooleanExtra(VpnActions.EXTRA_ESP_BOXES,
                        com.nepdon.wire.config.EspConfig.SHOW_BOXES);
        com.nepdon.wire.config.EspConfig.SHOW_RADAR =
                intent.getBooleanExtra(VpnActions.EXTRA_ESP_RADAR,
                        com.nepdon.wire.config.EspConfig.SHOW_RADAR);
        com.nepdon.wire.config.EspConfig.SHOW_ESP_LINE =
                intent.getBooleanExtra(VpnActions.EXTRA_ESP_LINE,
                        com.nepdon.wire.config.EspConfig.SHOW_ESP_LINE);
        com.nepdon.wire.config.EspConfig.BOX_COLOR_ARGB =
                intent.getIntExtra(VpnActions.EXTRA_ESP_BOX_COLOR,
                        com.nepdon.wire.config.EspConfig.BOX_COLOR_ARGB);
        if (espOverlay != null) {
            espOverlay.setDebugVisible(intent.getBooleanExtra(
                    VpnActions.EXTRA_ESP_DEBUG, espOverlay.isDebugVisible()));
        }
        showEspTune = intent.getBooleanExtra(VpnActions.EXTRA_ESP_TUNE, showEspTune);
        showModMenu = intent.getBooleanExtra(VpnActions.EXTRA_MOD_MENU, showModMenu);
        showAimSetup = com.nepdon.wire.config.FeatureGate.AIM && intent.getBooleanExtra(VpnActions.EXTRA_AIM_SETUP, showAimSetup);
        showAimButton = com.nepdon.wire.config.FeatureGate.AIM && intent.getBooleanExtra(VpnActions.EXTRA_AIM_BUTTON, showAimButton);
        showZoomButton = intent.getBooleanExtra(VpnActions.EXTRA_ZOOM_BUTTON, showZoomButton);
        applyRealtimeAppearance();
    }

    /**
     * Tell the injector the TRUE physical screen size.
     *
     * <p>Not the overlay view's size, and not {@code getDisplayMetrics()}. The
     * daemon maps raw panel coordinates onto the whole physical panel, so a
     * fire circle sized against an inset view lands ~170 px left of the real
     * button and every press reads as a miss. {@code getRealMetrics} is the
     * only call that reports the panel itself, so it is the right call here
     * regardless of what the view reports.</p>
     *
     * <p><b>UNRESOLVED, 2026-09-20 — do not build on either number.</b> This
     * javadoc has said the ESP view measures <b>2117x1080</b> against a
     * 2340x1080 display (223 px of cutout and nav-bar inset). The renderer's
     * own live VIEWPORT row, recorded the same session, reads
     * {@code 2340x1080 org 0,0 exact}. They cannot both be current, and the
     * 2117 note was written AFTER {@code FLAG_LAYOUT_NO_LIMITS} went on the ESP
     * window, which is exactly the flag that would have made it stale.</p>
     *
     * <p>It does not affect the projection either way — a WIDTH mismatch
     * cancels out of both projection scales, so it could only ever translate,
     * never rescale. It DOES affect the aim editor, which draws its placed
     * geometry against the view's {@code w/h} while {@link com.nepdon.wire.ui.aim.AimController}
     * resolves the same fractions against {@code getRealMetrics}. To settle it:
     * open the ESP setup panel in game and read the VIEWPORT row, or run
     * {@code dumpsys window windows} with the overlay up (overlay frames never
     * appear in a uiautomator dump — {@code FLAG_NOT_FOCUSABLE}).</p>
     */
    private void pushRealScreenSize() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                AimController.setScreen(dm.widthPixels, dm.heightPixels);
            }
        } catch (Throwable t) {
            android.util.Log.w("Fakelag", "real screen size unavailable", t);
        }
    }

    private void applyRealtimeAppearance() {
        pushRealScreenSize();
        syncFloatingButtonVisibility();
        button.updateButtonLayout(btnFreeze, freezeParams, 2, onFreeze, floatAlphaPercent, floatSizePercent);
        button.updateButtonLayout(btnGhost, ghostParams, 3, onGhost, floatAlphaPercent, floatSizePercent);
        button.updateButtonLayout(btnJitter, teleParams, 4, onJitter, floatAlphaPercent, floatSizePercent);
        devPopup.update(showDevMode, floatSizePercent);
        if (espTunePanel != null) espTunePanel.update(showEspTune, tuneHint);
        if (modMenuPanel != null) modMenuPanel.update(showModMenu, null);
        if (aimSetupPanel != null) aimSetupPanel.update(showAimSetup, tuneHint);
        syncAimButton();
        syncZoomButton();
    }

    private void syncFloatingButtonVisibility() {
        if (showFreeze) {
            if (btnFreeze == null) storeButton(button.makeBtn(getString(R.string.freeze), 18, 110, 2, floatAlphaPercent, floatSizePercent, R.drawable.ic_freeze));
            button.paintBtn(btnFreeze, getString(R.string.freeze), onFreeze, floatAlphaPercent, R.drawable.ic_freeze);
        } else {
            rm(btnFreeze, addedF);
            btnFreeze = null;
            freezeParams = null;
            addedF = false;
        }

        if (showGhost) {
            if (btnGhost == null) storeButton(button.makeBtn(getString(R.string.ghost), 156, 110, 3, floatAlphaPercent, floatSizePercent, R.drawable.ic_ghost));
            button.paintBtn(btnGhost, getString(R.string.ghost), onGhost, floatAlphaPercent, R.drawable.ic_ghost);
        } else {
            rm(btnGhost, addedG);
            btnGhost = null;
            ghostParams = null;
            addedG = false;
        }

        if (showTele) {
            if (btnJitter == null) storeButton(button.makeBtn(getString(R.string.tele), 18, 248, 4, floatAlphaPercent, floatSizePercent, R.drawable.ic_tele));
            button.paintBtn(btnJitter, getString(R.string.tele), onJitter, floatAlphaPercent, R.drawable.ic_tele);
        } else {
            rm(btnJitter, addedJ);
            btnJitter = null;
            teleParams = null;
            addedJ = false;
        }
    }

    private void click(int id) {
        // No optimistic flips: the engine's ACTION_STATE broadcast repaints
        // the button when the toggle actually lands (queued during restarts).
        // The beep still fires immediately as touch feedback.
        // Beep only if the tap can actually land. It used to beep
        // unconditionally, so after onRevoke() -- another VPN app takes the
        // slot -- every press was discarded while still sounding accepted.
        boolean live = PacketVpnService.engineAcceptsTaps();
        if (id == 2) {
            if (live) playToggleBeep(!onFreeze);
            send(VpnActions.ACTION_FREEZE);
        } else if (id == 3) {
            if (live) playToggleBeep(!onGhost);
            send(VpnActions.ACTION_GHOST);
        } else if (id == 4) {
            if (live) playToggleBeep(!onJitter);
            send(VpnActions.ACTION_JITTER);
        } else if (id == 9) {
            // AIM test harness: toggle the endless circle-swipe at the button's
            // own spot (see AimController). Purely client-side, no engine state.
            setAimActive(!onAim);
            playToggleBeep(onAim);
        } else if (id == 10) {
            // Scope zoom cycles 1x -> 2x -> 4x -> 8x. Client-side only, like
            // AIM: the packet engine has no scope state to be the truth about
            // (it is not in the stream at all), so flip and repaint locally.
            float z = com.nepdon.wire.config.EspTuning.cycleZoom();
            if (btnZoom != null) {
                button.paintBtn(btnZoom, String.format(java.util.Locale.US, "%.0fX", z),
                        z > 1.01f, floatAlphaPercent);
            }
            playToggleBeep(z > 1.01f);
        }
    }

    /**
     * The AIM button: the master switch for everything that touches the screen.
     *
     * <p>It used to gate only the corrective-swipe loop, while the scope-in
     * steer ran off {@code TEST_MODE} alone — so turning AIM off left the steer
     * firing on every scope. A control labelled AIM that does not stop the aim
     * is worse than no control.</p>
     *
     * <p>Off does NOT release the grab. Releasing mid-touch strands a pointer
     * exactly as grabbing mid-touch does; the daemon keeps the panel and simply
     * stops being told to do anything with it.</p>
     */
    private void setAimActive(boolean on) {
        onAim = on;
        if (on) {
            pushRealScreenSize();
            // Spawn the daemon NOW rather than on the first acquisition. It used
            // to start inside fireAtTarget(), after the freshness gate, so with
            // nothing acquired there was no daemon, no process and no log --
            // and "no target" was indistinguishable from "the input path never
            // came up".
            AimController.arm();
            AimController.setActive(true);
            startAimLoop();
        } else {
            stopAimLoop();
            AimController.setActive(false);
        }
        if (btnAim != null) {
            button.paintBtn(btnAim, getString(R.string.aim), on, floatAlphaPercent);
        }
    }

    // ---- real-aim loop ----------------------------------------------------
    // While the Aim toggle is on, fire ONE corrective swipe whenever a target is
    // acquired. Deliberately not a tracking controller: each swipe is computed
    // once, open-loop, from the error the renderer measured (see AimController
    // .fireAtTarget). The renderer's own gates decide whether a target exists at
    // all -- team known, stationary, in cone, in range.
    /** Main-looper handler for the aim loop. */
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long AIM_LOOP_MS = 120L;
    /** Minimum gap between swipes, so one settles before the next begins. */
    private static final long AIM_SHOT_GAP_MS = 350L;
    /** Shot gap is a floor: it only stretches, up to this fraction. Loop tick is symmetric. */
    private static final double AIM_SHOT_GAP_JITTER = 0.25;
    private static final double AIM_LOOP_JITTER = 0.30;
    private final Humanize aimRng = new Humanize();
    private long nextAimGapMs = AIM_SHOT_GAP_MS;
    private long lastAimShotMs = 0L;
    private Runnable aimLoop;

    private void startAimLoop() {
        stopAimLoop();
        aimLoop = new Runnable() {
            @Override
            public void run() {
                if (!onAim) return;
                long now = System.currentTimeMillis();
                if (now - lastAimShotMs >= nextAimGapMs) {
                    if (AimController.fireAtTarget()) {
                        lastAimShotMs = now;
                        nextAimGapMs = aimRng.upMs(AIM_SHOT_GAP_MS, AIM_SHOT_GAP_JITTER);
                    }
                }
                if (handler != null) handler.postDelayed(this, aimRng.jitterMs(AIM_LOOP_MS, AIM_LOOP_JITTER));
            }
        };
        if (handler != null) handler.post(aimLoop);
    }

    private void stopAimLoop() {
        if (handler != null && aimLoop != null) handler.removeCallbacks(aimLoop);
        aimLoop = null;
    }

    private void syncZoomButton() {
        if (showZoomButton) {
            float z = com.nepdon.wire.config.EspConfig.FOV_ZOOM;
            String lbl = String.format(java.util.Locale.US, "%.0fX", z);
            if (btnZoom == null) {
                storeButton(button.makeBtn(lbl, 156, 386, 10,
                        floatAlphaPercent, floatSizePercent));
            }
            button.updateButtonLayout(btnZoom, zoomParams, 10, z > 1.01f,
                    floatAlphaPercent, floatSizePercent);
        } else {
            rm(btnZoom, addedZoom);
            btnZoom = null;
            zoomParams = null;
            addedZoom = false;
        }
    }

    private void syncAimButton() {
        if (showAimButton) {
            if (btnAim == null) {
                storeButton(button.makeBtn(getString(R.string.aim), 18, 386, 9,
                        floatAlphaPercent, floatSizePercent));
            }
            button.updateButtonLayout(btnAim, aimParams, 9, onAim,
                    floatAlphaPercent, floatSizePercent);
        } else {
            // Never leave aim latched on behind a hidden button.
            if (onAim) setAimActive(false);
            rm(btnAim, addedAim);
            btnAim = null;
            aimParams = null;
            addedAim = false;
        }
    }

    private void send(String action) {
        // Filter taps take the in-process path when the service is alive: it
        // is the same process (no android:process in the manifest), so the
        // intent was a binder round trip to AMS that came back to this very
        // looper. For freeze that round trip sat in front of the packet
        // filter on every single press. See PacketVpnService.applyFilterTap.
        //
        // Anything else -- and the case where the service is not running, in
        // which case only an intent can start it -- still goes via the intent.
        com.nepdon.wire.filter.FilterKind kind =
                com.nepdon.wire.filter.FilterKind.fromAction(action);
        if (kind != null && PacketVpnService.applyFilterTap(kind)) {
            return;
        }
        try {
            Intent i = new Intent(this, PacketVpnService.class);
            i.setAction(action);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) { }
    }

    private void rm(View v, boolean added) {
        if (!added || v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception e) { }
    }
}
