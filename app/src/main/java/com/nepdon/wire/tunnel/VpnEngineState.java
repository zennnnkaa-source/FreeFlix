package com.nepdon.wire.tunnel;

/**
 * Immutable snapshot of the engine's user-visible state. The single source of
 * truth surfaced to the UI via the {@code com.nepdon.wire.STATE}
 * broadcast and the {@code PacketVpnService.snapshot()} static accessor, so
 * every surface (MainActivity power button, overlay filter buttons, capture
 * button) paints from engine reality instead of optimistic local flips.
 */
public final class VpnEngineState {

    /** All-off constant (service dead / engine never started). */
    public static final VpnEngineState INACTIVE = new VpnEngineState(
            false, false, false, false, false, false, false, false);

    public final boolean engineRun;
    public final boolean tunnelActive;
    public final boolean filterEnabled;
    public final boolean freezeActive;
    public final boolean ghostActive;
    public final boolean jitterActive;
    public final boolean captureActive;
    /** True once the movement stream is learned (effects armed). */
    public final boolean matchReady;

    public VpnEngineState(boolean engineRun, boolean tunnelActive, boolean filterEnabled,
                          boolean freezeActive, boolean ghostActive, boolean jitterActive,
                          boolean captureActive) {
        this(engineRun, tunnelActive, filterEnabled, freezeActive, ghostActive,
                jitterActive, captureActive, false);
    }

    public VpnEngineState(boolean engineRun, boolean tunnelActive, boolean filterEnabled,
                          boolean freezeActive, boolean ghostActive, boolean jitterActive,
                          boolean captureActive, boolean matchReady) {
        this.engineRun = engineRun;
        this.tunnelActive = tunnelActive;
        this.filterEnabled = filterEnabled;
        this.freezeActive = freezeActive;
        this.ghostActive = ghostActive;
        this.jitterActive = jitterActive;
        this.captureActive = captureActive;
        this.matchReady = matchReady;
    }

    /** True when filter/capture taps can be applied right now. */
    public boolean isRunnable() {
        return engineRun && filterEnabled;
    }
}
