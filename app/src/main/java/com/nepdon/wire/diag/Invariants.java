package com.nepdon.wire.diag;

/**
 * Rules that should always hold, and the alarms raised when they do not.
 *
 * <h3>Why counters alone are not enough</h3>
 *
 * <p>A dump full of numbers still needs someone who knows which numbers are
 * wrong. Every bug in this project's history was visible in principle and
 * missed in practice, because nothing said <i>this value is impossible</i>.
 * These rules encode that knowledge once, so the dump states a verdict instead
 * of inviting an interpretation.</p>
 *
 * <p>Each threshold below is derived from a measurement recorded in
 * {@code docs/}, not guessed. Where a number is a judgement call rather than a
 * measurement, it says so.</p>
 *
 * <h3>Rule for adding one</h3>
 *
 * <p>An invariant must be <b>impossible in healthy operation</b>, not merely
 * unusual. A rule that fires during normal play trains the reader to ignore
 * alarms, which is worse than having none — the same failure mode as a test
 * suite with a flaky test.</p>
 */
public final class Invariants {

    private Invariants() {}

    // ---- thresholds, each with its provenance ----

    /**
     * Match resets per snapshot window before it is a loop.
     *
     * <p>A real match boundary fires once. The Clash Squad regression fired
     * <b>789 times in 109 seconds</b> and blanked the ESP for 78 s continuously
     * (docs/CS_MODE_ESP_FAILURE.md). Two per window allows a genuine boundary
     * plus one sloppy re-detection; anything above is the loop.</p>
     */
    private static final int MAX_RESETS_PER_WINDOW = 2;

    /**
     * Own-state staleness before the renderer gives up entirely.
     *
     * <p>{@code EspRenderView.OWN_STALE_MS}. Cmd 2001 arrives every 99 ms and
     * the self-echo every 198 ms, so exceeding 2 s means every source has
     * stopped, not that one is slow.</p>
     */
    private static final long OWN_STALE_MS = 2000L;

    /**
     * Outbound packets below this in a window means the game's traffic is not
     * in the tunnel.
     *
     * <p>A live match sends ~10 position reports/sec. The documented failure
     * (docs/WHEN_NOTHING_WORKS.md §1) measured <b>out=1 against in=6247</b>
     * when the wrong build was targeted, and out=972/in=870 when healthy.</p>
     */
    private static final int MIN_OUT_PER_WINDOW = 3;

    /** Inbound-to-outbound ratio above which the tunnel is one-directional. */
    private static final int MAX_IN_OUT_RATIO = 20;

    private static volatile String worst = null;

