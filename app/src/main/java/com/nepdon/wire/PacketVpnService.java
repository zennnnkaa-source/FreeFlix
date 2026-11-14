package com.nepdon.wire;

import com.nepdon.wire.dev.FileWatchLog;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.VpnActions;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;

import com.nepdon.wire.tunnel.UdpSessionManager;
import com.nepdon.wire.tunnel.VpnEngine;
import com.nepdon.wire.tunnel.VpnEngineState;
import com.nepdon.wire.dev.DevLoggerImpl;
import com.nepdon.wire.filter.DefaultFilterEngine;
import com.nepdon.wire.filter.FilterEngine;
import com.nepdon.wire.filter.FilterKind;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin {@link VpnService} shell. Handles lifecycle, the foreground notification,
 * the dev-mode broadcast receiver, and action dispatch. All tunnel/packet work
 * lives in the {@code core} package.
 *
 * <p><b>State contract.</b> The service is the UI's single source of truth:
 * {@link #snapshot()} returns the live engine state synchronously (same
 * process), every applied action broadcasts {@code ACTION_STATE}, and taps
 * arriving during a tunnel restart are queued and replayed when the engine
 * becomes runnable again — the old code silently dropped them.</p>
 */
public class PacketVpnService extends VpnService {

    private static final String CH_ID = "vpn_svc";
    /**
     * Keep the NEPDON V1 engine as the active tunnel. Its packet pipeline owns
     * the ESP decoder; the standalone VIP loop forwards UDP but cannot supply
     * the entity stream required by boxes and lines.
     */
    private static final boolean VIP_HYBRID = false;
    private static final int MAX_PENDING_TOGGLES = 4;

    /** Live service instance (same-process synchronous state reads). */
    private static volatile PacketVpnService instance;

    /** Engine state right now; null when the service is dead. */
    public static VpnEngineState snapshot() {
        PacketVpnService s = instance;
        return s == null ? null : s.engine.snapshotState();
    }

    /** The live ESP engine; null when the service is dead. */
    public static com.nepdon.wire.esp.EspEngine espEngine() {
        PacketVpnService s = instance;
        return s == null ? null : s.engine.esp();
    }

    /** The live filter engine, for the tune panel; null while the tunnel is down. */
    public static FilterEngine filterEngineOrNull() {
        PacketVpnService s = instance;
        return s == null ? null : s.filterEngine;
    }

    /**
     * Applies a filter tap synchronously, skipping the AMS round trip.
     *
     * <p>{@link FloatingOverlayService} and this service share a process — the
     * manifest gives neither an {@code android:process}, which is also why the
     * static accessors above work at all. So routing a tap through
     * {@code startForegroundService} sent a binder call out to system_server
     * and waited for AMS to post {@code onStartCommand} back onto <b>this same
     * main looper</b>, behind whatever frame the ESP overlay was drawing. That
     * bought nothing: typically a few milliseconds, with a much worse tail
     * under AMS lock contention.</p>
     *
     * <p>Calling this from the touch handler runs the toggle <i>inside the
     * touch dispatch</i>, so {@code FreezeFilter.active} is already true by the
     * time the finger lifts.</p>
     *
     * <p>It deliberately routes through {@link #enqueueOrApplyFilterToggle}
     * rather than straight to {@code filterEngine.toggle}, so the pending-tap
     * queue, the engine-state guard and the {@code broadcastState()} repaint
     * contract all behave exactly as they did via the intent path. The only
     * thing removed is the transport.</p>
     *
     * @return true when handled in-process; false when the service is not
     *         running, in which case the caller must fall back to the intent
     *         (which can also start it).
     */
    public static boolean applyFilterTap(FilterKind kind) {
        if (VIP_HYBRID) return false;
        PacketVpnService s = instance;
        if (s == null || kind == null) return false;
        s.enqueueOrApplyFilterToggle(kind);
        s.engine.broadcastState();
        return true;
    }

    /**
     * True when a filter tap would actually do something.
     *
     * <p>Lets the caller avoid telling the user a tap landed when it did not.
     * {@code FloatingOverlayService.click} plays the toggle beep <i>before</i>
     * dispatching, so after {@code onRevoke()} — another VPN app takes the
     * slot — every press beeped "accepted" and was then discarded by
     * {@link #engineStopped()}, with the floating buttons looking unchanged.
     * That is indistinguishable from "freeze just didn't work that time".</p>
     */
    public static boolean engineAcceptsTaps() {
        if (VIP_HYBRID) return true;
        PacketVpnService s = instance;
        return s != null && !s.engineStopped();
    }

