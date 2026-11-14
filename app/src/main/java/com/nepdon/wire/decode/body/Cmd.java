package com.nepdon.wire.decode.body;

/**
 * Known values of the {@code Cmd} header field.
 *
 * <p><b>These are the game's own names</b>, recovered from {@code
 * global-metadata.dat} rather than guessed from traffic — see
 * {@code docs/CMD_CATALOGUE.md} and the full 1876-member listing in
 * {@code tools/ff_cmd_catalogue.txt}. Two enums share the namespace:</p>
 * <pre>
 *   1..99      GCommon.UDPMessageIDReserved   transport
 *   100..3586  message.KLINFKFBIKB            application
 * </pre>
 * <p>{@code RUDP_*} members ride the reliable channel, {@code UDP_*} the
 * unreliable state-sync channel — which matches the
 * {@code SENDOPTION_RELIABLE} / {@code SENDOPTION_VAR} split seen on the
 * wire.</p>
 *
 * <p>This enum is deliberately <b>not</b> exhaustive and nothing may branch on
 * it being so: {@link #of(int)} answers {@link #UNKNOWN} for anything
 * uncatalogued and never throws. Body parsing is keyed on the raw code through
 * {@link BodyDecoderRegistry} so a decoder can be registered for a command
 * listed here or not.</p>
 *
 * <h3>Corrections to this project's earlier guesses</h3>
 * <pre>
 *   106  was "fire event"            is RUDP_TAKE_DAMAGE
 *   107  was "player names"          is RUDP_DEAD (the names are the death notice)
 *   230  was "damage/state snapshot" is RUDP_UPDATE_PICKUPLIST (loot)
 *   231  was "damage event, HP"      is RUDP_ADD_CONTAINER (the 100/200/300 were item ids)
 * </pre>
 * <p><b>Do not build HP bars on 230/231.</b> Health and armour live in
 * {@link #RUDP_VAR_SYNC_PRI_DATA} (1900), a keyed property-update stream.</p>
 *
 * <h3>Observed rates (live capture, Battle Royale, server port 10014)</h3>
 * <pre>
 *   OUT 2001  10 Hz   plain  own position report, 23 varints
 *   IN  2012  5.06 Hz plain  the entity chain -- the ESP's position source
 *   IN  2001  5.06 Hz plain  per-entity position stream
 *   IN  2000  0.5 Hz  plain  server time
 *   IN  1900  0.72 Hz        health / armour property updates
 *   IN/OUT 3  1 Hz    plain  ping
 * </pre>
 *
 * <p>There is no faster position channel: inbound entity data is 198 ms, full
 * stop. Parsing correctness, not rate, is the lever on tracking accuracy.</p>
 */
public enum Cmd {

    /** Not one of the named commands. */
    UNKNOWN(-1),

    // ---- transport (GCommon.UDPMessageIDReserved, 1..99) ----

    /** Session hello. */
    UDP_HELLO(1),
    /** Reliability-layer acknowledgement. Never drop these. */
    UDP_ACK(2),
    /** Liveness ping / pong. Never drop these. */
    UDP_PING(3),
    /** Session teardown. */
    UDP_BYEBYE(4),
    /** Idle-session probe. */
    UDP_LONGTIMENOSEE(5),
    /** Connection acknowledgement. */
    MUDP_CONNECT_ACK(6),
    /** One fragment of a datagram too large for the MTU. */
    RUDP_SPLITTED_DATAGRAM(99),

    // ---- match lifecycle ----

    /** Join the match. */
    RUDP_JOIN_MATCH(100),
    /** Another player joined. */
    RUDP_PLAYER_JOIN(101),
    /** Another player left. */
    RUDP_PLAYER_QUIT(102),
    /** The match is over; the endpoint becomes the lobby/squad session. */
    RUDP_MATCH_END(103),
    /** The match has begun. */
    RUDP_BATTLE_START(110),

    // ---- combat ----

