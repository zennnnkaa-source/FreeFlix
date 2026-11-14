package com.nepdon.wire.diag;

/**
 * Diagnostic counter slots — one per place the code can silently decline to do
 * its job.
 *
 * <h3>Why this exists</h3>
 *
 * <p>The packet dumper records what arrived on the wire. It cannot record what
 * the code then <i>decided</i> about it, and every long-running bug in this
 * project has been a decision, not a packet:</p>
 *
 * <ul>
 *   <li>a match-boundary detector read {@code -1 & 0xFFFFFF} as a valid handle
 *       and reset the world ~10 times a second — <b>no packet anywhere shows
 *       this</b>;</li>
 *   <li>the overlay blanked 1.6 s into every vehicle ride because a staleness
 *       check read a field that stops advancing when mounted, while the
 *       fallback beneath it worked perfectly;</li>
 *   <li>a proximity rule painted 21 enemies per match as teammates, because the
 *       statistic it used is inverted in Battle Royale;</li>
 *   <li>health was invented rather than read for months, because the channel
 *       carrying it was encrypted and nothing said so.</li>
 * </ul>
 *
 * <p>Each took an evening of replaying logic offline against captures. As a
 * counter delta, each would have been a glance.</p>
 *
 * <h3>Conventions</h3>
 *
 * <p>Ids are dense {@code int} constants so a hit is an array index, never a
 * map lookup or a string. <b>Never renumber an existing slot</b> — old dumps
 * are read against these ids. Append at the end of a block; the blocks are
 * spaced so each has room.</p>
 *
 * <p>{@link #NAMES} is parallel to the ids. A slot with no name is a bug and
 * {@link Diag} reports it rather than printing a blank.</p>
 *
 * <p>Naming: {@code MODULE_WHAT_HAPPENED}, module prefix first, so a dump
 * sorts into subsystems and {@code rg ESP_} finds one module's whole story.</p>
 */
public final class Slot {

    private Slot() {}

    // ---- 0..31 tunnel ----
    public static final int TUNNEL_PKT_IN                 = 0;
    public static final int TUNNEL_PKT_OUT                = 1;
    public static final int TUNNEL_NOT_MATCH_TRAFFIC      = 2;
    public static final int TUNNEL_CRC_BAD                = 3;
    public static final int TUNNEL_DECODE_THREW           = 4;
    public static final int TUNNEL_SESSION_CLOSED_IO      = 5;
    public static final int TUNNEL_SESSION_CREATED        = 6;
    public static final int TUNNEL_SCOPE_EMPTY            = 7;
    public static final int TUNNEL_ESTABLISH_FAILED       = 8;
    public static final int TUNNEL_REVOKED                = 9;
    public static final int TUNNEL_RESTART                = 10;
    public static final int TUNNEL_JOIN_TIMEOUT           = 11;
    public static final int TUNNEL_FOREGROUND_FAILED      = 12;
    public static final int TUNNEL_WRITE_NO_WRITER        = 13;
    public static final int TUNNEL_IPV6_DROPPED           = 14;
    public static final int TUNNEL_OVERSIZE_DROPPED       = 15;
    public static final int TUNNEL_SESSION_NO_READER      = 16;
    public static final int TUNNEL_OUT_MALFORMED          = 17;
    /** send() threw: the packet never left, and no counter advanced. */
    public static final int TUNNEL_SEND_FAILED            = 18;

    // ---- 32..63 protocol ----
    public static final int PROTO_LAYOUT_SEEDED           = 32;
    public static final int PROTO_LAYOUT_SEED_REJECTED    = 33;
    public static final int PROTO_LAYOUT_CONFIRMED        = 34;
    public static final int PROTO_LAYOUT_SOLVED_BY_VOTE   = 35;
    public static final int PROTO_LAYOUT_RESOLVE          = 36;
    public static final int PROTO_NO_LAYOUT               = 37;
    public static final int PROTO_NO_KEY                  = 38;
    public static final int PROTO_DECRYPT_FAILED          = 39;
    public static final int PROTO_DECRYPT_OK              = 40;
    public static final int PROTO_CMD_UNRESOLVED          = 41;
    public static final int PROTO_KEY_RECOVERED           = 42;
    public static final int PROTO_KEY_ROTATED             = 43;
    public static final int PROTO_PERM_RECOVERED          = 44;

    // ---- 64..111 esp ----
    public static final int ESP_CHAIN_PKT                 = 64;
    public static final int ESP_CHAIN_PARSE_STRUCTURED    = 65;
    public static final int ESP_CHAIN_PARSE_FALLBACK      = 66;
    public static final int ESP_CHAIN_PARSE_REFUSED       = 67;
    public static final int ESP_KEEPALIVE_OK              = 68;
    public static final int ESP_KEEPALIVE_BAD             = 69;
    public static final int ESP_MATCH_RESET               = 70;
    public static final int ESP_TELEPORT_GUARD            = 71;
    public static final int ESP_VEL_REJECTED              = 72;
    public static final int ESP_HP_BOUND                  = 73;
    public static final int ESP_HP_UNBOUND                = 74;
    public static final int ESP_NAME_BOUND                = 75;

