package com.nepdon.wire.filter;

import com.nepdon.wire.config.VpnActions;

/**
 * The set of fake-lag effects the engine can toggle: FREEZE, GHOST, and
 * TELE (the teleport/jitter effect). Adding a new effect means adding an enum
 * constant here and registering a {@link PacketFilter} in the engine — no
 * changes to the transport core or the UI dispatch.
 */
public enum FilterKind {
    FREEZE,
    GHOST,
    JITTER,
    /** The aim / hit-zone rewrite (outbound Cmd 106 Body/Limb -> Head). */
    AIM;

    /** Maps a {@link VpnActions} control action to its kind, or null. */
    public static FilterKind fromAction(String action) {
        if (action == null) return null;
        if (VpnActions.ACTION_FREEZE.equals(action)) return FREEZE;
        if (VpnActions.ACTION_GHOST.equals(action)) return GHOST;
        if (VpnActions.ACTION_JITTER.equals(action)) return JITTER;
        if (VpnActions.ACTION_AIM.equals(action)) return AIM;
        return null;
    }
}