    /** Trigger pressed. */
    RUDP_START_FIRE(104),
    /** Trigger released. */
    RUDP_STOP_FIRE(105),
    /** Damage applied to an entity. */
    RUDP_TAKE_DAMAGE(106),
    /** Death notice; carries the ASCII player name. */
    RUDP_DEAD(107),
    /** Reload started. */
    RUDP_START_RELOAD(133),
    /** Reload finished. */
    RUDP_STOP_RELOAD(134),
    /**
     * Scope-in edge. <b>Outbound only</b>, sent by the game's own Weapon class
     * with an EMPTY body — the command id IS the state. Reliable in 242/242
     * observed packets across six captures, and perfectly paired with
     * {@link #RUDP_STOP_SNIPER}. The server echoes the pair back to spectators
     * as {@code RUDP_START/STOP_SNIPER_FOR_CLIENT_REPLAY} (1522/1523), which is
     * why scoping is server-visible at all.
     *
     * <p>This is the whole answer to "is the player scoped" — no screen capture,
     * no recognition model. See {@code com.nepdon.wire.protocol.ScopeState} and
     * {@code plan/research/RESEARCH_WIRE_SCOPE_STATE.md} §1.3.</p>
     */
    RUDP_START_SNIPER(137),
    /** Scope-out edge. The partner of {@link #RUDP_START_SNIPER}. */
    RUDP_STOP_SNIPER(138),
    /** Damage numbers shown on the HUD. */
    RUDP_SHOW_DAMAGE(168),
    /** Knocked down (not yet dead). */
    RUDP_KNOCK_DOWN(139),
    /** Revived from a knockdown. */
    RUDP_KNOCK_REVIVE(140),
    /** Server confirmation that our shot hit an enemy. */
    RUDP_S2C_ENEMY_HIT_NOTIFY(1109),

    // ---- interest management / zone ----

    /**
     * The server's interest-management message: which players the client is
     * told about at all. Governs what the entity chain can ever contain.
     */
    RUDP_SYNC_PLAYERS_VISIBILITY(135),
    /** The vehicle equivalent of {@link #RUDP_SYNC_PLAYERS_VISIBILITY}. */
    RUDP_SYNC_VEHICLES_VISIBILITY(136),
    /** Safe-zone geometry change. */
    RUDP_SAFE_ZONE_CHANGE(117),
    /** Server-driven position override (respawn, vehicle exit, zone pull). */
    RUDP_TELEPORT_PLAYER(145),

    // ---- inventory / loot ----

    /** Held-item change. */
    RUDP_CHANGE_INVENTORY_ON_HAND(108),
    /** Item picked up. */
    RUDP_PICKUP_INVENTORY(111),
    /** Item dropped. */
    RUDP_DROP_INVENTORY(112),
    /** Item used. */
    RUDP_USE_INVENTORY(113),
    /** Ground loot entry removed. */
    RUDP_DEL_PICKUP(115),
    /** Equipment slot changed. */
    RUDP_EQUIPMENT_CHANGED(121),
    /** Weapon attachment changed. */
    RUDP_ATTACHMENT_CHANGED(124),
    /** Ground-loot list append. */
    RUDP_ADD_PICKUPLIST(229),
    /** Ground-loot list update. Not damage — an earlier guess had this wrong. */
    RUDP_UPDATE_PICKUPLIST(230),
    /** A loot container. Not entity state — an earlier guess had this wrong. */
    RUDP_ADD_CONTAINER(231),

    // ---- property sync: where health and armour actually live ----

    /**
     * Per-player property updates, keyed by {@code EPRI_*} ids
     * ({@code EPRI_PLAYER_CUR_HP = 0}, {@code EPRI_PLAYER_MAX_HP = 1},
     * {@code EPRI_PLAYER_VEST_DURABILITY = 2},
     * {@code EPRI_PLAYER_HELMET_DURABILITY = 3}). 0.72 Hz.
     *
     * <p>This is the real health/armour channel. Nothing in this app has ever
     * read it, which is why the HUD shows a static value.</p>
     */
    RUDP_VAR_SYNC_PRI_DATA(1900),
    /** The match-wide equivalent of {@link #RUDP_VAR_SYNC_PRI_DATA}. */
    RUDP_VAR_SYNC_GRI_DATA(1901),

