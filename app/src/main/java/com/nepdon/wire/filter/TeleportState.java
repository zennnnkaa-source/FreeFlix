package com.nepdon.wire.filter;

/**
 * The teleport policy's states. One press advances the machine; the RAMP state
 * ends itself once the server has been walked all the way to the player.
 *
 * <pre>
 *   IDLE --press--&gt; ANCHOR --press--&gt; RAMP --arrived--&gt; IDLE
 *                      ^                 |
 *                      |              (press = abort)
 *                      +-----------------+----------&gt; IDLE
 * </pre>
 */
public enum TeleportState {

    /** Nothing is rewritten; the player's real position goes to the server. */
    IDLE("off"),

    /**
     * The server's belief is pinned at the anchor. Every position report is
     * rewritten to the anchor with zero velocity, so the server sees a player
     * standing still while the real player walks away.
     */
    ANCHOR("anchor"),

    /**
     * The server's belief is being walked from the anchor to the player's real
     * position, at most {@link TeleportFilter#MAX_STEP_M} metres per report.
     * This is the teleport itself.
     */
    RAMP("ramp");

    private final String label;

    TeleportState(String label) {
        this.label = label;
    }

    /** Short name for the dev panel and logs. */
    public String label() {
        return label;
    }

    /**
     * True when the toggle should read as ON — that is, ANCHOR only. RAMP is
     * still rewriting packets, but the user has already pressed off and the
     * button must reflect their intent, not the internal state.
     */
    public boolean isEngaged() {
        return this == ANCHOR;
    }

    /**
     * The state a toggle press moves to.
     *
     * <ul>
     *   <li>IDLE &rarr; ANCHOR — on: pin the server where you stand now.</li>
     *   <li>ANCHOR &rarr; RAMP — off: release, and catch the server up.</li>
     *   <li>RAMP &rarr; ANCHOR — pressing again during a release re-arms
     *       immediately rather than doing nothing, anchored at the position the
     *       server currently believes.</li>
     * </ul>
     *
     * <p><b>RAMP is not a third press.</b> It runs itself to completion and is
     * reported as OFF ({@link #isEngaged()} is false for it), because the user
     * pressed off and the button must agree. The first build left the light on
     * through the ramp as a progress indicator; it read as "the toggle is
     * broken" and got pressed two or three more times.</p>
     */
    public TeleportState onPress() {
        switch (this) {
            case IDLE:   return ANCHOR;
            case ANCHOR: return RAMP;
            default:     return ANCHOR;
        }
    }
}
