package com.nepdon.wire.esp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nepdon.wire.auth.ConfigStore;
import com.nepdon.wire.net.NativeBridge;
import com.nepdon.wire.net.NativeLoader;

/**
 * Live ESP state engine: recovers the session XOR mask, decodes the
 * own-player state and per-entity enemy states via LEB128 varint framing.
 *
 * <p>Wire format (verified 2026-09-10 on capture 131508, independently
 * cross-validated; supersedes all fixed-offset field maps):</p>
 * <ul>
 *   <li>Frame: b0 rolling counter, b1 7-bit nonce, b2-7 masked with the
 *       6-byte session XOR mask (recovered as the mode of OUT/24 b2-7,
 *       which decodes to all zeros there), b8-11 connection token,
 *       b12+ body = untagged LEB128 varint stream (plaintext).</li>
 *   <li><b>OUT 50-63 B keepalive</b> (~100 ms): exactly 23 varints.
 *       v0 = world X, v1 = world Z (height), v2 = world Y (smooth world
 *       coords, ~236 units/m), v3-v7 state values (capped 200),
 *       v8-v10 velocity/angle triple, v11 airborne flag, v12 state enum,
 *       v13/v14/v15 = float32 unit view direction vector (|v|=1.0 on
 *       3427/3427 packets — full signed yaw AND pitch:
 *       yaw = atan2(v13, v15), pitch = asin(v14)), v17/v18 flags,
 *       v22 = millisecond tick (+99/packet).</li>
 *   <li><b>IN 100-170 B entity blobs</b>: from b10, repeating entity
 *       records [id 1B][X varint][Z varint][Y varint][a][b][c][d]
 *       [6 zero bytes][next id]. No fixed 7-slot mux — a chain of N
 *       entities per packet.</li>
 *   <li><b>IN 39-41 B per-entity stream</b> (~5 Hz/entity): header
 *       b8-11 = 00 02 01 XX (XX = entity handle); exactly 20 varints
 *       from b12; v0/v1/v2 = X/Z/Y — same world frame as own state.</li>
 *   <li>Encrypted (NOT varints, ignore): OUT/24 input stream, IN/26/34/42/50
 *       with per-packet keystream (~50% varint parse failures).</li>
 * </ul>
 */
public final class EspEngine {

    // ---- session mask ----
    private final int[] maskCounts = new int[6 * 256];
    private int maskSamples = 0;
    private volatile int k2, k3, k4, k5, k6, k7;
    private volatile boolean maskValid = false;
    private volatile boolean maskLocked = false;

    // ---- own state (23-varint keepalive decode) ----
    private volatile double posX, posZ, posY;          // v0/v1/v2 world coords
    private volatile float dirX, dirY, dirZ;           // v13/v14/v15 float32 unit vector
    private volatile boolean airborne;                 // v11
    private volatile int stateEnum;                    // v12
    private volatile long tickMs;                      // v22
    /** Client's own RTT estimate in ms, straight from keepalive v18. */
    private volatile int rttMs = 0;
    /** Bezier-slide path position | slideId&lt;&lt;24 (v20). NOT a mount handle. */

    // ====================================================================
    // MOUNTED: THE KEEPALIVE STOPS, AND OWN STATE WITH IT
    // ====================================================================
    //
    // The game NEVER SENDS Cmd 2001 while the player is mounted.
    // PNCPKGJGBPP::EAIKJCCNGMJ dispatches on IsInVehicle() BEFORE it can ever
    // reach the 2001 branch: driver -> Cmd 2002, passenger who can fire ->
    // Cmd 2006, cannon -> Cmd 2031, IsOnChair() -> a different class again.
    // (docs/IL2CPP_FINDINGS.md; GhostFilter and TeleportFilter were already
    // fixed for this, the ESP never was.)
    //
    // Everything the projection needs -- our position AND our camera basis --
    // came from that one message, so mounting froze both at their last on-foot
    // values and the ESP and radar went silently wrong. That is the whole bug.
    //
    // The recovery needs no new protocol work: the server echoes our own
    // player back to us as an ordinary entity in the inbound chain, and we
    // already identify it (ownEntityId, ROLE_SELF). That echo carries position
    // AND the fixed-width pose bytes, so a camera basis can be rebuilt from
    // aimYaw/aimPitch.
    //
    // It is strictly worse than the 2001 path and is only used when 2001 is
    // silent:
    //   * yaw/pitch are quantised to 1.41 deg / 1.29 deg per LSB, against
    //     float32 on the keepalive;
    //   * it arrives on the 198 ms entity tick and is ~230 ms stale, against
    //     a 99 ms first-hand report;
    //   * while mounted the echoed entity tracks the VEHICLE, and the camera
    //     rig differs per vehicle (passenger 0.60/1.50/0.40, cannon
    //     0.40/1.15/0.25, catapult 0.40/6.00/-0.05, driver from GameVars) --
    //     the default on-foot boom is still applied, so expect a boom-shaped
    //     residual on top. See docs/GAME_CLIENT_CONSTANTS.md.
    //
    // Degraded and alive beats exact and blank.

    /** No 2001 for this long ⇒ mounted / on a chair. 2001 ticks every 99 ms. */
    private static final long OWN_STALE_MS = 400L;


    /**
     * True when the outbound keepalive has gone silent while the match is
     * still streaming — which the binary says means mounted, on a chair, or
     * in a cannon. There is no mount flag on the wire to read instead.
     */
    /**
     * Age of the freshest own-state sample, ms; -1 before the first.
     * Lets a caller choose its own staleness threshold instead of inheriting
     * {@code mounted()}'s 400 ms, which is tuned for "are we riding" and is far
     * too tight for "is the camera basis safe to aim from".
     */
    public long ownAgeMs() {
        return ownLastUpdateMs > 0L
                ? System.currentTimeMillis() - ownLastUpdateMs : -1L;
    }

    /** True when the server told us we are mounted (Cmd 125/126), no guessing. */
    public boolean mountedWire() { return mountedWire; }

    public boolean mounted() {
        // The server TELLS us, in Cmd 125 / Cmd 126. That is authoritative and
        // instant; the keepalive-silence test below is a 400 ms-late guess
        // that cannot distinguish mounting from a stalled uplink.
        //
        // They are OR'd rather than the wire flag simply winning, because the
        // flag can be missed: Cmd 125 is reliable and encrypted, so it is
        // invisible until the session key is recovered, and the engine can be
        // started mid-ride. Silence still implies mounted. Conversely a live
        // Cmd 2001 PROVES we are not mounted -- the game cannot send it while
        // in a vehicle -- so observeKeepalive clears the flag, which makes a
        // missed Cmd 126 self-correcting within one 99 ms tick.
        if (mountedWire) return true;
        return ownLastUpdateMs > 0L
                && System.currentTimeMillis() - ownLastUpdateMs > OWN_STALE_MS;
    }

    /** Server-confirmed mounted state, from Cmd 125 / Cmd 126. */
    private volatile boolean mountedWire = false;
    /** Vehicle object id we are riding, per Cmd 125; 0 when on foot. */

    /** Rider slot id for a vehicle object id, from Cmd 125/126. */
    private final Map<Integer, Integer> riderOfVehicle =
            new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();
    /** Vehicle object id a player slot is riding, from Cmd 125/126. */
    private final Map<Integer, Integer> vehicleOfRider =
            new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();

    /** Which player slot is riding this vehicle, or 0. */
    public int riderOfVehicle(int vehicleId) {
        Integer s = riderOfVehicle.get(Integer.valueOf(vehicleId));
        return s == null ? 0 : s.intValue();
    }