    /**
     * Evaluates every rule against the deltas in this window.
     *
     * @param windowMs length of the window; 0 on the first snapshot, when
     *                 rate-based rules cannot be judged and are skipped
     * @return alarm lines, or an empty string when everything holds
     */
    static String evaluate(long windowMs) {
        StringBuilder sb = new StringBuilder();
        worst = null;

        // ---- the reset loop that broke Clash Squad ----
        int resets = delta(Slot.ESP_MATCH_RESET);
        if (resets > MAX_RESETS_PER_WINDOW) {
            alarm(sb, "RESET_LOOP",
                    "match reset fired " + resets + " times this window (max "
                            + MAX_RESETS_PER_WINDOW + "). Entities, HP bindings and the "
                            + "roster are being cleared repeatedly; the ESP will be blank "
                            + "or flickering. This is the CS regression signature.");
        }

        // ---- the tunnel carrying nothing outbound ----
        if (windowMs > 0) {
            int out = delta(Slot.TUNNEL_PKT_OUT);
            int in = delta(Slot.TUNNEL_PKT_IN);
            if (in > 100 && out < MIN_OUT_PER_WINDOW) {
                alarm(sb, "NO_OUTBOUND",
                        "in=" + in + " out=" + out + ". The game's traffic is not entering "
                                + "the tunnel: wrong app targeted, or the match server is "
                                + "outside the installed routes. Nothing downstream can work.");
            } else if (out > 0 && in / Math.max(1, out) > MAX_IN_OUT_RATIO) {
                alarm(sb, "LOPSIDED",
                        "in/out ratio " + (in / Math.max(1, out)) + ":1. Healthy is near 1:1.");
            }
        }

        // ---- the classifier deciding nothing ----
        int clsTotal = delta(Slot.ESP_CLS_R1_SELF) + delta(Slot.ESP_CLS_R2_TEAM)
                + delta(Slot.ESP_CLS_R2_ENEMY) + delta(Slot.ESP_CLS_R3_CLOSURE_ENEMY)
                + delta(Slot.ESP_CLS_R4_LATE_ENEMY) + delta(Slot.ESP_CLS_R6_DEFAULT_ENEMY);
        int clsBlocked = delta(Slot.ESP_CLS_NO_OWN_ID) + delta(Slot.ESP_CLS_R2_NO_OWN_TEAM);
        if (clsBlocked > 0 && clsTotal == 0) {
            alarm(sb, "CLASSIFIER_BLIND",
                    "every classify() call bailed before deciding (" + clsBlocked
                            + " blocked, 0 decided). Own identity or own team is unknown, so "
                            + "no box can be coloured and unclassified entities are not drawn.");
        }

        // ---- an authoritative team source that never fires ----
        //
        // TOTALS, not deltas. Cmd 118 binds and the Cmd 101 roster arrive in a
        // burst at match start and then essentially stop, so a window-delta
        // test fires in every healthy mid-match window -- 64 false alarms in
        // one capture, against a classifier that was working perfectly. The
        // real failure this is meant to catch is "no authoritative team byte
        // has EVER arrived this match", and that is a total.
        int teamAuth = total(Slot.ESP_TEAM_SRC_BIND) + total(Slot.ESP_TEAM_SRC_ROSTER);
        if (clsTotal > 200 && teamAuth == 0) {
            alarm(sb, "NO_AUTHORITATIVE_TEAM",
                    "classified " + clsTotal + " entities with zero Cmd 118 / Cmd 101 team "
                            + "bytes. Friend/foe is running entirely on the red default; "
                            + "teammates will show as enemies.");
        }

        // ---- THE AIM VERDICT ----
        //
        // These two exist because the last mile of the aim cannot be checked
        // off the device: the daemon only runs in a live match, so whether the
        // steer actually converges is a question only real play can answer.
        // Rather than leave that as a manual dump-reading step, the app renders
        // the verdict itself the first time it is used.

        int aimInj  = total(Slot.AIM_INJECTED);
        int aimOn   = total(Slot.AIM_ON_TARGET);
        int aimClamp= total(Slot.AIM_TRAVEL_CLAMPED);

        // Steering hard and never arriving. Before 2026-09-20 this read
        // 2 on-target against 2778 ticks, and the causes were an aim point
        // computed from a different pipeline than the drawn box, and
        // corrections a fifth of the size they needed to be (PX_PER_DEG is the
        // HIP-FIRE figure; scoped damps the look ~0.2x).
        if (aimInj > 60 && aimOn * 12 < aimInj) {
            alarm(sb, "AIM_NOT_CONVERGING",
                    "injected " + aimInj + " steers but landed on target only "
                            + aimOn + " times. The aim is moving the view and "
                            + "not arriving. Raise SCOPED GAIN if it crawls, "
                            + "lower it if it overshoots and oscillates; if the "
                            + "aim point sits off the BOX rather than off the "
                            + "target, that is box calibration, not aim.");
        }

        // Pinned at the cap means the corrections are not closing the error, so
        // the offset accumulates until it is limited every single time. That is
        // the runaway that used to drag the contact off the panel and swing the
        // view at the sky; the cap now contains it, but being pinned still says
        // the gain is wrong.
        if (aimInj > 60 && aimClamp * 2 > aimInj) {
            alarm(sb, "AIM_PINNED_AT_CAP",
                    aimClamp + " of " + aimInj + " steers hit the travel cap. "
                            + "The corrections are not closing the error, so "
                            + "the offset accumulates until it is limited. "
                            + "SCOPED GAIN is almost certainly too low.");
        }

        // ---- the renderer drawing nothing while the engine has data ----
        int drew = delta(Slot.RENDER_DREW_BOX);
        int nothing = delta(Slot.RENDER_DREW_NOTHING);
        if (nothing > 100 && drew == 0 && clsTotal > 0) {
            alarm(sb, "RENDER_BLIND",
                    "the engine classified " + clsTotal + " entities but the renderer drew 0 "
                            + "boxes in " + nothing + " frames (boxes AND radar, they share "
                            + "one array). Gates, in order: RENDER_NO_OWN_STATE (own state "
                            + "older than 2 s), RENDER_ENTITY_STALE (every entity older than "
                            + "1.5 s), then the role filter. RENDER_ROLE_FILTERED is NOT a "
                            + "fault -- it counts our own echo being skipped, once per frame "
                            + "in normal play.");
        }

        // ---- mounted with no camera source ----
        // Cmd 2001 stops dead when you mount. 2069 replaces its aim, 2002 does
        // not, and 2006/2015/2016/2031 send nothing at all -- so the inbound
        // self-echo is the only camera left. When that is silent too the
        // overlay keeps drawing against a FROZEN view basis, which looks like
        // "the ESP is wrong in vehicles" rather than like a failure.
        int from2002 = delta(Slot.ESP_OWN_FROM_2002);
        int from2069 = delta(Slot.ESP_OWN_FROM_2069);
        int fromEcho = delta(Slot.ESP_OWN_FROM_ECHO);
        if (from2002 > 20 && from2069 == 0 && fromEcho == 0) {
            alarm(sb, "MOUNTED_NO_CAMERA",
                    from2002 + " Cmd 2002 records (a car) with no Cmd 2069 and no self-echo. "
                            + "2002 carries position but no aim at all, so the camera basis "
                            + "is frozen at whatever it held when you mounted and every box "
                            + "and radar bearing is computed against a stale view.");
        }

        // ---- mounted with no position carrier at all ----
        int vehRefused = delta(Slot.ESP_VEHICLE_PARSE_REFUSED);
        if (vehRefused > 5 && from2002 + from2069 == 0) {
            alarm(sb, "VEHICLE_PARSE_FAILING",
                    vehRefused + " vehicle-state bodies refused and none accepted. Own "
                            + "position while mounted is falling back to the self-echo or "
                            + "going stale entirely; check the field-8 raw-byte reading.");
        }

        // ---- the structural chain parse degrading ----
        // Exact parse vs shape-heuristic fallback. Healthy is >90%; the CS
        // reset loop dragged it to 59.4% as a pure side effect, which makes
        // this the earliest warning available -- it moves before anything is
        // visible on screen.
        int structured = delta(Slot.ESP_CHAIN_PARSE_STRUCTURED);
        int fallback = delta(Slot.ESP_CHAIN_PARSE_FALLBACK);
        int chainTotal = structured + fallback;
        if (chainTotal > 200 && structured * 10 < chainTotal * 9) {
            alarm(sb, "CHAIN_PARSE_DEGRADED",
                    "structural parse " + (structured * 100 / Math.max(1, chainTotal))
                            + "% of " + chainTotal + " chain packets (healthy >90%). "
                            + "Entity data is being read by the shape fallback -- check "
                            + "for a reset loop or a layout change upstream.");
        }

        // ---- decryption failing wholesale ----
        int decOk = delta(Slot.PROTO_DECRYPT_OK);
        int decBad = delta(Slot.PROTO_DECRYPT_FAILED);
        if (decBad > 50 && decBad > decOk * 4) {
            alarm(sb, "DECRYPT_FAILING",
                    decBad + " failures against " + decOk + " successes. The session key is "
                            + "wrong or stale -- reliable channels (health, names, binds) are "
                            + "all unreadable.");
        }

        // ---- a seeded layout being rejected repeatedly ----
        int seedRej = delta(Slot.PROTO_LAYOUT_SEED_REJECTED);
        if (seedRej > 2) {
            alarm(sb, "SEED_REJECTED",
                    "the lobby-declared header layout was rejected " + seedRej
                            + " times. Either field 42 is being mis-parsed or the mask "
                            + "derivation is wrong; the solver is carrying the session "
                            + "instead, so the match-start burst is being lost.");
        }

        // ---- the aim ticking while it can never act ----
        int aimTicks = delta(Slot.AIM_TICK);
        int aimInject = delta(Slot.AIM_INJECTED);
        int aimNoShiz = delta(Slot.AIM_NO_SHIZUKU) + delta(Slot.AIM_NO_PERMISSION)
                + delta(Slot.AIM_BINDER_DEAD);
        if (aimTicks > 500 && aimInject == 0 && aimNoShiz > 0) {
            alarm(sb, "AIM_CANNOT_ACT",
                    "the aim controller ticked " + aimTicks + " times and injected nothing, "
                            + "with " + aimNoShiz + " Shizuku-side refusals. It is burning "
                            + "binder calls with no possibility of working.");
        }

        // ---- HARD RULE 1: a pick reached the aim with no server team byte ----
        //
        // This must read 0 forever. ROLE_ENEMY is also what "we could not
        // decrypt Cmd 118/101" looks like, so a bypass here is how the aim
        // would end up swinging at a teammate -- with nothing on screen saying
        // so, because the target would look like any other enemy.
        int teamBypass = delta(Slot.AIM_TEAM_GATE_BYPASSED);
        if (teamBypass > 0) {
            alarm(sb, "AIM_TEAM_GATE_BYPASSED",
                    teamBypass + " aim picks were published with no server-published team "
                            + "byte. Hard rule 1 says only that byte may mark a target as "
                            + "safe to engage. AimTarget.set refuses these, so a non-zero "
                            + "count means a caller is constructing a pick some other way.");
        }

        // ---- aiming while OUR OWN camera basis is frozen ----
        //
        // Cmd 2001 stops the moment the player mounts anything, so the view
        // direction stops updating. An aim computed from that basis does not
        // miss by a little; it swings at whatever bearing the basis was left
        // holding. The refusal is counted so "the aim went quiet in a vehicle"
        // is a fact on the HUD rather than a guess.
        int selfMount = delta(Slot.AIM_REFUSE_SELF_MOUNTED);
        int acquired = delta(Slot.AIM_ACQUIRED);
        if (selfMount > 200 && acquired == 0) {
            alarm(sb, "AIM_IDLE_MOUNTED",
                    "the aim refused every frame for " + selfMount + " frames because we are "
                            + "mounted (Cmd 2001 stops in vehicles, so the camera basis is "
                            + "frozen). This is the aim working as designed, not a fault -- "
                            + "but it explains a completely silent aim.");
        }

        // ---- a UDP session being recreated repeatedly ----
        int sessClosed = delta(Slot.TUNNEL_SESSION_CLOSED_IO);
        int decodeThrew = delta(Slot.TUNNEL_DECODE_THREW);
        if (sessClosed > 5) {
            alarm(sb, "SESSION_CHURN",
                    sessClosed + " UDP sessions closed this window. Each close re-NATs the "
                            + "next outbound packet onto a NEW SOURCE PORT mid-match, which "
                            + "is the documented kick fingerprint.");
        }
        if (decodeThrew > 0) {
            alarm(sb, "DECODER_THREW",
                    decodeThrew + " inbound decoder exceptions. Packets are being dropped by "
                            + "a bug in our own parsing, not by the network.");
        }

        return sb.toString();
    }

    private static void alarm(StringBuilder sb, String tag, String detail) {
        if (worst == null) worst = tag;
        sb.append("# DIAGALARM ").append(tag).append(' ').append(detail).append('\n');
    }

    /**
     * Delta for a slot within the current window.
     *
     * <p>Reads the value {@link Diag#snapshot} has already folded into its
     * baseline, so this must only be called from inside that call — which is
     * why it is package-private and takes no lock of its own.</p>
     */
    private static int delta(int slot) {
        return Diag.deltaOf(slot);
    }

    /**
     * Cumulative value of a slot since engine start.
     *
     * <p>Use this for "has X EVER happened" rules. A window delta is the wrong
     * test for anything that arrives in a burst and then legitimately stops —
     * match-start binds, the roster, the header-layout seed — because such a
     * rule fires in every healthy window afterwards, and an alarm that fires
     * in normal play is worse than no alarm at all.</p>
     */
    private static int total(int slot) {
        return Diag.get(slot);
    }
}
