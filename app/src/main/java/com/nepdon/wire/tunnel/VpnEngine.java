package com.nepdon.wire.tunnel;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.config.VpnActions;
import com.nepdon.wire.dev.DevLogger;
import com.nepdon.wire.dev.capture.FreezeDiffAnalyzer;
import com.nepdon.wire.dev.capture.PacketCapture;
import com.nepdon.wire.filter.FilterEngine;
import com.nepdon.wire.filter.FilterKind;
import com.nepdon.wire.protocol.GamePacketTaxonomy;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tunnel lifecycle + run loop. Owns the tun descriptor, the active/run flags,
 * the byte/loss counters, the lobby-route fallback, and the stats broadcast.
 * The per-packet work is delegated to {@link PacketLoop}.
 */
public class VpnEngine implements Runnable {

    private static final int MTU = 1280;
    private static final String IPV6 = "fd64:2534:1111::2";
    private static final int TELE_REPLAY_DELAY_DEFAULT_MS = 1;
    private static final int TELE_REPLAY_DELAY_MIN_MS = 1;
    private static final int TELE_REPLAY_DELAY_MAX_MS = 50;

    // ---- shared state (package-private so core classes can read/write) ----
    volatile boolean isRun = false;
    volatile boolean isActive = false;
    volatile boolean isFilterEnabled = false;
    volatile boolean stopped = false;   // set by stop(); restart must never respawn after this
    volatile int tunIP = 0xC0A8010A;
    volatile ParcelFileDescriptor tun;
    volatile Thread vpnT;
    volatile boolean lobbyRouteEnabled = true;
    volatile boolean lobbyRouteFallbackFull = false;
    /** True when the last establish() installed a default route under an app scope. */
    volatile boolean routesAppScoped = false;
    volatile long rtt = 40L;
    volatile int teleReplayDelayMs = TELE_REPLAY_DELAY_DEFAULT_MS;

    final AtomicLong bIn = new AtomicLong();
    final AtomicLong bOut = new AtomicLong();
    final AtomicLong pSent = new AtomicLong();
    final AtomicLong pDrop = new AtomicLong();
    final AtomicLong outUdpSeen = new AtomicLong();

    private Set<String> targetPackages = new LinkedHashSet<>();

    /** Userspace TCP relay for login/lobby/voice traffic routed into the VPN. */
    private TcpProxy tcpProxy;
    /** Tun-side writer for the TCP relay (owned by the active run loop). */
    private volatile FileOutputStream tunWriter;

    private final VpnService vpnService;
    private final FilterEngine filterEngine;
    private final UdpSessionManager sessions;
    private final DevLogger logger;
    private final Context context;
    private final ExecutorService threadPool;
    private final PacketLoop packetLoop;
    private final TunAddressPool tunAddressPool;
    private final PacketCapture capture;
    /** Always-on event telemetry (match/filter/health events, no payloads). */
    private final com.nepdon.wire.dev.capture.TelemetryCollector telemetry =
            new com.nepdon.wire.dev.capture.TelemetryCollector();
    /** Live ESP state engine (mask recovery, own/enemy state decoding). */
    private final com.nepdon.wire.esp.EspEngine esp =
            new com.nepdon.wire.esp.EspEngine();
    /**
     * Exact protocol parser: solves the session's header encoding from traffic
     * and reads {@code Cmd}, {@code Flags} and the CRC off every match packet.
     *
     * <p><b>It runs behind the existing heuristics, never in place of them.</b>
     * {@link com.nepdon.wire.protocol.PacketPipeline#observeAndParse} answers
     * null until the solver converges (a few hundred outbound packets) and
     * again for a window after every match/room transition, because the key —
     * and with it the mask and the field permutation — rotates per session. So
     * every consumer keeps the shape-based path that always works and treats
     * the exact command as a bonus.</p>
     *
     * <p>The ESP feed is deliberately <b>not</b> routed through this yet.
     * {@code EspBodyDecoders} is written and ready, but registering it while
     * {@link PacketLoop} and {@link UdpSessionManager} still run their own
     * shape-gated feeds would hand {@code EspEngine} every position twice —
     * duplicate samples at a zero time delta, straight into dead reckoning.
     * Switching over means deleting those feeds in the same change and
     * re-validating in-game; until then the calibrated path is left exactly as
     * it is.</p>
     */
    /**
     * Recovers the UDP cipher key from the lobby TCP downlink, where the server
     * sends it in the clear. This is the only way to obtain it WITHOUT ROOT --
     * reading it out of game memory needs a rooted device, and the phone is not
     * rooted. Fed from TcpProxy's server-&gt;client read loop.
     */
    // AIM BIAS AND ITS A/B HARNESS ARE GONE.
    //
    // The hypothesis - that rotating the aim vector we report in Cmd 2001
    // moves where the server resolves a hit - was tested and DISPROVEN
    // (control 19 HP n=81 vs biased 17 HP n=46; the server resolves the body
    // part itself). See the tombstone in PacketLoop for the full result.
    //
    // The experiment harness was still running observe() on EVERY parsed
    // packet in BOTH directions to answer a question that is settled. The
    // docs are the record; a live A/B rig is not.