    /**
     * Feeds one <b>inbound</b> Cmd 2002 — the batched list of every visible
     * vehicle. A completely different message from the outbound Cmd 2002 that
     * reports our own vehicle state; only the command id is shared.
     *
     * <h3>Why this matters</h3>
     *
     * <p>A remote player who mounts does not disappear from the entity chain,
     * but their update rate roughly halves — measured presence 48.6–57.1 %
     * during a ride against 100 % for non-riders in the very same packets, and
     * 100 % for the same player ten seconds earlier on foot. This list is the
     * other half: it carries every visible vehicle at the full 198 ms tick, so
     * pairing a vehicle with its rider restores a smooth box on an enemy who
     * is driving. See {@code docs/MOUNTED_CAPTURE_ANALYSIS.md} Q2 and Q4.</p>
     *
     * <h3>Layout, measured exact on 592/592 packets</h3>
     *
     * <pre>
     * body  := listHdr:varint          ; recordCount &lt;&lt; 1, even in 592/592
     *          record x recordCount
     *          v12:varint  tick:varint
     * record := id:varint              ; VEHICLE id, NOT a PlayerID
     *           x,y,z:zigzag           ; millimetres
     *           qx,qy,qz,qw:zigzag     ; quaternion x1000
     *           op:RAW BYTE            ; bit 0x80 = occupied
     *           vx,vy,vz:zigzag
     * </pre>
     *
     * <p><b>Field 8 is a raw byte here too.</b> 19.4 % of inbound packets carry
     * a record with {@code op >= 0x80}, and a varint walk desyncs on exactly
     * those — which corrects {@code VEHICLE_DISPATCH_MAP.md} §9.6's claim that
     * it is "constant 8 in the inbound samples".</p>
     *
     * <p>{@code op & 0x80} means the vehicle has an occupant: set on 0 of 756
     * unoccupied records and 115 of 155 occupied ones. Perfect specificity,
     * ~74 % sensitivity, so it is safe to trust a positive and wrong to read
     * anything into a negative.</p>
     */
    public void observeVehicleList(byte[] body, int off, int len) {
        if (NativeBridge.DECODE_LEASH
                && !ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return;
        }
        if (body == null || off < 0 || len <= 0 || off + len > body.length) return;
        final int end = off + len;
        int[] p = new int[] { off };

        long hdr = vi(body, p, end);
        if (hdr <= 0 || (hdr & 1L) != 0L) return;      // always even; odd = mis-parse
        int count = (int) (hdr >> 1);
        if (count < 1 || count > 64) return;

        // PARSE EVERYTHING FIRST, APPLY NOTHING UNTIL THE WHOLE BODY VALIDATES.
        // A half-applied list would teleport some players and leave others,
        // which is exactly the kind of plausible-looking corruption that costs
        // a debugging session. Same rule VehicleSync follows for our own state.
        final long[] vx = new long[count];
        final long[] vy = new long[count];
        final long[] vz = new long[count];
        final int[] vid = new int[count];
        final boolean[] vocc = new boolean[count];

        for (int i = 0; i < count; i++) {
            long veh = vi(body, p, end);
            long x = zz(vi(body, p, end));
            long up = zz(vi(body, p, end));
            long z = zz(vi(body, p, end));
            long qx = zz(vi(body, p, end));
            long qy = zz(vi(body, p, end));
            long qz = zz(vi(body, p, end));
            long qw = zz(vi(body, p, end));
            if (p[0] >= end) return;
            int op = body[p[0]] & 0xFF;                // RAW BYTE -- see javadoc
            p[0]++;
            vi(body, p, end);                          // velocity x
            vi(body, p, end);                          // velocity up
            vi(body, p, end);                          // velocity z
            if (p[0] > end || veh <= 0) return;

            // The quaternion identity is the parse oracle: four components
            // scaled by 1000, so the squares sum to about 1e6. Map-plausible
            // coordinate bounds are deliberately NOT used -- that exact
            // mistake caused the documented phantom-entity bug.
            long norm = qx * qx + qy * qy + qz * qz + qw * qw;
            if (norm < 900000L || norm > 1100000L) return;

            vid[i] = (int) veh;
            vx[i] = x; vy[i] = up; vz[i] = z;
            vocc[i] = (op & 0x80) != 0;
        }
        // The body must end on v12 + tick and NOTHING else. This terminal
        // cursor check is the discriminator that catches a wrong field count
        // or a desynced raw-byte read; the quaternion passes either way.
        // vi() signals truncation by returning -1 AND parking the cursor at
        // end, so the cursor test alone would pass on a truncated read --
        // check the return as well.
        if (vi(body, p, end) < 0) return;              // v12
        if (vi(body, p, end) < 0) return;              // tick
        if (p[0] != end) return;

        long now = System.currentTimeMillis();
        int applied = 0;
        for (int i = 0; i < count; i++) {
            int rider = riderOfVehicle(vid[i]);
            if (rider <= 0) continue;                  // nobody known to be aboard
            if (ownEntityId > 0 && rider == ownEntityId) continue;  // 2002 is first-hand
            Track t = entities.get(Integer.valueOf(rider));
            if (t == null) continue;                   // never invent an entity from here

            // Seat offset is zero to within measurement: the server's own echo
            // of a mounted player lands within 25-324 mm of the vehicle root.
            // So the vehicle position IS the player position; no correction.
            updateTrack(rider, vx[i], vy[i], vz[i], now);
            t.inVehicle = true;
            t.vehicleId = vid[i];
            t.vehicleOccupied = vocc[i];
            applied++;
        }
        if (applied > 0) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.ESP_VEHICLE_LIST_APPLIED, applied);
        }
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_VEHICLE_LIST_PKT);
    }

    /**
     * Feeds one Cmd 125 {@code RUDP_GETON_VEHICLE} or Cmd 126
     * {@code RUDP_GETOFF_VEHICLE} body.
     *
     * <p>Layout, per the schema table and confirmed against live packets:
     * Cmd 125 is {@code U U B U B} and Cmd 126 is {@code U U U}; in both, field
     * 0 is the vehicle object id and field 1 is a <b>full PlayerID</b> with the
     * usual {@code (teamByte &lt;&lt; 24) | slot24} packing — e.g. 167772177 =
     * {@code 0x0A000011}, team 10 slot 17.</p>
     *
     * <p>Broadcast for every player, not just us, so this is also the only
     * authoritative statement of <b>which enemy is in which vehicle</b>.</p>
     *
     * @param on true for Cmd 125 (mounting), false for Cmd 126 (dismounting)
     */
    public void observeMount(byte[] body, int off, int len, boolean on) {
        if (body == null || off < 0 || len <= 0 || off + len > body.length) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_MOUNT_PARSE_BAD);
            return;
        }
        int end = off + len;
        int[] p = new int[] { off };
        long vehicleId = vi(body, p, end);
        long playerId = vi(body, p, end);
        if (p[0] > end || vehicleId <= 0 || playerId <= 0) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_MOUNT_PARSE_BAD);
            return;
        }
        // THE CHAIN ENTITY ID IS THE LOW BYTE, not the low 24 bits.
        //
        // The game's own unpacker (BHGGAEEHJCO::LMCLIKOCCJM @0x07c49ca4)
        // produces {slot24 = &0xFFFFFF, teamByte = >>>24, lowByte = &0xFF}, and
        // it is the LOW BYTE that the inbound-2001 handler compares against a
        // chain record id (@0x07a293b4, to skip its own echo). So &0xFF is the
        // join from any PlayerID -- Cmd 125/126, Cmd 101, Cmd 147 -- to an
        // entity in our map, and no Cmd 118 bind is needed for identity.
        //
        // Identical to &0xFFFFFF for every handle in the corpus (all slots are
        // well under 256), so this is a correctness fix with no observed
        // behaviour change: it only differs where the old reading would have
        // produced a value above 127 and been rejected outright.
        int slot = (int) (playerId & 0xFFL);
        int team = (int) (playerId >>> 24);
        // Same id space and the same validity bounds as the Cmd 2001 handle.
        if (slot < 1 || slot > 127 || !isValidTeam(team)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_MOUNT_PARSE_BAD);
            return;
        }
        // TEAM_SRC_HANDLE, not TEAM_SRC_BIND. This field is packed exactly like
        // the Cmd 2001 handle, so HANDLE is what it is; labelling it BIND would
        // let it outrank a genuine Cmd 118 and, since noteTeam creates a Track
        // for an unknown id, a mis-read here would manufacture an entity with
        // a wrong team. Friend/foe is the highest-stakes thing in this engine
        // -- take the conservative rung.
        noteTeam(slot, team, TEAM_SRC_HANDLE);

        int veh = (int) vehicleId;
        Integer slotKey = Integer.valueOf(slot);
        Integer vehKey = Integer.valueOf(veh);
        if (on) {
            riderOfVehicle.put(vehKey, slotKey);
            vehicleOfRider.put(slotKey, vehKey);
        } else {
            vehicleOfRider.remove(slotKey);
            // Only clear the reverse entry if it still names this rider; a
            // dismount for a vehicle somebody else has since boarded must not
            // erase the new rider.
            Integer cur = riderOfVehicle.get(vehKey);
            if (cur != null && cur.intValue() == slot) riderOfVehicle.remove(vehKey);
        }

        if (ownEntityId > 0 && slot == ownEntityId) {
            mountedWire = on;
            com.nepdon.wire.diag.Diag.hit(on
                    ? com.nepdon.wire.diag.Slot.ESP_MOUNT_ON_SELF
                    : com.nepdon.wire.diag.Slot.ESP_MOUNT_OFF_SELF);
        } else {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_MOUNT_REMOTE);
        }
    }

    /**
     * Rebuilds own position and camera basis from our own entity echo.
     * Called only for the ROLE_SELF track, and only once 2001 has gone quiet.
     *
     * <p>Deliberately does NOT touch {@code ownLastUpdateMs} (that is the
     * mounted detector itself, and refreshing it here would make the state
     * flap) and does NOT call {@code pushOwnHistory} — the lag estimator
     * cross-correlates our REPORTED history against this very echo, so
     * seeding the history from the echo would correlate it with itself and
     * collapse the measurement to zero. With 2001 silent the history simply
     * ages out and the last good lag figure is held, which is correct.</p>
     */
    private void refreshOwnFromEcho(Track t, long now, boolean takePos, boolean takeDir) {
        if (t == null || !t.poseValid) return;
        if (takePos) {
            posX = t.x;
            posY = t.y;
            posZ = t.z;
            ownPosSrc = OWN_SRC_ECHO;
            ownStateMs = now;
        }
        if (takeDir) {
            // Same frame as the renderer's own fallback basis: yaw 0 = +Z(world)
            // increasing toward +X, pitch positive looks up.
            double yaw = Math.toRadians(t.aimYawDeg);
            double pit = Math.toRadians(t.aimPitchDeg);
            double cp = Math.cos(pit);
            // A basis rebuilt from a 1.41-deg quantised yaw on a 198 ms tick makes
            // a nonsense angular velocity. Refuse to extrapolate it.
            publishCamSample((float) (Math.sin(yaw) * cp), (float) Math.sin(pit),
                    (float) (Math.cos(yaw) * cp), now, false);
            ownDirSrc = OWN_SRC_ECHO;
        }
        if (!takePos && !takeDir) return;
        // There used to be an `ownEcho` boolean here meaning "some part of own
        // state is echo-derived", written on all three paths and READ BY
        // NOTHING - the HUD row its comments promised was never wired. The
        // Diag slot below carries the same fact through the channel this
        // project actually reads, so the flag is gone; ownPosSrc/ownDirSrc
        // still hold the finer answer (which PART is echo-derived).
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_OWN_FROM_ECHO);
    }

    // ---- own-state source precedence ----
    //
    // Position and direction resolve INDEPENDENTLY, because Cmd 2002 supplies
    // a position and no camera at all. A source may write only when every
    // better source has gone stale.
    //
    //   position:  2001 > 2069 > 2002 > self-echo
    //   direction: 2001 > 2069 > self-echo      (2002 has no aim; see below)
    //
    // Without this the echo (198 ms) and the vehicle carrier (99 ms) both
    // wrote position and fought each other, so own position alternated
    // between the vehicle root and the server's echo of the player body
    // several times a second.

    public static final int OWN_SRC_NONE = 0;
    public static final int OWN_SRC_2001 = 1;
    public static final int OWN_SRC_2069 = 2;
    public static final int OWN_SRC_2002 = 3;
    public static final int OWN_SRC_ECHO = 4;
    /** Camera approximated from the Cmd 2002 vehicle quaternion. Heading, not view. */
    public static final int OWN_SRC_2002Q = 5;

    /**
     * Use the vehicle's heading as the camera while mounted.
     *
     * <p>Default on, because the measured alternative is a camera frozen at
     * the moment of mounting — there is no player look direction on the wire
     * at all while riding. Turn it off to see the raw failure.</p>
     */
    public static volatile boolean MOUNT_VEHICLE_CAMERA = true;

    /** A vehicle carrier is considered live for this long after a record. */
    private static final long MOUNT_STALE_MS = 400L;

    private volatile int  ownPosSrc = OWN_SRC_NONE;
    private volatile int  ownDirSrc = OWN_SRC_NONE;
    private volatile long ownMs2069 = 0L;
    private volatile long ownMs2002 = 0L;

    private boolean mayWritePos(int src, long now) {
        if (src == OWN_SRC_2001) return true;
        if (now - ownLastUpdateMs <= OWN_STALE_MS) return false;
        if (src == OWN_SRC_2069) return true;
        if (now - ownMs2069 <= MOUNT_STALE_MS) return false;
        if (src == OWN_SRC_2002) return true;
        if (now - ownMs2002 <= MOUNT_STALE_MS) return false;
        return true;                       // echo, the last resort
    }

    /**
     * As {@link #mayWritePos}, but the 2002 rung supplies a vehicle HEADING
     * rather than a view, and it outranks the echo.
     *
     * <p>Both are ultimately the same physical quantity — the echo's mounted
     * aim field was measured to be the vehicle rotation re-encoded — but ours
     * is first-hand at 99 ms and exact, while the echo is a ~300 ms-lagged
     * copy quantised to 1.41°/LSB. Letting the echo write as well made the two
     * fight and added a visible judder to every bearing.</p>
     */
    private boolean mayWriteDir(int src, long now) {
        if (src == OWN_SRC_2001) return true;
        if (now - ownLastUpdateMs <= OWN_STALE_MS) return false;
        if (src == OWN_SRC_2069) return true;
        if (now - ownMs2069 <= MOUNT_STALE_MS) return false;
        if (src == OWN_SRC_2002 || src == OWN_SRC_2002Q) return true;
        if (now - ownMs2002q <= MOUNT_STALE_MS) return false;
        return true;                       // echo, the last resort
    }

    /** Wall-clock of the last camera written from the vehicle quaternion. */
    private volatile long ownMs2002q = 0L;

    /**
     * True when own position currently comes from the inbound entity chain
     * rather than from one of our own outbound reports.
     *
     * <p>The renderer applies an axis map to chain coordinates and not to own
     * coordinates, because the map exists precisely to bring the chain frame
     * into the own frame. An echo-derived own position has NOT been through
     * that map, so the renderer has to apply it there too or, with the map
     * turned on, our own position lands in a different frame from every
     * entity and all the distances are nonsense.</p>
     */
    public boolean ownPosChainFrame() { return ownPosSrc == OWN_SRC_ECHO; }

    private long ownLastUpdateMs = 0L;

    /**
     * Wall-clock of the last own-state refresh from ANY source.
     *
     * <p>Distinct from {@link #ownLastUpdateMs}, and the distinction is the
     * whole point. {@code ownLastUpdateMs} tracks Cmd 2001 SPECIFICALLY,
     * because its going silent is how {@link #mounted()} detects a vehicle --
     * so the mounted self-echo path must never advance it, or the detector
     * would immediately un-detect the thing it just detected.</p>
     *
     * <p>But the renderer needs the opposite question answered: "do I have
     * usable own state at all?" It was asking {@code ownLastUpdateMs}, which
     * by construction stops the moment you mount. With the renderer's own
     * 2000 ms staleness bail, the mounted ESP therefore survived about
     * <b>1.6 seconds</b> and then the entire overlay went dark -- the self-echo
     * fallback was working perfectly and being thrown away downstream.</p>
     *
     * <p>Same mistake as the match-boundary sentinel fixed on 2026-09-15: a
     * field given deliberate semantics in one place, with a reader elsewhere
     * that was never told. When a field's meaning is narrowed, grep its
     * readers -- every one of them.</p>
     */
    private volatile long ownStateMs = 0L;
    // Same three-thread exposure as the history ring above: written by the
    // TUN reader, read by the renderer for the calibration readout.
    private volatile int ownSamples = 0;
    private volatile int keepaliveParseOk = 0;
    private volatile int keepaliveParseBad = 0;

    /**
     * Our own squad index, from the keepalive handle's high field.
     *
     * <p><b>The high field is a SQUAD INDEX, not a binary own/enemy flag.</b>
     * Values 1..14 are observed and the value is assigned per match. The old
     * rule "type 1 = own team, 2 = enemy" was measured on four captures that
     * all happened to place us on team 1; where our own team is 2 the rule is
     * exactly INVERTED — teammates painted as enemies and our own self-echo
     * drawn as a target.</p>
     *
     * <p>The correct test is relational: an entity is a friend iff its team
     * index equals ours. Measured accuracy of that rule: <b>100.000% over
     * 87 585 samples across 10 sessions, 0 errors in either direction</b>,
     * against 88.3% (8 898 friends painted enemy, 918 enemies painted friend)
     * for the code this replaces.</p>
     */
    private volatile int ownTeam = 0;
    /** Wall-clock of the keepalive that set {@link #ownTeam}; 0 = never. */
    private volatile long ownTeamSeenMs = 0L;
    /** Wall-clock of the last match reset, the freshness datum for rung 0. */
    private volatile long lastMatchResetMs = 0L;

    // ---- enemy entities (dynamic, keyed by entity id) ----

    /**
     * The entity broadcast tick, measured at <b>198 ms</b> (5.05 Hz per
     * entity) over 49 246 inter-arrival samples across six sessions. It is a
     * hard tick, not a rate: inter-quartile range 2 ms, 75% of all gaps in a
     * single 25 ms bin, and the median is 198 ms at every concurrency level
     * from 1 to 8 live entities - cadence does NOT degrade with lobby size.
     * Every tracked entity arrives inside the same sub-millisecond burst, so
     * there is no per-entity staggering to exploit.
     */
    private static final long TICK_MS = 198L;

    /**
     * Velocity is the LATEST single-tick finite difference, deliberately
     * unsmoothed. Smoothing was measured and it HURTS - RMS error relative to
     * a naive hold: latest tick 0.574, EMA(0.6) 0.580, 2-tick mean 0.595,
     * EMA(0.4) 0.615. The tick is so regular that averaging only adds lag.
     * For visual smoothness, smooth the RENDERED position toward the
     * prediction; never the velocity.
     */
    private static final double VEL_GAIN = 1.0;

    /**
     * Accept a velocity sample only from a gap that is really one tick.
     * A wider window (the first version used 20-600 ms) silently mixes in
     * multi-tick gaps and dropout recoveries, whose straight-line chord says
     * little about the path actually taken.
     */
    private static final long VEL_DT_MIN_MS = 185L;
    private static final long VEL_DT_MAX_MS = 260L;

    /**
     * Absurdity guard only, in units/ms. 13 000 u/s = 13.7 m/s sits above
     * every one of 44 436 measured samples (max 11 938 u/s = 12.57 m/s).
     * <b>Do not clamp below this.</b> Clamping real speeds was measured and
     * every clamp made things worse: no clamp 1044 RMS, 8000 u/s 1082,
     * 6500 u/s 1098, 5500 u/s 1110, 4000 u/s 1140. Fast players are real.
     */
    private static final double VEL_MAX_U_PER_MS = 13.0;

    /**
     * A position jump beyond this is a teleport/respawn, not motion: zero the
     * velocity and re-seed rather than extrapolate from it. This is the exact
     * filter whose absence destroyed the correlation analysis twice.
     */
    private static final double VEL_TELEPORT_UNITS = 20000.0;

    /**
     * Server sub-tick in ms. The in-packet velocity field is displacement per
     * ONE of these, while a record interval is two of them - which is why the
     * measured ratio of field to per-record delta is exactly 0.500.
     */
    private static final double SUB_TICK_MS = 99.0;

    // ====================================================================
    // LIVE LAG MEASUREMENT (auto-LEAD)
    // ====================================================================
    //
    // How it is possible to measure your own ping from the game stream, with
    // no timing packet and no cooperation from the server:
    //
    // The server echoes OUR OWN player back to us as an ordinary entity
    // record. That echo is not a fresh reading - it is the position we
    // ourselves reported, after it travelled to the server, waited for the
    // next broadcast tick, and travelled back. We still have the original: we
    // sent it, so we know exactly what it was and exactly when we sent it.
    //
    // So keep a short history of our own reported positions, and when an echo
    // arrives, find WHICH of our past positions it matches. The age of that
    // history entry IS the round-trip staleness, in milliseconds, measured
    // end to end, including the tick wait. This is the same cross-correlation
    // used offline to establish 230 ms - just done incrementally, live.
    //
    // Two conditions make or break it:
    //   * We must be MOVING. If we stand still every history entry matches
    //     equally well and the lag is unidentifiable - the estimate would be
    //     pure noise. Samples taken while stationary are discarded.
    //   * The match must be unambiguous, so the best entry has to be a good
    //     fit in absolute terms, not merely the least bad.

    /** Own position history, ~10 Hz keepalives; 96 entries = ~9.6 s. */
    private static final int OWN_HIST = 96;
    private final long[] histMs = new long[OWN_HIST];
    private final double[] histX = new double[OWN_HIST];
    private final double[] histY = new double[OWN_HIST];
    // VOLATILE: WRITTEN ON THE TUN THREAD, READ AND RESET ON A RECV THREAD.
    //
    // pushOwnHistory runs at 10 Hz from observeKeepalive on the single TUN
    // reader; measureLag reads the same ring at ~5 Hz from a UDP recv thread,
    // and resetMatch zeroes both indices from a recv thread at every match
    // boundary. The index arithmetic stays in range for any int value, so
    // this cannot throw -- but a torn read correlates a lag sample against a
    // half-written entry, and AUTO_LEAD (on by default) feeds that straight
    // into LEAD_MS. Cheap to make correct; expensive to debug if left.
    private volatile int histCount = 0;
    private volatile int histHead = 0;

    /** Smoothed round-trip staleness in ms; 0 until enough samples. */
    private volatile double lagMs = 0;
    private volatile int lagSamples = 0;

    /** Plausible bounds for the measurement; outside this it is not a match. */
    private static final double LAG_MIN_MS = 40.0;
    private static final double LAG_MAX_MS = 900.0;

    /** The echo must land within this of a past position to count (~1 m). */
    private static final double LAG_MATCH_UNITS = 1.2 * EspEngine.UNITS_PER_METRE;

    /** Minimum path length over the history window to trust a match (~3 m). */
    private static final double LAG_MOVE_UNITS = 3.0 * EspEngine.UNITS_PER_METRE;

    /** EMA weight per accepted lag sample. */
    private static final double LAG_ALPHA = 0.12;

    private void pushOwnHistory(long now) {
        histMs[histHead] = now;
        histX[histHead] = posX;
        histY[histHead] = posY;
        histHead = (histHead + 1) % OWN_HIST;
        if (histCount < OWN_HIST) histCount++;
    }

    /**
     * Matches one self-echo position against our own history and folds the
     * implied lag into the running estimate.
     */
    private void measureLag(double ex, double ey, long now) {
        if (histCount < 8) return;

        // Require real movement across the window, else the match is
        // degenerate: standing still, every entry fits and the lag is
        // unidentifiable.
        double path = 0;
        int prev = -1;
        for (int k = 0; k < histCount; k++) {
            int i = (histHead - 1 - k + OWN_HIST * 2) % OWN_HIST;
            if (prev >= 0) {
                path += Math.hypot(histX[i] - histX[prev], histY[i] - histY[prev]);
            }
            prev = i;
        }
        if (path < LAG_MOVE_UNITS) return;

        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < histCount; k++) {
            int i = (histHead - 1 - k + OWN_HIST * 2) % OWN_HIST;
            double age = now - histMs[i];
            if (age < LAG_MIN_MS || age > LAG_MAX_MS) continue;
            double d = Math.hypot(histX[i] - ex, histY[i] - ey);
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best < 0 || bestD > LAG_MATCH_UNITS) return;

        double sample = now - histMs[best];
        if (sample < LAG_MIN_MS || sample > LAG_MAX_MS) return;
        if (lagSamples == 0) lagMs = sample;
        else lagMs += (sample - lagMs) * LAG_ALPHA;
        lagSamples++;
    }

    /**
     * Round-trip staleness in ms, or 0 when nothing is known yet.
     *
     * <p>Prefers our own self-echo cross-correlation, which measures the
     * quantity we actually care about end to end and was validated by two
     * independent methods agreeing within 30 ms. But that needs roughly three
     * metres of movement before it can identify a lag at all, so until it
     * converges we fall back on the game's own RTT estimate (keepalive v18),
     * which is available from the very first packet and needs no movement.</p>
     *
     * <p>They are not the same quantity and should not be averaged: in the
     * sessions where both were measured, v18 ran about 20 ms ABOVE the
     * self-echo figure (e.g. 254 vs 230). The fallback is therefore a good
     * starting estimate, not a replacement.</p>
     */
    public double measuredLagMs() {
        if (lagSamples >= 12) return lagMs;
        return (rttMs >= 5) ? rttMs : 0;
    }

    /** True when the lag figure came from the self-echo rather than v18. */
    public boolean lagFromEcho() { return lagSamples >= 12; }

    /** Full health in this mode (HP 200 / EP 200 loadout). */
    public static final int MAX_HP = 200;

    public static final int ROLE_UNKNOWN = 0;
    // ---- team-byte provenance, in ascending precedence ----
    //
    // All four sources carry the SAME physical quantity -- the team byte of
    // PlayerID = (team << 24) | slot -- so they cannot disagree. The ordering
    // exists so the earliest and most complete source wins, and so the HUD can
    // report which one answered. See docs/FRIEND_FOE_AUTHORITATIVE.md §3.2.
    public static final int TEAM_SRC_NONE   = 0;
    /** Cmd 2013/2018 handle events. */
    public static final int TEAM_SRC_HANDLE = 1;
    /** Cmd 2010 damage handles: only names people who have been shot. */
    public static final int TEAM_SRC_DAMAGE = 2;
    /** Cmd 101 PLAYER_JOIN + Cmd 107/139 killer handles. */
    public static final int TEAM_SRC_ROSTER = 3;
    /** Cmd 118 RUDP_BIND_PRI, type 1. Authoritative and earliest. */
    public static final int TEAM_SRC_BIND   = 4;

    /**
     * A team byte that can actually name a team.
     *
     * <p>The field is 8 bits wide. <b>0 means unassigned and 255 is a
     * sentinel</b>, and both are fatal if treated as teams: two entities
     * carrying 0 compare equal to each other and to an unset {@code ownTeam},
     * which paints strangers blue. The previous {@code 1..63} bound was also
     * wrong — 63 belongs to the 64-slot player bitmask packed at the other end
     * of the same word, not to the team field.</p>
     */
    private static boolean isValidTeam(int b) {
        return b >= 1 && b <= 254;
    }

    public static final int ROLE_SELF = 1;
    public static final int ROLE_TEAM = 2;
    public static final int ROLE_ENEMY = 3;

    /**
     * Game units per metre, in ZIGZAG-DECODED units. <b>Position is in
     * millimetres.</b>
     *
     * <p><b>This was 950 and that was wrong by 5.3%.</b> The 950 came from
     * measuring gravity at -9313 units/s^2 from airborne frames and dividing by
     * Earth's 9.81 m/s^2 — sound method, wrong assumption. The binary settles
     * it: the quantiser {@code message.KANJBNIANHC::COGCJKJPPHI} multiplies
     * each component by a literal {@code 1000.0f} before rounding
     * (see {@code docs/IL2CPP_FINDINGS.md} §5.1).</p>
     *
     * <p>The corollary is that Free Fire's gravity really is <b>-9.313
     * m/s^2</b>, a deliberate tuning value, which is entirely normal for a
     * mobile shooter. Our old figure was not noise — it was a faithful
     * measurement of a game that does not use Earth gravity.</p>
     *
     * <p>Everything derived in metres shrinks 5.3% as a result. The projection
     * is unaffected: it works on the delta/distance ratio, so a uniform scale
     * change cancels. Only displayed distances and radar ranges move, and they
     * move toward correct.</p>
     */
    public static final double UNITS_PER_METRE = 1000.0;

    /** Self-echo radius: the server's echo of us tracks within a few metres. */
    /** Teammate proximity radius. */
    // TEAM_UNITS / TEAM_FRAC DELETED with the proximity promotion they served.
    // Do not reintroduce a geometric teammate test: in Battle Royale the
    // statistic is INVERTED, because the entity stream is interest-managed and
    // "often nearby" identifies an enemy, not a squadmate. Full measurement in
    // docs/FRIEND_FOE_AUTHORITATIVE.md.
    private final Map<Integer, Track> entities = new ConcurrentHashMap<>();

    // ---- calibration readout ----
    private volatile String calibReadout = "ESP: waiting for mask...";

    public EspEngine() { }

    // ---- varint reader ----

    /**
     * Returns the index just past {@code count} LEB128 varints starting at
     * {@code i}, or -1 if the data runs out.
     *
     * <p><b>The body does not start at a fixed offset.</b> The message begins
     * with an object-handle varint at b8, and how many bytes that takes
     * depends on the value. Assuming it is always 4 bytes (so the body starts
     * at b12) holds only while the handle happens to be small. In a training
     * match the local handle is {@code 0x10000010} — id 16, type 0x10 — which
     * needs <b>five</b> bytes, so a fixed b12 starts reading in the MIDDLE of
     * the handle. The keepalive then decodes to 24 varints instead of 23, the
     * exact-count check rejects it, own position never updates, and the whole
     * ESP renders nothing at all. Measured: 0/190 keepalives accepted at the
     * fixed offset, <b>190/190</b> when the offset is derived — all 190 with a
     * unit direction vector and a clean ~99 ms tick.</p>
     */
    /** Reads one LEB128 varint at {@code i}; returns -1 when truncated. */
    private static long readVarint(byte[] data, int i, int end) {
        long v = 0; int sh = 0;
        while (i < end) {
            int c = data[i++] & 0xff;
            v |= (long) (c & 0x7f) << sh;
            if ((c & 0x80) == 0) return v;
            sh += 7;
            if (sh > 63) return -1;
        }
        return -1;
    }

    private static int skipVarints(byte[] data, int i, int end, int count) {
        for (int k = 0; k < count; k++) {
            int sh = 0;
            boolean done = false;
            while (i < end) {
                int c = data[i++] & 0xff;
                if ((c & 0x80) == 0) { done = true; break; }
                sh += 7;
                if (sh > 63) return -1;
            }
            if (!done) return -1;
        }
        return i;
    }

    /** Parses untagged LEB128 varints from data[off..off+end). Returns the
     *  values or null when truncated mid-varint. */
    private static long[] varints(byte[] data, int off, int end) {
        long[] tmp = new long[32];
        int n = 0;
        int i = off;
        while (i < end) {
            long v = 0;
            int s = 0;
            boolean done = false;
            while (i < end) {
                int c = data[i++] & 0xff;
                v |= (long) (c & 0x7f) << s;
                s += 7;
                if ((c & 0x80) == 0) { done = true; break; }
                if (s > 63) return null;   // malformed
            }
            if (!done) return null;
            if (n == tmp.length) {
                long[] t = new long[tmp.length * 2];
                System.arraycopy(tmp, 0, t, 0, n);
                tmp = t;
            }
            tmp[n++] = v;
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * Zigzag decode (protobuf {@code sint32}): {@code (v>>>1) ^ -(v&1)}.
     *
     * <p>The body's numeric fields are signed and zigzag-encoded — reading
     * them raw mirrors the world on whichever axis the value is negative,
     * which is what put the ESP boxes in the wrong place. Proof:
     * {@code |zz(v3),zz(v4),zz(v5)|} = 100.000 on <b>100%</b> of keepalives
     * in both full matches (p1 99.37, p50 100.00, p99 100.65) — a unit
     * vector scaled by 100; nothing else explains that. And in a fast
     * movement window the velocity triple {@code zz(v8,v9,v10)} =
     * (-57, 0, +35) matches the zigzag position delta (-55, +36) directly.
     *
     * <p>Effect on the ESP: fraction of nearby enemies within 30 deg of the
     * view axis (chance 0.167) went 0.226 -> 0.438 (capture 155351) and
     * 0.341 -> 0.480 (161940) purely from decoding zigzag.
     *
     * <p>NOT every field is zigzag: {@code v13/v14/v15} are raw float32 bit
     * patterns and {@code v22} is a plain monotone ms counter. Encoding is
     * per-field, so never apply this blindly.
     */
    private static long zz(long v) {
        return com.nepdon.wire.util.ZigZag.decode(v);
    }

    /** Bit-exact uint32 -> float32 conversion. */
    private static float f32(long u) {
        return Float.intBitsToFloat((int) (u & 0xffffffffL));
    }

    // ---- mask recovery (fed by every OUT/24 packet) ----

    /** Feeds one outbound 24B input-stream packet (the mask oracle). */
    public void observeInputStream(byte[] data, int off) {
        if (maskLocked) return;
        if (off < 0 || off + 24 > data.length) return;
        for (int i = 0; i < 6; i++) {
            int b = data[off + 2 + i] & 0xff;
            maskCounts[i * 256 + b]++;
        }
        maskSamples++;
        if (maskSamples >= 96) {
            evaluateMask();
        }
    }

    private void evaluateMask() {
        int[] k = new int[6];
        boolean strong = true;
        for (int i = 0; i < 6; i++) {
            int best = 0, bestCount = 0, total = 0;
            for (int v = 0; v < 256; v++) {
                int c = maskCounts[i * 256 + v];
                total += c;
                if (c > bestCount) { bestCount = c; best = v; }
            }
            k[i] = best;
            if (total == 0 || bestCount * 100 < total * 90) strong = false;
        }
        if (strong) {
            k2 = k[0]; k3 = k[1]; k4 = k[2]; k5 = k[3]; k6 = k[4]; k7 = k[5];
            maskValid = true;
            if (maskSamples >= 288) {
                maskLocked = true;
            }
        }
    }

    /**
     * Validation hook (small IN/11-13). The decoded b2-7 window holds
     * [flags16][length16][channel16] rotated by a per-session constant,
     * where length16 = UDP payload length + 8. So scan b2-7 for the
     * length low byte = plen + 8; a hit validates the mask.
     */
    public void observeSmallInbound(byte[] data, int off, int plen) {
        if (!maskValid || plen < 11 || plen > 13) return;
        if (off < 0 || off + plen > data.length) return;
        int want = (plen + 8) & 0xff;
        int[] kk = {k2, k3, k4, k5, k6, k7};
        for (int j = 2; j < 8; j++) {
            if (((data[off + j] & 0xff) ^ kk[j - 2]) == want) {
                if (!maskLocked && maskSamples >= 288) {
                    maskLocked = true;
                }
                return;   // one hit is enough validation evidence
            }
        }
    }

    // ---- own state (OUT 50-63 B keepalive, 23 varints) ----

    public void observeKeepalive(byte[] data, int off, int plen) {
        if (NativeBridge.DECODE_LEASH
                && !ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return;
        }
        // Keepalive v-index map ("the combinations of v"). Baked defaults, OR —
        // when the server leash is armed — the indices come from the server decode
        // profile, so a cracked offline client (no profile) has no index map.
        // Parity-checked equal to these baked values (tools/verify_decode_maps.py),
        // so both paths decode identically; the walk logic is unchanged.
        final boolean kaSrv = NativeBridge.DECODE_LEASH && ConfigStore.keepaliveMapArmed();
        final int vPosX = kaSrv ? ConfigStore.kaPosX() : 0;
        final int vPosZ = kaSrv ? ConfigStore.kaPosZ() : 1;
        final int vPosY = kaSrv ? ConfigStore.kaPosY() : 2;
        final int vAir  = kaSrv ? ConfigStore.kaAirborne() : 11;
        final int vPose = kaSrv ? ConfigStore.kaPose() : 12;
        final int vCamX = kaSrv ? ConfigStore.kaCamX() : 13;
        final int vCamY = kaSrv ? ConfigStore.kaCamY() : 14;
        final int vCamZ = kaSrv ? ConfigStore.kaCamZ() : 15;
        final int vRtt  = kaSrv ? ConfigStore.kaRtt() : 18;
        final int vTick = kaSrv ? ConfigStore.kaTick() : 22;
        final int nBody = kaSrv ? ConfigStore.kaBodyVarints() : 23;
        if (plen < 32 || off < 0 || off + plen > data.length) return;
        int end = off + plen;
        // Body starts after the leading object-handle varint, NOT at a fixed
        // b12 - see skipVarints. A 5-byte handle is normal in some modes.
        int bodyStart = skipVarints(data, off + 8, end, 1);
        if (bodyStart < 0) {
            keepaliveParseBad++;
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_KEEPALIVE_BAD);
            return;
        }
        long[] v = varints(data, bodyStart, end);
        if (v == null || v.length != nBody) {
            keepaliveParseBad++;
            return;
        }
        keepaliveParseOk++;
        // Our own entity id comes from the decoded handle (type<<24)|id. The
        // old "<= 8" bound was the Clash Squad roster again: own ids of 11,
        // 12, 16 and 45 all occur in real sessions, and outside 1-8 our own
        // entity was never identified, so the self-echo was drawn as an enemy.
        long handle = readVarint(data, off + 8, end);
        int oid = (int) (handle & 0xFFFFFFL);
        if (oid >= 1 && oid <= 127) ownEntityId = oid;
        int otm = (int) (handle >>> 24);
        long now = System.currentTimeMillis();
        // 1..254: the field is a full byte. 0 is unassigned, 255 a sentinel.
        // The old <= 63 bound came from the 64-slot player bitmask packed at
        // the other end of the same word and silently rejected every team
        // above 63 -- the measured BR match had own team 9, but squads run to
        // at least 15.
        if (isValidTeam(otm)) {
            ownTeam = otm;
            ownTeamSeenMs = now;
        }
        posX = (double) zz(v[vPosX]);
        posZ = (double) zz(v[vPosZ]);
        posY = (double) zz(v[vPosY]);
        airborne = v[vAir] != 0;
        stateEnum = (int) v[vPose];
        // Camera-forward angular velocity: measure the rotation from the
        // PREVIOUS forward sample to this one BEFORE overwriting it, so the
        // renderer can lead the view basis during a turn (see extrapolatedDir).
        float ndx = f32(v[vCamX]), ndy = f32(v[vCamY]), ndz = f32(v[vCamZ]);
        publishCamSample(ndx, ndy, ndz, now, true);
        tickMs = v[vTick];
        // v18 is the client's own RTT estimate in milliseconds. Verified
        // against the 1 Hz outbound probe and its inbound echo across seven
        // sessions: measured RTT p50 vs v18 p50 agreed to 1-18 ms every time
        // (254/254, 283/287, 263/267, 282/287, 239/250, 276/281, 267/280).
        // Per-sample correlation is weak (0.07-0.46) because it is smoothed
        // on a different cadence, so treat it as a distribution, not a
        // per-packet truth.
        long r = v[vRtt];
        if (r >= 5 && r <= 2000) rttMs = (int) r;
        // RETRACTED 2026-09-13: v20 is NOT a mount handle. It is a bezier-slide
        // path position packed with a slide id in the top byte
        // (docs/IL2CPP_FINDINGS.md, outbound 2001 layout). The old reading was
        // self-contradicting anyway: this message is NEVER SENT while mounted,
        // so a mount field inside it could never have been non-zero for that
        // reason. Mounted state is detected by this keepalive going silent --
        // see mounted().
        pushOwnHistory(now);
        ownLastUpdateMs = now;
        ownStateMs = now;
        ownPosSrc = OWN_SRC_2001;
        ownDirSrc = OWN_SRC_2001;
        // The game cannot send Cmd 2001 while mounted, so receiving one is
        // proof we are not. This makes a missed or undecryptable Cmd 126
        // self-correcting within a single 99 ms tick, which matters because
        // Cmd 126 is reliable and therefore invisible before the session key
        // is recovered.
        if (mountedWire) {
            mountedWire = false;
        }
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_OWN_FROM_2001);
        ownSamples++;
        if (ownSamples % 10 == 0) updateCalibReadout();
    }

    // ---- enemy entities: IN 100-170 B blob chains ----

    /**
     * Feeds one IN entity-chain packet (family: b8 == 0, b9 = 2 x record
     * count). Both key bytes sit OUTSIDE the masked b2-7 window, which is
     * cyclically rotated per session — never identify this family from b2-7.
     *
     * <p><b>Record splitting (capture 155351, 5318 chain packets).</b> The
     * body from b10 is one flat LEB128 varint stream. Records are found by
     * splitting that stream at every index i where</p>
     * <pre>  v[i] in 1..8   AND   v[i+1] &gt;= 10000   AND   v[i+2] &gt;= 500</pre>
     * <p>i.e. [id][X][Z]. This reproduces b9/2 records exactly on
     * <b>5262/5317 packets (99.0%)</b> with zero duplicate ids.</p>
     *
     * <p>The previous byte-level scanner failed badly because it gated on
     * <i>absolute</i> coordinate ranges (X &le; 1.3e6, Z &le; 65535). Those
     * bounds are map-specific: this map reaches X 1 394 820 and Z 166 214, so
     * genuine record starts were rejected, records merged, and every field
     * after the miss was garbage. That produced ~60 phantom entity ids
     * (128, 130, 132 … 254 — all mid-varint continuation bytes read as ids)
     * and million-unit position jumps: the "boxes move but land nowhere near
     * the enemy" symptom. Splitting the decoded varint stream needs no upper
     * bounds at all.</p>
     *
     * <p>Record layout: [id][X][Z][Y][trailing state fields]. Single-record
     * packets carry 20 trailing fields; multi-record chains carry ~8-10.
     * Only X/Z/Y are consumed here.</p>
     */
    /**
     * Largest entity id accepted as a record start.
     *
     * <p><b>This was 8 and it silently broke every mode except Clash Squad.</b>
     * 8 is the 4v4 roster; Battle Royale / survival lobbies assign ids well
     * past it. Measured on survival capture 20260910_221243 (1639 chain
     * packets), sweeping the ceiling:</p>
     *
     * <pre>
     *   ceiling   exact record split   records recovered   max id seen
     *       8            0.0%                14.7%              6
     *      16            0.8%                18.0%             13
     *      32            0.8%                33.1%             26
     *      64           94.8%                98.1%             64
     *     127           94.9%                98.1%            123
     * </pre>
     *
     * <p>64 is the knee: it covers a 50-player lobby with headroom while
     * keeping the id test tight enough to anchor the record splitter. Raising
     * it to 127 buys 0.1% and admits three-figure ids as false starts.
     * Regression on Clash Squad captures: 100.0% -> 99.8% on 20260908_205830,
     * and 0.0% -> 100.0% on 20260908_105542 — no meaningful loss, large
     * gain.</p>
     */
    private static final int MAX_ENTITY_ID = 64;
    // NOTE: kept only as the upper bound of ID_CEILINGS. The live ceiling is
    // chosen per packet by the adaptive split in observeChainPacket — a fixed
    // value regresses some sessions whichever value is picked.

    /** Upper bound on declared records per chain (b9/2). */
    private static final int MAX_CHAIN_RECORDS = 64;

    /** Bytes before the first record (b0..b9). */
    private static final int CHAIN_HEADER = 10;

    /**
     * Minimum bytes per declared record, and the ONLY thing separating a real
     * entity chain from the encrypted transport.
     *
     * <p>4 was the naive arithmetic floor (an id plus three 1-byte varints)
     * and it was far too loose. The ECB-encrypted "channelled" stream uses
     * {@code (b8, b9)} as a CHANNEL ID, and in some sessions that channel id
     * happens to be {@code (0, 6)} — which is exactly the shape of a 3-record
     * chain. In capture 173746 that let <b>6 490 ciphertext packets</b> in:
     * only 41.1% of "chains" split correctly, and worse, the parser emitted
     * <b>522 phantom records whose ids collided with real players 4, 7 and
     * 8</b>, with X values up to 1.06e12. It was corrupting live tracks, not
     * merely scoring badly.</p>
     *
     * <p>Real records measure <b>19.29 bytes each</b> at the floor across all
     * eight sessions; the encrypted cluster sits at a median of 5.33. 16
     * leaves ~20% margin and is selective rather than blunt: it rejects 91.1%
     * of 173746's collision set but <b>0.0%</b> in five of the other six
     * sessions. (Rejecting every {@code 10+8k}-length packet outright would
     * throw away 455/844/838/343 genuine chains in other sessions.)</p>
     *
     * <p>Three independent proofs those packets are ciphertext: lengths are
     * exactly {@code 10+8k}; varint validity is <b>50.0%</b> (3247/6490 — a
     * coin flip, since a random stream parses iff its last byte has bit 7
     * clear); and only <b>835 distinct first 8-byte blocks across 6 490
     * packets</b> (top one repeated 296x) against 2321/3812 for real chains.</p>
     */
    private static final int MIN_RECORD_BYTES = 16;

    /**
     * Minimum X for a record start.
     *
     * <p>Was 10 000, which silently deleted a whole phase of the match: in the
     * pre-drop aeroplane phase of a survival game the entire lobby sits near
     * the map origin at X ~ 4 000, so every real record was rejected. A
     * failing packet dumps as an unambiguous stride —
     * {@code [id=44][X=4399][Z=2800320][Y=4212]}, {@code [id=41][X=4693]},
     * {@code [id=46][X=16572]} — with an identical Z for every player
     * (~1 474 m altitude), i.e. the plane.</p>
     *
     * <p>Chosen on a physical metric, not on split rate: implied teleports
     * over 100 m, summed across all eight sessions — 10000 -&gt; 109,
     * 1000 -&gt; 123, <b>500 -&gt; 139</b>, 200 -&gt; 165, 100 -&gt; 274,
     * 0 -&gt; 1243. Clean knee at 500.</p>
     */
    private static final long MIN_RECORD_X = 500L;

    /**
     * Longest packet still considered a chain. Survival chains reach ~630 B
     * against Clash Squad's ~190 B, so the old 400 B ceiling clipped the
     * biggest (most useful) updates in a full lobby.
     */
    private static final int CHAIN_MAX_LEN = 1400;

    // ---- phase-4b: native-decode shadow compare (NativeBridge.NATIVE_SHADOW) --
    // Reused DIRECT buffers for the zero-copy JNI hand-off, and a snapshot of the
    // Java structural parse for the field-for-field compare. Allocated lazily on
    // the first shadowed packet, touched only on the single packet thread, and —
    // being gated by a compile-time-constant false flag — never even reachable in
    // a release build until the flag is flipped. shadowIn holds a copy of the
    // packet (native reads a direct buffer, not a byte[]); shadowOut receives the
    // serialized records (u16 count + count * CHAIN_RECORD_WIRE bytes).
    private ByteBuffer shadowIn;
    private ByteBuffer shadowOut;
    private final int[] sjId = new int[MAX_CHAIN_RECORDS];
    private final long[] sjX = new long[MAX_CHAIN_RECORDS];
    private final long[] sjZ = new long[MAX_CHAIN_RECORDS];
    private final long[] sjY = new long[MAX_CHAIN_RECORDS];
    private final int[] sjBy = new int[MAX_CHAIN_RECORDS];
    private final int[] sjAy = new int[MAX_CHAIN_RECORDS];
    private final int[] sjAp = new int[MAX_CHAIN_RECORDS];
    private final boolean[] sjSelf = new boolean[MAX_CHAIN_RECORDS];
    /** Records the Java parse committed for the last structurally parsed chain, or -1. */
    private int sjCount = -1;

    public void observeChainPacket(byte[] data, int off, int plen) {
        // Server-gated decode leash: no live profile => decode nothing (the crack
        // fix). Compile-time-false by default, so R8 strips this from release.
        if (NativeBridge.DECODE_LEASH
                && !ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return;
        }
        // Chain split thresholds: baked defaults, OR server-sourced when the leash
        // is armed (parity-proven equal — tools/verify_decode_maps.py), symmetric
        // with the keepalive v-map + native chain decoder. ID_CEILINGS/Z_FLOORS/
        // CHAIN_HEADER stay local (not carried in the profile).
        final boolean chSrv = NativeBridge.DECODE_LEASH && ConfigStore.chainMaxEntityId() > 0;
        final long chMinRecordX = chSrv ? ConfigStore.chainMinRecordX() : MIN_RECORD_X;
        final int chMaxEntityId = chSrv ? (int) ConfigStore.chainMaxEntityId() : MAX_ENTITY_ID;
        final int chMinRecBytes = chSrv ? ConfigStore.chainMinRecordBytes() : MIN_RECORD_BYTES;
        final int chMaxRecords = chSrv ? ConfigStore.chainMaxRecords() : MAX_CHAIN_RECORDS;
        final int chMaxLen = chSrv ? ConfigStore.chainMaxLen() : CHAIN_MAX_LEN;
        if (plen < 20 || plen > chMaxLen) return;
        if (off < 0 || off + plen > data.length) return;
        if ((data[off + 8] & 0xff) != 0x00) return;
        int b9 = data[off + 9] & 0xff;
        if ((b9 & 1) != 0) return;
        int declared = b9 / 2;
        if (declared < 1 || declared > chMaxRecords) return;
        // Plausibility, replacing the old hard "<= 8 records" cap: a record
        // is at minimum an id plus three 1-byte varints, so a packet that
        // cannot physically hold the count it declares is not a chain at all.
        // Several small (26-42 B) packets in every session have b8 == 0 and a
        // b9 that would imply 100+ records; this rejects them on arithmetic
        // instead of on a roster size that only ever fit Clash Squad.
        if (plen < CHAIN_HEADER + declared * chMinRecBytes) return;

        // Records start after the two leading varints (the zero handle and the
        // record count), not at a fixed b10. Identical for the common case
        // where both are single bytes, correct when they are not - b9 == 0x80
        // (64 records) is a legal two-byte count and would have shifted the
        // whole parse by one byte.
        int recStart = skipVarints(data, off + 8, off + plen, 2);
        if (recStart < 0) return;
        long now = System.currentTimeMillis();

        // ---- MATCH BOUNDARY ----
        // Two signals, and BOTH are needed. A handle change alone is not
        // enough: five sessions in the corpus all run team 1 / id 1, so
        // consecutive matches are indistinguishable by handle. Silence alone
        // is not enough either, because a long stall can happen within a
        // match. Measured over 62 back-to-back pairs: no reset leaves 33 323
        // friend-painted-red samples; handle-change only leaves 13 959;
        // handle-change OR 30 s silence leaves ZERO.
        // "We do not know who we are" MUST encode as 0, because 0 is what the
        // guard below tests for.
        //
        // REGRESSION, 2026-09-13 (commit 0873617), fixed 2026-09-15. That
        // commit made resetMatch() wipe identity with the sentinel
        // ownEntityId = -1 -- correct in itself -- but this expression masked
        // it: -1 & 0xFFFFFF == 0x00FFFFFF, which is NOT zero. So a wiped
        // identity did not read as "unknown", it read as A DIFFERENT MATCH.
        // The next chain packet therefore reset again, which wiped identity
        // again, which looked like another new match: a self-sustaining loop
        // clearing entities/pri/roster about ten times a second, with rung 0
        // suppressing classification throughout.
        //
        // It escaped BR because the loop can only break on a tick that carries
        // exactly ONE chain packet, and BR's interest-managed stream produces
        // those 49.7% of the time (median 0.6 s apart). Clash Squad replicates
        // all eight players every tick in a rigid cadence, so lone-chain ticks
        // are 1.4% (median 17.4 s). Same bug, 29x worse recovery: measured p90
        // blackout 24.15 s in CS against 2.27 s in BR, and 10% of CS boundaries
        // never recovered at all. One capture logged 789 resets in 109 s.
        //
        // The lesson: a sentinel only works if EVERY reader agrees on it. This
        // one was invented in resetMatch() and never taught to its reader.
        final long ownHandleNow = (isValidTeam(ownTeam) && ownEntityId > 0)
                ? (((long) ownTeam << 24) | (ownEntityId & 0xFFFFFFL))
                : 0L;
        boolean newByHandle = (matchHandle != 0 && ownHandleNow != 0
                && ownHandleNow != matchHandle);
        boolean newBySilence = (lastChainMs != 0 && now - lastChainMs > MATCH_GAP_MS);
        if (newByHandle || newBySilence) {
            resetMatch(now);
        }
        if (firstChainMs == 0) firstChainMs = now;
        if (ownHandleNow != 0) matchHandle = ownHandleNow;
        lastChainMs = now;
        // ---- STRUCTURAL PARSE (preferred) ----
        // The remote-player record is NOT a pure varint run. Bytes 7, 8 and 9
        // are FIXED WIDTH and must not be varint-decoded:
        //
        //   varint [0..6]  id, X, height, Y, vX, vHeight, vY
        //   byte   [7]     body / leg yaw      u8
        //   byte   [8]     aim / head yaw      u8
        //   byte   [9]     aim pitch           int8
        //   varint [10..13] mode, state, reserved, reserved
        //
        // The giveaway was byte accounting: the trailing region is invariably
        // 7 bytes wide while the varint count read out of it varies from 5 to
        // 7, which a varint run cannot do but a fixed block can. Those bytes
        // frequently decode as NON-CANONICAL (zero-padded) varints, which the
        // old scanner silently accepted. The self-echo record is different
        // again: 20 plain varints and no byte block.
        //
        // This parse hits 99.77-100% of chain packets across ten sessions and,
        // unlike the heuristic scanner, never invents phantom entity ids (the
        // scanner produced up to 40 per session).
        // THE BEST LEADING INDICATOR IN THE CODEBASE.
        //
        // The structural parse is exact; the fallback is a shape heuristic. The
        // ratio between them degrades before any user-visible symptom appears,
        // and it moves for reasons that have nothing to do with parsing: during
        // the Clash Squad reset loop it fell from 96.3% to 59.4% purely as a
        // side effect, because the entity map kept being cleared underneath it.
        // A drop here is the earliest warning the system produces.
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CHAIN_PKT);
        if (parseStructured(data, recStart, off + plen, declared, now, chMinRecordX, chMaxEntityId)) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.ESP_CHAIN_PARSE_STRUCTURED);
            // Phase-4b: validate the native decoder against the Java parse we
            // just committed. Java remains authoritative; this only counts.
            if (NativeBridge.NATIVE_SHADOW) {
                shadowCompareChain(data, off, plen, now);
            }
            return;
        }
        com.nepdon.wire.diag.Diag.hit(
                com.nepdon.wire.diag.Slot.ESP_CHAIN_PARSE_FALLBACK);

        // ---- fallback: the heuristic scanner ----
        long[] v = varints(data, recStart, off + plen);
        if (v == null || v.length < 4) return;

        // ---- ADAPTIVE RECORD SPLIT ----
        // Two knobs decide where records start: the id ceiling and the Z
        // floor. Neither has one right value.
        //
        // A FIXED id ceiling is wrong in both directions. 8 (the Clash Squad
        // roster) found nothing in survival, and in Clash session 183716 —
        // where the server happened to assign ids past 8 — it scored 0.0%.
        // But simply raising it to 64 regressed Clash session 161940 from
        // 99.4% to 87.8%, because a loose ceiling admits false record starts.
        // The ceiling is a property of the SESSION's id assignment, not of
        // the game mode, so it cannot be a constant.
        //
        // So: try each (ceiling, Z floor) from tightest to loosest and keep
        // the first that reproduces the b9 record count exactly. b9 is a
        // free, per-packet oracle for the right answer — use it instead of
        // guessing. Tightest-first matters: a loose pair can also hit the
        // count, by counting a false start and missing a real one.
        //
        // Measured exact-split rate (n = 30 171 chain packets, 8 sessions):
        //
        //   session            id<=8    id<=64   ADAPTIVE
        //   CLASH 150701       100.0%   100.0%    100.0%
        //   CLASH 155351        99.0%    99.1%     99.1%
        //   CLASH 161940        99.4%    87.8%     99.4%
        //   CLASH 173746        40.7%    39.6%     42.2%   (see below)
        //   CLASH 183716         0.0%   100.0%    100.0%
        //   CLASH 205046       100.0%   100.0%    100.0%
        //   SURVIVAL 222258      0.0%    73.1%     73.1%
        //   SURVIVAL 221243      0.0%    94.8%     94.8%
        //
        // Adaptive is best-or-equal on every session. Session 173746 is poor
        // under ALL settings, so its failure is something else entirely and
        // is tracked separately — do not tune the ceiling against it.
        int idMax = 0, zmin = 0;
        outer:
        for (int c = 0; c < ID_CEILINGS.length; c++) {
            for (int zi = 0; zi < Z_FLOORS.length; zi++) {
                if (countStarts(v, Z_FLOORS[zi], ID_CEILINGS[c], chMinRecordX) == declared) {
                    idMax = ID_CEILINGS[c];
                    zmin = Z_FLOORS[zi];
                    break outer;
                }
            }
        }
        if (idMax == 0) {
            // Nothing reproduced the count. Fall back to the loosest pair and
            // take what we can; a partial chain beats no chain at all.
            idMax = ID_CEILINGS[ID_CEILINGS.length - 1];
            zmin = Z_FLOORS[0];
        }

        int found = 0;
        for (int i = 0; i + 3 < v.length; i++) {
            if (v[i] < 1 || v[i] > idMax) continue;
            if (v[i + 1] < chMinRecordX) continue;
            if (v[i + 2] < zmin) continue;
            // Split on the RAW stream (the X/Z gates are calibrated on raw
            // values), but store zigzag-decoded coords.
            updateTrack((int) v[i], zz(v[i + 1]), zz(v[i + 2]), zz(v[i + 3]), now);
            // The server's echo of our own player is the lag oracle.
            if (ownEntityId > 0 && (int) v[i] == ownEntityId) {
                measureLag(zz(v[i + 1]), zz(v[i + 3]), now);
            }
            // Seed from the server velocity triple when this record carries
            // one and we have no measured velocity yet (first sighting).
            if (i + 6 < v.length) {
                Track seeded = entities.get((int) v[i]);
                if (seeded != null && !seeded.velValid) {
                    seedVelocity(seeded, v[i + 4], v[i + 5], v[i + 6]);
                }
            }
            if (++found >= declared) break;
        }
    }

    /**
     * Phase-4b native-decode validation. Runs the Rust Cmd-2012 decoder over the
     * same packet and compares its records field-for-field against the Java parse
     * captured in {@link #parseStructured}. Java stays authoritative — this only
     * moves the {@code ESP_CHAIN_NATIVE_*} counters, so a live match can prove
     * parity (MATCH 100% / DIVERGE 0%) before the native path is ever trusted.
     * Buffers are reused (no steady-state allocation) and {@code chainDecode}
     * returns codes rather than throwing, so nothing crosses the JNI boundary as
     * an exception. Runs only on the single packet thread.
     */
    private void shadowCompareChain(byte[] data, int off, int plen, long now) {
        if (!NativeLoader.isReady() || sjCount < 0) { sjCount = -1; return; }
        // The server leash is denominated in unix SECONDS (ConfigStore profile).
        long nowSec = now / 1000L;
        if (!ConfigStore.decodeArmed(nowSec)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CHAIN_NATIVE_UNARMED);
            sjCount = -1;
            return;
        }
        if (shadowIn == null) {
            shadowIn = ByteBuffer.allocateDirect(CHAIN_MAX_LEN);
            shadowOut = ByteBuffer
                    .allocateDirect(2 + MAX_CHAIN_RECORDS * NativeBridge.CHAIN_RECORD_WIRE)
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
        if (plen > shadowIn.capacity()) { sjCount = -1; return; }
        shadowIn.clear();
        shadowIn.put(data, off, plen);
        int n = NativeBridge.chainDecode(
                shadowIn, 0, plen, ownEntityId,
                ConfigStore.chainMaxLen(), ConfigStore.chainMaxRecords(),
                ConfigStore.chainMinRecordBytes(), ConfigStore.chainMinRecordX(),
                ConfigStore.chainMaxEntityId(), ConfigStore.decodeValidUntil(), nowSec,
                shadowOut);
        int slot;
        if (n < 0) {
            slot = com.nepdon.wire.diag.Slot.ESP_CHAIN_NATIVE_REFUSED;
        } else if (compareShadow(n)) {
            slot = com.nepdon.wire.diag.Slot.ESP_CHAIN_NATIVE_MATCH;
        } else {
            slot = com.nepdon.wire.diag.Slot.ESP_CHAIN_NATIVE_DIVERGE;
        }
        com.nepdon.wire.diag.Diag.hit(slot);
        sjCount = -1;
    }

    /** True iff the native records in {@link #shadowOut} match the captured Java parse. */
    private boolean compareShadow(int n) {
        if (n != sjCount) return false;
        final int w = NativeBridge.CHAIN_RECORD_WIRE;
        if ((shadowOut.getShort(0) & 0xFFFF) != sjCount) return false;
        for (int r = 0; r < n; r++) {
            int base = 2 + r * w;
            int id = shadowOut.getInt(base);
            long x = shadowOut.getLong(base + 4);
            long z = shadowOut.getLong(base + 12);
            long y = shadowOut.getLong(base + 20);
            int by = shadowOut.get(base + 52) & 0xFF;
            int ay = shadowOut.get(base + 53) & 0xFF;
            int ap = shadowOut.get(base + 54);          // signed on purpose
            boolean self = shadowOut.get(base + 55) != 0;
            if (id != sjId[r] || x != sjX[r] || z != sjZ[r] || y != sjY[r]) return false;
            if (self != sjSelf[r]) return false;
            // Pose bytes are populated only for non-self records on both sides.
            if (!self && (by != sjBy[r] || ay != sjAy[r] || ap != sjAp[r])) return false;
        }
        return true;
    }

    /** Cursor-based varint read; returns -1 and parks the cursor on failure. */
    private static long vi(byte[] d, int[] pos, int end) {
        long v = 0; int sh = 0; int i = pos[0];
        while (i < end) {
            int c = d[i++] & 0xff;
            v |= (long) (c & 0x7f) << sh;
            if ((c & 0x80) == 0) { pos[0] = i; return v; }
            sh += 7;
            if (sh > 63) break;
        }
        pos[0] = end;
        return -1;
    }

    /**
     * Parses a chain packet with the exact record structure. Returns false
     * without touching any track if the layout does not hold, so the caller
     * can fall back.
     *
     * <p>Validation is the packet itself: after {@code declared} records the
     * remaining bytes must be exactly the trailing tick varint (1-3 bytes).
     * Nothing is committed until that holds.</p>
     */
    private boolean parseStructured(byte[] d, int start, int end, int declared, long now,
                                    long minRecordX, int maxEntityId) {
        int[] pos = { start };
        int[] ids = new int[declared];
        long[] xs = new long[declared], zs = new long[declared], ys = new long[declared];
        long[] vxs = new long[declared], vzs = new long[declared], vys = new long[declared];
        int[] by = new int[declared], ay = new int[declared], ap = new int[declared];
        int[] md = new int[declared], st = new int[declared];
        boolean[] full = new boolean[declared];
        boolean mdstHigh = false;

        for (int r = 0; r < declared; r++) {
            long id = vi(d, pos, end);
            if (id < 1 || id > maxEntityId) return false;
            long x = vi(d, pos, end);
            long z = vi(d, pos, end);
            long y = vi(d, pos, end);
            if (x < minRecordX) return false;
            ids[r] = (int) id; xs[r] = x; zs[r] = z; ys[r] = y;

            if (ownEntityId > 0 && id == ownEntityId) {
                // Self-echo: 20 plain varints total, no fixed-width block.
                full[r] = true;
                for (int k = 4; k < 20; k++) {
                    if (vi(d, pos, end) < 0 && pos[0] >= end) return false;
                }
                continue;
            }
            vxs[r] = vi(d, pos, end);
            vzs[r] = vi(d, pos, end);
            vys[r] = vi(d, pos, end);
            if (pos[0] + 3 > end) return false;
            by[r] = d[pos[0]] & 0xff;
            ay[r] = d[pos[0] + 1] & 0xff;
            ap[r] = d[pos[0] + 2];          // signed on purpose
            pos[0] += 3;
            // FIELDS 10/11: read as varints, and the binary says raw bytes.
            //
            // MEASURED 2026-09-15 over 12 452 candidate packets: the two
            // readings accept EXACTLY the same 6 344 packets, raw-byte rescues
            // none that varint refuses, and neither field ever reaches 0x80.
            // Below 0x80 the encodings are byte-identical, so the distinction
            // is currently unobservable and changing working parse code on
            // binary evidence the wire cannot confirm would be a gratuitous
            // risk. It would become a real desync the instant either field
            // exceeds 0x7F -- exactly the Cmd 2002 field-8 trap -- so count it
            // instead of guessing. A non-zero counter here means: switch to
            // raw bytes. See docs/PROTOCOL_MASTER.md Sec 4.5.
            md[r] = (int) vi(d, pos, end);
            st[r] = (int) vi(d, pos, end);
            if (md[r] >= 0x80 || st[r] >= 0x80) mdstHigh = true;
            vi(d, pos, end);                 // reserved, zero in >=99.5%
            vi(d, pos, end);                 // reserved, zero in 100%
            if (pos[0] > end) return false;
        }
        // The packet must end on its trailing tick varint.
        int left = end - pos[0];
        if (left < 1 || left > 3) return false;

        // Phase-4b: snapshot the fully-validated Java parse for the native
        // shadow compare (raw, pre-zigzag — exactly what nuvola_core::chain
        // serializes). Only when the shadow path is live; a compile-time false
        // flag lets R8 strip this whole block from a release build.
        if (NativeBridge.NATIVE_SHADOW) {
            for (int r = 0; r < declared; r++) {
                sjId[r] = ids[r];
                sjX[r] = xs[r]; sjZ[r] = zs[r]; sjY[r] = ys[r];
                sjBy[r] = by[r]; sjAy[r] = ay[r]; sjAp[r] = ap[r];
                sjSelf[r] = full[r];
            }
            sjCount = declared;
        }

        // ONLY COUNT ON A PACKET THAT FULLY VALIDATED.
        //
        // This counter was originally hit inside the loop above, and the first
        // capture after a game update made it read 295 -- which looked exactly
        // like the predicted raw-byte desync arriving. It was not. Those hits
        // all came from packets that went on to FAIL the terminal check, where
        // md/st are garbage read off a mis-aligned cursor. A counter that fires
        // while a parse is already failing measures the failure, not its cause.
        // Past the check above, a high value is a real field value.
        if (mdstHigh) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.ESP_CHAIN_MDST_HIGH);
        }

        for (int r = 0; r < declared; r++) {
            updateTrack(ids[r], zz(xs[r]), zz(zs[r]), zz(ys[r]), now);
            Track t = entities.get(ids[r]);
            if (t == null) continue;

            // POSE IS ASSIGNED FOR EVERY ENTITY, OUR OWN INCLUDED.
            //
            // This used to sit after the self-record `continue` below, so our
            // own track never got aimYaw/aimPitch and never got poseValid --
            // and refreshOwnFromEcho opens with `if (!t.poseValid) return`.
            // The mounted camera fallback was therefore dead from the first
            // moment ownEntityId became known: Cmd 2001 stops when you mount,
            // 2002 carries no aim at all, and the echo that was supposed to
            // cover the gap bailed on line one. The camera basis froze at
            // whatever it held when you got on, so every box and every radar
            // bearing was computed against a stale view for the whole ride
            // and snapped back the instant you got off. That IS the "ESP and
            // radar die in a vehicle" bug.
            //
            // poseValid was nevertheless sometimes true for self, which is
            // what made this survive so long: before ownEntityId is learned
            // our own record takes the normal path, so the flag latches early
            // and then the ANGLES stop updating while the flag stays set.
            // A frozen-but-valid pose is indistinguishable from a working one.
            t.bodyYawDeg = by[r] * YAW_DEG_PER_LSB;
            t.aimYawDeg = ay[r] * YAW_DEG_PER_LSB;
            t.aimPitchDeg = ap[r] * PITCH_DEG_PER_LSB;
            t.mode = md[r];
            t.state = st[r];
            t.poseValid = true;

            if (ownEntityId > 0 && ids[r] == ownEntityId) {
                // Own record: the lag estimator wants it, dead reckoning does
                // not -- the renderer never draws ROLE_SELF, and seeding a
                // velocity here would feed the cross-correlator its own input.
                measureLag(zz(xs[r]), zz(ys[r]), now);
                continue;
            }
            if (!t.velValid) seedVelocity(t, vxs[r], vzs[r], vys[r]);
        }
        return true;
    }

    /** Candidate id ceilings, tightest first (see observeChainPacket). */
    private static final int[] ID_CEILINGS = { 8, 16, 32, 64 };

    /** Candidate Z floors, strictest first. */
    private static final int[] Z_FLOORS = { 500, 0 };

    /** Number of record starts for a given id ceiling and Z floor. */
    private static int countStarts(long[] v, int zmin, int idMax, long minRecordX) {
        int n = 0;
        for (int i = 0; i + 3 < v.length; i++) {
            if (v[i] >= 1 && v[i] <= idMax
                    && v[i + 1] >= minRecordX && v[i + 2] >= zmin) n++;
        }
        return n;
    }


    /**
     * Census-validated update rule (§8): walk-gate (~50K units) accepts
     * movement directly; larger jumps go to pendingTeleport and are only
     * accepted when the NEXT record corroborates (respawn/phase transition),
     * else discarded as a parse phantom.
     */
    private void updateTrack(int id, long x, long z, long y, long now) {
        // THE MEASUREMENT THAT SETTLES WHETHER Cmd 135 GATES THE STREAM.
        //
        // The binary proves the visibility mask drives rendering only -- the
        // 2001/2012 handlers never consult it. What it cannot tell us is
        // whether the SERVER also declines to send positions for players it has
        // marked hidden; that is server policy. So count the cases: a position
        // arriving for a cleared bit means the server streams regardless, and
        // the mask is not our range limit. Zero across a whole match means it
        // does gate, and the mask IS a faithful proxy for effective range.
        if (com.nepdon.wire.protocol.VisibilityMask.hasMask()
                && !com.nepdon.wire.protocol.VisibilityMask.isVisible(id)) {
            com.nepdon.wire.protocol.VisibilityMask.noteStreamedWhileHidden();
        }
        Track t = entities.get(id);
        if (t == null) {
            t = new Track();
            t.x = x; t.z = z; t.y = y;
            t.pendingX = x; t.pendingZ = z; t.pendingY = y;
            t.firstSeenMs = now;
            // SEED lastMoveMs, or a target that never moves is never "still".
            //
            // It defaulted to 0 and is only written when a record moves more
            // than 50 units, so a player who has not moved since we first saw
            // them kept lastMoveMs == 0 forever. The renderer turns that into
            // stillMs == 0, the aim's `stillMs < AIM_STILL_MS` gate reads it as
            // MOVING, and the easiest target on the field was the one target
            // the aim would never take. On the HUD it counted as `moving=N`,
            // which looks like the gate working.
            t.lastMoveMs = now;
            entities.put(id, t);
        } else if (t.pendingTeleport) {
            // Corroboration: does the new record sit near the pending
            // teleport target? Then the jump was real (respawn).
            if (Math.abs(x - t.pendingX) + Math.abs(z - t.pendingZ) < 50000) {
                t.x = t.pendingX; t.z = t.pendingZ; t.y = t.pendingY;
                t.lastMoveMs = now;
            }
            t.pendingTeleport = false;
            // Apply the current record through the normal gate below.
            double d = Math.abs(x - t.x) + Math.abs(z - t.z);
            if (d <= 50000) {
                if (d > 50) t.lastMoveMs = now;
                t.x = x; t.z = z; t.y = y;
            } else {
                t.pendingTeleport = true;
                t.pendingX = x; t.pendingZ = z; t.pendingY = y;
            }
        } else {
            double d = Math.abs(x - t.x) + Math.abs(z - t.z);
            if (d <= 50000) {
                if (d > 50) t.lastMoveMs = now;
                t.x = x; t.z = z; t.y = y;
            } else {
                // Hold position; wait for corroboration.
                t.pendingTeleport = true;
                t.pendingX = x; t.pendingZ = z; t.pendingY = y;
            }
        }
        if (t.dead && now - t.deathMs > 1200L) t.dead = false;
        updateVelocity(t, now);
        t.updateMs = now;
        t.updates++;
        classify(id, t);
        // Mounted recovery: with Cmd 2001 silent this echo is the only source
        // of our own position and camera basis. See OWN_STALE_MS.
        // The echo fills in whatever the vehicle carriers are NOT supplying.
        // In a car that is the camera only (2002 has no aim); in a passenger
        // seat, a chair or a cannon nothing is sent at all and the echo
        // supplies both, which is the only thing keeping the overlay alive
        // in those states.
        if (t.role == ROLE_SELF && mounted()) {
            refreshOwnFromEcho(t, now,
                    mayWritePos(OWN_SRC_ECHO, now),
                    mayWriteDir(OWN_SRC_ECHO, now));
        }
    }

    /**
     * Folds one finite-difference velocity sample into the track.
     *
     * <p>Called after the position gate above has run, so a rejected teleport
     * never becomes a 500 m/s velocity that would fling the box off screen.
     * Samples with an implausible gap or an implausible speed are dropped
     * rather than clamped: a bad sample carries no information, and clamping
     * it would still drag the smoothed value in the wrong direction.</p>
     */
    private void updateVelocity(Track t, long now) {
        long dt = now - t.prevMs;
        if (t.prevMs != 0L && dt >= VEL_DT_MIN_MS && dt <= VEL_DT_MAX_MS) {
            double dx = t.x - t.prevX;
            double dy = t.y - t.prevY;
            double dz = t.z - t.prevZ;
            if (Math.abs(dx) > VEL_TELEPORT_UNITS || Math.abs(dy) > VEL_TELEPORT_UNITS
                    || Math.abs(dz) > VEL_TELEPORT_UNITS) {
                // Teleport / respawn: this chord is not motion.
                t.vx = 0; t.vy = 0; t.vz = 0;
                t.velValid = false;
            } else {
                double nvx = dx / dt * VEL_GAIN;
                double nvy = dy / dt * VEL_GAIN;
                double nvz = dz / dt * VEL_GAIN;
                if (Math.sqrt(nvx * nvx + nvy * nvy) <= VEL_MAX_U_PER_MS) {
                    // Latest sample wins outright - see VEL_GAIN: smoothing
                    // measured worse than no smoothing at every setting.
                    t.vx = nvx; t.vy = nvy; t.vz = nvz;
                    t.velValid = true;
                }
            }
        } else if (t.prevMs != 0L && dt > VEL_DT_MAX_MS) {
            // Came back after a dropout: the old velocity is meaningless and
            // the new chord spans too long to trust. Wait for a clean tick.
            t.velValid = false;
        }
        t.prevX = t.x; t.prevY = t.y; t.prevZ = t.z;
        t.prevMs = now;
    }

    /**
     * Seeds velocity from the server in-packet velocity field.
     *
     * <p>The server DOES send velocity: in a remote-player record the three
     * varints after {@code [id][X][height][Y]} are displacement per 99 ms
     * sub-tick (correlation 0.847 / 0.686 / 0.838 against the concurrent
     * per-record delta, and a median field-to-delta ratio of exactly 0.500 -
     * the record interval is two sub-ticks). It is concurrent, not leading.</p>
     *
     * <p><b>It is NOT the main estimator, because it measures worse than the
     * finite difference</b> (RMS relative to naive hold: finite difference
     * 0.553, best-scaled server field 0.681, and every blend of the two fell
     * between them). The field covers one 99 ms sub-tick so it is noisier;
     * the finite difference averages the full 198 ms and predicts the next
     * 198 ms better.</p>
     *
     * <p>What it gives that the finite difference cannot: velocity from a
     * SINGLE record. A target that just appeared, or returned after a
     * dropout, can be extrapolated on its very first packet instead of
     * standing still for a whole tick. So it seeds, then the finite
     * difference takes over.</p>
     */
    private static void seedVelocity(Track t, long rawVx, long rawVz, long rawVy) {
        if (t.velValid) return;            // a real measurement outranks the seed
        double vx = zz(rawVx) / SUB_TICK_MS;
        double vz = zz(rawVz) / SUB_TICK_MS;
        double vy = zz(rawVy) / SUB_TICK_MS;
        if (Math.sqrt(vx * vx + vy * vy) > VEL_MAX_U_PER_MS) return;
        t.vx = vx; t.vy = vy; t.vz = vz;
        t.velValid = true;
    }

    /**
     * Role classification, capture-validated on 150701 (Clash Squad 4v4,
     * 12161 entity records over 346 s).
     *
     * <p><b>Self</b>: the server echoes the player's own state as an entity
     * record. Its X/Z/Y are byte-identical to the keepalive at matched
     * timestamps (median offset 178/2/90 units, 86% of samples within
     * 2000 u — the residual is just the ~100 ms echo delay). That entity
     * must never be drawn, and it is also the proof that enemy records
     * share the own coordinate frame exactly.</p>
     *
     * <p><b>Teammate</b>: squadmates hold station near you. The robust
     * discriminator is the FRACTION of samples inside 10 m, not the median
     * distance (a median threshold misclassified 2 of 8 entities on capture
     * 161940, where teammates sat at 11-16 m and enemies at 33-43 m).
     * Measured fraction-inside-10 m across three full matches:</p>
     * <pre>
     *   capture   teammates            enemies
     *   150701    0.54 0.46 0.37       0.00 0.02 0.02 0.05
     *   155351    0.68 0.51 0.51       0.04 0.05 0.07 0.07
     *   161940    0.38 0.37 0.37       0.06 0.09 0.11 0.17
     * </pre>
     * <p>Lowest teammate 0.37, highest enemy 0.17 — the 0.27 midpoint gives
     * a correct 1 self + 3 teammates + 4 enemies on all three matches.</p>
     */
    /**
     * Assigns a role, in strict priority order. The handle is authoritative;
     * everything below it is a fallback that only applies while no handle has
     * named this entity.
     *
     * <p>Measured over 87 585 samples, 10 sessions, both game modes: this
     * decides <b>92.9%</b> of samples at <b>100.000%</b> accuracy, 0 friends
     * painted as enemies and 0 enemies painted as friends.</p>
     *
     * <p><b>Proximity may promote to TEAM but must never conclude ENEMY.</b>
     * Allowing it to call enemy raises coverage to 97.4% but costs 1 217
     * friend-painted-as-enemy samples. A wrong colour is worse than no
     * colour — and the undecided remainder is 64.1% enemies anyway, so a
     * neutral box is still usually a box on a foe. You lose the colour, not
     * the target.</p>
     */
    private void classify(int id, Track t) {
        // RUNG 0 -- FRESHNESS GATE, NARROWED.
        //
        // A stale or missing own team can only manufacture a FRIEND label,
        // because it is the denominator of exactly one comparison: rung 2's
        // `t.team == ownTeam`. Rungs 3, 4 and 6 conclude ENEMY only and need no
        // own team at all.
        //
        // It originally returned outright, which blanked the ESP completely for
        // as long as identity was unknown -- and the reset loop fixed at the
        // match-boundary detector kept identity unknown for a median 24 s per
        // Clash Squad round. Blanking was never the safe behaviour it looked
        // like: red-by-default is measured safe (the undecided remainder is
        // 96.9% enemies in BR), whereas drawing nothing loses the target
        // entirely. So gate the one rung that can say TEAM, and let the rest
        // run.
        final boolean ownTeamUsable =
                isValidTeam(ownTeam) && ownTeamSeenMs >= lastMatchResetMs;

        // RUNG 1 -- SELF, from the keepalive handle.
        if (ownEntityId > 0 && t == entities.get(ownEntityId)) {
            t.role = ROLE_SELF;
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_R1_SELF);
            return;
        }

        // Pull the authoritative team byte if we do not have one yet. A PULL,
        // not a push: both sources are already populated by their own message
        // handlers, and asking them here means a bind that lands late still
        // takes effect on the next sample instead of needing a plumbed
        // callback. Cmd 118 outranks the roster; both outrank damage/handle
        // events, which only ever name people who have been shot at.
        if (t.teamSrc < TEAM_SRC_BIND) {
            int s = pri.squadFor(id);
            if (isValidTeam(s)) {
                noteTeam(id, s, TEAM_SRC_BIND);
            } else if (t.teamSrc < TEAM_SRC_ROSTER) {
                s = roster.squad(id);
                if (isValidTeam(s)) noteTeam(id, s, TEAM_SRC_ROSTER);
            }
        }

        // RUNG 2 -- THE SERVER'S OWN TEAM BYTE. The ONLY rung that may say TEAM.
        //
        // RECOMPUTE, never early-return. The old code did `if (t.teamKnown)
        // return;`, which froze a verdict computed against whatever ownTeam
        // happened to hold at the time -- so a role decided before our own team
        // was known could never be corrected. Recomputing costs one comparison.
        // Gated on rung 0: without a trustworthy own team this comparison
        // cannot be made, but the ENEMY-only rungs below still can.
        if (ownTeamUsable && isValidTeam(t.team)) {
            t.teamKnown = true;
            t.role = (t.team == ownTeam) ? ROLE_TEAM : ROLE_ENEMY;
            // WHICH RUNG DECIDED, and to what. Reconstructing this offline is
            // exactly what found the BR proximity inversion and the CS blackout.
            com.nepdon.wire.diag.Diag.hit(t.role == ROLE_TEAM ? com.nepdon.wire.diag.Slot.ESP_CLS_R2_TEAM : com.nepdon.wire.diag.Slot.ESP_CLS_R2_ENEMY);
            return;
        }
        if (!ownTeamUsable) com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_R2_NO_OWN_TEAM);

        // WE MUST BE ABLE TO EXCLUDE OURSELVES BEFORE DEFAULTING ANYTHING TO
        // ENEMY. Rung 1 identifies self from ownEntityId; until that is known,
        // our own self-echo entity is indistinguishable from a stranger and
        // rung 6 would paint a red box on our own character.
        //
        // This replaces the old `if (ownSamples < 8) return;`, which guarded
        // the same thing by proxy and also gated the deleted proximity rung.
        // ownEntityId is the precise condition: it is what rung 1 actually
        // needs, it arrives on the first keepalive rather than the eighth, and
        // it does not hold the ENEMY-only rungs back for eight samples the way
        // a sample count did.
        if (ownEntityId <= 0) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_NO_OWN_ID);
            return;
        }

        // RUNG 3 -- SQUAD CLOSURE -> ENEMY.
        // No squad exceeds MAX_SQUAD, so once our own squad is fully named,
        // everyone else must be an enemy. Concludes ENEMY only, which is the
        // safe direction. Inert in solo BR, where ownSquadKnown() tops out at 1.
        if (ownSquadKnown() >= MAX_SQUAD) {
            t.role = ROLE_ENEMY;
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_R3_CLOSURE_ENEMY);
            return;
        }

        // RUNG 4 -- LATE ARRIVAL -> ENEMY.
        // Squad rosters are fixed at match start and squadmates stream from the
        // first moment; an entity first seen 20 s in cannot be one. Concludes
        // ENEMY only.
        if (firstChainMs != 0 && t.firstSeenMs != 0
                && t.firstSeenMs - firstChainMs > LATE_ARRIVAL_MS) {
            t.role = ROLE_ENEMY;
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_R4_LATE_ENEMY);
            return;
        }

        // RUNG 5 -- DELETED. It was a proximity promotion to TEAM, and it was
        // not merely imprecise in Battle Royale, it was INVERTED.
        //
        // Measured on a real BR squad match (31 360 samples, Cmd 118 as ground
        // truth): real teammates scored 0.206 / 0.183 / 0.158 on fraction-of-
        // samples-inside-10 m -- all BELOW the 0.27 threshold -- while enemies
        // scored 0.888 down to 0.641, all ABOVE it. The rule fired backwards.
        //
        // The cause is replication, not geometry. BR interest-manages the
        // entity stream: squadmates replicate continuously at ~5 Hz even at
        // range, while everyone else streams only while nearby. So "fraction of
        // samples within 10 m" really measures "is interest-managed", and in BR
        // that is the OPPOSITE of teammate. The Clash Squad captures it was
        // tuned on have no such filtering, which is why it validated there and
        // nowhere else. The drop then puts all ~50 players within metres of
        // each other, and the rule needed only 40 updates -- about 8 s.
        //
        // Result: 21 non-squad players ended the match permanently ROLE_TEAM,
        // 29.47% of everything drawn, median promotion 5.2 s after first
        // sighting -- while still in the aircraft.
        //
        // It is deleted rather than re-tuned or mode-gated because the
        // authoritative source CANNOT out-vote it: wiring Cmd 118 in moved the
        // error only 29.47% -> 28.14%, since proximity latches first. And it
        // stuck because the old rung 6 was a bare comment, so when the
        // condition stopped holding the method fell off the end and kept the
        // stale role.
        //
        // THE RULE THAT REPLACES IT: only a server-published team byte may ever
        // produce blue. No geometry, no co-location, no update rate.

        // RUNG 6 -- DEFAULT -> ENEMY.
        // Undecided renders red. Measured safe: the undecided remainder is
        // 96.9% enemies in BR (3 673 enemy vs 117 friend samples). The 64.1%
        // in the old javadoc was a whole-corpus figure dominated by Clash
        // Squad and does not describe BR.
        t.role = ROLE_ENEMY;
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_CLS_R6_DEFAULT_ENEMY);
    }

    /**
     * Degrees per LSB for the two 8-bit yaw fields: a full turn in 256 steps.
     * Confirmed with no offset term — 90.1% of moving samples land within 5
     * degrees of the heading implied by the entity's own velocity, median
     * residual 1.49 deg, which is one quantisation step.
     */
    private static final float YAW_DEG_PER_LSB = 360.0f / 256.0f;

    /**
     * Degrees per LSB for the signed 8-bit pitch field.
     *
     * <p>Measured 1.29 deg/LSB (IQR 1.256-1.301) against our own camera pitch,
     * and |value| never exceeded 64 in 73 125 records, so the field spans
     * about +/-82.5 deg. The tidy alternative 360/256 = 1.406 is 9% higher and
     * is NOT what the data gives, but the ground-truth sample (n=141 over a
     * narrow pitch span) cannot separate the two at the 5% level — so this is
     * exposed for confirmation by eye rather than treated as settled.</p>
     */
    public static volatile float PITCH_DEG_PER_LSB = 1.29f;

    /** Largest squad observed in any session, across both game modes. */
    private static final int MAX_SQUAD = 4;

    /**
     * An entity first seen this long after our own first chain packet cannot
     * be a squadmate.
     *
     * <p>Squad rosters are fixed at match start and squadmates stream
     * continuously, so they appear almost immediately. Measured over 11
     * matches and 30 known teammates: teammate first-sighting is p50 0.0 s,
     * p90 4.3 s, <b>max 11.0 s</b> — not one teammate anywhere in the corpus
     * appeared later than 11 s. Enemies are p50 42.1 s, p75 258 s.</p>
     *
     * <p>Threshold sweep against the full corpus: 10 s gives 462 friends
     * painted as enemies; <b>15 s, 20 s, 30 s and 60 s all give zero</b>. 20 s
     * is the middle of that safe plateau.</p>
     *
     * <p><b>Why this rung exists:</b> squad closure needs 4 known teammates,
     * so in a SOLO lobby it can never arm — the count tops out at 1 and
     * {@code >= 4} is unsatisfiable. That left every entity UNKNOWN and, with
     * SHOW_UNCLASSIFIED off, drew nothing at all: the reported solo blackout.
     * This rung does not depend on squad size, so it works in solo, duo and
     * squad alike. Measured: a solo player now draws MORE (61 101 samples)
     * than the old code managed with a full squad (59 943), with zero
     * teammates painted as enemies.</p>
     */
    private static final long LATE_ARRIVAL_MS = 20000L;

    /**
     * Chain silence that implies a new match. Worst real intra-match chain
     * gap measured is 15.8 s, so this is roughly 2x margin.
     */
    private static final long MATCH_GAP_MS = 30000L;

    /** Wall-clock of the first chain packet of the current match. */
    private volatile long firstChainMs = 0L;
    /** Wall-clock of the most recent chain packet. */
    private volatile long lastChainMs = 0L;
    /** Own handle of the current match, to detect a new one. */
    private volatile long matchHandle = 0L;

    /**
     * Feeds one inbound packet and decodes it if it is a DAMAGE event.
     *
     * <p><b>Family:</b> IN, and bytes b8..b11 form an object handle —
     * {@code (0x80|id) 0x80 0x80 tt} with {@code tt} = 0x08 (own team) or
     * 0x10 (enemy team). Varints from b8 give exactly 8 fields:</p>
     * <pre>
     *   v0 victim handle    (type&lt;&lt;24)|id
     *   v1 attacker handle  (type&lt;&lt;24)|id
     *   v2 damage source    zigzag; &gt;0 = weapon, &lt;0 = hazard (zone/fall)
     *   v3 server tick      16.500 ms per tick (60.60 Hz)
     *   v4 flag (~always 0)
     *   v5 DAMAGE in HP     PLAIN, not zigzag
     *   v6, v7 flags
     * </pre>
     *
     * <p>Verified on four matches: 265/532/565/215 events, zero on the clean
     * lobby capture. Cumulative damage per victim hits exactly 200 at death,
     * and the tick rate measures 60.603/60.604 Hz. Victim-vs-attacker order
     * was settled geometrically — the angle from the claimed attacker's
     * camera to the victim has a median of 3.8-6.3 deg, versus 28-141 deg
     * for the reverse assignment.</p>
     *
     * <p><b>This is the authoritative friend/foe source.</b> The handle's
     * type byte gives the team directly, exactly and immediately — no warmup
     * and no proximity guessing. It agreed with the independent geometric
     * classifier on 32/32 entities across four matches.</p>
     */
    /**
     * Small inbound handle events (len 15-17, at most 4 varints from b8).
     * These carry a single object handle, and it belongs to our own team in
     * <b>355 of 356</b> observed cases (99.7%) — a free teammate label that
     * needs no combat to arrive, unlike the damage feed whose median
     * time-to-first-handle is 20 s.
     */
    public void observeHandleEvent(byte[] data, int off, int plen) {
        if (plen < 14 || plen > 20) return;
        if (off < 0 || off + plen > data.length) return;
        int h0 = data[off + 8] & 0xff;
        if (h0 < 0x80) return;
        if ((data[off + 9] & 0xff) != 0x80 || (data[off + 10] & 0xff) != 0x80) return;
        int tt = data[off + 11] & 0xff;
        if (tt == 0 || (tt & 0x07) != 0) return;
        long[] v = varints(data, off + 8, off + plen);
        if (v == null || v.length < 1 || v.length > 4) return;
        int id = (int) (v[0] & 0xFFFFFF);
        int team = (int) (v[0] >>> 24);
        if (id < 1 || id > 127 || team < 1) return;
        noteTeam(id, team, TEAM_SRC_HANDLE);
    }

    /** Cmd 2010 v4: cumulative damage absorbed by shields/armour points. */
    private long spDamageTotal;
    /** Hits that carried any SP damage. */
    private long spDamageEvents;

    public void observeDamage(byte[] data, int off, int plen) {
        if (plen < 20 || plen > 40) return;
        if (off < 0 || off + 12 > data.length || off + plen > data.length) return;
        int h0 = data[off + 8] & 0xff;
        // NO UPPER BOUND. h0 is the first byte of a zero-padded 4-byte varint,
        // so it is 0x80 | (id & 0x7F) and spans 0x80..0xFF for ids 0..127.
        //
        // The old `> 0xBF` ceiling cut at id 63 and silently discarded every
        // damage event for entity ids 64..127 -- which this very method then
        // contradicts three lines later by accepting `victim <= 127`. It is the
        // same 63/64 confusion as the old `otm <= 63` team-byte bound: a
        // different field's width leaking into this one. The sibling guard in
        // observeHandleEvent (:1577) never had the ceiling, so the two
        // disagreed about which entities exist.
        if (h0 < 0x80) return;
        if ((data[off + 9] & 0xff) != 0x80 || (data[off + 10] & 0xff) != 0x80) return;
        // b11 is the team index shifted left 3 — NOT a 0x08/0x10 enum. Pinning
        // it to those two values discarded 20.5% of all damage events, and
        // 100% of them in three of ten sessions, which silently disabled the
        // authoritative team path and left the ESP on pure proximity.
        int tt = data[off + 11] & 0xff;
        if (tt == 0 || (tt & 0x07) != 0) return;
        long[] v = varints(data, off + 8, off + plen);
        if (v == null || v.length != 8) return;

        // Mask 24 bits, not 8: observed ids run to 55.
        int victim = (int) (v[0] & 0xFFFFFF), vTeam = (int) (v[0] >>> 24);
        int attacker = (int) (v[1] & 0xFFFFFF), aTeam = (int) (v[1] >>> 24);
        int damage = (int) v[5];
        // v4 is damage absorbed by SHIELD / ARMOUR POINTS, decoded from the
        // binary: the handler skips its whole block when this is zero and then
        // calls Player::PlayHitSPEffect, while v5 gates the HP path. Counting
        // only v5 understates what an enemy actually absorbed, which matters
        // against a shielded target. Both are PLAIN u16 varints, not zigzag.
        int spDamage = (int) v[4];
        if (spDamage < 0 || spDamage > MAX_HP) spDamage = 0;
        if (victim < 1 || victim > 127 || attacker < 1 || attacker > 127) return;
        if (damage <= 0 || damage > MAX_HP) {
            // An SP-only hit carries no HP damage at all. It is still a real
            // hit and still proves the attacker/victim pairing, so record the
            // team facts and the event rather than discarding it.
            if (spDamage > 0 && damage == 0) {
                noteTeam(victim, vTeam, TEAM_SRC_DAMAGE);
                noteTeam(attacker, aTeam, TEAM_SRC_DAMAGE);
                spDamageEvents++;
                spDamageTotal += spDamage;
            }
            return;
        }
        spDamageTotal += spDamage;
        if (spDamage > 0) spDamageEvents++;

        noteTeam(victim, vTeam, TEAM_SRC_DAMAGE);
        noteTeam(attacker, aTeam, TEAM_SRC_DAMAGE);
        damageEvents++;

        Track t = entities.get(victim);
        if (t == null) { t = new Track(); entities.put(victim, t); }
        long now = System.currentTimeMillis();
        // The damage feed is what BINDS a Cmd 1900 replication owner to this
        // entity handle: an HP drop of the same size lands within a quarter
        // second. See PriTracker.
        pri.onDamage(victim, damage, now);
        // Estimated HP stays as a FALLBACK only -- it is a lower bound built
        // from our own hits, so it is wrong for anyone we have not shot. The
        // renderer prefers the real value from Cmd 1900 whenever the owner is
        // bound, and this is what it falls back to when it is not.
        t.hp -= damage;
        if (t.hp <= 0) {
            t.hp = MAX_HP;          // respawn resets; the estimate is a lower bound
            t.dead = true;
            t.deathMs = now;
            kills++;
        }
        t.lastDamageMs = now;
    }

    /** Records the authoritative team for an entity id. */
    /**
     * Records an entity's squad index and derives its role RELATIVE to ours.
     *
     * <p>Team indices are per-match, so no absolute value means "friendly".
     * Verified on 107 entities across 10 sessions: every entity with two or
     * more handle observations reported the same team every time (0
     * conflicts), squads never exceed 4 members, and 99.92% of damage events
     * are between different teams.</p>
     */
    private void noteTeam(int id, int team, int src) {
        if (id < 1 || id > 127) return;
        // 0 = unassigned and 255 = sentinel; neither names a team, and both
        // would compare equal to themselves and paint strangers blue.
        if (!isValidTeam(team)) return;
        Track t = entities.get(id);
        if (t == null) { t = new Track(); entities.put(id, t); }
        // A weaker source must never overwrite a stronger one. All sources
        // carry the same quantity so they cannot disagree, but precedence
        // keeps teamSrc honest for the HUD and stops a late damage handle
        // downgrading a Cmd 118 bind.
        if (src < t.teamSrc) return;
        t.team = team;
        t.teamSrc = src;
        switch (src) {
            case TEAM_SRC_BIND:   com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_TEAM_SRC_BIND); break;
            case TEAM_SRC_ROSTER: com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_TEAM_SRC_ROSTER); break;
            case TEAM_SRC_DAMAGE: com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_TEAM_SRC_DAMAGE); break;
            case TEAM_SRC_HANDLE: com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_TEAM_SRC_HANDLE); break;
            default: break;
        }
        if (!isValidTeam(ownTeam)) return;   // cannot judge until we know ours
        t.teamKnown = true;
        t.role = (id == ownEntityId) ? ROLE_SELF
                : (team == ownTeam ? ROLE_TEAM : ROLE_ENEMY);
    }

    /**
     * Number of DISTINCT own-team ids known, our own included. Once this
     * reaches the maximum squad size, every other entity must be an enemy.
     */
    /**
     * Clears all match-scoped state.
     *
     * <p><b>Nothing used to clear {@code entities} — ever.</b> Entity ids AND
     * squad indices are reassigned per match, so a track survived into the
     * next match carrying a stale team, and {@code classify()} refuses to
     * revisit anything with {@code teamKnown}. Measured over 62 legal
     * back-to-back match pairs: <b>33 323 samples of a red box on a
     * squadmate, in 55 of 62 pairs</b> — while every match run alone scores
     * zero. Worse, {@code ownSquadKnown()} read 4 on the first packet of the
     * new match purely from the previous match's squad, arming closure on
     * stale data.</p>
     *
     * <p>That is the reported "wrong for the first minutes, then corrects" —
     * it self-heals only as fresh handles rename each entity.</p>
     */
    private void resetMatch(long now) {
        // The counter that would have caught the CS regression in seconds:
        // it fired 789 times in 109 s when a sentinel was misread.
        com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_MATCH_RESET);
        entities.clear();
        // OUR OWN IDENTITY IS ALSO PER-MATCH. Entity ids and squad indices are
        // both reassigned every match, so carrying ownTeam or ownEntityId
        // across a boundary manufactures a false label in one direction or the
        // other -- the exact mirror of the stale-track bug documented below,
        // and it survived that fix. lastMatchResetMs arms rung 0 so nothing is
        // classified at all until a fresh keepalive has named us again.
        ownTeam = 0;
        ownTeamSeenMs = 0L;
        ownEntityId = -1;
        // Clear the handle we are comparing AGAINST too. Leaving the old
        // match's handle here while our own identity is unknown is the second
        // half of the reset loop described at the boundary detector: the next
        // packet that re-learns our identity would compare it against the
        // previous match and reset a third time.
        matchHandle = 0L;
        lastMatchResetMs = now;
        // Vehicle object ids and rider bindings are per-match like everything
        // else here. A surviving entry would name last match's rider.
        mountedWire = false;
        riderOfVehicle.clear();
        vehicleOfRider.clear();
        // Own-state provenance: without this the ladder would still believe a
        // vehicle carrier from the previous match was live and refuse the
        // first echo of the new one.
        ownPosSrc = OWN_SRC_NONE;
        ownDirSrc = OWN_SRC_NONE;
        ownMs2069 = 0L;
        ownMs2002 = 0L;
        ownMs2002q = 0L;
        // Replication owner ids are reassigned per match exactly like entity
        // ids, so a surviving binding would show a stranger's health.
        pri.reset();
        roster.reset();
        firstChainMs = now;
        lastChainMs = now;
        ownSamples = 0;
        lagSamples = 0;
        lagMs = 0;
        histCount = 0;
        histHead = 0;
    }

    /**
     * Friend/foe provenance, one line.
     *
     * <p>Loud by design: only a server-published team byte may paint a box
     * blue, so if {@code own=0} or {@code known} stays at 0 the authoritative
     * path is dead and everything is falling through to the red default. That
     * is safe, but it is not the same as working, and a silent no-op is
     * indistinguishable from a working feature.</p>
     */
    public String teamLine() {
        int known = 0, b = 0, r = 0, d = 0, h = 0, unk = 0;
        for (Track t : entities.values()) {
            if (isValidTeam(t.team)) {
                known++;
                switch (t.teamSrc) {
                    case TEAM_SRC_BIND:   b++; break;
                    case TEAM_SRC_ROSTER: r++; break;
                    case TEAM_SRC_DAMAGE: d++; break;
                    case TEAM_SRC_HANDLE: h++; break;
                    default: break;
                }
            } else {
                unk++;
            }
        }
        long age = (lastMatchResetMs == 0L) ? 0L
                : (System.currentTimeMillis() - lastMatchResetMs) / 1000L;
        return String.format(
                "TEAM own=%d known=%d/%d src=118:%d/101:%d/dmg:%d/hnd:%d unk=%d reset=+%ds",
                ownTeam, known, entities.size(), b, r, d, h, unk, age);
    }

    private int ownSquadKnown() {
        int n = 0;
        for (Track t : entities.values()) {
            if (t.team == ownTeam && t.team > 0) n++;
        }
        return n;
    }

    /** Our own entity id, taken from the keepalive handle (b8 & 0x7F). */
    private volatile int ownEntityId = -1;

    /** Our own entity slot, or -1 before it is learned. */
    public int ownEntityId() {
        return ownEntityId;
    }
    private volatile int damageEvents = 0;
    private volatile int kills = 0;

    /** Real per-player health from Cmd 1900, and the owner->handle binding. */
    private final PriTracker pri = new PriTracker();

    public PriTracker pri() { return pri; }

    /**
     * Feeds one decrypted Cmd 1900 body. Health is the only thing in the ESP
     * that is server-authoritative rather than inferred, so this is the one
     * number on screen that does not need a confidence caveat -- once its owner
     * is bound to an entity.
     */
    /**
     * Upper bound on properties buffered from one Cmd 1900 record.
     *
     * <p>Generous: a real record carries a handful. Hitting it means the body
     * desynced, which decode() reports as false anyway -- the bound exists so
     * a runaway parse cannot allocate, not as a real limit.</p>
     */
    private static final int PRI_BATCH = 256;

    public void observePriSync(byte[] body, int off, int len) {
        final long now = System.currentTimeMillis();
        // BUFFER, THEN COMMIT. The sink used to write straight into PriTracker
        // while the body was still being walked, and decode()'s boolean return
        // -- whose own contract says a false means the caller must DISCARD
        // what the sink already received -- was thrown away. So a record that
        // desynced half way through had already committed junk HP against real
        // players, silently, and the only symptom was a health bar that was
        // wrong in a way nothing could explain.
        final int[] n = { 0 };
        final long[] owners = new long[PRI_BATCH];
        final int[] varIds = new int[PRI_BATCH];
        final long[] values = new long[PRI_BATCH];
        boolean ok = com.nepdon.wire.decode.body.PriSync.decode(body, off, len, true,
                new com.nepdon.wire.decode.body.PriSync.Sink() {
                    @Override
                    public void onProperty(long owner, int varId, long value) {
                        if (n[0] >= PRI_BATCH) return;   // absurd record; ok will be false
                        owners[n[0]] = owner;
                        varIds[n[0]] = varId;
                        values[n[0]] = value;
                        n[0]++;
                    }
                });
        if (!ok) {
            com.nepdon.wire.diag.Diag.hit(
                    com.nepdon.wire.diag.Slot.ESP_PRI_DECODE_REFUSED);
            return;                                   // commit nothing
        }
        for (int i = 0; i < n[0]; i++) {
            pri.onProperty(owners[i], varIds[i], values[i], now);
        }
    }

    /** Varints read from a Cmd 121 body for the equipment join. */
    private static final int EQUIP_SCAN = 10;

    /**
     * Feeds one decrypted Cmd 121 {@code RUDP_EQUIPMENT_CHANGED} body.
     *
     * <p>Nothing here is displayed. It exists purely to BIND a Cmd 1900
     * replication owner to an entity handle when the authoritative Cmd 118
     * answer is missing — which it is for the whole pre-key roster burst at
     * match handover.</p>
     *
     * <p>Field 0 is the handle; the remaining varints are collected as a value
     * SET rather than one named field, because the join matches <b>both</b>
     * halves of {@code EPRI_ITEM_ON_HAND} — the inventory instance id and the
     * weapon id — against it. Requiring both is what lets the correlation
     * window widen safely: measured, one field at ±150 ms binds 19 owners,
     * while both halves at ±350 ms bind 51 with zero ambiguity and 28/28
     * agreement with Cmd 118.</p>
     */
    public void observeEquipment(byte[] body, int off, int len) {
        if (body == null || off < 0 || len < 4 || off + len > body.length) return;
        com.nepdon.wire.decode.body.WireReader r =
                new com.nepdon.wire.decode.body.WireReader(body, off, len);
        long handleRaw = r.u();
        if (r.bad) return;
        int handle = (int) (handleRaw & 0xFFFFFFL);
        long[] vals = new long[EQUIP_SCAN];
        int n = 0;
        while (n < EQUIP_SCAN && r.hasMore()) {
            long v = r.u();
            if (r.bad) break;
            vals[n++] = v;
        }
        if (n > 0) pri.onEquipment(handle, vals, n, System.currentTimeMillis());
    }

    /** Match roster: player names keyed to the entity slot. */
    private final MatchRoster roster = new MatchRoster();

    public MatchRoster roster() { return roster; }

    /**
     * Feeds one decrypted Cmd 118 {@code RUDP_BIND_PRI} body: a list of
     * {@code (U owner, B type, U packedEntity)} records. This is the server
     * telling us the owner-to-entity mapping outright, so it is preferred over
     * every inferred binding.
     */
    public void observeBindPri(byte[] body, int off, int len) {
        com.nepdon.wire.decode.body.WireReader r =
                new com.nepdon.wire.decode.body.WireReader(body, off, len);
        // The leading varint is TWICE the record count, not the count —
        // verified 365/365 (2->1, 4->2, 8->4, 20->10, 26->13, 32->16). Reading
        // it as the count ran the loop twice as far and relied on the reader's
        // bad flag to stop, which happens to be harmless on exact-consumption
        // bodies but is not something to leave in place.
        long header = r.u();
        int count = (int) (header >> 1);
        if (r.bad || count < 1 || count > 256) return;
        for (int i = 0; i < count && !r.bad; i++) {
            long owner = r.u();
            int type = r.rawByte();
            long ent = r.u();
            if (r.bad) return;
            pri.onBind(owner, type, ent);
        }
    }

    /**
     * A victim position this stale (ms) is no longer trusted as a hit-point
     * target. The entity broadcast tick is {@value #TICK_MS} ms, so a live
     * target under fire refreshes every ~200 ms; this allows a few missed
     * ticks before the position is treated as unknown. Kept small so the
     * hit-point rewrite never claims a head at a position the target left long
     * ago.
     */
    // (headPointForVictim + HEAD_POINT_STALE_MS were removed 2026-09-18 with
    // AimFilter — they served only the Cmd-106 hit-point forgery.)

    // ---- accessors ----

    public boolean maskReady() { return maskValid; }

    /** Own world X (varint v0). */
    public double posX() { return posX; }
    /** Own world Z / height (varint v1). */
    public double posZ() { return posZ; }
    /** Own world Y (varint v2). */
    public double posY() { return posY; }

    /** View direction unit vector X component (float32 v13). */
    public float dirX() { return dirX; }
    /** View direction unit vector Y component (float32 v14). */
    public float dirY() { return dirY; }
    /** View direction unit vector Z component (float32 v15). */
    public float dirZ() { return dirZ; }

    /** Full yaw in radians: atan2(dirX, dirZ). */
    public double yawRad() { return Math.atan2(dirX, dirZ); }

    /** Full pitch in radians: asin(dirY). */
    public double pitchRad() { return Math.asin(Math.max(-1.0, Math.min(1.0, dirY))); }

    // ====================================================================
    // CAMERA-FORWARD ANGULAR EXTRAPOLATION
    // ====================================================================
    //
    // The renderer leads each entity POSITION forward in time (dead reckoning
    // + LEAD), but the camera forward it projects against comes straight off
    // the last ~10 Hz keepalive with no lead of its own. During a camera turn
    // that stale basis trails the real camera by up to a full ~100 ms sample,
    // so EVERY box drifts uniformly in the turn direction and snaps back when
    // the next keepalive lands - and an edge target can briefly fall behind the
    // near plane (d.f < NEAR) and vanish. The fix is to measure the angular
    // velocity of the forward vector between the last two samples and rotate the
    // freshest forward to the present instant (plus a lead), so the basis tracks
    // the camera instead of trailing it.

    /** Wall-clock of the freshest direction sample; 0 until the first. */
    private volatile long dirSampleMs = 0L;
    /** Angular velocity of the forward vector, axis*speed in rad/ms. */
    private volatile float camAngVelX, camAngVelY, camAngVelZ;
    /** True when camAngVel* holds a usable (in-band, non-cut) measurement. */
    private volatile boolean camAngVelValid = false;

    /**
     * One immutable camera sample. The packet thread writes a whole sample at
     * once and the renderer reads one reference per frame. Before this, the
     * forward vector, its time and its angular velocity lived in separate
     * volatile fields, so a frame could mix components from two samples and
     * point the basis somewhere no sample ever pointed.
     */
    public static final class CamSample {
        public final float x, y, z;
        public final long ms;
        /** Smoothed angular velocity, axis*speed in rad/ms. Meaningful only when wValid. */
        public final float wx, wy, wz;
        public final boolean wValid;
        /** False for the echo-derived fallback, whose yaw is quantised. */
        public final boolean fromWire;

        CamSample(float x, float y, float z, long ms,
                  float wx, float wy, float wz, boolean wValid, boolean fromWire) {
            this.x = x; this.y = y; this.z = z; this.ms = ms;
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.wValid = wValid; this.fromWire = fromWire;
        }
    }

    private volatile CamSample camSnap;

    /** Freshest camera sample, or null before the first. Read it ONCE per frame. */
    public CamSample camSnapshot() { return camSnap; }

    /**
     * Publishes a camera sample. {@code measure} is true for a Cmd 2001 sample
     * and false for the echo fallback, which must never be differenced.
     *
     * <p>Angular velocity is measured against the previous WIRE sample, then
     * exponentially smoothed. One pair is a difference of two quantised samples,
     * and the lead multiplies that difference by up to ~150 ms, so measurement
     * noise lands directly on the basis. Smoothing keeps one noisy pair from
     * becoming a visible wobble. (Arrival timing is already tight: Cmd 2001 is
     * measured at median 100 ms, p99 103 ms.)</p>
     */
    private void publishCamSample(float nx, float ny, float nz, long nowMs, boolean measure) {
        CamSample p = camSnap;
        if (measure && p != null && p.fromWire) {
            computeCameraAngVel(p.x, p.y, p.z, p.ms, nx, ny, nz, nowMs);
        } else {
            camAngVelValid = false;
        }
        float wx = 0f, wy = 0f, wz = 0f;
        boolean wValid = false;
        if (camAngVelValid) {
            float mx = camAngVelX, my = camAngVelY, mz = camAngVelZ;
            if (p != null && p.wValid) {
                wx = CAM_OMEGA_A * mx + (1f - CAM_OMEGA_A) * p.wx;
                wy = CAM_OMEGA_A * my + (1f - CAM_OMEGA_A) * p.wy;
                wz = CAM_OMEGA_A * mz + (1f - CAM_OMEGA_A) * p.wz;
            } else {
                wx = mx; wy = my; wz = mz;
            }
            wValid = true;
        }
        dirX = nx; dirY = ny; dirZ = nz;
        dirSampleMs = nowMs;
        camSnap = new CamSample(nx, ny, nz, nowMs, wx, wy, wz, wValid, measure);
    }

    /**
     * Camera pan rate, deg/s, or -1 when there is no usable measurement.
     *
     * <p>Published so a screenshot can certify itself. Every static
     * calibration here — zoom, boom, shoulder — is only valid on a SETTLED
     * view, because a stale camera basis slides every box together by
     * {@code rate x age}, and at a scoped 3.27x a gentle 20 deg/s pan is about
     * one box width. Without this on the HUD there is no way to tell a rig
     * error from a pan afterwards, and every previous reading of this symptom
     * was contaminated by exactly that ambiguity.</p>
     */
    public float camPanDegPerSec() {
        if (!camAngVelValid) return -1f;
        double m = Math.sqrt((double) camAngVelX * camAngVelX
                + (double) camAngVelY * camAngVelY
                + (double) camAngVelZ * camAngVelZ);
        return (float) Math.toDegrees(m) * 1000f;   // rad/ms -> deg/s
    }

    /** Age of the freshest camera-direction sample, ms; -1 before the first. */
    public long camSampleAgeMs(long now) {
        return dirSampleMs == 0L ? -1L : (now - dirSampleMs);
    }

    /** dt band (ms) for a keepalive pair to yield a usable angular velocity. */
    private static final long CAM_DT_MIN_MS = 60L;
    private static final long CAM_DT_MAX_MS = 160L;
    /**
     * A single-sample rotation beyond this is a camera cut / respawn / vehicle
     * snap, not a turn: drop the measurement rather than extrapolate through it.
     */
    private static final double CAM_CUT_RAD = Math.toRadians(120.0);
    /** Weight of the newest measured angular velocity in the smoothed one. */
    private static final float CAM_OMEGA_A = 0.55f; // A: angvel tracks turns faster
    /** Longest look-ahead the basis may extrapolate, ms. Bounded in time, not angle. */
    private static final double CAM_MAX_AHEAD_MS = 280.0; // A: allow longer cam lead while turning
    /** Smoothing of the measured send period, the divisor for camera angular velocity. */
    private static final double CAM_PERIOD_A = 0.1;
    private double camPeriodMs = 100.0;

    /**
     * Measures the angular velocity of the forward vector from the previous
     * sample (p*, at pMs) to the new sample (n*, at nMs) and stores it for
     * {@link #extrapolatedDir}. Invalidates the estimate when the gap is out of
     * the normal 60-160 ms band or the rotation is too large to be a turn.
     */
    private void computeCameraAngVel(float px, float py, float pz, long pMs,
                                     float nx, float ny, float nz, long nMs) {
        long dt = nMs - pMs;
        if (pMs == 0L || dt < CAM_DT_MIN_MS || dt > CAM_DT_MAX_MS) {
            camAngVelValid = false;
            return;
        }
        // The camera turns between SEND instants, and the game sends on a steady
        // ~100 ms grid (measured cadence: median 100, p99 103 ms). Arrival gaps
        // still carry tun-loop jitter, and dividing the angle by that arrival dt
        // puts the jitter straight into the rate, where the lead multiplies it
        // into the basis. Divide by a smoothed period instead.
        camPeriodMs += CAM_PERIOD_A * (dt - camPeriodMs);
        final double period = camPeriodMs;
        double pm = Math.sqrt((double) px * px + (double) py * py + (double) pz * pz);
        double nm = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (pm < 1e-6 || nm < 1e-6) { camAngVelValid = false; return; }
        double pxn = px / pm, pyn = py / pm, pzn = pz / pm;
        double nxn = nx / nm, nyn = ny / nm, nzn = nz / nm;
        // axis = p x n, angle = atan2(|p x n|, p.n)
        double crx = pyn * nzn - pzn * nyn;
        double cry = pzn * nxn - pxn * nzn;
        double crz = pxn * nyn - pyn * nxn;
        double sinMag = Math.sqrt(crx * crx + cry * cry + crz * crz);
        double dot = pxn * nxn + pyn * nyn + pzn * nzn;
        double ang = Math.atan2(sinMag, dot);
        if (ang > CAM_CUT_RAD) { camAngVelValid = false; return; }   // camera cut
        if (sinMag < 1e-7) {
            // No measurable rotation (steady view): a zero angular velocity is
            // valid and makes extrapolatedDir a no-op, which is what we want.
            camAngVelX = 0f; camAngVelY = 0f; camAngVelZ = 0f;
            camAngVelValid = true;
            return;
        }
        double speed = ang / period;      // rad/ms
        double inv = speed / sinMag;      // scales the (unnormalized) axis to axis*speed
        camAngVelX = (float) (crx * inv);
        camAngVelY = (float) (cry * inv);
        camAngVelZ = (float) (crz * inv);
        camAngVelValid = true;
    }

    /**
     * Fills {@code out[0..2]} with the forward unit vector of camera sample
     * {@code c}, rotated forward by {@code (now - c.ms) + leadMs} along its
     * smoothed angular velocity. Returns true when the rotation was applied;
     * false when there is no usable velocity, in which case {@code out} holds
     * the raw sample so the caller proceeds identically either way.
     *
     * <p>The lead is bounded in TIME ({@link #CAM_MAX_AHEAD_MS}), so a stalled
     * stream freezes the basis instead of spinning it. The old form clamped the
     * ANGLE to 30 deg. That clamp is a snap, not a bound: above roughly 200 deg/s
     * the lead saturated, and each new keepalive advanced the basis forward in one
     * step. Unclamped, the predicted angle is continuous across samples for as
     * long as the rate holds.</p>
     *
     * <p>Known trade-off, not a bug: prediction cannot see a stop until the next
     * keepalive. When a turn ends abruptly, the basis runs on for up to one
     * interval at the last rate, roughly {@code rate * (lead + 100 ms)}, then
     * snaps back. At 600 deg/s that is about 75 deg. Lowering
     * {@code EspConfig.CAM_EXTRAP_MS} shrinks it, at the cost of steady-turn lag
     * by the same amount of time.</p>
     */
    public static boolean extrapolatedDir(CamSample c, long now, double leadMs,
                                          double maxDeg, float[] out) {
        if (c == null || out == null || out.length < 3) return false;
        out[0] = c.x; out[1] = c.y; out[2] = c.z;
        if (!c.wValid) return false;
        double ahead = (double) (now - c.ms) + leadMs;
        if (ahead <= 0) return false;
        if (ahead > CAM_MAX_AHEAD_MS) ahead = CAM_MAX_AHEAD_MS;
        double wx = c.wx, wy = c.wy, wz = c.wz;
        double wmag = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wmag < 1e-9) return false;
        double theta = wmag * ahead;
        double maxRad = Math.toRadians(maxDeg);
        if (maxRad > 0 && theta > maxRad) theta = maxRad;
        if (theta < 1e-6) return false;
        double cm = Math.sqrt((double) c.x * c.x + (double) c.y * c.y + (double) c.z * c.z);
        if (cm < 1e-6) return false;
        double fx = c.x / cm, fy = c.y / cm, fz = c.z / cm;
        double ax = wx / wmag, ay = wy / wmag, az = wz / wmag;
        // Rodrigues: f' = f*cos + (a x f)*sin + a*(a.f)*(1-cos)
        double ct = Math.cos(theta), st = Math.sin(theta);
        double dotAF = ax * fx + ay * fy + az * fz;
        double krx = ay * fz - az * fy;
        double kry = az * fx - ax * fz;
        double krz = ax * fy - ay * fx;
        double rx = fx * ct + krx * st + ax * dotAF * (1 - ct);
        double ry = fy * ct + kry * st + ay * dotAF * (1 - ct);
        double rz = fz * ct + krz * st + az * dotAF * (1 - ct);
        double rm = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rm < 1e-6) return false;
        out[0] = (float) (rx / rm);
        out[1] = (float) (ry / rm);
        out[2] = (float) (rz / rm);
        return true;
    }

    /**
     * Wall-clock ms of the freshest camera-direction sample, or 0 before the
     * first one.
     *
     * <p>The aim controller needs this, not a guessed latency constant: the
     * error it measures describes the camera as of THIS instant, so everything
     * it injected afterwards is still in flight and must not be counted twice.
     * Cmd 2001 arrives every 100 ms (measured: median 100 ms, p99 103 ms over
     * 7 082 samples), so guessing costs up to a full sample of phase error --
     * which is most of the loop's dead time.</p>
     */
    public long dirSampleMs() {
        return dirSampleMs;
    }

    /**
     * Camera pitch in degrees from the freshest direction sample, +up.
     *
     * <p>The direction vector is unit length, so the pitch is just
     * asin(dirY). Exposed for the aim calibrator, which needs a ground-truth
     * angle to compare against a known injected drag -- that is how deg/px and
     * the loop latency get measured instead of guessed.</p>
     */
    public double camPitchDeg() {
        double y = dirY;
        if (y > 1.0) y = 1.0;
        else if (y < -1.0) y = -1.0;
        return Math.toDegrees(Math.asin(y));
    }

    /**
     * Freshness of own state from any source -- what a CONSUMER should ask.
     *
     * <p>Use this, not {@link #ownLastUpdateMs()}, to decide whether own
     * position and camera are usable. That one answers "is Cmd 2001 alive",
     * which is a question only {@link #mounted()} should be asking.</p>
     */
    public long ownStateMs() { return ownStateMs; }

    /**
     * Feeds one outbound Cmd 2002 / 2069 vehicle-state body.
     *
     * <p>First-hand own state while mounted, at the 99 ms outbound cadence
     * rather than the self-echo's 198 ms tick. Position always; camera basis
     * only from 2069, because 2002 carries no player aim at all.</p>
     *
     * <p><b>Does NOT touch {@code ownLastUpdateMs}</b> -- that field tracks
     * Cmd 2001 specifically and its silence is how {@link #mounted()} works.
     * <b>Does NOT push to the own-position history</b> -- {@code measureLag}
     * cross-correlates that history against the inbound self-echo, so seeding
     * it from any derived source collapses the estimate to zero. Both traps are
     * the same ones {@code refreshOwnFromEcho} documents.</p>
     */
    public void observeVehicleState(int cmd, byte[] body, int off, int len) {
        com.nepdon.wire.decode.body.VehicleSync.Result r =
                com.nepdon.wire.decode.body.VehicleSync.parse(cmd, body, off, len);
        if (r == null) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_VEHICLE_PARSE_REFUSED);
            return;                                // malformed or failed validation
        }
        long now = System.currentTimeMillis();
        final int src = (cmd == 2069) ? OWN_SRC_2069 : OWN_SRC_2002;
        if (src == OWN_SRC_2069) ownMs2069 = now; else ownMs2002 = now;

        // Position is the VEHICLE ROOT, not the player body. Same frame, same
        // millimetre scale as Cmd 2001, so no conversion -- but the camera rig
        // differs per vehicle and the on-foot boom is still applied downstream,
        // so expect a boom-shaped residual while mounted.
        if (mayWritePos(src, now)) {
            posX = (double) r.x;
            posZ = (double) r.up;
            posY = (double) r.z;
            ownPosSrc = src;
            ownStateMs = now;
        }

        if (r.hasAim) {
            // 2069 only: a real player aim forward, ~0.29 deg precision
            // against the self-echo's 1.41 deg.
            float m = (float) Math.sqrt(
                    r.aimX * r.aimX + r.aimUp * r.aimUp + r.aimZ * r.aimZ);
            if (m > 1e-4f && mayWriteDir(src, now)) {
                dirX = r.aimX / m;
                dirY = r.aimUp / m;
                dirZ = r.aimZ / m;
                dirSampleMs = now;
                ownDirSrc = src;
                camAngVelValid = false;
            }
        } else {
            // 2002 carries only the VEHICLE's rotation. That is emphatically
            // not a camera basis -- the player free-looks independently of
            // where the car points -- so the camera keeps coming from the
            // self-echo and only the position improves here.
            // 2002 carries no player aim. MEASURED 2026-09-15 across both
            // rides in the corpus: neither does anything else on the wire.
            // The inbound self-echo's moving aim field is just the VEHICLE's
            // facing re-encoded (median 0.5 deg, p90 3.5 deg from the forward
            // vector of the quaternion we ourselves sent, N=64), and the
            // echo's 3-D camera vector is byte-identical to its last pre-mount
            // value in 67 of 67 mounted samples -- the server stops publishing
            // our camera the moment we mount and resumes ~1.3 s after we get
            // off. See docs/MOUNTED_CAPTURE_ANALYSIS.md Q1.
            //
            // So the choice is not "vehicle heading vs the real camera". It is
            // "vehicle heading vs a camera frozen at the instant you got in",
            // and a frozen basis is what made every box and radar bearing
            // wrong for the whole ride. VEHICLE_PARSE_SPEC.md Sec 5.2 rejected
            // this source on the assumption that something better existed;
            // that assumption is now measured false and Sec 5.2 is retracted
            // for the mounted case.
            //
            // Taken from OUR OWN quaternion rather than the echo's copy of it:
            // first-hand, exact, 99 ms, no round-trip lag, no 1.41 deg
            // quantisation. It is still the body, not the view, so it is wrong
            // by the free-look angle whenever the rider looks away from the
            // direction of travel -- which is why MOUNT_VEHICLE_CAMERA exists
            // and why the HUD names the source.
            if (com.nepdon.wire.config.FeatureGate.MOUNT_CAM && MOUNT_VEHICLE_CAMERA && mayWriteDir(src, now)) {
                float fx = 2f * (r.qx * r.qz + r.qw * r.qy);
                float fz = 1f - 2f * (r.qx * r.qx + r.qy * r.qy);
                float h = (float) Math.sqrt(fx * fx + fz * fz);
                if (h > 1e-4f) {
                    dirX = fx / h;
                    dirZ = fz / h;
                    // Pitch is NOT recoverable. The quaternion's vertical
                    // component is terrain slope, not where the rider is
                    // looking, and using it would pitch the whole overlay on
                    // every hill. Level is the least-wrong constant.
                    dirY = 0f;
                    dirSampleMs = now;
                    ownDirSrc = OWN_SRC_2002Q;
                    ownMs2002q = now;
                }
            }
            // A heading is not a camera, and it is quantised besides. Never
            // extrapolate it.
            if (ownPosSrc == src) camAngVelValid = false;
        }
        com.nepdon.wire.diag.Diag.hit(cmd == 2069 ? com.nepdon.wire.diag.Slot.ESP_OWN_FROM_2069 : com.nepdon.wire.diag.Slot.ESP_OWN_FROM_2002);
    }

    /** Wall-clock of the last accepted vehicle-state record; 0 = never. */
    /** Which carrier last fed us: 2002, 2069, or 0. */

    /** One line naming the active own-state source, for the HUD. */
    public String ownSourceLine() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(96);
        sb.append("pos=").append(srcName(ownPosSrc))
          .append(" dir=").append(srcName(ownDirSrc))
          .append(" age=").append(ownStateMs == 0L ? -1L : (now - ownStateMs)).append("ms");
        if (mounted()) sb.append(" MNT");
        if (ownPosSrc == OWN_SRC_NONE && ownDirSrc == OWN_SRC_NONE) {
            sb.append("  NO OWN STATE");
        } else if (mounted() && ownPosSrc == OWN_SRC_ECHO && ownDirSrc == OWN_SRC_ECHO) {
            // Passenger seat, chair, cannon or catapult: nothing is sent at
            // all, so the echo is carrying everything. Name it, because this
            // is the state with no first-hand data of any kind.
            sb.append("  NO CARRIER -- echo only");
        }
        int rej = com.nepdon.wire.decode.body.VehicleSync.rejected();
        int vop = com.nepdon.wire.decode.body.VehicleSync.opVarintReads();
        if (rej > 0) sb.append("  rej=").append(rej);
        if (vop > 0) sb.append("  OP>=0x80 READ AS VARINT x").append(vop);
        return sb.toString();
    }

    private static String srcName(int src) {
        switch (src) {
            case OWN_SRC_2001: return "2001";
            case OWN_SRC_2069: return "2069";
            case OWN_SRC_2002: return "2002";
            case OWN_SRC_2002Q: return "2002q(heading!)";
            case OWN_SRC_ECHO: return "echo";
            default:           return "none";
        }
    }

    /** Live entity map (id -> track); dynamic, not capped. */
    public Map<Integer, Track> entities() { return entities; }

    /**
     * Live player counts by role, for the status title.
     *
     * <p>Fills {@code out} with {enemies, teammates, unclassified}. Counts only
     * entities seen within {@code ms}, and excludes our own self-echo -- the
     * question the title answers is "how many OTHER players can I see", and
     * including ourselves would make a solo drop read as 1.</p>
     *
     * <p>Unclassified is reported separately rather than folded into enemies,
     * because the two mean different things to the operator: a live
     * unclassified count means the friend/foe source has not arrived yet and
     * colours are still settling, which is exactly the "is it working yet"
     * question the title exists to answer.</p>
     *
     * @param out a 3-element array, reused by the caller; untouched if too small
     */
    public void roleCounts(long ms, int[] out) {
        if (out == null || out.length < 3) return;
        out[0] = 0; out[1] = 0; out[2] = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Track> e : entities.entrySet()) {
            Track t = e.getValue();
            if (now - t.updateMs >= ms) continue;
            if (t.dead) continue;
            int id = e.getKey();
            if (ownEntityId > 0 && id == ownEntityId) continue;   // never count self
            if (t.role == ROLE_SELF) continue;
            if (t.role == ROLE_TEAM) out[1]++;
            else if (t.role == ROLE_ENEMY) out[0]++;
            else out[2]++;
        }
    }

    /** Number of entities updated within the last ms milliseconds. */
    public int activeEntityCount(long ms) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Track t : entities.values()) {
            if (now - t.updateMs < ms) n++;
        }
        return n;
    }

    /** Live calibration readout for the overlay. */
    public String calibrationReadout() {
        return calibReadout;
    }

    private void updateCalibReadout() {
        StringBuilder sb = new StringBuilder();
        if (!maskValid) {
            sb.append("ESP LEARN (").append(maskSamples).append(")");
        } else {
            sb.append("ESP READY");
            if (maskLocked) sb.append(" (locked)");
        }
        sb.append(String.format("\nPOS %.0f,%.0f Z=%.0f", posX, posY, posZ));
        sb.append('\n').append(teamLine());
        sb.append(String.format("\nDIR %.3f,%.3f,%.3f yaw=%.0f\u00b0 pitch=%.0f\u00b0",
                dirX, dirY, dirZ,
                Math.toDegrees(yawRad()), Math.toDegrees(pitchRad())));
        if (airborne) sb.append(" AIR");
        long now = System.currentTimeMillis();
        int active = activeEntityCount(3000);
        int moving = 0;
        for (Track t : entities.values()) {
            if (now - t.updateMs < 3000 && now - t.lastMoveMs < 1500) moving++;
        }
        sb.append(String.format("\nSELF e%d  DMG %d  KILLS %d",
                ownEntityId, damageEvents, kills));
        sb.append("\nENT ").append(active).append(" live, ").append(moving).append(" moving")
          .append(" (ka ok/bad ").append(keepaliveParseOk).append("/").append(keepaliveParseBad).append(")");
        int shown = 0;
        for (Map.Entry<Integer, Track> e : entities.entrySet()) {
            if (shown >= 4) break;
            Track t = e.getValue();
            if (now - t.updateMs >= 3000) continue;
            double dx = t.x - posX;
            double dy = t.y - posY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double brg = Math.toDegrees(Math.atan2(dx, dy));
            if (brg < 0) brg += 360;
            String st = (now - t.lastMoveMs < 1500) ? "MOV" : "still";
            String tag = t.role == ROLE_SELF ? "S" : (t.role == ROLE_TEAM ? "T"
                    : (t.role == ROLE_ENEMY ? "E" : "?"));
            sb.append(String.format("\n%s%02d %s %.0fm @%.0f\u00b0 hp%d%s",
                    tag, e.getKey(), st, dist / UNITS_PER_METRE, brg, t.hp,
                    t.dead ? " DEAD" : ""));
            shown++;
        }
        calibReadout = sb.toString();
    }
}