    // ---- state sync (unreliable, plaintext) ----

    /** Server clock sync, 0.5 Hz. */
    UDP_SYNC_SERVER_TIME(2000),

    /**
     * Player position sync. Outbound it is the client's own 10 Hz state report
     * (23 varints: position, view vector, RTT, object handle); inbound it is
     * the per-entity position stream at 5.06 Hz, structurally a one-record
     * chain. Plaintext in every observed session.
     */
    UDP_PLAYER_STATE_SYNC(2001),

    /** Vehicle position sync. */
    UDP_VEHICLE_STATE_SYNC(2002),
    /** Airdrop position sync, 0.86 Hz. */
    UDP_AIRDROP_STATE_SYNC(2003),
    /** Bot position sync. */
    UDP_AI_STATE_SYNC(2009),
    /** Damage taken, on the unreliable channel. */
    UDP_PLAYER_HURT(2010),

    /**
     * The entity chain: N entity records in one packet at 5.06 Hz. This is the
     * ESP's primary position source. Plaintext in every observed session.
     */
    UDP_PLAYER_STATE_SYNC_V2(2012),

    /** Healing applied. */
    UDP_PLAYER_HEAL(2013),
    /** Position-only update (no pose). */
    UDP_PLAYER_POSITION_ONLY(2014),
    /** Aim rotation only. */
    UDP_SYNC_AIM_ROTATION(2016);

    private final int code;

    Cmd(int code) {
        this.code = code;
    }

    /** The wire value, or -1 for {@link #UNKNOWN}. */
    public int code() {
        return code;
    }

    /**
     * Maps a wire value to a constant. Tolerant by contract: an uncatalogued
     * or out-of-range ushort answers {@link #UNKNOWN} rather than throwing.
     * Allocation-free (javac compiles this to a lookupswitch).
     */
    public static Cmd of(int code) {
        switch (code) {
            case 1:    return UDP_HELLO;
            case 2:    return UDP_ACK;
            case 3:    return UDP_PING;
            case 4:    return UDP_BYEBYE;
            case 5:    return UDP_LONGTIMENOSEE;
            case 6:    return MUDP_CONNECT_ACK;
            case 99:   return RUDP_SPLITTED_DATAGRAM;
            case 100:  return RUDP_JOIN_MATCH;
            case 101:  return RUDP_PLAYER_JOIN;
            case 102:  return RUDP_PLAYER_QUIT;
            case 103:  return RUDP_MATCH_END;
            case 104:  return RUDP_START_FIRE;
            case 105:  return RUDP_STOP_FIRE;
            case 106:  return RUDP_TAKE_DAMAGE;
            case 107:  return RUDP_DEAD;
            case 108:  return RUDP_CHANGE_INVENTORY_ON_HAND;
            case 110:  return RUDP_BATTLE_START;
            case 111:  return RUDP_PICKUP_INVENTORY;
            case 112:  return RUDP_DROP_INVENTORY;
            case 113:  return RUDP_USE_INVENTORY;
            case 115:  return RUDP_DEL_PICKUP;
            case 117:  return RUDP_SAFE_ZONE_CHANGE;
            case 121:  return RUDP_EQUIPMENT_CHANGED;
            case 124:  return RUDP_ATTACHMENT_CHANGED;
            case 133:  return RUDP_START_RELOAD;
            case 134:  return RUDP_STOP_RELOAD;
            case 135:  return RUDP_SYNC_PLAYERS_VISIBILITY;
            case 136:  return RUDP_SYNC_VEHICLES_VISIBILITY;
            case 137:  return RUDP_START_SNIPER;
            case 138:  return RUDP_STOP_SNIPER;
            case 139:  return RUDP_KNOCK_DOWN;
            case 140:  return RUDP_KNOCK_REVIVE;
            case 145:  return RUDP_TELEPORT_PLAYER;
            case 168:  return RUDP_SHOW_DAMAGE;
            case 229:  return RUDP_ADD_PICKUPLIST;
            case 230:  return RUDP_UPDATE_PICKUPLIST;
            case 231:  return RUDP_ADD_CONTAINER;
            case 1109: return RUDP_S2C_ENEMY_HIT_NOTIFY;
            case 1900: return RUDP_VAR_SYNC_PRI_DATA;
            case 1901: return RUDP_VAR_SYNC_GRI_DATA;
            case 2000: return UDP_SYNC_SERVER_TIME;
            case 2001: return UDP_PLAYER_STATE_SYNC;
            case 2002: return UDP_VEHICLE_STATE_SYNC;
            case 2003: return UDP_AIRDROP_STATE_SYNC;
            case 2009: return UDP_AI_STATE_SYNC;
            case 2010: return UDP_PLAYER_HURT;
            case 2012: return UDP_PLAYER_STATE_SYNC_V2;
            case 2013: return UDP_PLAYER_HEAL;
            case 2014: return UDP_PLAYER_POSITION_ONLY;
            case 2016: return UDP_SYNC_AIM_ROTATION;
            default:   return UNKNOWN;
        }
    }