    private final com.nepdon.wire.protocol.LobbyKeySniffer lobbyKey =
            new com.nepdon.wire.protocol.LobbyKeySniffer();

    private final com.nepdon.wire.protocol.PacketPipeline pipeline =
            new com.nepdon.wire.protocol.PacketPipeline();

    public VpnEngine(VpnService vpnService, FilterEngine filterEngine, UdpSessionManager sessions,
                     DevLogger logger, Context context) {
        this.vpnService = vpnService;
        this.filterEngine = filterEngine;
        this.sessions = sessions;
        this.logger = logger;
        this.context = context;
        this.threadPool = sessions.getThreadPool();
        this.tunAddressPool = new TunAddressPool(context);
        this.capture = new PacketCapture(context);
        // Route the dev logger's events into the one capture pipeline instead
        // of its own parallel buffer. Without this the logger still counts
        // correctly but its events never reach the capture file.
        if (logger instanceof com.nepdon.wire.dev.DevLoggerImpl) {
            ((com.nepdon.wire.dev.DevLoggerImpl) logger).setCaptureSink(capture);
        }
        this.tcpProxy = new TcpProxy(vpnService, this, threadPool);
        this.packetLoop = new PacketLoop(this, filterEngine, sessions, logger);
        this.targetPackages = Prefs.getTargetPackages(context);
        sessions.setEngine(this);
        sessions.setFilterEngine(filterEngine);
    }

    // ---- telemetry ----

    /** The always-on telemetry collector (hot-path-safe event recording). */
    public com.nepdon.wire.dev.capture.TelemetryCollector telemetry() {
        return telemetry;
    }

    /** The live ESP engine (fed from the packet loop + recv threads). */
    public com.nepdon.wire.esp.EspEngine esp() {
        return esp;
    }

    /** The exact protocol parser (fed from the packet loop + recv threads). */
    public com.nepdon.wire.protocol.PacketPipeline pipeline() {
        return pipeline;
    }

    /** Lobby-TCP key recovery (see {@link com.nepdon.wire.protocol.LobbyKeySniffer}). */
    public com.nepdon.wire.protocol.LobbyKeySniffer lobbyKey() {
        return lobbyKey;
    }

    /** Starts the telemetry poller (called from start()). */
    private void startTelemetry() {
        telemetry.start(new com.nepdon.wire.dev.capture.TelemetryCollector.PollerSource() {
            @Override
            public boolean isMatchLive() {
                return GamePacketTaxonomy.isMatchStreamLive(System.currentTimeMillis());
            }

            @Override
            public boolean isFreezeActive() {
                return filterEngine.isKindActive(FilterKind.FREEZE);
            }

            @Override
            public long matchEndpointPacked() {
                int ip = GamePacketTaxonomy.matchServerIp();
                int port = GamePacketTaxonomy.matchServerPort();
                if (ip == 0) return 0L;
                return ((long) ip << 32) | (port & 0xffffffffL);
            }

            @Override
            public int matchServerIp() {
                return GamePacketTaxonomy.matchServerIp();
            }

            @Override
            public int matchServerPort() {
                return GamePacketTaxonomy.matchServerPort();
            }

            @Override
            public long droppedPackets() {
                return pDrop.get();
            }

            @Override
            public long sentPackets() {
                return pSent.get();
            }

            @Override
            public int voiceFramesAndReset() {
                return (int) GamePacketTaxonomy.drainVoiceFrames();
            }
        });
    }

    /** Dumps the telemetry to dev_logs and returns the file (background). */
    public void dumpTelemetry() {
        executeSafe(new Runnable() {
            @Override
            public void run() {
                File dir = context.getExternalFilesDir("dev_logs");
                if (dir == null) dir = new File(context.getFilesDir(), "dev_logs");
                File f = telemetry.dump(dir, "manual");
                if (f != null) {
                    Prefs.get(context).edit().putString(Prefs.KEY_TELEMETRY_LAST_FILE, f.getAbsolutePath()).commit();
                }
                logger.logControl("TELEMETRY_DUMP",
                        "file=" + (f == null ? "null" : f.getName()));
            }
        });
    }

