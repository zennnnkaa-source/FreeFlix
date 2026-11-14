package com.nepdon.wire.esp;

import com.nepdon.wire.decode.body.PriSync;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Real per-player health, read off the wire instead of guessed.
 *
 * <p><b>What was wrong.</b> {@code Track.hp} started at
 * {@code MAX_HP} and was only ever decremented by damage <i>we</i> dealt — the
 * code said as much: "HP is a lower bound". So an enemy we had never shot read
 * 200 forever, damage from anyone else was invisible, healing and revives never
 * registered, and the number drifted pessimistic all match. That is the "it
 * shows 120, then 72, then a random value" report: those were our own past hits
 * subtracted from a fiction.</p>
 *
 * <p><b>The real source</b> is Cmd 1900 {@code RUDP_VAR_SYNC_PRI_DATA}, a keyed
 * property stream (see {@link PriSync}). Property 0 is
 * {@code EPRI_PLAYER_CUR_HP}, confirmed against HUD screenshots (n=20) and
 * again here: over a 16.8-minute match capture the values form a clean 0-200
 * continuum (200/199/198/197/196/195/194/193/192/191/189/188/185/184/182/...),
 * exactly the shape real health has.</p>
 *
 * <p><b>The hard part is the join.</b> Cmd 1900 keys on
 * {@code ownerReplicationId}, an arbitrary server-assigned int with <b>no</b>
 * relation to the ESP handle {@code (team<<24)|id}. It cannot be derived; it
 * has to be learned. This capture carries 328 distinct owners (270 of them with
 * health) against a few dozen players, so most owners are not even people —
 * binding the wrong one would put a vehicle's health on a player's box.</p>
 *
 * <p>The binding used here is the damage correlation documented in
 * {@code docs/COMBAT_AND_HEALTH.md} §1: when owner <i>O</i>'s HP drops by
 * &Delta;, a Cmd 2010 damage event naming victim handle <i>H</i> with the same
 * &Delta; lands within a quarter second. Measured purity 98.2% (588/599) over
 * 16 owners. 98.2% is not good enough to trust a single coincidence, so a
 * binding is only accepted after {@value #CONFIRM_VOTES} independent agreements
 * and is dropped the moment a contradiction appears.</p>
 */
public final class PriTracker {

    /** Agreements required before a binding is trusted. */
    private static final int CONFIRM_VOTES = 2;
    /** Damage and the HP drop it caused must land within this window. */
    private static final long CORRELATE_MS = 250L;
    /**
     * Equipment-change correlation window.
     *
     * <p>The window can be this wide only because the join is STRICT: both
     * halves of EPRI_ITEM_ON_HAND -- the inventory instance id and the weapon
     * id -- must appear in the same Cmd 121 message. Matching a single field
     * needs +/-150 ms to stay unambiguous and binds 19 owners; matching both
     * halves at +/-350 ms binds <b>51 with zero ambiguity and 28/28 agreement
     * with the authoritative Cmd 118 answer</b>. Strictness buys the window.</p>
     */
    private static final long EQUIP_MS = 350L;
    /** Forget an owner that has said nothing for this long. */
    private static final long OWNER_STALE_MS = 30_000L;
    /**
     * Health is LATCHED, not polled — there is no staleness rule, by design.
     *
     * <p>Measured over 4 662 player samples: only 0.37% of updates repeat the
     * previous value, so the stream is change-driven, and the p99 gap between
     * updates is <b>42.7 seconds</b> (a healthy player out of combat simply
     * says nothing). An earlier 15 s staleness cut-off was therefore below the
     * normal quiet period and would blank the bar on exactly the players who
     * are at full health. Silence means "unchanged", not "lost" — unlike
     * position, which really does go stale.</p>
     */
    public static final long HP_STALE_MS = Long.MAX_VALUE;
    /** Plausible bounds for a real max-HP announcement. */
    private static final int MAX_HP_MIN = 50, MAX_HP_MAX = 2000;

    /** Everything Cmd 1900 tells us about one replication owner. */
    private static final class Owner {
        int curHp = -1;
        int maxHp = -1;
        int curEp = -1;
        int prevHp = -1;
        long prevHpMs;
        long updatedMs;
        /** Entity handle this owner is believed to be, or -1. */
        int handle = -1;
        int votes;
        int candidate = -1;
        /**
         * 1 = the server called this a player, 0 = explicitly not one,
         * -1 = not yet stated.
         *
         * <p>This matters because the EPRI key namespace is PER ENTITY TYPE,
         * selected by the entity's ReplicationEntityTag. varId 0 is
         * EPRI_PLAYER_CUR_HP only for a player; on a vehicle or a plant the
         * same id is a different property entirely. A capture carries 181
         * non-player binds against 28 player ones, so reading health off
         * whatever happens to be in slot 0 would be reading crates.</p>
         */
        int isPlayer = -1;
    }

    /** A recent HP drop waiting to be matched against a damage event. */
    private static final class Drop {
        long owner;
        int delta;
        long atMs;
    }

    /** A recent equipment identity, from either side of the join. */
    private static final class Equip {
        long hi;         // onHand >> 32, inventory instance id  (Cmd 1900 side)
        long lo;         // onHand & 0xFFFFFFFF, weapon id       (Cmd 1900 side)
        long owner;
        int handle;      // Cmd 121 side
        long[] vals;     // Cmd 121 side: the message's varint set
        int nVals;
        long atMs;
    }

    private final ConcurrentHashMap<Long, Owner> owners = new ConcurrentHashMap<>();
    /** owner -> handle, only once confirmed. */
    private final ConcurrentHashMap<Long, Integer> bound = new ConcurrentHashMap<>();
    /** handle -> owner, the direction the renderer asks in. */
    private final ConcurrentHashMap<Integer, Long> byHandle = new ConcurrentHashMap<>();

    /**
     * slot -> squad byte, straight off Cmd 118.
     *
     * <p>This value was computed on every bind and thrown away. It is the
     * SERVER'S OWN friend/foe answer -- {@code PlayerID = (team &lt;&lt; 24) | slot},
     * the same packing the entity handle and Cmd 101 use -- and it arrives in
     * the match-start burst, before anyone has been shot at. Discarding it is
     * why the ESP had to guess team from geometry, and why it guessed
     * backwards in Battle Royale. See docs/FRIEND_FOE_AUTHORITATIVE.md.</p>
     */
    private final ConcurrentHashMap<Integer, Integer> slotSquad = new ConcurrentHashMap<>();

    /** Small ring of unmatched HP drops; correlation is a quarter-second game. */
    private final Drop[] drops = new Drop[64];
    private int dropHead = 0;

    /** Recent onHand identities from Cmd 1900, awaiting a Cmd 121. */
    private final Equip[] mine = new Equip[128];
    private int mineHead = 0;
    /** Recent Cmd 121 equipment changes, awaiting an onHand. */
    private final Equip[] theirs = new Equip[128];
    private int theirsHead = 0;

    private volatile int properties = 0;
    private volatile int hpUpdates = 0;
    private volatile int conflicts = 0;
    private volatile int nonPlayers = 0;

    public PriTracker() {
        for (int i = 0; i < drops.length; i++) drops[i] = new Drop();
        for (int i = 0; i < mine.length; i++) mine[i] = new Equip();
        for (int i = 0; i < theirs.length; i++) theirs[i] = new Equip();
    }

    /** Clears everything. Owner ids are reassigned per match. */
    /**
     * The squad byte Cmd 118 announced for a chain slot, or -1.
     *
     * <p>Authoritative and early: this is the server's own team id, not an
     * inference. Prefer it over every heuristic.</p>
     */
    public int squadFor(int slot) {
        Integer s = slotSquad.get(slot);
        return (s == null) ? -1 : s;
    }

    public void reset() {
        owners.clear();
        bound.clear();
        byHandle.clear();
        slotSquad.clear();
        for (Drop d : drops) { d.owner = 0; d.delta = 0; d.atMs = 0; }
        for (Equip e : mine) e.atMs = 0;
        for (Equip e : theirs) e.atMs = 0;
        properties = 0;
        hpUpdates = 0;
        conflicts = 0;
        nonPlayers = 0;
    }

    /** One decoded property from Cmd 1900. */
    public void onProperty(long ownerId, int varId, long value, long nowMs) {
        properties++;
        Owner o = owners.get(ownerId);
        if (o == null) {
            o = new Owner();
            Owner prev = owners.putIfAbsent(ownerId, o);
            if (prev != null) o = prev;
        }
        o.updatedMs = nowMs;
        // EPRI ids: baked defaults, OR — when the server leash is armed — sourced
        // from the server decode profile, so the health-decode key namespace lives
        // server-side (a cracked offline client without a profile can't map varIds
        // to fields). Parity-proven equal (tools/verify_decode_maps.py); the logic
        // below is unchanged. (A switch needs constant case labels, so this is an
        // if-chain over the resolved ids.)
        final boolean priSrv = com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && com.nepdon.wire.auth.ConfigStore.priLayoutArmed();
        final int idCurHp  = priSrv ? com.nepdon.wire.auth.ConfigStore.priCurHp()  : PriSync.EPRI_CUR_HP;
        final int idMaxHp  = priSrv ? com.nepdon.wire.auth.ConfigStore.priMaxHp()  : PriSync.EPRI_MAX_HP;
        final int idOnHand = priSrv ? com.nepdon.wire.auth.ConfigStore.priOnHand() : PriSync.EPRI_ON_HAND;
        final int idCurEp  = priSrv ? com.nepdon.wire.auth.ConfigStore.priCurEp()  : PriSync.EPRI_CUR_EP;
        // WHICH scope. varId 12 EPRI_PLAYER_SIGHTING_ID is named in the game's
        // own property enum and has been arriving here on every match since this
        // decoder was written -- and been dropped on the floor by the if-chain
        // below. It is forwarded RAW to the probe in ScopeState; nothing here
        // interprets it, because nothing has measured it yet.
        //
        // Note the leash fallback: a server layout that omits "sighting" leaves
        // priSighting at -1, and -1 matches no varId in 0..254 -- so the reader
        // would go permanently, silently quiet. Falling back to the baked 12
        // instead is the difference between a dead probe and a working one.
        final int idSight  = (priSrv && com.nepdon.wire.auth.ConfigStore.priSighting() >= 0)
                ? com.nepdon.wire.auth.ConfigStore.priSighting() : PriSync.EPRI_SIGHTING;
        if (varId == idSight) {
            com.nepdon.wire.protocol.ScopeState.observeSighting(ownerId, value);
            return;
        }
        if (varId == idCurHp) {
            // varId 0 is CUR_HP only for a PLAYER entity; the key namespace is per
            // entity type. If Cmd 118 has told us this owner is not a player, slot
            // 0 means something else.
            if (o.isPlayer == 0) return;
            int hp = (int) value;
            // Health is a small non-negative number. Anything else is a property
            // we have mis-identified, not a 4-billion-HP player.
            if (hp < 0 || hp > MAX_HP_MAX) return;
            int before = o.curHp;
            o.curHp = hp;
            hpUpdates++;
            if (before > hp) {
                noteDrop(ownerId, before - hp, nowMs);
            }
            o.prevHp = before;
            o.prevHpMs = nowMs;
        } else if (varId == idMaxHp) {
            // Trustworthy — but ONLY on a confirmed player. Ungated, var 1 looks
            // like garbage (0/1/2, 6000, 201326641) and that was mistaken for the
            // field being unreliable in this build. It is not: restricted to the
            // Cmd-118 type-1 player set it reads 200 in 27/27, and every junk value
            // belongs to a non-player owner where varId 1 is a different property
            // entirely (201326610 is the entity handle 0x0C000012). Using the real
            // value matters for modes and characters whose max is not 200.
            if (o.isPlayer == 0) return;
            int m = (int) value;
            if (m >= MAX_HP_MIN && m <= MAX_HP_MAX) o.maxHp = m;
        } else if (varId == idOnHand) {
            // (inventoryItemId << 32) | weaponId. The HIGH half is an inventory
            // instance id that Cmd 121 also names, which is what makes the join
            // possible without anyone having to shoot.
            long hi = value >>> 32;
            long lo = value & 0xFFFFFFFFL;
            if (hi != 0 && lo != 0) {
                noteMine(ownerId, hi, lo, nowMs);
                // The fixed-scope fallback for tier: AWM/Kar98k/M24 ship 8x and
                // SKS/SVD/AC80 are fixed 4x, so the weapon id narrows the
                // magnification even if varId 12 turns out not to carry it.
                com.nepdon.wire.protocol.ScopeState.observeWeapon(lo);
            }
        } else if (varId == idCurEp) {
            int ep = (int) value;
            if (ep >= 0 && ep <= MAX_HP_MAX) o.curEp = ep;
        }
    }

    private void noteDrop(long owner, int delta, long nowMs) {
        if (delta <= 0 || delta > MAX_HP_MAX) return;
        dropHead = (dropHead + 1) % drops.length;
        Drop d = drops[dropHead];
        d.owner = owner;
        d.delta = delta;
        d.atMs = nowMs;
    }

    private void noteMine(long owner, long hi, long lo, long nowMs) {
        mineHead = (mineHead + 1) % mine.length;
        Equip e = mine[mineHead];
        e.hi = hi;
        e.lo = lo;
        e.owner = owner;
        e.handle = -1;
        e.atMs = nowMs;
        matchEquip(nowMs);
    }

    /**
     * A Cmd 121 {@code RUDP_EQUIPMENT_CHANGED}: {@code handle} equipped the
     * inventory instance {@code inv}. This is the PRIMARY binding source -- it
     * fires 1 122 times in a match against 629 damage events, and needs no
     * combat at all, so it binds players we have never fought.
     */
    public void onEquipment(int handle, long[] vals, int n, long nowMs) {
        if (handle < 1 || handle > 127 || vals == null || n <= 0) return;
        theirsHead = (theirsHead + 1) % theirs.length;
        Equip e = theirs[theirsHead];
        e.owner = -1L;
        e.handle = handle;
        e.vals = vals.clone();
        e.nVals = n;
        e.atMs = nowMs;
        matchEquip(nowMs);
    }

    /**
     * Joins the two sides on the inventory instance id.
     *
     * <p>Requires the match to be UNAMBIGUOUS: exactly one owner and exactly
     * one handle in the window. Equipment events arrive in bursts (everyone
     * loots at once), and without this test the bursts produce plausible-looking
     * cross-matches. Measured, the test is the difference between 19/19
     * unanimous bindings and 47 bindings of which only 6 were.</p>
     */
    private void matchEquip(long nowMs) {
        for (Equip m : mine) {
            if (m.atMs == 0 || Math.abs(nowMs - m.atMs) > EQUIP_MS) continue;
            long owner = -1L;
            int handle = -1;
            for (Equip t : theirs) {
                if (t.atMs == 0 || t.vals == null) continue;
                if (Math.abs(m.atMs - t.atMs) > EQUIP_MS) continue;
                if (!hasBoth(t, m.hi, m.lo)) continue;
                if (handle != -1 && handle != t.handle) { handle = -2; break; }
                handle = t.handle;
                owner = m.owner;
            }
            if (handle > 0 && owner != -1L) vote(owner, handle);
        }
    }

    /** Both halves of onHand must appear in the same Cmd 121 message. */
    private static boolean hasBoth(Equip t, long hi, long lo) {
        if (hi == 0 || lo == 0) return false;
        boolean gotHi = false, gotLo = false;
        for (int i = 0; i < t.nVals; i++) {
            long v = t.vals[i];
            if (v == hi) gotHi = true;
            else if (v == lo) gotLo = true;
        }
        return gotHi && gotLo;
    }

    /**
     * Cmd 118 {@code RUDP_BIND_PRI} — the server stating outright which
     * replication owner belongs to which entity. This is AUTHORITATIVE and
     * supersedes both correlation heuristics.
     *
     * <p>Record is {@code (U owner, B type, U packedEntity)}. Only
     * {@code type == 1} is a player: measured over a real match, the type-1
     * records are 28 rows giving <b>26 owners to 26 distinct slots, 1:1 with
     * zero collisions</b>, slots 15-53 (exactly the chain id range) and squad
     * bytes 2-15; every other type (13, 25, 72, ...) has a zero squad byte and
     * is a world object — a crate or a door, not a person. 21 of those 26
     * owners also report health, so this single message binds more players,
     * with certainty, than either statistical method inferred.</p>
     *
     * <p>The heuristics are kept because this message is reliable and can be
     * missed during the pre-key window at match handover, where the header
     * layout is not yet solved and whole roster bursts decode as unknown.</p>
     */
    public void onBind(long owner, int type, long packedEntity) {
        int slot = (int) (packedEntity & 0xFFFFFFL);
        int squad = (int) ((packedEntity >>> 24) & 0xFF);
        boolean player = (type == 1) && squad != 0 && slot >= 1 && slot <= 127;
        // Keep the squad byte. Only a type-1 record describes a person; every
        // other type carries squad 0 and is a crate or a door, so recording
        // those would hand the classifier a team of 0 for world objects.
        if (player && squad <= 254) slotSquad.put(slot, squad);
        Owner o = owners.get(owner);
        if (o == null) {
            o = new Owner();
            Owner prev = owners.putIfAbsent(owner, o);
            if (prev != null) o = prev;
        }
        if (!player) {
            // Remember the negative too: it lets us REFUSE health from this
            // owner rather than merely decline to bind it, which is the
            // difference between showing nothing and showing a crate's
            // property as somebody's HP.
            o.isPlayer = 0;
            o.curHp = -1;
            nonPlayers++;
            return;
        }
        o.isPlayer = 1;
        bindAuthoritative(owner, slot);
    }

    private void bindAuthoritative(long ownerId, int handle) {
        Owner o = owners.get(ownerId);
        if (o == null) {
            o = new Owner();
            Owner prev = owners.putIfAbsent(ownerId, o);
            if (prev != null) o = prev;
        }
        Long old = byHandle.put(handle, ownerId);
        if (old != null && old != ownerId) {
            bound.remove(old);
            Owner oo = owners.get(old);
            if (oo != null) { oo.handle = -1; oo.votes = 0; oo.candidate = -1; }
        }
        o.handle = handle;
        o.candidate = handle;
        o.votes = Integer.MAX_VALUE;                 // never overridden by a guess
        bound.put(ownerId, handle);
    }

    /**
     * A Cmd 2010 damage event: {@code victim} took {@code damage}. Correlates
     * against recent HP drops to learn which owner is which entity.
     */
    public void onDamage(int victimHandle, int damage, long nowMs) {
        if (victimHandle < 1 || victimHandle > 127 || damage <= 0) return;
        for (Drop d : drops) {
            if (d.atMs == 0 || d.delta != damage) continue;
            long dt = nowMs - d.atMs;
            if (dt < -CORRELATE_MS || dt > CORRELATE_MS) continue;
            vote(d.owner, victimHandle);
            d.atMs = 0;          // consume it; one drop binds at most once
            return;
        }
    }

    private void vote(long ownerId, int handle) {
        Owner o = owners.get(ownerId);
        if (o == null) return;
        if (o.votes == Integer.MAX_VALUE) return;       // server said so; do not guess over it
        if (o.handle == handle) return;                 // already settled
        if (o.candidate == handle) {
            o.votes++;
        } else {
            if (o.candidate != -1) conflicts++;
            o.candidate = handle;
            o.votes = 1;
        }
        if (o.votes < CONFIRM_VOTES) return;
        // A handle can only belong to one owner. If something else claimed it,
        // the newer evidence wins and the old binding is dropped rather than
        // left to show one player's health on another's box.
        Long old = byHandle.put(handle, ownerId);
        if (old != null && old != ownerId) {
            bound.remove(old);
            Owner oo = owners.get(old);
            if (oo != null) { oo.handle = -1; oo.votes = 0; oo.candidate = -1; }
            conflicts++;
        }
        o.handle = handle;
        bound.put(ownerId, handle);
    }

    /** Current HP for an entity handle, or -1 when unknown or stale. */
    public int hpFor(int handle, long nowMs) {
        Long id = byHandle.get(handle);
        if (id == null) return -1;
        Owner o = owners.get(id);
        if (o == null || o.curHp < 0) return -1;
        return o.curHp;
    }

    /** Announced max HP for an entity handle, or -1 when not known. */
    public int maxHpFor(int handle) {
        Long id = byHandle.get(handle);
        if (id == null) return -1;
        Owner o = owners.get(id);
        return (o == null) ? -1 : o.maxHp;
    }

    /** One line for the dev HUD. */
    public String debugLine() {
        return "owners=" + owners.size()
                + " bound=" + byHandle.size()
                + " props=" + properties
                + " hp=" + hpUpdates
                + " nonplayer=" + nonPlayers
                + (conflicts > 0 ? " conflict=" + conflicts : "");
    }
}