    // Phase-4b native-decode shadow compare (NativeBridge.NATIVE_SHADOW). The
    // native Cmd-2012 decoder runs in PARALLEL with the Java parse and the two
    // results are compared field-for-field; Java stays authoritative. MATCH vs
    // DIVERGE is the parity signal that must read 100%/0% in a live match before
    // the native path can be trusted authoritative. REFUSED = native returned -1
    // where Java parsed (a divergence of its own kind); UNARMED = shadow was on
    // but no live server decode profile, so the leash correctly declined.
    public static final int ESP_CHAIN_NATIVE_MATCH        = 76;
    public static final int ESP_CHAIN_NATIVE_DIVERGE      = 77;
    public static final int ESP_CHAIN_NATIVE_REFUSED      = 78;
    public static final int ESP_CHAIN_NATIVE_UNARMED      = 79;

    // The classifier ladder. WHICH RUNG DECIDED is the single most valuable
    // diagnostic in this codebase: reconstructing it offline is exactly what
    // found both the BR proximity inversion and the CS blackout.
    public static final int ESP_CLS_NO_OWN_ID             = 80;
    public static final int ESP_CLS_R1_SELF               = 81;
    public static final int ESP_CLS_R2_TEAM               = 82;
    public static final int ESP_CLS_R2_ENEMY              = 83;
    public static final int ESP_CLS_R2_NO_OWN_TEAM        = 84;
    public static final int ESP_CLS_R3_CLOSURE_ENEMY      = 85;
    public static final int ESP_CLS_R4_LATE_ENEMY         = 86;
    public static final int ESP_CLS_R6_DEFAULT_ENEMY      = 87;
    public static final int ESP_TEAM_SRC_BIND             = 88;
    public static final int ESP_TEAM_SRC_ROSTER           = 89;
    public static final int ESP_TEAM_SRC_DAMAGE           = 90;
    public static final int ESP_TEAM_SRC_HANDLE           = 91;

    // Own-state source ladder. Which feed is supplying position and camera.
    public static final int ESP_OWN_FROM_2001             = 96;
    public static final int ESP_OWN_FROM_2069             = 97;
    public static final int ESP_OWN_FROM_2002             = 98;
    public static final int ESP_OWN_FROM_ECHO             = 99;
    public static final int ESP_OWN_NONE                  = 100;
    public static final int ESP_VEHICLE_PARSE_REFUSED     = 101;
    public static final int ESP_VEHICLE_OP_VARINT         = 102;
    public static final int ESP_PRI_DECODE_REFUSED        = 103;
    /** Cmd 125: we mounted. Authoritative, unlike the 2001-silence guess. */
    public static final int ESP_MOUNT_ON_SELF             = 104;
    /** Cmd 126: we dismounted. */
    public static final int ESP_MOUNT_OFF_SELF            = 105;
    /** Cmd 125/126 for somebody else -- a remote player got on or off. */
    public static final int ESP_MOUNT_REMOTE              = 106;
    /** Cmd 125/126 body that would not parse. */
    public static final int ESP_MOUNT_PARSE_BAD           = 107;
    /** Inbound Cmd 2002 vehicle-list packets parsed. */
    public static final int ESP_VEHICLE_LIST_PKT          = 108;
    /** Vehicle-list records applied to a known rider's track. */
    public static final int ESP_VEHICLE_LIST_APPLIED      = 109;
    /** Chain field 10/11 reached 0x80 -- the varint reading is now WRONG. */
    public static final int ESP_CHAIN_MDST_HIGH           = 110;
    /**
     * The server-gated decode leash (NativeBridge.DECODE_LEASH) declined a live
     * decode because no unexpired server profile was armed. When enforced, this
     * is what makes a login-bypassed client decode nothing; a licensed session
     * that refreshed /config never trips it. A non-zero value on a legitimate
     * session means the profile expired mid-match (refresh cadence too slow).
     */
    public static final int ESP_LEASH_CLOSED              = 111;

    // ---- config / plugin registry (uses the tail of the esp block) ----
    // The settings layer sits between user intent and behaviour, which is
    // exactly where this codebase's silent-skip failures live. Every bail in
    // it is counted.
    public static final int CFG_LOAD_ALL                  = 118;
    public static final int CFG_SETTING_WRITTEN           = 119;
    public static final int CFG_SETTING_RESET             = 120;
    public static final int CFG_VALIDATE_REFUSED          = 121;
    public static final int CFG_SCHEMA_MIGRATED           = 122;
    public static final int CFG_FEATURE_ENABLED           = 123;
    public static final int CFG_FEATURE_DISABLED          = 124;
    public static final int CFG_NOT_TOGGLEABLE            = 125;
    public static final int CFG_NO_PREFS                  = 126;
    /** A settings page was opened. */
    public static final int UI_PAGE_SHOWN                 = 127;
    /** A control receiver could not be registered as NOT_EXPORTED. */
    public static final int SEC_RECEIVER_EXPORTED         = 128;
    /** updateViewLayout threw -- an overlay button is detached. */
    public static final int UI_BUTTON_LAYOUT_THREW        = 129;