    // ---- state accessors (used by PacketVpnService) ----
    public boolean isRun() {
        return isRun;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isFilterEnabled() {
        return isFilterEnabled;
    }

    // ---- engine state snapshot + broadcast (UI single source of truth) ----

    /** Listener notified on every state change (wired by PacketVpnService). */
    public interface StateListener {
        void onEngineStateChanged(VpnEngineState state);
    }

    private volatile StateListener stateListener;

    public void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    /** Immutable snapshot of every user-visible flag. */
    public VpnEngineState snapshotState() {
        return new VpnEngineState(
                isRun,
                isActive,
                isFilterEnabled,
                filterEngine.isKindActive(FilterKind.FREEZE),
                filterEngine.isKindActive(FilterKind.GHOST),
                filterEngine.isKindActive(FilterKind.JITTER),
                capture.isRunning(),
                GamePacketTaxonomy.movementLearned());
    }

    /** Broadcasts the full state to the UI (package-scoped, not exported). */
    public void broadcastState() {
        VpnEngineState s = snapshotState();
        Intent i = new Intent(VpnActions.ACTION_STATE);
        i.setPackage(context.getPackageName());
        i.putExtra("engine_run", s.engineRun);
        i.putExtra("tunnel_active", s.tunnelActive);
        i.putExtra(VpnActions.EXTRA_FILTER_ENABLED, s.filterEnabled);
        i.putExtra("freeze_active", s.freezeActive);
        i.putExtra("ghost_active", s.ghostActive);
        i.putExtra("jitter_active", s.jitterActive);
        i.putExtra("capture_active", s.captureActive);
        i.putExtra("match_ready", s.matchReady);
        i.putExtra(VpnActions.EXTRA_MODE, s.tunnelActive ? "ACTIVE" : "STANDBY");
        // setPackage makes this an EXPLICIT broadcast: only this app can
        // receive it. That is the sender half of closing the exported-
        // receiver hole -- on API 21-32 there is no RECEIVER_NOT_EXPORTED
        // flag, so this is the only defence there. See
        // PacketVpnService.registerReceiverCompat.
        i.setPackage(context.getPackageName());
        context.sendBroadcast(i);
        StateListener l = stateListener;
        if (l != null) {
            l.onEngineStateChanged(s);
        }
    }

    private void notifyStateChanged() {
        broadcastState();
    }

    public void setFilterEnabled(boolean enabled) {
        isFilterEnabled = enabled;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public void setTargetPackages(Set<String> packages) {
        targetPackages = packages == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(packages);
    }

    public int getTeleReplayDelayMs() {
        return teleReplayDelayMs;
    }

    // ---- settings ----
    public void loadTeleReplayDelay() {
        int saved = Prefs.get(context).getInt(Prefs.KEY_TELE_REPLAY_DELAY, TELE_REPLAY_DELAY_DEFAULT_MS);
        teleReplayDelayMs = clampTeleReplayDelayMs(saved);
    }

    public void setTeleReplayDelayMs(int value) {
        teleReplayDelayMs = clampTeleReplayDelayMs(value);
    }

    public void loadLobbyRouteSetting(boolean rebuildIfChanged) {
        setLobbyRouteEnabled(true, rebuildIfChanged);
    }

    public void setLobbyRouteEnabled(boolean enabled, boolean rebuildIfChanged) {
        boolean changed = lobbyRouteEnabled != enabled;
        lobbyRouteEnabled = enabled;
        if (changed) {
            lobbyRouteFallbackFull = false;
            logger.logControl("MODE_VPN_ROUTE_CHANGED",
                    "route=" + activeRouteModeName() + " active=" + isActive + " run=" + isRun);
            if (rebuildIfChanged && isRun) {
                restartTunnel(isActive);
            }
        }
    }

    private static int clampTeleReplayDelayMs(int value) {
        if (value < TELE_REPLAY_DELAY_MIN_MS) return TELE_REPLAY_DELAY_MIN_MS;
        if (value > TELE_REPLAY_DELAY_MAX_MS) return TELE_REPLAY_DELAY_MAX_MS;
        return value;
    }

    // ---- lifecycle ----
    public void start() {
        // RE-ARM. stop() latches `stopped` true forever, and run() opens with
        // `if (stopped) return;` -- so after any stop, start() set isRun=true
        // and spawned a thread that immediately returned, leaving the engine
        // wedged "running" with no tunnel. Every later Activate then saw
        // engineRun and toasted "already running". start() is the only
        // legitimate re-arm point.
        stopped = false;
        isRun = true;
        vpnT = new Thread(this, "VPN");
        vpnT.start();
        startStats();
        startTelemetry();
        telemetry.event(com.nepdon.wire.dev.capture.TelemetryCollector.EV_TUNNEL_RESTART);
        // Capture is ALWAYS-ON: every engine start opens a fresh dump, and the
        // dumper streams to disk in real time (CaptureWriter flushes every
        // 500 ms, fsyncs every 5 s, rotates at 32 MB) — there is no user button
        // to press and nothing waits for a stop. Guarded by isRunning() so a
        // redundant start (e.g. a restartTunnel) cannot double-open the writer.
        if (!capture.isRunning()) {
            startCapture(com.nepdon.wire.dev.capture.PacketCapture.MAX_PAYLOAD_BYTES,
                    com.nepdon.wire.dev.capture.PacketCapture.MODE_FULL, 0);
        }
        notifyStateChanged();
    }

    public void toggle(final boolean on) {
        if (on == isActive) return;
        restartTunnel(on);
    }

    /** Runs a task on the engine pool; silently rejects after shutdown. */
    private void executeSafe(Runnable task) {
        if (stopped) return;
        try {
            threadPool.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // pool shut down between the stopped check and the execute
        }
    }

    public void restartTunnel(final boolean nextActive) {
        executeSafe(new Runnable() {
            @Override
            public void run() {
                if (stopped) return;   // service is gone; never respawn a tunnel
                isRun = false;
                // RST the relayed TCP connections while the tun writer is
                // still live — AFTER tun.close() the writes would hit a dead
                // fd and the RSTs would silently never reach the game.
                try {
                    tcpProxy.closeAll();
                } catch (Exception e) {
                    // ignore
                }
                try {
                    if (tun != null) tun.close();
                } catch (Exception e) {
                    // ignore
                }
                tun = null;
                // JOIN MUST BE CHECKED, NOT ASSUMED.
                //
                // join(1000) returns after a second whether or not the thread
                // died. The retiring loop's finally-block ends with
                // `tunWriter = null` -- so a timed-out join let the OLD thread
                // null the NEW session's writer moments after the new loop set
                // it. TcpProxy writes only through writeToTun, which then
                // silently returns: lobby and login traffic relayed OUT with
                // nothing ever delivered back, forever, with no retransmit
                // queue and no error anywhere.
                boolean joined = true;
                try {
                    if (vpnT != null) {
                        vpnT.join(1000);
                        joined = !vpnT.isAlive();
                    }
                } catch (Exception e) {
                    joined = false;
                }
                if (!joined) {
                    // Give it one more second, then proceed regardless -- but
                    // say so, because the handoff below is now racing.
                    try {
                        if (vpnT != null) {
                            vpnT.join(1000);
                            joined = !vpnT.isAlive();
                        }
                    } catch (Exception e) {
                        joined = false;
                    }
                    if (!joined) {
                        com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.TUNNEL_JOIN_TIMEOUT, "old tun thread alive after 2s");
                        logger.logControl("MODE_VPN_RESTART_JOIN_TIMEOUT",
                                "old tun thread still alive after 2s -- "
                                        + "writer handoff may race");
                    }
                }
                if (stopped) return;   // stop() ran while we were joining
                sessions.closeAll();
                // Reset the protocol learners AFTER the old loop is dead, so
                // old-tunnel observations can't repopulate the new session's
                // state (the encoding rotates per session anyway). Also
                // invalidate filter-held buffers so pre-restart encodings
                // can't leak into the new session.
                GamePacketTaxonomy.resetLearned();
                // The header encoding is per-session too: a solved mask and
                // permutation from the old tunnel would parse the new one's
                // packets onto the wrong bytes.
                pipeline.reset();
                pipeline.markCurrent();
                lobbyKey.reset();
                com.nepdon.wire.protocol.PacketIntegrity.resetCounters();
                filterEngine.onTunnelRestart();
                isActive = nextActive;
                if (stopped) return;   // stop() raced us between checks
                isRun = true;
                vpnT = new Thread(VpnEngine.this, nextActive ? "VPN-A" : "VPN-S");
                vpnT.start();
                // The stats loop keys off isRun and may have died during the
                // restart window; it is idempotent, so just start it again.
                startStats();
                notifyStateChanged();
            }
        });
    }

    @Override
    public void run() {
        if (stopped) return;   // stop() raced the thread spawn
        // Fresh session: drop any learned obfuscation channels from the
        // previous tunnel (the game rotates its encoding per session). The
        // restart path also resets after the old loop joins — this covers
        // the first start and any path that reaches run() without a restart.
        GamePacketTaxonomy.resetLearned();
        pipeline.reset();
        pipeline.markCurrent();
        lobbyKey.reset();
        try {
            TunAddressPool.TunAddress tunAddress = tunAddressPool.nextTunAddress();
            String ip = tunAddress.ip;
            String ipsix = IPV6;
            tunIP = tunAddress.ipInt;

            VpnService.Builder builder = vpnService.new Builder()
                    .setSession("SunnyMod Tunnel")
                    .addAddress(ip, 24)
                    .addAddress(ipsix, 64)
                    .setMtu(MTU)
                    .setBlocking(true);

            // Scope the tunnel to the target apps.
            //
            // COVER EVERY INSTALLED FREE FIRE BUILD, not just the one that was
            // picked. Free Fire ships as freefiremax, freefireth and regional
            // packages such as garena.game.kgvn, and addAllowedApplication
            // carries ONLY what is listed -- so playing a build that is not in
            // the set produces total, silent failure: no outbound reaches the
            // tun, the taxonomy never learns, every packet is classified
            // foreign, and ESP, radar, ghost and freeze all die together while
            // the app still reports the engine running.
            //
            // Measured, from two sessions minutes apart: the targeted build
            // gave out=972 in=870 foreign=0 decrypted=1334, while a session on
            // an untargeted build gave out=1 in=6247 foreign=6248 decrypted=0.
            // That failure has now been diagnosed three separate times, so the
            // rule is: if the user picked a Free Fire build, cover them all.
            java.util.LinkedHashSet<String> allow =
                    new java.util.LinkedHashSet<>(targetPackages);
            boolean targetsGame = false;
            for (String pkg : targetPackages) {
                if (com.nepdon.wire.config.GameTargets.isKnownGame(pkg)) {
                    targetsGame = true;
                    break;
                }
            }
            if (targetsGame) {
                allow.addAll(com.nepdon.wire.config.GameTargets.installedGames(context));
            }
            // SCOPE AND ROUTES MUST BE GATED TOGETHER.
            //
            // Routes were gated on isActive and the allow-list was not, so
            // STANDBY built a tunnel that named the game as its only member and
            // then installed NO ROUTES AT ALL -- a tunnel scoped to the one app
            // that must never lose connectivity, with nowhere for its packets
            // to go, held open while the thread sleeps in a 200 ms loop. Whether
            // that black-holes the game is version-dependent, which is the worst
            // kind of answer; either way it is never what standby wanted.
            //
            // Standby's job is to hold the VPN slot, not to capture anything.
            // So when inactive: no scope, no routes, nothing captured.
            int scopedApps = 0;
            if (isActive) {
                for (String pkg : allow) {
                    if (pkg != null && !pkg.isEmpty()) {
                        try {
                            builder.addAllowedApplication(pkg);
                            scopedApps++;
                        } catch (Exception e) {
                            // Not installed for this user; the others still apply.
                        }
                    }
                }
                addActiveRoutes(builder, scopedApps > 0);
                // The requested set is not the applied set: a package listed in
                // prefs but uninstalled silently drops out here, and the old log
                // printed the request, so a tunnel scoped to nothing looked
                // identical to a healthy one.
                if (scopedApps == 0) {
                    com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.TUNNEL_SCOPE_EMPTY, "requested=" + allow + " applied=0");
                    logger.logControl("MODE_VPN_SCOPE_EMPTY",
                            "requested=" + allow + " applied=0 -- tunnel is UNSCOPED");
                }
            }

            tun = builder.establish();
            if (tun == null) {
                com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.TUNNEL_ESTABLISH_FAILED,
                        "establish() returned null -- VPN consent revoked?");
                stop();
                return;
            }
            logger.logControl("MODE_VPN_TUN_READY",
                    "active=" + isActive
                            + " route=" + (isActive ? activeRouteModeName() : "none_standby")
                            + " pkg=" + allow
                            + " applied=" + scopedApps
                            + " tun_ip=" + ip);

            if (isActive) {
                try {
                    FileInputStream in = new FileInputStream(tun.getFileDescriptor());
                    FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
                    tunWriter = out;
                    packetLoop.loop(in, out);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // RST the relayed TCP connections while the tun writer is
                    // still live, so the game's stack fails fast and
                    // reconnects instead of retransmitting into silence.
                    try {
                        tcpProxy.closeAll();
                    } catch (Exception e) {
                        // ignore
                    }
                    tunWriter = null;
                }
            } else {
                while (isRun && !isActive) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Only tear the whole engine down when this thread's session is
            // still the live one. During a restart handoff the new thread has
            // already flipped isRun=true; the old thread must NOT stop() it.
            if (isRun && vpnT == Thread.currentThread() && !stopped) stop();
        }
    }