    /**
     * True for the whole reserved transport range (1..99): hello, ack, ping,
     * byebye, the idle probe and datagram fragments. A superset of
     * {@link #isTransportControl(int)} for callers that want to leave the
     * entire session layer alone.
     */
    public static boolean isTransportLayer(int code) {
        return code >= 1 && code <= 99;
    }

    /**
     * True for the inbound streams that carry other players' positions
     * ({@link #UDP_PLAYER_STATE_SYNC} and
     * {@link #UDP_PLAYER_STATE_SYNC_V2}). These are non-reliable, so dropping
     * them owes the server nothing.
     */
    public static boolean isEntityStream(int code) {
        return code == 2001 || code == 2012;
    }

    /**
     * True for every inbound stream that moves a world object on screen:
     * the player-entity chains plus vehicles, airdrops, bots, the
     * position-only variant and the aim-rotation sync.
     *
     * <p>This is the freeze drop set, and it is deliberately the <b>only</b>
     * thing freeze withholds. Freeze used to drop everything from the match
     * endpoint in a 21-450 byte window, which also swallowed the player's own
     * damage feed — {@link #UDP_PLAYER_HURT} at 23-27 B, plus
     * {@link #RUDP_TAKE_DAMAGE}, {@link #RUDP_SHOW_DAMAGE},
     * {@link #RUDP_DEAD} and the HP channel
     * {@link #RUDP_VAR_SYNC_PRI_DATA}. The client was therefore never told
     * about damage the server had already applied, which made "my shots do
     * not register" impossible to distinguish from "my shots register and I
     * am not shown it". Those all pass now; only position is withheld.</p>
     *
     * <p>All of these are {@code UDP_} (non-reliable), so withholding them
     * owes the server no acknowledgement. That is not true of the reliable
     * combat messages, which the server retransmits until acknowledged —
     * another reason the old length window was the wrong instrument.</p>
     */
    /**
     * True for the messages that tell the player about damage, death and
     * health — the feed freeze used to swallow whole.
     *
     * <p>Used for telemetry, so the dev panel can show that these are now
     * getting through rather than leaving it to be inferred.</p>
     */
    public static boolean isCombatFeedback(int code) {
        switch (code) {
            case 106:    // RUDP_TAKE_DAMAGE
            case 107:    // RUDP_DEAD
            case 168:    // RUDP_SHOW_DAMAGE   (the HUD damage numbers)
            case 1900:   // RUDP_VAR_SYNC_PRI_DATA (HP / armour)
            case 2010:   // UDP_PLAYER_HURT    (the authoritative damage feed)
                return true;
            default:
                return false;
        }
    }