    // ---- 112..143 decode ----
    public static final int DEC_VARINT_TRUNCATED          = 112;
    public static final int DEC_BODY_SHORT                = 113;
    public static final int DEC_BODY_TRAILING_BYTES       = 114;
    public static final int DEC_TEA_BAD_LENGTH            = 115;
    public static final int DEC_TEA_BAD_PADDING           = 116;

    // ---- 144..175 filter ----
    public static final int FLT_MARK_SEEN                 = 144;
    public static final int FLT_MARK_REWRITTEN            = 145;
    public static final int FLT_MARK_NO_TARGET            = 146;
    public static final int FLT_MARK_NO_KEY               = 147;
    public static final int FLT_MARK_BAD                  = 148;
    public static final int FLT_AIM_SEEN                  = 149;
    public static final int FLT_AIM_REWRITTEN             = 150;
    public static final int FLT_AIM_NO_KEY                = 151;
    public static final int FLT_AIM_PASS_SAFE             = 152;
    public static final int FLT_GHOST_HELD                = 153;
    public static final int FLT_TELE_REWRITTEN            = 154;
    public static final int FLT_LAYOUT_STALE_REFUSED      = 155;
    /**
     * Freeze spared a world-state packet because it was RELIABLE.
     *
     * <p>Withholding one starves the server's ack and it retransmits forever.
     * Measured: Cmd 2003 arrives reliable ~0.0076/s. Seeing this counter move
     * is the filter doing its job, not a fault.</p>
     */
    public static final int FREEZE_RELIABLE_SPARED        = 156;
    /**
     * Freeze fell back to the byte-shape test because the command was
     * unresolved. Measured over a full day of captures this never happened on
     * the match stream — if it starts, the header solver is in trouble and
     * freeze is running on a heuristic that misses 100% of Cmd 2002.
     */
    public static final int FREEZE_SHAPE_FALLBACK         = 157;
    /** Freeze's shape fallback declined a packet it could not identify. */
    public static final int FREEZE_SHAPE_UNMATCHED        = 158;
    /**
     * A teleport ramp report could not be written, so the cursor was rolled
     * back. Climbing here means the header layout is stale (usually the
     * re-convergence window after a match transition) and the ramp is stalled,
     * not advancing.
     */
    public static final int FLT_TELE_WRITE_FAILED         = 159;
    /**
     * The ramp route was spent while the server was still more than one legal
     * step behind — the cursor had run ahead of what was transmitted. The ramp
     * re-aims instead of landing into a jump the server would reject.
     */
    public static final int FLT_TELE_CURSOR_BEHIND        = 160;
    /** Teleport state was force-reset at a match or tunnel boundary. */
    public static final int FLT_TELE_BOUNDARY_RESET       = 161;
    /** A replay was refused because the saved anchor packet was stale. */
    public static final int FLT_TELE_STALE_ANCHOR         = 162;
    /**
     * Inbound Cmd 144 force-sync arrived while teleport was engaged — the
     * server rejected our reported position and put the player back. This is
     * the direct "it was refused" signal; before it existed a snap-back was
     * indistinguishable from a working ramp.
     */
    public static final int FLT_TELE_FORCE_SYNC           = 163;

    // ---- 176..207 ui / render ----
    // The "ESP drew nothing" ladder: which layer gave up.
    public static final int RENDER_FRAME                  = 176;
    public static final int RENDER_NO_ENGINE              = 177;
    public static final int RENDER_MASK_NOT_READY         = 178;
    public static final int RENDER_NO_OWN_STATE           = 179;
    public static final int RENDER_NO_VIEWPORT            = 180;
    public static final int RENDER_ENTITY_STALE           = 181;
    public static final int RENDER_ROLE_FILTERED          = 182;
    public static final int RENDER_BEHIND_CAMERA          = 183;
    public static final int RENDER_DREW_BOX               = 184;
    public static final int RENDER_DREW_NOTHING           = 185;
    public static final int RENDER_LOOP_DIED              = 186;
    public static final int RENDER_WINDOW_ADD_FAILED      = 187;
    /** registerReceiver threw -- a UI surface stopped following the engine. */
    public static final int UI_RECEIVER_REGISTER_FAILED   = 188;
    /** An overlay button's icon could not be loaded; the button is text-only. */
    public static final int UI_BUTTON_ICON_MISSING        = 189;
    /**
     * A filter tap was discarded because the engine was fully stopped.
     *
     * <p>The tap still played its toggle beep, so the user heard "accepted"
     * and nothing happened. Reachable after {@code onRevoke()} — another VPN
     * app took the slot — which is invisible from the floating buttons.</p>
     */
    public static final int UI_TAP_DISCARDED_ENGINE_OFF   = 190;
    /** A filter tap was queued because the tunnel was mid-restart. */
    public static final int UI_TAP_QUEUED                 = 191;
    /** A queued tap aged past the TTL and was thrown away. */
    public static final int UI_TAP_STALE_DROPPED          = 192;
    /** Two queued taps of one kind cancelled out and were coalesced away. */
    public static final int UI_TAP_COALESCED              = 193;