    /**
     * Installs the tunnel's routes.
     *
     * <p><b>When the tunnel is app-scoped, route everything.</b> An IP route
     * list and an {@code addAllowedApplication} list are two independent
     * filters and the tunnel carries only their INTERSECTION. Once the
     * allow-list names the game, the route list can no longer help — it can
     * only subtract — so any address the game uses but the routes omit is
     * silently invisible, and every feature dies together while the app still
     * reports the engine running.</p>
     *
     * <p><b>That is not hypothetical; it is what happened.</b> The old narrow
     * pair was justified by a survey of 182 708 outbound packets over 40
     * captures in which every single one went to {@code 148.222.x} or
     * {@code 148.153.x} — so {@code 148.0.0.0/8} looked total, and
     * {@code ff-route-fallback-kick} recorded "do not widen without a capture
     * showing game traffic outside those blocks". Capture
     * {@code capture_raw_20260913_210003} is that capture: the lobby announced
     * {@code MATCH_ASSIGNED srv=43.252.210.125:10012}, which matches neither
     * route, and the session recorded 32 packets — all NAT beacons to
     * {@code 148.222.94.x}, {@code foreign=32}, {@code decrypted=0}, and the
     * phase machine reporting <i>"no UDP stream after the match assignment"</i>.
     * Garena has added match servers outside 148/8 since that survey.</p>
     *
     * <p>Widening to a default route does NOT reintroduce the mid-match kick
     * documented in {@code ff-route-fallback-kick}. That kick came from
     * {@code restartTunnel} tearing down the interface and every UDP session
     * <i>while a match was live</i>; the routes themselves were never the
     * mechanism. This is chosen once, at establish time, and never switched
     * underneath a running match.</p>
     *
     * <p>The old note also warned that {@code 0.0.0.0/0} sends "the whole
     * device's traffic" through the userspace relay. That is only true of an
     * UNSCOPED tunnel — with an allow-list installed the default route carries
     * the game's packets and nothing else. So the narrow pair is kept for
     * exactly the case the warning was about: no app scoping in effect.</p>
     */
    private void addActiveRoutes(VpnService.Builder builder, boolean appScoped) {
        routesAppScoped = appScoped;
        if (appScoped) {
            builder.addRoute("0.0.0.0", 0);
            return;
        }
        if (lobbyRouteEnabled && !lobbyRouteFallbackFull) {
            // Unscoped: keep the tunnel off the rest of the device's traffic.
            builder.addRoute("148.0.0.0", 8);
            builder.addRoute("103.78.86.128", 25);
        } else {
            builder.addRoute("0.0.0.0", 0);
        }
    }