    /**
     * True for every OUTBOUND message that carries our own position.
     *
     * <p><b>Cmd 2001 is not the only one, and assuming it was made ghost and
     * teleport silently stop working the moment the player mounts anything.</b>
     * The sender, {@code COW.GamePlay.PNCPKGJGBPP::EAIKJCCNGMJ}, dispatches on
     * {@code Player::IsInVehicle()} <i>before</i> it ever reaches the 2001
     * branch:</p>
     *
     * <pre>
     *   driver                      -&gt; 2002  UDP_VEHICLE_STATE_SYNC
     *   passenger who can fire      -&gt; 2006
     *   cannon / mounted weapon     -&gt; 2031  UDP_SYNC_CANNON
     *   on a chair                  -&gt; another class again
     *   on foot                     -&gt; 2001  UDP_PLAYER_STATE_SYNC
     * </pre>
     *
     * <p>Withholding works on any of them, because dropping a packet needs no
     * knowledge of its body. <b>Rewriting does not</b> — only 2001's layout is
     * decoded, so teleport's ramp has to stand down while mounted rather than
     * corrupt a body it cannot parse. See {@code docs/IL2CPP_FINDINGS.md} §5.1.</p>
     *
     * <p>OUTBOUND ONLY. Inbound 2002 is other players' vehicles and must never
     * be treated as ours.</p>
     */
    /**
     * The game's own name for any id, including the ~1900 this enum does not
     * model. Delegates to {@link CmdCatalogue}, the complete reference; this
     * enum deliberately covers only the ids the app acts on.
     */
    public static String nameOf(int code) {
        Cmd c = of(code);
        return c != UNKNOWN ? c.name() : CmdCatalogue.nameOr(code);
    }

    public static boolean isOwnPositionCarrier(int code) {
        // Server-sourced carrier id-set when the leash is armed (Ghost/Teleport
        // then can't find the position channel without a live profile); baked
        // otherwise. Parity-proven equal. DECODE_LEASH is compile-time-false, so
        // R8 strips the server branch from release.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.gatesArmed()) {
            return gateContains(com.nepdon.wire.auth.ConfigStore.gateOwnPos(), code);
        }
        switch (code) {
            case 2001:   // UDP_PLAYER_STATE_SYNC   -- on foot
            case 2002:   // UDP_VEHICLE_STATE_SYNC  -- driving
            case 2006:   // passenger who can fire
            case 2031:   // UDP_SYNC_CANNON
                return true;
            default:
                return false;
        }
    }

    private static boolean gateContains(int[] set, int code) {
        if (set == null) return false;
        for (int c : set) if (c == code) return true;
        return false;
    }

    /** True for the mounted variants, whose bodies we cannot yet parse. */
    public static boolean isMountedPositionCarrier(int code) {
        return code != 2001 && isOwnPositionCarrier(code);
    }

    public static boolean isWorldStateStream(int code) {
        // Server-sourced when armed (Freeze/Ghost can't tell world state from own
        // state without a live profile); baked otherwise. See isOwnPositionCarrier.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.gatesArmed()) {
            return gateContains(com.nepdon.wire.auth.ConfigStore.gateWorldState(), code);
        }
        switch (code) {
            case 2001:   // UDP_PLAYER_STATE_SYNC       (inbound: one-record chain)
            case 2002:   // UDP_VEHICLE_STATE_SYNC
            case 2003:   // UDP_AIRDROP_STATE_SYNC
            case 2009:   // UDP_AI_STATE_SYNC
            case 2012:   // UDP_PLAYER_STATE_SYNC_V2    (the entity chain)
            case 2014:   // UDP_PLAYER_POSITION_ONLY
            case 2016:   // UDP_SYNC_AIM_ROTATION
                return true;
            default:
                return false;
        }
    }

    // ================================================================
    // Predicates added for the capture annotator. Additive only — nothing
    // above is changed, and CmdCatalogue remains the complete reference.
    // ================================================================

}