    // ---- 208..239 aim ----
    // The "aim did nothing" ladder: ~8 preconditions that used to collapse
    // into a single boolean the user saw as "not ready".
    public static final int AIM_TICK                      = 208;
    public static final int AIM_DISABLED                  = 209;
    public static final int AIM_NO_SHIZUKU                = 210;
    public static final int AIM_NO_PERMISSION             = 211;
    public static final int AIM_BINDER_DEAD               = 212;
    public static final int AIM_NO_TARGET                 = 213;
    public static final int AIM_YIELD_TO_TOUCH            = 214;
    public static final int AIM_PARKED_IDLE               = 215;
    public static final int AIM_TRAVEL_CAPPED             = 216;
    public static final int AIM_INJECTED                  = 217;
    public static final int AIM_INJECT_THREW              = 218;
    public static final int AIM_REGRIP                    = 219;
    public static final int AIM_STALL_DETECTED            = 220;
    public static final int TOUCH_WATCH_START_FAILED      = 221;
    public static final int TOUCH_WATCH_DIED              = 222;
    /** A 106 field read fell outside the walked body (never seen; see AimFilter). */
    public static final int AIM_FIELD_READ_BAD            = 223;
    /** A step of the aim's Shizuku/hidden-api setup threw; the aim is unarmed. */
    public static final int AIM_SETUP_STEP_FAILED         = 224;
    /** Outbound Cmd 137 RUDP_START_SNIPER seen: the player scoped in. */
    public static final int SCOPE_IN                      = 225;
    /** Outbound Cmd 138 RUDP_STOP_SNIPER seen: the player scoped out. */
    public static final int SCOPE_OUT                     = 226;
    /**
     * A scope edge repeated the state it was already in (START after START).
     * Measured 0 across six captures — if this climbs, either the solver missed
     * an edge or 137/138 are not the clean pair the survey says they are.
     */
    public static final int SCOPE_PAIR_DESYNC             = 227;
    /**
     * A NEW distinct Cmd 1900 varId 12 EPRI_PLAYER_SIGHTING_ID value was seen.
     * The tier probe: this firing 3-4 times a match is what "varId 12 carries
     * the scope level" would look like; it firing 30 times means the id is an
     * item/instance value, not a tier. Never fired = the id is silent here.
     */
    public static final int SCOPE_SIGHT_VALUE             = 228;

    // ---- 229..233 setup panel / layout editor ----
    /**
     * A tap on a setup panel header counted toward the five-tap secret.
     *
     * <p>Counted AND reset are both recorded because a gesture with no feedback
     * is indistinguishable from a dead title: if the editor "will not open",
     * this pair says whether the taps are arriving at all and whether they are
     * arriving inside the window.</p>
     */
    public static final int SETUP_TAP_COUNTED             = 229;
    /** A five-tap burst expired before reaching five. */
    public static final int SETUP_TAP_RESET               = 230;
    /** The layout editor window was added. */
    public static final int SETUP_EDITOR_OPEN             = 231;
    /** The layout editor saved a placement. */
    public static final int SETUP_EDITOR_SAVE             = 232;
    /**
     * {@code wm.addView} for the editor threw.
     *
     * <p>The one failure that looks exactly like the gesture not working: the
     * taps landed, the open ran, and nothing appeared.</p>
     */
    public static final int SETUP_EDITOR_ADD_FAILED       = 233;
    /**
     * A corrective swipe was shortened to keep it on screen.
     *
     * <p>A clamped swipe under-turns. Without this counter the symptom is "the
     * aim always stops short" with nothing on screen saying why, and the
     * instinct is to raise PX / DEG -- which makes it worse.</p>
     */
    public static final int AIM_TRAVEL_CLAMPED            = 234;
    /** The fire-button test spin started (a real press landed in the circle). */
    public static final int AIM_TEST_SPIN_START           = 235;
    /** The test spin ended and the player's own press was released. */
    public static final int AIM_TEST_SPIN_END             = 236;
    /**
     * The aim decided it was already on target and sent nothing. Distinguishes
     * "steering and settled" from "never ran", which read identically before.
     */
    public static final int AIM_ON_TARGET                 = 237;
    /** A target was acquired and published to AimTarget. */
    public static final int AIM_ACQUIRED                  = 238;
    /**
     * MUST READ 0 FOREVER. A pick reached AimTarget without a server-published
     * team byte. Hard rule 1: only a server team byte may mark a teammate, and
     * "ENEMY" is also what "we could not decrypt Cmd 118/101" looks like.
     */
    public static final int AIM_TEAM_GATE_BYPASSED        = 239;
    /** Target refused: unmodelled or unknown posture. */
    public static final int AIM_REFUSE_POSE               = 244;
    /** Target refused: knocked down (body horizontal, model does not apply). */
    public static final int AIM_REFUSE_KNOCKED            = 245;
    /** Target refused: skydiving or falling (the vertical axis stops being trustworthy). */
    public static final int AIM_REFUSE_AIRBORNE           = 246;
    /** Target refused: swimming (root submerged, root-to-body relation unmeasured). */
    public static final int AIM_REFUSE_SWIM               = 247;
    /** Target refused: mounted on a vehicle we do not model. */
    public static final int AIM_REFUSE_TARGET_MOUNTED     = 248;
    /**
     * Refused because WE are mounted. Cmd 2001 stops in vehicles, so our own
     * camera basis is frozen and every projection is computed against a stale
     * view -- aiming from it does not miss by a little, it swings at an
     * arbitrary bearing.
     */
    public static final int AIM_REFUSE_SELF_MOUNTED       = 249;
    /** Target refused: its freshest record is older than AIM_MAX_SRC_AGE_MS. */
    public static final int AIM_REFUSE_STALE_SRC          = 250;