    private ExecutorService threadPool;
    private DevLoggerImpl devLogger;
    private UdpSessionManager sessions;
    private FilterEngine filterEngine;
    private VpnEngine engine;
    private BroadcastReceiver devReceiver;

    /** Filter taps queued while the tunnel was restarting. */
    private final ArrayDeque<PendingToggle> pendingToggles = new ArrayDeque<>();
    /** Guards {@link #drainPendingToggles} against its own state broadcast. */
    private final java.util.concurrent.atomic.AtomicBoolean draining =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // volatile: written on the main thread in onStartCommand, read and cleared
    // in drainPendingToggles, which runs on a POOL thread whenever the drain is
    // driven by VpnEngine's 1500 ms stats loop or the end of restartTunnel.
    // pendingToggles itself is guarded (synchronized + the draining CAS); these
    // two had no barrier at all.
    private volatile boolean pendingCaptureStart = false;
    private volatile int pendingCaptureHexLimit = 32;

    @Override
    public void onCreate() {
        super.onCreate();
        if (com.nepdon.wire.config.plugin.Features.all().length == 0) com.nepdon.wire.features.Registry.install(getApplicationContext());
        com.nepdon.wire.web.ControlServer.start(getApplicationContext());
        FileWatchLog.init(getApplicationContext());
        FileWatchLog.line("VPN", "service onCreate");
        threadPool = Executors.newCachedThreadPool();
        devLogger = new DevLoggerImpl(this);
        sessions = new UdpSessionManager(this, devLogger, threadPool);
        filterEngine = new DefaultFilterEngine(sessions, devLogger, this);
        engine = new VpnEngine(this, filterEngine, sessions, devLogger, this);
        engine.setStateListener(new VpnEngine.StateListener() {
            @Override
            public void onEngineStateChanged(VpnEngineState state) {
                if (state.isRunnable()) drainPendingToggles();
            }
        });
        setupForeground();
        setupDevReceiver();
        devLogger.setEnabled(false);
        engine.loadTeleReplayDelay();
        engine.loadLobbyRouteSetting(false);
        instance = this;
        startHeartbeat();
    }

    // ---- continuous-auth heartbeat (app->licensing-server HTTPS, off the packet
    // thread and the game socket). Re-arms the rolling decode profile while the
    // tunnel is up; a lapse fail-closes decode. Never restarts the tunnel. ----
    private java.util.concurrent.ScheduledExecutorService heartbeatPool;

    private void startHeartbeat() {
        // No server to beat in non-enforcing debug; only run when enabled.
        if (!com.nepdon.wire.auth.AuthConfig.HEARTBEAT_ENABLED
                || !com.nepdon.wire.auth.AuthConfig.ENFORCE) {
            return;
        }
        heartbeatPool = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduleBeat(0);
    }

