package com.nepdon.wire.decode.body;

import com.nepdon.wire.util.Leb128;
import com.nepdon.wire.util.ZigZag;

/**
 * Outbound Cmd 2002 {@code UDP_VEHICLE_STATE_SYNC} and Cmd 2069
 * {@code UDP_VEHICLEWEAPONFIRE_STATE_SYNC} — our own state while mounted.
 *
 * <h3>Why this exists</h3>
 *
 * <p>The game <b>never sends Cmd 2001 while mounted</b>.
 * {@code PNCPKGJGBPP::EAIKJCCNGMJ} tests {@code IsInVehicle()} before it can
 * reach the 2001 branch, so mounting anything freezes the only source of our
 * own position and camera. A self-echo fallback already covers this, but it is
 * quantised to 1.41°/LSB on a 198 ms tick; these carriers are first-hand at
 * 99 ms.</p>
 *
 * <h3>Which carrier is which</h3>
 *
 * <p><b>2069 for a horse, 2002 for a car.</b> The dispatcher branches on
 * {@code CanDriverFire()}, whose only overriding implementation in the entire
 * image is {@code MountableAnimal} — so a rideable animal takes the
 * weapon-fire path and a car does not. That matters because <b>2002 carries no
 * player aim vector at all</b>, only the vehicle's own rotation, while 2069 is
 * 2002 plus a real aim forward. On a horse the camera basis is recoverable; in
 * a car it is not, and the self-echo remains the only source.</p>
 *
 * <h3>The trap</h3>
 *
 * <p>Field 8 is <b>ONE RAW BYTE</b> sitting between the quaternion and the
 * velocity. A varint walk over it eats the following byte the moment its value
 * reaches 0x80, shifting every field after it. The two source documents
 * disagree about this field — one reads {@code Write(byte)} to
 * {@code WriteFixU8}, the other claims a varint, for the same serializer — so
 * this parser tries the raw-byte reading first and re-parses as a varint only
 * if validation fails. A counter records which the wire actually used; the
 * retry can only ever trigger when {@code op >= 0x80}.</p>
 *
 * <h3>Validation before use</h3>
 *
 * <p>Nothing here is trusted without the quaternion check: the four components
 * are scaled by 1000, so their squares must sum to about 1 000 000. A record
 * that fails is refused outright rather than half-applied — a partially parsed
 * own-state is worse than no update, because the renderer cannot tell it is
 * wrong.</p>
 *
 * <p><b>No on-wire sample of either command exists in the capture corpus</b>
 * (every Cmd 2002 record in 330 capture directories is the inbound batched
 * vehicle list, a different class). This parser is written from the
 * disassembly and is deliberately defensive: accept either command, validate,
 * and fall back to the existing self-echo path when anything looks wrong.</p>
 */
public final class VehicleSync {

    private VehicleSync() {}

    /** {@code UDP_VEHICLE_STATE_SYNC} — driver of a car/bike. No player aim. */
    public static final int CMD_VEHICLE = 2002;
    /** {@code UDP_VEHICLEWEAPONFIRE_STATE_SYNC} — horse/mount. Has player aim. */
    public static final int CMD_VEHICLE_FIRE = 2069;

    /** True when {@code cmd} is one this parser handles. */
    public static boolean isVehicleCarrier(int cmd) {
        return cmd == CMD_VEHICLE || cmd == CMD_VEHICLE_FIRE;
    }

    /** Parsed result. Reused per thread; copy anything you keep. */
    public static final class Result {
        /** Vehicle LevelVehicle object id — NOT an ESP entity id. */
        public long vehicleId;
        /** Vehicle world position in millimetres, wire order (X, UP, Z). */
        public long x, up, z;
        /** Vehicle rotation quaternion, already divided by 1000. */
        public float qx, qy, qz, qw;
        /** Vehicle velocity in m/s. */
        public float vx, vup, vz;
        /** Undecoded input bitfield. */
        public int packedOp;
        /** Server tick — NOT a millisecond clock. */
        public long serverTick;
        /** True only for Cmd 2069: the player aim forward is present. */
        public boolean hasAim;
        /** Player aim forward, unit-ish, only when {@link #hasAim}. */
        public float aimX, aimUp, aimZ;
        /** Client millisecond clock, only when {@link #hasAim}. */
        public long clientMs;
        /** True when field 8 had to be re-read as a varint. */
        public boolean opWasVarint;
    }

