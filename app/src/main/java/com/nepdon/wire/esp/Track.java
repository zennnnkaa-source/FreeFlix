package com.nepdon.wire.esp;

/**
 * One tracked entity. Promoted out of {@link EspEngine} 2026-09-20; the code is
 * unchanged.
 *
 * <p><b>It stays in package {@code esp} on purpose.</b> Several fields are
 * package-private ({@code pendingTeleport}, {@code pendingX/Y/Z}) and are
 * written by {@code EspEngine}'s teleport-corroboration path. Moving this into
 * a sub-package would make them invisible and the build would fail -- which is
 * the good case; the bad case is someone widening them to {@code public} to
 * make the move work and quietly enlarging the surface.</p>
 *
 * <p><b>Every field here is {@code volatile} and must stay that way.</b> They
 * are written on the VPN packet threads and read on the UI thread in
 * {@code EspRenderView.update()}; the renderer reads them as raw fields
 * (~20 sites) because {@code onDraw} allocates nothing and an accessor would
 * not pay for itself at 120 Hz. Dropping a {@code volatile} in a move like this
 * costs nothing at compile time and loses cross-thread visibility silently.</p>
 */
public final class Track {
    public volatile double x, z, y;
    public volatile long updateMs = 0L;
    public volatile long lastMoveMs = 0L;
    public volatile int updates = 0;
    /** Pending teleport (respawn corroboration, census §8). */
    volatile boolean pendingTeleport = false;
    volatile double pendingX, pendingZ, pendingY;
    /** Samples seen within the teammate proximity radius. */
    /**
     * Where {@link #team} came from; higher wins. A weaker source must
     * never overwrite a stronger one, and the HUD uses it to say WHY a box
     * is the colour it is.
     */
    public volatile int teamSrc = EspEngine.TEAM_SRC_NONE;
    /** Classification: 0 unknown, 1 self, 2 teammate, 3 enemy. */
    public volatile int role = EspEngine.ROLE_UNKNOWN;
    /** HP lower bound from the damage feed; 200 = full / unknown. */
    public volatile int hp = EspEngine.MAX_HP;
    /** Wall-clock of the hit that took this entity to 0. */
    public volatile long deathMs = 0L;
    /** True between a lethal hit and the next position update burst. */
    public volatile boolean dead = false;
    /** Wall-clock of the last damage event naming this entity. */
    public volatile long lastDamageMs = 0L;
    /** Team came from a handle (authoritative), not proximity. */
    public volatile boolean teamKnown = false;
    /** Squad index from the object handle; 0 = not yet seen. */
    public volatile int team = 0;
    /** Wall-clock of this entity's FIRST chain sighting. */
    public volatile long firstSeenMs = 0L;

    // ---- pose (fixed-width bytes 7,8,9 of the remote record) ----
    /** Body / leg yaw in degrees, 0 = +Y, increasing toward +X. */
    public volatile float bodyYawDeg = 0f;
    /** Aim / head yaw in degrees, same frame as bodyYawDeg. */
    public volatile float aimYawDeg = 0f;
    /** Aim pitch in degrees; negative looks down. */
    public volatile float aimPitchDeg = 0f;
    /** Movement mode enum (ground / jump / parachute / vehicle). */
    public volatile int mode = 0;
    /** Action state enum (idle / sprint / crawl / airborne / falling). */
    public volatile int state = 0;
    /** True once a pose has been decoded for this entity. */
    public volatile boolean poseValid = false;

    /** True once this player was seen riding, per Cmd 125 + the 2002 list. */
    public volatile boolean inVehicle = false;
    /** Vehicle object id they are riding, 0 when on foot. */
    public volatile int vehicleId = 0;
    /** The vehicle list's own occupancy bit for that vehicle. */
    public volatile boolean vehicleOccupied = false;

    // ---- dead reckoning ----
    // The server streams each entity's position only a few times a
    // second, but we draw at display refresh. Rendering the last known
    // position therefore puts the box where the target WAS, which is the
    // "box is ahead of / behind the player" lag. We keep a smoothed
    // velocity so the renderer can carry the position forward between
    // updates. Units are game-units per MILLISECOND (the renderer works
    // in ms), horizontal vx/vy and vertical vz to match x/y/z above.
    public volatile double vx = 0, vy = 0, vz = 0;
    /** Position and wall-clock of the previous accepted update. */
    volatile double prevX, prevY, prevZ;
    volatile long prevMs = 0L;
    /** True once at least one velocity sample has been folded in. */
    public volatile boolean velValid = false;
}