    // ---- 240..255 auth / continuous-auth heartbeat ----
    /** A /auth/heartbeat succeeded and re-armed the rolling decode profile. */
    public static final int AUTH_HB_OK                    = 240;
    /** A heartbeat attempt failed (network/5xx/timeout) — benign miss, retried. */
    public static final int AUTH_HB_FAIL                  = 241;
    /** A heartbeat returned a terminal 401 (revoked/deactivated) — stop re-arming. */
    public static final int AUTH_HB_TERMINAL              = 242;
    /** A received ACK was rejected as stale (epoch <= the one already held). */
    public static final int AUTH_HB_STALE_ACK             = 243;

    /** One past the highest id. Grow this when a block fills. */
    public static final int COUNT = 256;

    private static final String[] NAMES = new String[COUNT];

    static {
        n(TUNNEL_PKT_IN, "TUNNEL_PKT_IN");
        n(TUNNEL_PKT_OUT, "TUNNEL_PKT_OUT");
        n(TUNNEL_NOT_MATCH_TRAFFIC, "TUNNEL_NOT_MATCH_TRAFFIC");
        n(TUNNEL_CRC_BAD, "TUNNEL_CRC_BAD");
        n(TUNNEL_DECODE_THREW, "TUNNEL_DECODE_THREW");
        n(TUNNEL_SESSION_CLOSED_IO, "TUNNEL_SESSION_CLOSED_IO");
        n(TUNNEL_SESSION_CREATED, "TUNNEL_SESSION_CREATED");
        n(TUNNEL_SCOPE_EMPTY, "TUNNEL_SCOPE_EMPTY");
        n(TUNNEL_ESTABLISH_FAILED, "TUNNEL_ESTABLISH_FAILED");
        n(TUNNEL_REVOKED, "TUNNEL_REVOKED");
        n(TUNNEL_RESTART, "TUNNEL_RESTART");
        n(TUNNEL_JOIN_TIMEOUT, "TUNNEL_JOIN_TIMEOUT");
        n(TUNNEL_FOREGROUND_FAILED, "TUNNEL_FOREGROUND_FAILED");
        n(TUNNEL_WRITE_NO_WRITER, "TUNNEL_WRITE_NO_WRITER");
        n(TUNNEL_IPV6_DROPPED, "TUNNEL_IPV6_DROPPED");
        n(TUNNEL_OVERSIZE_DROPPED, "TUNNEL_OVERSIZE_DROPPED");
        n(TUNNEL_SESSION_NO_READER, "TUNNEL_SESSION_NO_READER");
        n(TUNNEL_OUT_MALFORMED, "TUNNEL_OUT_MALFORMED");
        n(TUNNEL_SEND_FAILED, "TUNNEL_SEND_FAILED");

        n(PROTO_LAYOUT_SEEDED, "PROTO_LAYOUT_SEEDED");
        n(PROTO_LAYOUT_SEED_REJECTED, "PROTO_LAYOUT_SEED_REJECTED");
        n(PROTO_LAYOUT_CONFIRMED, "PROTO_LAYOUT_CONFIRMED");
        n(PROTO_LAYOUT_SOLVED_BY_VOTE, "PROTO_LAYOUT_SOLVED_BY_VOTE");
        n(PROTO_LAYOUT_RESOLVE, "PROTO_LAYOUT_RESOLVE");
        n(PROTO_NO_LAYOUT, "PROTO_NO_LAYOUT");
        n(PROTO_NO_KEY, "PROTO_NO_KEY");
        n(PROTO_DECRYPT_FAILED, "PROTO_DECRYPT_FAILED");
        n(PROTO_DECRYPT_OK, "PROTO_DECRYPT_OK");
        n(PROTO_CMD_UNRESOLVED, "PROTO_CMD_UNRESOLVED");
        n(PROTO_KEY_RECOVERED, "PROTO_KEY_RECOVERED");
        n(PROTO_KEY_ROTATED, "PROTO_KEY_ROTATED");
        n(PROTO_PERM_RECOVERED, "PROTO_PERM_RECOVERED");

        n(ESP_CHAIN_PKT, "ESP_CHAIN_PKT");
        n(ESP_CHAIN_PARSE_STRUCTURED, "ESP_CHAIN_PARSE_STRUCTURED");
        n(ESP_CHAIN_PARSE_FALLBACK, "ESP_CHAIN_PARSE_FALLBACK");
        n(ESP_CHAIN_PARSE_REFUSED, "ESP_CHAIN_PARSE_REFUSED");
        n(ESP_KEEPALIVE_OK, "ESP_KEEPALIVE_OK");
        n(ESP_KEEPALIVE_BAD, "ESP_KEEPALIVE_BAD");
        n(ESP_MATCH_RESET, "ESP_MATCH_RESET");
        n(ESP_TELEPORT_GUARD, "ESP_TELEPORT_GUARD");
        n(ESP_VEL_REJECTED, "ESP_VEL_REJECTED");
        n(ESP_HP_BOUND, "ESP_HP_BOUND");
        n(ESP_HP_UNBOUND, "ESP_HP_UNBOUND");
        n(ESP_NAME_BOUND, "ESP_NAME_BOUND");
        n(ESP_CHAIN_NATIVE_MATCH, "ESP_CHAIN_NATIVE_MATCH");
        n(ESP_CHAIN_NATIVE_DIVERGE, "ESP_CHAIN_NATIVE_DIVERGE");
        n(ESP_CHAIN_NATIVE_REFUSED, "ESP_CHAIN_NATIVE_REFUSED");
        n(ESP_CHAIN_NATIVE_UNARMED, "ESP_CHAIN_NATIVE_UNARMED");

        n(ESP_CLS_NO_OWN_ID, "ESP_CLS_NO_OWN_ID");
        n(ESP_CLS_R1_SELF, "ESP_CLS_R1_SELF");
        n(ESP_CLS_R2_TEAM, "ESP_CLS_R2_TEAM");
        n(ESP_CLS_R2_ENEMY, "ESP_CLS_R2_ENEMY");
        n(ESP_CLS_R2_NO_OWN_TEAM, "ESP_CLS_R2_NO_OWN_TEAM");
        n(ESP_CLS_R3_CLOSURE_ENEMY, "ESP_CLS_R3_CLOSURE_ENEMY");
        n(ESP_CLS_R4_LATE_ENEMY, "ESP_CLS_R4_LATE_ENEMY");
        n(ESP_CLS_R6_DEFAULT_ENEMY, "ESP_CLS_R6_DEFAULT_ENEMY");
        n(ESP_TEAM_SRC_BIND, "ESP_TEAM_SRC_BIND");
        n(ESP_TEAM_SRC_ROSTER, "ESP_TEAM_SRC_ROSTER");
        n(ESP_TEAM_SRC_DAMAGE, "ESP_TEAM_SRC_DAMAGE");
        n(ESP_TEAM_SRC_HANDLE, "ESP_TEAM_SRC_HANDLE");

        n(ESP_OWN_FROM_2001, "ESP_OWN_FROM_2001");
        n(ESP_OWN_FROM_2069, "ESP_OWN_FROM_2069");
        n(ESP_OWN_FROM_2002, "ESP_OWN_FROM_2002");
        n(ESP_OWN_FROM_ECHO, "ESP_OWN_FROM_ECHO");
        n(ESP_OWN_NONE, "ESP_OWN_NONE");
        n(ESP_VEHICLE_PARSE_REFUSED, "ESP_VEHICLE_PARSE_REFUSED");
        n(ESP_VEHICLE_OP_VARINT, "ESP_VEHICLE_OP_VARINT");
        n(ESP_PRI_DECODE_REFUSED, "ESP_PRI_DECODE_REFUSED");
        n(ESP_MOUNT_ON_SELF, "ESP_MOUNT_ON_SELF");
        n(ESP_MOUNT_OFF_SELF, "ESP_MOUNT_OFF_SELF");
        n(ESP_MOUNT_REMOTE, "ESP_MOUNT_REMOTE");
        n(ESP_MOUNT_PARSE_BAD, "ESP_MOUNT_PARSE_BAD");
        n(ESP_VEHICLE_LIST_PKT, "ESP_VEHICLE_LIST_PKT");
        n(ESP_VEHICLE_LIST_APPLIED, "ESP_VEHICLE_LIST_APPLIED");
        n(ESP_CHAIN_MDST_HIGH, "ESP_CHAIN_MDST_HIGH");
        n(ESP_LEASH_CLOSED, "ESP_LEASH_CLOSED");

        n(CFG_LOAD_ALL, "CFG_LOAD_ALL");
        n(CFG_SETTING_WRITTEN, "CFG_SETTING_WRITTEN");
        n(CFG_SETTING_RESET, "CFG_SETTING_RESET");
        n(CFG_VALIDATE_REFUSED, "CFG_VALIDATE_REFUSED");
        n(CFG_SCHEMA_MIGRATED, "CFG_SCHEMA_MIGRATED");
        n(CFG_FEATURE_ENABLED, "CFG_FEATURE_ENABLED");
        n(CFG_FEATURE_DISABLED, "CFG_FEATURE_DISABLED");
        n(CFG_NOT_TOGGLEABLE, "CFG_NOT_TOGGLEABLE");
        n(CFG_NO_PREFS, "CFG_NO_PREFS");
        n(UI_PAGE_SHOWN, "UI_PAGE_SHOWN");
        n(SEC_RECEIVER_EXPORTED, "SEC_RECEIVER_EXPORTED");
        n(UI_BUTTON_LAYOUT_THREW, "UI_BUTTON_LAYOUT_THREW");
        n(DEC_VARINT_TRUNCATED, "DEC_VARINT_TRUNCATED");
        n(DEC_BODY_SHORT, "DEC_BODY_SHORT");
        n(DEC_BODY_TRAILING_BYTES, "DEC_BODY_TRAILING_BYTES");
        n(DEC_TEA_BAD_LENGTH, "DEC_TEA_BAD_LENGTH");
        n(DEC_TEA_BAD_PADDING, "DEC_TEA_BAD_PADDING");

        n(FLT_MARK_SEEN, "FLT_MARK_SEEN");
        n(FLT_MARK_REWRITTEN, "FLT_MARK_REWRITTEN");
        n(FLT_MARK_NO_TARGET, "FLT_MARK_NO_TARGET");
        n(FLT_MARK_NO_KEY, "FLT_MARK_NO_KEY");
        n(FLT_MARK_BAD, "FLT_MARK_BAD");
        n(FLT_AIM_SEEN, "FLT_AIM_SEEN");
        n(FLT_AIM_REWRITTEN, "FLT_AIM_REWRITTEN");
        n(FLT_AIM_NO_KEY, "FLT_AIM_NO_KEY");
        n(FLT_AIM_PASS_SAFE, "FLT_AIM_PASS_SAFE");
        n(FLT_GHOST_HELD, "FLT_GHOST_HELD");
        n(FLT_TELE_REWRITTEN, "FLT_TELE_REWRITTEN");
        n(FLT_LAYOUT_STALE_REFUSED, "FLT_LAYOUT_STALE_REFUSED");
        n(FREEZE_RELIABLE_SPARED, "FREEZE_RELIABLE_SPARED");
        n(FREEZE_SHAPE_FALLBACK, "FREEZE_SHAPE_FALLBACK");
        n(FREEZE_SHAPE_UNMATCHED, "FREEZE_SHAPE_UNMATCHED");
        n(FLT_TELE_WRITE_FAILED, "FLT_TELE_WRITE_FAILED");
        n(FLT_TELE_CURSOR_BEHIND, "FLT_TELE_CURSOR_BEHIND");
        n(FLT_TELE_BOUNDARY_RESET, "FLT_TELE_BOUNDARY_RESET");
        n(FLT_TELE_STALE_ANCHOR, "FLT_TELE_STALE_ANCHOR");
        n(FLT_TELE_FORCE_SYNC, "FLT_TELE_FORCE_SYNC");

        n(RENDER_FRAME, "RENDER_FRAME");
        n(RENDER_NO_ENGINE, "RENDER_NO_ENGINE");
        n(RENDER_MASK_NOT_READY, "RENDER_MASK_NOT_READY");
        n(RENDER_NO_OWN_STATE, "RENDER_NO_OWN_STATE");
        n(RENDER_NO_VIEWPORT, "RENDER_NO_VIEWPORT");
        n(RENDER_ENTITY_STALE, "RENDER_ENTITY_STALE");
        n(RENDER_ROLE_FILTERED, "RENDER_ROLE_FILTERED");
        n(RENDER_BEHIND_CAMERA, "RENDER_BEHIND_CAMERA");
        n(RENDER_DREW_BOX, "RENDER_DREW_BOX");
        n(RENDER_DREW_NOTHING, "RENDER_DREW_NOTHING");
        n(RENDER_LOOP_DIED, "RENDER_LOOP_DIED");
        n(RENDER_WINDOW_ADD_FAILED, "RENDER_WINDOW_ADD_FAILED");
        n(UI_RECEIVER_REGISTER_FAILED, "UI_RECEIVER_REGISTER_FAILED");
        n(UI_BUTTON_ICON_MISSING, "UI_BUTTON_ICON_MISSING");
        n(UI_TAP_DISCARDED_ENGINE_OFF, "UI_TAP_DISCARDED_ENGINE_OFF");
        n(UI_TAP_QUEUED, "UI_TAP_QUEUED");
        n(UI_TAP_STALE_DROPPED, "UI_TAP_STALE_DROPPED");
        n(UI_TAP_COALESCED, "UI_TAP_COALESCED");

        n(AIM_TICK, "AIM_TICK");
        n(AIM_DISABLED, "AIM_DISABLED");
        n(AIM_NO_SHIZUKU, "AIM_NO_SHIZUKU");
        n(AIM_NO_PERMISSION, "AIM_NO_PERMISSION");
        n(AIM_BINDER_DEAD, "AIM_BINDER_DEAD");
        n(AIM_NO_TARGET, "AIM_NO_TARGET");
        n(AIM_YIELD_TO_TOUCH, "AIM_YIELD_TO_TOUCH");
        n(AIM_PARKED_IDLE, "AIM_PARKED_IDLE");
        n(AIM_TRAVEL_CAPPED, "AIM_TRAVEL_CAPPED");
        n(AIM_INJECTED, "AIM_INJECTED");
        n(AIM_INJECT_THREW, "AIM_INJECT_THREW");
        n(AIM_REGRIP, "AIM_REGRIP");
        n(AIM_STALL_DETECTED, "AIM_STALL_DETECTED");
        n(TOUCH_WATCH_START_FAILED, "TOUCH_WATCH_START_FAILED");
        n(TOUCH_WATCH_DIED, "TOUCH_WATCH_DIED");
        n(AIM_FIELD_READ_BAD, "AIM_FIELD_READ_BAD");
        n(AIM_SETUP_STEP_FAILED, "AIM_SETUP_STEP_FAILED");
        n(SCOPE_IN, "SCOPE_IN");
        n(SCOPE_OUT, "SCOPE_OUT");
        n(SCOPE_PAIR_DESYNC, "SCOPE_PAIR_DESYNC");
        n(SCOPE_SIGHT_VALUE, "SCOPE_SIGHT_VALUE");
        n(SETUP_TAP_COUNTED, "SETUP_TAP_COUNTED");
        n(SETUP_TAP_RESET, "SETUP_TAP_RESET");
        n(SETUP_EDITOR_OPEN, "SETUP_EDITOR_OPEN");
        n(SETUP_EDITOR_SAVE, "SETUP_EDITOR_SAVE");
        n(SETUP_EDITOR_ADD_FAILED, "SETUP_EDITOR_ADD_FAILED");
        n(AIM_TRAVEL_CLAMPED, "AIM_TRAVEL_CLAMPED");
        n(AIM_TEST_SPIN_START, "AIM_TEST_SPIN_START");
        n(AIM_TEST_SPIN_END, "AIM_TEST_SPIN_END");
        n(AIM_ON_TARGET, "AIM_ON_TARGET");
        n(AIM_ACQUIRED, "AIM_ACQUIRED");
        n(AIM_TEAM_GATE_BYPASSED, "AIM_TEAM_GATE_BYPASSED");
        n(AIM_REFUSE_POSE, "AIM_REFUSE_POSE");
        n(AIM_REFUSE_KNOCKED, "AIM_REFUSE_KNOCKED");
        n(AIM_REFUSE_AIRBORNE, "AIM_REFUSE_AIRBORNE");
        n(AIM_REFUSE_SWIM, "AIM_REFUSE_SWIM");
        n(AIM_REFUSE_TARGET_MOUNTED, "AIM_REFUSE_TARGET_MOUNTED");
        n(AIM_REFUSE_SELF_MOUNTED, "AIM_REFUSE_SELF_MOUNTED");
        n(AIM_REFUSE_STALE_SRC, "AIM_REFUSE_STALE_SRC");

        n(AUTH_HB_OK, "AUTH_HB_OK");
        n(AUTH_HB_FAIL, "AUTH_HB_FAIL");
        n(AUTH_HB_TERMINAL, "AUTH_HB_TERMINAL");
        n(AUTH_HB_STALE_ACK, "AUTH_HB_STALE_ACK");
    }

    private static void n(int id, String name) {
        NAMES[id] = name;
    }

    /** Human name for a slot, or a placeholder that makes the gap obvious. */
    public static String name(int id) {
        if (id < 0 || id >= COUNT) return "SLOT_OUT_OF_RANGE_" + id;
        String s = NAMES[id];
        return (s == null) ? ("SLOT_UNNAMED_" + id) : s;
    }

}
