package com.nepdon.wire.esp;

import com.nepdon.wire.decode.body.WireReader;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Player names, keyed to the entity slot the ESP already tracks.
 *
 * <p><b>Names were never missing from the wire</b> — nothing was reading them.
 * There is no name anywhere in the 252 {@code EPRI_*} properties (all of them
 * are uint), so Cmd 1900 can never carry one. The real source is the match-start
 * roster:</p>
 *
 * <table>
 *   <tr><th>cmd</th><th>name</th><th>gives</th></tr>
 *   <tr><td>101</td><td>{@code RUDP_PLAYER_JOIN}</td>
 *       <td>every player's nickname, once, at match start</td></tr>
 *   <tr><td>107</td><td>{@code RUDP_DEAD}</td><td>the KILLER's name</td></tr>
 *   <tr><td>139</td><td>{@code RUDP_KNOCK_DOWN}</td><td>the KILLER's name</td></tr>
 *   <tr><td>102</td><td>{@code RUDP_PLAYER_QUIT}</td><td>removal</td></tr>
 * </table>
 *
 * <p><b>The id join.</b> The roster's {@code entityId} is not the chain id
 * directly — it is packed {@code (squad << 24) | slot}, and the low 24 bits are
 * exactly the chain entity id. Verified across all 53 entities of a real match:
 * 53 distinct packed ids to 53 distinct slots, zero collisions. So the join is
 * a mask, and the high byte hands us the squad index for free.</p>
 *
 * <p>Slots are unique only <i>within</i> a match, so the map is cleared on a
 * match boundary — a name carried across would label a stranger.</p>
 */
public final class MatchRoster {

    /** Longest plausible nickname in bytes; guards against a mis-parse. */
    private static final int MAX_NAME_BYTES = 64;

    private final ConcurrentHashMap<Integer, String> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> squads = new ConcurrentHashMap<>();

    private volatile int joins = 0;
    private volatile int fromDeaths = 0;

    public void reset() {
        names.clear();
        squads.clear();
        joins = 0;
        fromDeaths = 0;
    }

    /** The name for an entity slot, or null. */
    public String name(int slot) {
        return names.get(slot);
    }

    /** The squad index the roster announced for a slot, or -1. */
    public int squad(int slot) {
        Integer s = squads.get(slot);
        return s == null ? -1 : s;
    }

    public int size() {
        return names.size();
    }

    public String debugLine() {
        return "named=" + names.size() + " join=" + joins + " kill=" + fromDeaths;
    }

    /**
     * Cmd 101 {@code RUDP_PLAYER_JOIN}.
     *
     * <pre>
     *   [0] U  account uid
     *   [1] U  (always 0)
     *   [2] U  entityId = (squad &lt;&lt; 24) | slot
     *   [3] S  nickname
     *   ...    position and aim follow; we never need them
     * </pre>
     */
    public void observeJoin(byte[] body, int off, int len) {
        WireReader r = new WireReader(body, off, len);
        r.skipU(2);
        long entityId = r.u();
        String nick = r.str(MAX_NAME_BYTES);
        if (r.bad || nick == null || nick.isEmpty()) return;
        if (!put(entityId, nick)) return;
        joins++;
    }

    /**
     * Cmd 107 {@code RUDP_DEAD} / Cmd 139 {@code RUDP_KNOCK_DOWN}.
     *
     * <pre>
     *   [0] U  victim
     *   [1] U  killer  = (squad &lt;&lt; 24) | slot
     *   [2] S  killerName
     * </pre>
     *
     * <p>Only ever names the KILLER, never the victim — 97/97 in the corpus.
     * It fills gaps mid-match for players whose join message was missed.</p>
     */
    public void observeKill(byte[] body, int off, int len) {
        WireReader r = new WireReader(body, off, len);
        r.skipU(1);
        long killer = r.u();
        String nick = r.str(MAX_NAME_BYTES);
        if (r.bad || nick == null || nick.isEmpty()) return;
        int slot = (int) (killer & 0xFFFFFFL);
        if (names.containsKey(slot)) return;      // join already named them
        if (!put(killer, nick)) return;
        fromDeaths++;
    }

    /** Cmd 102 {@code RUDP_PLAYER_QUIT}: a single entityId. */
    public void observeQuit(byte[] body, int off, int len) {
        WireReader r = new WireReader(body, off, len);
        long entityId = r.u();
        if (r.bad) return;
        int slot = (int) (entityId & 0xFFFFFFL);
        names.remove(slot);
        squads.remove(slot);
    }

    private boolean put(long entityId, String nick) {
        int slot = (int) (entityId & 0xFFFFFFL);
        int squad = (int) ((entityId >>> 24) & 0xFF);
        // The ESP indexes entities 1..127; anything else is a mis-parse, and a
        // mis-parsed name would be attached to a real player's box.
        if (slot < 1 || slot > 127) return false;
        for (int i = 0; i < nick.length(); i++) {
            char c = nick.charAt(i);
            if (c < 0x20 && c != 0) return false;   // control bytes = not a name
        }
        names.put(slot, nick);
        if (squad > 0) squads.put(slot, squad);
        return true;
    }
}