    private void scheduleBeat(long delaySec) {
        final java.util.concurrent.ScheduledExecutorService pool = heartbeatPool;
        if (pool == null || pool.isShutdown()) return;
        try {
            pool.schedule(new Runnable() {
                @Override public void run() {
                    long nextSec = com.nepdon.wire.auth.AuthConfig.HEARTBEAT_FALLBACK_SEC;
                    try {
                        nextSec = com.nepdon.wire.auth.SessionManager.heartbeat(PacketVpnService.this);
                    } catch (com.nepdon.wire.auth.LicenseClient.AuthException e) {
                        if ("unauthorized".equals(e.code) || "invalid".equals(e.code)) {
                            // Terminal (revoked/deactivated/no session): stop re-arming;
                            // the rolling lease lapses and decode fail-closes.
                            com.nepdon.wire.diag.Diag.hit(
                                    com.nepdon.wire.diag.Slot.AUTH_HB_TERMINAL);
                            return; // no reschedule
                        }
                        // Benign miss (network/server): retry sooner than a full beat.
                        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AUTH_HB_FAIL);
                        nextSec = 30;
                    } catch (Throwable t) {
                        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.AUTH_HB_FAIL);
                        nextSec = 30;
                    }
                    scheduleBeat(nextSec);
                }
            }, delaySec, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // pool shutdown between the guard and schedule — nothing to do.
        }
    }

    private void stopHeartbeat() {
        java.util.concurrent.ScheduledExecutorService pool = heartbeatPool;
        heartbeatPool = null;
        if (pool != null) pool.shutdownNow();
    }

    /**
     * Promotes the service to the foreground.
     *
     * <p>Two bugs fixed here, 2026-09-15.</p>
     *
     * <p><b>It was skipped entirely below API 26</b> while minSdk is 21, so on
     * those devices this was a plain background service the system could kill
     * at will, mid-match. The notification channel is the only part that needs
     * API 26; {@code startForeground} itself has existed since API 5.</p>
     *
     * <p><b>It was the one unguarded risky call in this file.</b> Every other
     * is wrapped. On Android 12+ a foreground start from the background throws
     * {@code ForegroundServiceStartNotAllowedException}, and this service IS
     * started from the background -- every filter toggle routes through
     * {@code startForegroundService}. Today that survives only on the
     * SYSTEM_ALERT_WINDOW exemption; the day it does not, an uncaught throw in
     * {@code onCreate} takes the whole service down rather than degrading.</p>
     */
    private void setupForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "VPN Service", NotificationManager.IMPORTANCE_MIN);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Notification n = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CH_ID)
                            .setContentTitle("SunnyMod")
                            .setContentText("VPN đang hoạt động...")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .build()
                    : buildLegacyNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
        } catch (Throwable t) {
            // Degrade to a background service rather than killing onCreate.
            // Loudly: a service the system may reap at any moment is not the
            // same thing as a working one, and the difference is invisible
            // until a match drops.
            if (devLogger != null) {
                devLogger.logControl("MODE_VPN_FOREGROUND_FAILED",
                        "cls=" + t.getClass().getSimpleName() + " msg=" + t.getMessage());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildLegacyNotification() {
        return new Notification.Builder(this)
                .setContentTitle("SunnyMod")
                .setContentText("VPN đang hoạt động...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    /**
     * The tunnel was taken away -- by the user, or by another VPN app starting.
     *
     * <p><b>This did not exist anywhere in the repo.</b> Without it the engine
     * kept running against a dead descriptor, and worse, every subsequent
     * Activate hit {@code establish() == null -> stop()} with no log and no
     * distinguishable signal: the button appeared to do nothing, permanently,
     * with no way to tell that the OS had revoked consent rather than the app
     * having a bug. Tearing down here means the next Activate re-prompts for
     * VPN permission the way it should.</p>
     */
    @Override
    public void onRevoke() {
        if (devLogger != null) {
            devLogger.logControl("MODE_VPN_REVOKED",
                    "tunnel revoked by system or another VPN app");
        }
        com.nepdon.wire.diag.Diag.event(
                com.nepdon.wire.diag.Slot.TUNNEL_REVOKED,
                "tunnel revoked by system or another VPN app");
        try {
            if (engine != null) {
                engine.stop();
                // Repaint the UI: without this the toggle keeps showing ON
                // over a tunnel the OS has already taken away.
                engine.broadcastState();
            }
        } catch (Throwable ignored) {
        }
        stopHeartbeat();
        super.onRevoke();
    }

    private void setupDevReceiver() {
        devReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!VpnActions.ACTION_APPLY_FLOAT_APPEARANCE.equals(intent.getAction())) return;
                boolean enabled = intent.getBooleanExtra(VpnActions.EXTRA_DEV_MODE, devLogger.isEnabled());
                devLogger.setEnabled(enabled);
                engine.setTeleReplayDelayMs(intent.getIntExtra(
                        VpnActions.EXTRA_TELE_REPLAY_DELAY, engine.getTeleReplayDelayMs()));
                engine.setLobbyRouteEnabled(intent.getBooleanExtra(VpnActions.EXTRA_LOBBY_ROUTE, true), true);
            }
        };

        try {
            IntentFilter filter = new IntentFilter(VpnActions.ACTION_APPLY_FLOAT_APPEARANCE);
            registerReceiverCompat(devReceiver, filter);
        } catch (Exception e) {
            // No ACTION_APPLY_FLOAT_APPEARANCE means dev-mode and the tele
            // replay delay stop reaching the engine, with nothing logged.
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.UI_RECEIVER_REGISTER_FAILED);
        }
    }

    /**
     * Registers a control receiver so that <b>only this app</b> can reach it.
     *
     * <h3>The hole this closes</h3>
     *
     * <p>This used to pass {@code RECEIVER_NOT_EXPORTED} on API 33+ and then
     * fall through to a bare {@code registerReceiver} on everything older —
     * which is <b>implicitly exported</b>. {@code minSdk} is 21, so on the
     * whole of API 21–32 any installed app could broadcast
     * {@code APPLY_FLOAT_APPEARANCE} and land in the handler below, which
     * reads {@code EXTRA_LOBBY_ROUTE} straight into
     * {@code engine.setLobbyRouteEnabled}.</p>
     *
     * <p>That is the lobby-route fallback hard rule 4 exists because of: it
     * dropped players out of live matches. A third-party app being able to
     * flip it mid-match — and to switch dev mode on — is not a theoretical
     * hole.</p>
     *
     * <p>API 21–32 has no flag. {@code setPackage} on the SENDER is NOT the
     * defence — it only scopes this app's OUTBOUND broadcasts; it does nothing to
     * stop a third-party app sending INBOUND to an implicitly-exported receiver
     * (2026-09-18 audit, M1). The real defence on those APIs is a
     * {@code signature}-level {@code broadcastPermission}: only a same-signing-key
     * app (i.e. this one, which is auto-granted the permission) may deliver.</p>
     */
    private static final String PERM_INTERNAL_BROADCAST =
            "com.nepdon.wire.permission.INTERNAL_BROADCAST";

    private void registerReceiverCompat(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver == null || filter == null) return;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                java.lang.reflect.Method method = Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class,
                        IntentFilter.class,
                        Integer.TYPE);
                // Context.RECEIVER_NOT_EXPORTED
                method.invoke(this, receiver, filter, Integer.valueOf(4));
                return;
            } catch (Exception ignored) {
                com.nepdon.wire.diag.Diag.hit(
                        com.nepdon.wire.diag.Slot.SEC_RECEIVER_EXPORTED);
            }
        }
        try {
            // API 21-32: require the signature permission so only this app can send.
            registerReceiver(receiver, filter, PERM_INTERNAL_BROADCAST, null);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();

        if (VIP_HYBRID && isVipAction(action)) {
            Intent vip = new Intent(this, VipVpnService.class);
            vip.setAction(action);
            if (intent.hasExtra(VpnActions.EXTRA_PACKAGE)) {
                vip.putExtra(VpnActions.EXTRA_PACKAGE,
                        intent.getStringExtra(VpnActions.EXTRA_PACKAGE));
            } else if (VpnActions.ACTION_SET_APP.equals(action)) {
                Set<String> targets = Prefs.getTargetPackages(this);
                if (targets != null && !targets.isEmpty()) {
                    vip.putExtra(VpnActions.EXTRA_PACKAGE, targets.iterator().next());
                }
            }
            if (VpnActions.ACTION_STOP.equals(action)) {
                stopService(vip);
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vip);
                else startService(vip);
            } catch (Exception ignored) { }
            return START_STICKY;
        }

        if (VpnActions.ACTION_SET_APP.equals(action)) {
            Set<String> packages = Prefs.getTargetPackages(this);
            String single = intent.getStringExtra(VpnActions.EXTRA_PACKAGE);
            if (single == null) single = intent.getStringExtra(VpnActions.EXTRA_PACKAGE_ALT);
            if (single != null && !single.isEmpty()) {
                packages = new LinkedHashSet<>();
                packages.add(single);
                Prefs.setTargetPackages(this, packages);
            }
            engine.setTargetPackages(packages);
            engine.disableFilters();
            clearPendingToggles();
            engine.setActive(true);
            engine.setFilterEnabled(true);
            if (engine.isRun()) {
                engine.restartTunnel(true);
            }
        } else if (VpnActions.ACTION_STOP.equals(action)) {
            clearPendingToggles();
            stop();
            return START_NOT_STICKY;
        } else if (VpnActions.ACTION_ACTIVATE.equals(action)) {
            engine.setFilterEnabled(true);
            if (!engine.isRun()) {
                engine.setActive(true);
            } else if (!engine.isActive()) {
                engine.toggle(true);
            } else {
                engine.setActive(true);
            }
        } else if (VpnActions.ACTION_DEACTIVATE.equals(action)) {
            engine.disableFilters();
            if (engine.isActive()) engine.toggle(false);
            return START_NOT_STICKY;
        } else {
            FilterKind kind = FilterKind.fromAction(action);
            if (kind != null) {
                enqueueOrApplyFilterToggle(kind);
            } else if (VpnActions.ACTION_CAPTURE_START.equals(action)) {
                // Default the raw-dump hex limit to the FULL payload, not 200 bytes.
                //
                // 200 silently destroyed the most valuable packets in every
                // capture: 3 064 of 318 187 packets clipped (2.88% of all
                // bytes), and the loss was heavily BIASED - 100% of the 9-,
                // 10-, 11- and 25-record entity chains were cut, i.e. every
                // crowded-lobby update, taking ~17 621 entity records with
                // them. Worse, the summary reported "TRUNCATION: 0 packets
                // clipped" because it checked the in-memory copy rather than
                // what was written, so every Python analysis run off a raw
                // capture got a green light on a corrupt corpus.
                int hexLimit = intent.getIntExtra(
                        VpnActions.EXTRA_CAPTURE_HEX_LIMIT,
                        com.nepdon.wire.dev.capture.PacketCapture.MAX_PAYLOAD_BYTES);
                int mode = com.nepdon.wire.dev.capture.PacketCapture.modeFromName(
                        intent.getStringExtra(VpnActions.EXTRA_CAPTURE_MODE),
                        com.nepdon.wire.dev.capture.PacketCapture.modeFromName(
                                Prefs.get(this).getString(Prefs.KEY_CAPTURE_MODE, "full"),
                                com.nepdon.wire.dev.capture.PacketCapture.MODE_FULL));
                int retro = Prefs.get(this).getInt(Prefs.KEY_CAPTURE_RETRO_SEC, 0);
                if (engine.isRun()) {
                    engine.startCapture(hexLimit, mode, retro);
                } else if (!engineStopped()) {
                    pendingCaptureStart = true;
                    pendingCaptureHexLimit = hexLimit;
                }
            } else if (VpnActions.ACTION_CAPTURE_STOP.equals(action)
                    || VpnActions.ACTION_CAPTURE_DUMP.equals(action)) {
                pendingCaptureStart = false;
                engine.stopCapture();
            } else if (VpnActions.ACTION_TELEMETRY_DUMP.equals(action)) {
                engine.dumpTelemetry();
            }
        }

        if (!engine.isRun() && (VpnActions.ACTION_SET_APP.equals(action) || VpnActions.ACTION_ACTIVATE.equals(action))) {
            engine.start();
        }
        engine.broadcastState();
        return START_STICKY;
    }

    private static boolean isVipAction(String action) {
        return VpnActions.ACTION_SET_APP.equals(action)
                || VpnActions.ACTION_ACTIVATE.equals(action)
                || VpnActions.ACTION_DEACTIVATE.equals(action)
                || VpnActions.ACTION_FREEZE.equals(action)
                || VpnActions.ACTION_GHOST.equals(action)
                || VpnActions.ACTION_JITTER.equals(action)
                || VpnActions.ACTION_STOP.equals(action);
    }

    private boolean engineStopped() {
        return !engine.isRun() && !engine.isFilterEnabled() && !engine.isActive();
    }

    /**
     * A queued tap older than this is discarded rather than applied.
     *
     * <p>A tap that arrives during a tunnel restart is queued, and the
     * {@code broadcastState()} right after it repaints the button OFF, because
     * the toggle has not landed yet. The user reads that as "it turned itself
     * off" and presses again — and that second press, which does land, drains
     * the first one in the same call chain and toggles straight back off. Two
     * presses, net zero. Discarding a stale tap breaks that loop: by the time
     * the engine is runnable again the user's intent is whatever they pressed
     * most recently, not what they pressed before the restart.</p>
     */
    private static final long PENDING_TOGGLE_TTL_MS = 3000L;

    /** Applies a filter tap now, or queues it while the tunnel is restarting. */
    private void enqueueOrApplyFilterToggle(FilterKind kind) {
        if (engine.isRun() && engine.isFilterEnabled()) {
            applyFilterToggle(kind);
            return;
        }
        if (engineStopped()) {
            // The engine is off, so the UI does show the truth -- but the tap
            // was still silently eaten, and FloatingOverlayService.click has
            // ALREADY played the toggle beep, so the user was told it landed.
            // Reachable after onRevoke(): another VPN app takes the slot, the
            // engine stops, and the floating buttons look exactly the same.
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.UI_TAP_DISCARDED_ENGINE_OFF);
            return;
        }
        synchronized (pendingToggles) {
            // COALESCE BY PARITY. A toggle queue is parity-sensitive: the TTL
            // above breaks the restart loop only when the second press lands
            // AFTER the queue drains. Two presses inside the same restart
            // window both queue, both apply in order, and cancel -- which is
            // the very "two presses, net zero" the TTL was added to stop.
            //
            // JITTER is exempt: teleport.press() is a three-state machine
            // (arm -> go -> done), so its presses COUNT and must not cancel.
            if (kind != FilterKind.JITTER) {
                for (java.util.Iterator<PendingToggle> it = pendingToggles.iterator();
                        it.hasNext(); ) {
                    if (it.next().kind == kind) {
                        it.remove();
                        com.nepdon.wire.diag.Diag.hit(
                                com.nepdon.wire.diag.Slot.UI_TAP_COALESCED);
                        return;   // this press undoes the queued one
                    }
                }
            }
            while (pendingToggles.size() >= MAX_PENDING_TOGGLES) {
                pendingToggles.pollFirst();
            }
            pendingToggles.addLast(new PendingToggle(kind, System.currentTimeMillis()));
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.UI_TAP_QUEUED);
        }
    }

    private void applyFilterToggle(FilterKind kind) {
        filterEngine.toggle(kind);
        engine.noteFilterToggled();
        engine.scheduleLobbyRouteFallbackIfNoUdp(triggerFor(kind));
    }

    /**
     * Replays taps that arrived during a restart, once the engine is runnable.
     *
     * <p>Re-entrant by construction: {@link #applyFilterToggle} broadcasts the
     * new state, the state listener calls back into here, and without the
     * guard that inner call drains and applies the remaining queue in the
     * middle of the outer one — flipping a filter the user had just turned on.
     * The guard makes the outer drain the only one that runs.</p>
     */
    private void drainPendingToggles() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            boolean appliedAny = false;
            long now = System.currentTimeMillis();
            while (true) {
                PendingToggle t;
                synchronized (pendingToggles) {
                    t = pendingToggles.pollFirst();
                }
                if (t == null) break;
                if (now - t.atMs > PENDING_TOGGLE_TTL_MS) {
                    // Diag, not just devLogger: DevLoggerImpl is a no-op in
                    // production (devMode = false), so a discarded tap left
                    // literally no trace anywhere.
                    com.nepdon.wire.diag.Diag.hit(
                            com.nepdon.wire.diag.Slot.UI_TAP_STALE_DROPPED);
                    devLogger.logControl("DROP_STALE_TOGGLE",
                            "kind=" + t.kind + " age_ms=" + (now - t.atMs));
                    continue;
                }
                applyFilterToggle(t.kind);
                appliedAny = true;
            }
            if (pendingCaptureStart) {
                pendingCaptureStart = false;
                engine.startCapture(pendingCaptureHexLimit);
                appliedAny = true;
            }
            if (appliedAny) engine.broadcastState();
        } finally {
            draining.set(false);
        }
    }

    private void clearPendingToggles() {
        synchronized (pendingToggles) {
            pendingToggles.clear();
        }
        pendingCaptureStart = false;
    }

    /** A filter tap that could not be applied yet, with the time it arrived. */
    private static final class PendingToggle {
        final FilterKind kind;
        final long atMs;

        PendingToggle(FilterKind kind, long atMs) {
            this.kind = kind;
            this.atMs = atMs;
        }
    }

    private static String triggerFor(FilterKind kind) {
        switch (kind) {
            case FREEZE: return "freeze";
            case GHOST:  return "ghost";
            case JITTER: return "tele";
            case AIM:    return "aim";
            default: return "unknown";
        }
    }

    /**
     * MUST defer to super for the framework's own bind, or onRevoke() is dead.
     *
     * <p>{@code VpnService.onBind} is not a stub: for the
     * {@code android.net.VpnService} action AOSP returns an internal callback
     * binder, and the platform transacts on it to deliver the revoke. This
     * used to return null unconditionally, so the system's reference stayed
     * null, the transact was swallowed, and {@link #onRevoke()} could never
     * fire -- meaning the tunnel was never stopped when another VPN app took
     * the slot. The TUN reader kept looping on a dead descriptor while the
     * state broadcast still said engineRun=true, which is exactly the
     * "establish() returns null forever" symptom onRevoke was written to
     * prevent.</p>
     */
    @Override
    public android.os.IBinder onBind(Intent intent) {
        if (intent != null && SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return null;
    }

    @Override
    public void onDestroy() {
        com.nepdon.wire.web.ControlServer.stop();
        instance = null;
        try {
            if (devReceiver != null) unregisterReceiver(devReceiver);
        } catch (Exception e) {
            // ignore
        }
        stop();
        super.onDestroy();
    }

    private void stop() {
        stopHeartbeat();
        devLogger.setEnabled(false);
        engine.stop();
    }
}