    /**
     * What the tunnel is ACTUALLY routing, for the log and the HUD.
     *
     * <p>The old string was a hard-coded list of subnets that no longer
     * described the installed routes, which is precisely the sort of silent
     * mislabelling that hid the 43.252.x match server -- a diagnostic that
     * lies is worse than none. It now reports the branch that ran.</p>
     */
    public String activeRouteModeName() {
        if (routesAppScoped) return "all_ipv4_app_scoped";
        if (!lobbyRouteEnabled) return "full_ipv4";
        if (lobbyRouteFallbackFull) return "full_ipv4_fallback";
        return "narrow_148_8_plus_103_78_86_128_25_UNSCOPED";
    }

    /**
     * DISABLED — this was kicking players out of live matches.
     *
     * <p>It used to arm on <b>every</b> filter tap: if {@code outUdpSeen} had
     * not advanced 1 200 ms later it set {@link #lobbyRouteFallbackFull} and
     * called {@code restartTunnel}. Three things made that destructive:</p>
     *
     * <ol>
     *   <li>The restart tears down the tun interface and every UDP session
     *       mid-match, so the game loses its connection and the player is
     *       returned to the lobby — no ban, just a "network error".</li>
     *   <li>The fallback route is {@code 0.0.0.0/0}: the <i>whole device's</i>
     *       traffic through the userspace relay, not just the game's. That is
     *       far more than a real-time shooter tolerates, so the trouble
     *       continued after the restart.</li>
     *   <li>The flag is a <b>latch</b>. Nothing clears it but
     *       {@link #disableFilters()} — which is exactly why the player found
     *       that "refreshing the engine" was the only thing that stopped it,
     *       and why it kept happening with no filter even turned on.</li>
     * </ol>
     *
     * <p>And it could never have helped: <b>0 of 182 708</b> outbound game
     * packets across 40 captures fall outside the routes that are already
     * installed ({@code 148.0.0.0/8} plus {@code 103.78.86.128/25}) — every one
     * went to 148.222.x or 148.153.x. A quiet 1 200 ms means the player is not
     * in a match yet, not that the routes are wrong. The wide 148/8 route was
     * added for precisely this reason; see the comment in
     * {@link #addActiveRoutes} ("without mid-match fallback restarts"). This
     * hook is the leftover of the narrow-route design it replaced.</p>
     *
     * <p>Kept as a no-op so the call sites stay honest about what used to
     * happen here. Do not reinstate without a capture showing game traffic
     * outside those blocks.</p>
     */
    public void scheduleLobbyRouteFallbackIfNoUdp(final String trigger) {
        // Deliberately does nothing. See the javadoc above.
    }

