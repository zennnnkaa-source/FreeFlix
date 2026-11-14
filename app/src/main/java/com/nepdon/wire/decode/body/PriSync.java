package com.nepdon.wire.decode.body;

/**
 * Live decoder for Cmd 1900 {@code RUDP_VAR_SYNC_PRI_DATA} — the keyed
 * property stream where real player health actually lives.
 *
 * <p><b>Why this exists.</b> The ESP used to invent health: every track started
 * at {@code MAX_HP} and was only ever decremented by damage <i>we</i> dealt, with
 * the code itself admitting "HP is a lower bound". Damage from anyone else,
 * healing, med kits, revives and armour were all invisible, so the number on
 * screen was fiction that happened to start at 200. Health is not a field in a
 * state packet — it is a property update in this stream, and nothing was reading
 * the channel.</p>
 *
 * <p><b>Grammar</b> (authority {@code GCommon.ReplicationDataPool::
 * SyncReplicationData}; measured 6 709/6 709 packets parsing with exact byte
 * consumption and zero residue across three keyed sessions, plus 46/46 for
 * Cmd 1901 — see {@code docs/COMBAT_AND_HEALTH.md} §1):</p>
 *
 * <pre>
 *   body   := group+
 *   group  := varint  ownerReplicationId     (1900 only; 1901 omits it)
 *             u16 LE  blockLen               ReadFixUInt16 — 2 RAW BYTES
 *             record*                        exactly blockLen bytes
 *   record := u8      varId                  ReadByte — 1 RAW BYTE
 *             varint  value
 * </pre>
 *
 * <p><b>The two traps.</b> {@code blockLen} is two raw little-endian bytes and
 * {@code varId} is one raw byte — neither is a varint, and treating either as
 * one desynchronises the whole walk. Everything else is a plain LEB128 varint,
 * including floats (the game's {@code ReadSingle} reads a varint and
 * reinterprets the bits), so an unknown {@code varId} can always be skipped as
 * exactly one varint without losing framing.</p>
 */
public final class PriSync {

    private PriSync() {}

    // ---- property ids, message.NIPHKJAOKGI (256 members, ordinals 0..255) ----
    //
    // THE KEY NAMESPACE IS PER ENTITY TYPE, selected by the entity's
    // ReplicationEntityTag. These ids are the PLAYER namespace; sibling enums
    // exist for vehicles, plants, the escort boss and the match globals, and
    // the same ordinal means something different in each. Never read health
    // from an owner that has not been confirmed a player -- see
    // PriTracker.onBind. Valid ids are 0..254 (EPRI_PLAYER_NB = 255, and
    // Player::GetMaxRepDataCount returns 0xFF).
    //
    // Health is UInt16 read as a PLAIN (non-zigzag) varint at 1:1 scale --
    // 200 HP is 200 on the wire. Ids 2/3/4 are UInt64 packings and 83/150 are
    // float32 bit patterns, which is why an unknown id must be skipped as
    // exactly one varint rather than interpreted.
    public static final int EPRI_CUR_HP   = 0;   // CONFIRMED n=20 vs HUD
    public static final int EPRI_MAX_HP   = 1;   // CONFIRMED — only 9 values ever
    public static final int EPRI_VEST     = 2;   // (itemId << 32) | durability
    public static final int EPRI_HELMET   = 3;   // same packing
    public static final int EPRI_ON_HAND  = 4;   // (inventoryItemId << 32) | weaponId
    /**
     * {@code EPRI_PLAYER_SIGHTING_ID} — named in the game's own PLAYER property
     * enum ({@code message.NIPHKJAOKGI}), the candidate carrier of WHICH sight
     * (2x/4x/8x) a player has up. <b>NAMED, NOT MEASURED.</b> Values arrive on
     * this id today and were discarded until 2026-09-19; whether it is a tier
     * ordinal, an item id, or something else entirely is unknown and must not
     * be assumed. {@code protocol.ScopeState} logs the raw values so one
     * Training Ground session can settle it — see the plan's §11.3.
     */
    public static final int EPRI_SIGHTING = 12;
    public static final int EPRI_CUR_EP   = 6;
    public static final int EPRI_MAX_EP   = 7;
    public static final int EPRI_FIRING   = 21;  // {0,1,2}, CONFIRMED n=2159
    public static final int EPRI_TOTAL_DMG = 31; // strictly monotonic per player

    /** Receives every decoded property. */
    public interface Sink {
        /**
         * @param owner  ownerReplicationId — an arbitrary server-assigned int,
         *               <b>unrelated</b> to the ESP handle {@code (team<<24)|id}.
         *               It has to be learned; see {@code PriBinder}.
         * @param varId  the EPRI property id
         * @param value  the raw varint value (interpretation depends on varId)
         */
        void onProperty(long owner, int varId, long value);
    }

    /**
     * Walks a decrypted Cmd 1900/1901 body.
     *
     * @param hasOwner true for 1900 (per-player), false for 1901 (match-wide)
     * @return true when the body was consumed exactly; false means the body was
     *         not this grammar (wrong command, bad key, clipped) and the caller
     *         must discard whatever the sink already received
     */
    public static boolean decode(byte[] buf, int off, int len, boolean hasOwner, Sink sink) {
        if (buf == null || off < 0 || len <= 0 || off + len > buf.length) return false;
        // Server-gated leash: no live profile => no health decode (fail-closed,
        // rendering-neutral: HP simply stays unknown). R8-stripped while off.
        if (com.nepdon.wire.net.NativeBridge.DECODE_LEASH
                && !com.nepdon.wire.auth.ConfigStore.decodeArmed(System.currentTimeMillis() / 1000L)) {
            com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.ESP_LEASH_CLOSED);
            return false;
        }
        int i = off;
        final int end = off + len;
        int groups = 0;
        while (i < end) {
            long owner = -1L;
            if (hasOwner) {
                long v = varint(buf, i, end);
                if (v == BAD) return false;
                owner = v & VALUE_MASK;
                i += (int) (v >>> SHIFT_LEN);
            }
            if (i + 2 > end) return false;
            int blockLen = (buf[i] & 0xff) | ((buf[i + 1] & 0xff) << 8);
            i += 2;
            int stop = i + blockLen;
            if (stop > end || blockLen < 0) return false;
            while (i < stop) {
                int varId = buf[i] & 0xff;
                i++;
                long v = varint(buf, i, stop);
                if (v == BAD) return false;
                long value = v & VALUE_MASK;
                i += (int) (v >>> SHIFT_LEN);
                if (sink != null) sink.onProperty(owner, varId, value);
            }
            if (i != stop) return false;
            groups++;
            if (groups > 256) return false;
        }
        return i == end;
    }

    // A varint read has to return both the value and how many bytes it took.
    // Packing them into one long keeps this allocation-free on a path that runs
    // for every property of every player several times a second.
    private static final int SHIFT_LEN = 56;
    private static final long VALUE_MASK = (1L << SHIFT_LEN) - 1L;
    private static final long BAD = -1L;

    private static long varint(byte[] b, int i, int end) {
        long v = 0;
        int shift = 0;
        int n = 0;
        while (i + n < end && n < 10) {
            int c = b[i + n] & 0xff;
            v |= (long) (c & 0x7f) << shift;
            n++;
            if ((c & 0x80) == 0) {
                if (v < 0) v &= VALUE_MASK;      // keep the packing unambiguous
                return (v & VALUE_MASK) | ((long) n << SHIFT_LEN);
            }
            shift += 7;
        }
        return BAD;
    }
}