    private static final ThreadLocal<Result> SCRATCH = new ThreadLocal<Result>() {
        @Override protected Result initialValue() { return new Result(); }
    };
    private static final ThreadLocal<int[]> CURSOR = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };

    /** Records that needed the varint re-read; a permanent 0 confirms raw-byte. */
    private static volatile int opVarintReads = 0;
    /** Records refused by the quaternion check. */
    private static volatile int rejected = 0;

    public static int opVarintReads() { return opVarintReads; }
    public static int rejected() { return rejected; }

    /**
     * Parses one outbound vehicle-state body.
     *
     * @param cmd 2002 or 2069
     * @return the parsed record, or null when the body is malformed or fails
     *         validation. Never a partial result.
     */
    public static Result parse(int cmd, byte[] b, int off, int len) {
        if (!isVehicleCarrier(cmd) || b == null || off < 0 || len <= 0
                || off + len > b.length) {
            return null;
        }
        Result r = tryParse(cmd, b, off, len, false);
        if (r != null) return r;
        // Field 8 may be a varint after all (the two source docs disagree).
        // This branch is reachable only when that byte is >= 0x80.
        r = tryParse(cmd, b, off, len, true);
        if (r != null) {
            r.opWasVarint = true;
            opVarintReads++;
            return r;
        }
        rejected++;
        return null;
    }

    private static Result tryParse(int cmd, byte[] b, int off, int len, boolean opAsVarint) {
        int[] p = CURSOR.get();
        p[0] = off;
        int end = off + len;
        Result r = SCRATCH.get();
        r.opWasVarint = false;

        long v = Leb128.read(b, p, end);
        if (v == Leb128.BAD) return null;
        r.vehicleId = v;

        long[] pos = new long[3];
        for (int i = 0; i < 3; i++) {
            long q = Leb128.read(b, p, end);
            if (q == Leb128.BAD) return null;
            pos[i] = ZigZag.decode(q);
        }
        r.x = pos[0]; r.up = pos[1]; r.z = pos[2];

        float[] quat = new float[4];
        for (int i = 0; i < 4; i++) {
            long q = Leb128.read(b, p, end);
            if (q == Leb128.BAD) return null;
            quat[i] = ZigZag.decode(q) / 1000.0f;
        }
        r.qx = quat[0]; r.qy = quat[1]; r.qz = quat[2]; r.qw = quat[3];

        // FIELD 8: ONE RAW BYTE by default. See the class javadoc.
        if (opAsVarint) {
            long op = Leb128.read(b, p, end);
            if (op == Leb128.BAD) return null;
            r.packedOp = (int) (op & 0xFF);
        } else {
            if (p[0] >= end) return null;
            r.packedOp = b[p[0]] & 0xFF;
            p[0]++;
        }

        float[] vel = new float[3];
        for (int i = 0; i < 3; i++) {
            long q = Leb128.read(b, p, end);
            if (q == Leb128.BAD) return null;
            vel[i] = ZigZag.decode(q) / 100.0f;
        }
        r.vx = vel[0]; r.vup = vel[1]; r.vz = vel[2];

        long tick = Leb128.read(b, p, end);
        if (tick == Leb128.BAD) return null;
        r.serverTick = tick;

        r.hasAim = false;
        if (cmd == CMD_VEHICLE_FIRE) {
            float[] aim = new float[3];
            for (int i = 0; i < 3; i++) {
                long q = Leb128.read(b, p, end);
                if (q == Leb128.BAD) return null;
                aim[i] = ZigZag.decode(q) / 100.0f;
            }
            long ms = Leb128.read(b, p, end);
            if (ms == Leb128.BAD) return null;
            r.aimX = aim[0]; r.aimUp = aim[1]; r.aimZ = aim[2];
            r.clientMs = ms;
            r.hasAim = true;
        }

        // TERMINAL CURSOR: the body must be consumed exactly. A record that
        // leaves bytes over parsed to a different shape than the one intended.
        if (p[0] != end) return null;

        if (!quaternionSane(r)) return null;
        if (r.hasAim && !aimSane(r)) return null;
        return r;
    }

    /**
     * The quaternion must be a unit quaternion scaled by 1000, so the sum of
     * squares is ~1 000 000. A 5% band absorbs the per-component quantisation
     * without admitting a mis-parse, which shifts a whole field and misses by
     * orders of magnitude rather than percent.
     */
    private static boolean quaternionSane(Result r) {
        double s = (double) r.qx * r.qx + (double) r.qy * r.qy
                + (double) r.qz * r.qz + (double) r.qw * r.qw;
        return s > 0.90 && s < 1.10;
    }

    /** The aim forward is a direction; its length must be near 1. */
    private static boolean aimSane(Result r) {
        double s = (double) r.aimX * r.aimX + (double) r.aimUp * r.aimUp
                + (double) r.aimZ * r.aimZ;
        return s > 0.80 && s < 1.25;
    }

}