    private final AtomicBoolean statsRunning = new AtomicBoolean(false);
    /** Stats ticks since the last diagnostic block; 10 ticks = ~15 s. */
    private int diagTick = 0;

    private void startStats() {
        if (!statsRunning.compareAndSet(false, true)) return;   // already running
        executeSafe(new Runnable() {
            @Override
            public void run() {
                try {
                    while (isRun && !stopped) {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            return;
                        }
                        // MODULE HEALTH, every ~15 s into the capture.
                        //
                        // Ten windows per snapshot rather than one: at 1.5 s
                        // the deltas are too small to read a rate off, and the
                        // block would dominate the file. 15 s is short enough
                        // that a failure is still bracketed to a moment.
                        //
                        // Only when capture is running -- the counters
                        // themselves are always live and free, but the file is
                        // the operator's, and writing to it uninvited is how
                        // the retro-ring ended up costing a copy per packet
                        // with capture switched off.
                        if (++diagTick >= 10) {
                            diagTick = 0;
                            if (capture.isRunning()) {
                                capture.writeDiagBlock();
                            }
                        }
                        long sent = pSent.get();
                        long drop = pDrop.get();
                        long total = sent + drop;
                        int loss = total > 0 ? (int) (drop * 100L / total) : 0;
                        String mode = isActive ? "ACTIVE" : "STANDBY";
                        Intent i = new Intent(VpnActions.ACTION_STATS);
                        i.setPackage(context.getPackageName());
                        i.putExtra(VpnActions.EXTRA_PING, (int) rtt);
                        i.putExtra(VpnActions.EXTRA_LOSS, loss);
                        i.putExtra(VpnActions.EXTRA_BYTES_IN, bIn.get());
                        i.putExtra(VpnActions.EXTRA_BYTES_OUT, bOut.get());
                        i.putExtra(VpnActions.EXTRA_FILTER_ENABLED, isFilterEnabled);
                        i.putExtra(VpnActions.EXTRA_MODE, mode);
                        i.setPackage(context.getPackageName());
                        context.sendBroadcast(i);
                        // Late-subscribing UIs converge to the truth on the
                        // same tick.
                        broadcastState();
                    }
                } finally {
                    statsRunning.set(false);
                }
            }
        });
    }

    /** Current effect bitmask, mirrored into every captured packet. */
    private int currentFilterMask() {
        int m = 0;
        if (filterEngine.isKindActive(FilterKind.FREEZE)) m |= com.nepdon.wire.dev.capture.CaptureRecord.F_FREEZE;
        if (filterEngine.isKindActive(FilterKind.GHOST)) m |= com.nepdon.wire.dev.capture.CaptureRecord.F_GHOST;
        // Teleport is marked whenever it is REWRITING, not only while the
        // button reads ON.
        //
        // isKindActive(JITTER) is isActive() is state.isEngaged(), which is
        // ANCHOR only -- deliberately, because the button must read OFF once
        // the user has pressed off. But the RAMP is the teleport, and tagging
        // the capture from the same predicate meant the release phase was
        // labelled plain MATCH. Result: across 2841 capture files there is not
        // one row tagged JITTER, so teleport has never been observed in a
        // capture at all -- the exact window that needs debugging was the one
        // marked "effect off".
        //
        // A separate predicate, not a change to isEngaged(): the UI reads that
        // one and genuinely wants OFF during the ramp.
        if (filterEngine.isKindActive(FilterKind.JITTER)
                || filterEngine.isKindRewriting(FilterKind.JITTER)) {
            m |= com.nepdon.wire.dev.capture.CaptureRecord.F_JITTER;
        }
        return m;
    }

    /** Deactivate every effect and reset the lobby-route fallback flag. */
    public void disableFilters() {
        isFilterEnabled = false;
        filterEngine.disableAll();
        lobbyRouteFallbackFull = false;
        capture.setFilterMask(0);
        notifyStateChanged();
    }

    // ---- TCP relay ----

    /** Userspace TCP relay for routed login/lobby/voice connections. */
    public TcpProxy tcpProxy() {
        return tcpProxy;
    }

    /**
     * Writes a synthesized IP packet into the tun (called by the TCP relay).
     * Synchronized on the writer because the relay threads are off-loop.
     */
    public void writeToTun(byte[] pkt) {
        FileOutputStream out = tunWriter;
        if (out == null) return;
        try {
            synchronized (out) {
                out.write(pkt);
            }
        } catch (Exception e) {
            // tunnel closed; relay connections will notice on next read
        }
    }

    // ---- Packet capture ----

    /** Starts capturing every packet with freeze-phase stamps (defaults). */
    public void startCapture(int hexLimit) {
        startCapture(hexLimit,
                com.nepdon.wire.dev.capture.PacketCapture.MODE_FULL, 0);
    }

    /**
     * Starts a sectioned capture.
     *
     * @param mode     what to keep (PacketCapture.MODE_*)
     * @param retroSec seconds of pre-press history from the always-on ring
     */
    public void startCapture(int hexLimit, int mode, int retroSec) {
        capture.setFilterMask(currentFilterMask());
        // Per-capture, not per-process: without this the coverage footer
        // reports cumulative counters from every capture since app start.
        com.nepdon.wire.dev.capture.CaptureAnnotator.resetCounters();
        capture.start(hexLimit, mode, retroSec);
        // Lobby (TCP) capture writes its own file alongside the UDP one --
        // it deframes [cmd][len BE] and decodes protobuf, which the raw UDP
        // record format cannot represent.
        {
            File lobbyDir = context.getExternalFilesDir("dev_logs");
            if (lobbyDir == null) lobbyDir = new File(context.getFilesDir(), "dev_logs");
            com.nepdon.wire.dev.capture.LobbyCapture.start(lobbyDir);
        }
        logger.logControl("CAPTURE_ON", "hex_limit=" + hexLimit
                + " mode=" + com.nepdon.wire.dev.capture.PacketCapture.modeName(mode)
                + " retro_sec=" + retroSec);
        telemetry.event(com.nepdon.wire.dev.capture.TelemetryCollector.EV_CAPTURE_ON);
        notifyStateChanged();
    }

    /** Stops capturing and writes the raw dump + freeze diff report (background). */
    public void stopCapture() {
        // The stop/drain/write/analyze pipeline can take multiple seconds with
        // a full capture (join up to 3s + 160k-line file write + analysis).
        // Running it on the caller (service main) thread blocked every queued
        // intent behind it — filter toggles silently "didn't take" and had to
        // be re-pressed. All of it now happens off the main thread.
        executeSafe(new Runnable() {
            @Override
            public void run() {
                capture.stop();
                com.nepdon.wire.dev.capture.LobbyCapture.stop();
                File dir = context.getExternalFilesDir("dev_logs");
                if (dir == null) dir = new File(context.getFilesDir(), "dev_logs");
                // Snapshot ONCE, up front. Writing the raw dump for a long
                // match takes seconds; pressing capture-start again inside
                // that window calls records.clear(), so a later snapshot()
                // returned the handful of packets of the NEW capture. That
                // is exactly what happened on capture 161940: the raw file
                // held 37513 records while the decoded dump saw 7.
                final java.util.List<com.nepdon.wire.dev.capture.CaptureRecord> snap =
                        capture.snapshot();
                final long dropped = capture.droppedByCapacity();
                File raw = capture.writeRawCapture(dir);
                File report = new FreezeDiffAnalyzer(snap, dropped).analyze(dir);
                File decoded = null;
                try {
                    decoded = new com.nepdon.wire.dev.capture.DecodedDumper(
                            snap, dropped).dump(dir);
                } catch (Throwable t) {
                    // Never let the analysis pipeline kill the VPN.
                    logger.logControl("DECODED_DUMP_FAIL", String.valueOf(t));
                }
                if (raw != null) {
                    Prefs.get(context).edit().putString(Prefs.KEY_CAPTURE_LAST_RAW, raw.getAbsolutePath()).commit();
                }
                if (report != null) {
                    Prefs.get(context).edit().putString(Prefs.KEY_CAPTURE_LAST_REPORT, report.getAbsolutePath()).commit();
                }
                logger.logControl("CAPTURE_OFF",
                        "raw=" + (raw == null ? "null" : raw.getName())
                                + " report=" + (report == null ? "null" : report.getName())
                                + " decoded=" + (decoded == null ? "null" : decoded.getName()));
                // snap/dropped, NOT a fresh snapshot() - see the note above. A
                // second snapshot() here copies up to MAX_RECORDS entries just to
                // read size(), and reports the NEXT capture's count when the user
                // has already pressed start again during this dump.
                telemetry.event(com.nepdon.wire.dev.capture.TelemetryCollector.EV_CAPTURE_OFF,
                        snap.size(), (int) dropped);
                notifyStateChanged();
            }
        });
    }

    /** Records the freeze state whenever it changes (called from toggle dispatch). */
    public void noteFilterToggled() {
        boolean freezeOn = filterEngine.isKindActive(FilterKind.FREEZE);
        capture.setFilterMask(currentFilterMask());
        telemetry.event(freezeOn
                ? com.nepdon.wire.dev.capture.TelemetryCollector.EV_FREEZE_ON
                : com.nepdon.wire.dev.capture.TelemetryCollector.EV_FREEZE_OFF);
        telemetry.event(filterEngine.isKindActive(FilterKind.GHOST)
                ? com.nepdon.wire.dev.capture.TelemetryCollector.EV_GHOST_ON
                : com.nepdon.wire.dev.capture.TelemetryCollector.EV_GHOST_OFF);
        telemetry.event(filterEngine.isKindActive(FilterKind.JITTER)
                ? com.nepdon.wire.dev.capture.TelemetryCollector.EV_JITTER_ON
                : com.nepdon.wire.dev.capture.TelemetryCollector.EV_JITTER_OFF);
        notifyStateChanged();
    }

    /** Hot-path recorder owned here, used by PacketLoop and UdpSessionManager. */
    PacketCapture capture() {
        return capture;
    }

    public void stop() {
        isFilterEnabled = false;
        stopped = true;
        isRun = false;
        // Drop lobby reassembly state and the phase machine with the tunnel.
        // Carrying either across a restart would splice two sessions' streams
        // together and keep a stale match/mode label.
        com.nepdon.wire.dev.capture.LobbyCapture.shutdown();
        com.nepdon.wire.protocol.SessionTimeline.reset();
        filterEngine.disableAll();
        notifyStateChanged();
        // RST relayed TCP connections while the tun writer is still live.
        try {
            tcpProxy.closeAll();
        } catch (Exception e) {
            // ignore
        }
        try {
            if (tun != null) tun.close();
        } catch (Exception e) {
            // ignore
        }
        sessions.closeAll();
        // Re-assert the flags LAST.
        //
        // stop() sets these first and then does work that can block; a restart
        // interleaving with that could leave isRun == true with no tunnel
        // behind it. Nothing then corrects it -- the stats loop exits on
        // `stopped` -- so MainActivity refused every subsequent Activate with
        // "Engine already running" until the process died. Setting them again
        // after the teardown makes the invariant hold whatever order the two
        // callers arrive in.
        isRun = false;
        isActive = false;
        tunWriter = null;
        if (threadPool != null) threadPool.shutdownNow();
        vpnService.stopSelf();
    }
}
