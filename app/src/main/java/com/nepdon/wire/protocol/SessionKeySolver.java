package com.nepdon.wire.protocol;

import com.nepdon.wire.decode.frame.Crc7;
import com.nepdon.wire.decode.frame.Direction;
import com.nepdon.wire.decode.frame.HeaderField;
import com.nepdon.wire.decode.frame.HeaderLayout;
import com.nepdon.wire.decode.frame.MsgKey;
import com.nepdon.wire.decode.frame.PacketFlags;
import com.nepdon.wire.decode.frame.SendOption;

/**
 * Recovers a session's header encoding — the XOR mask and the
 * {@code HEAD_SECRET} field permutation — from traffic alone, with no key and
 * no handshake.
 *
 * <h3>Why this is possible</h3>
 * <p>{@code Length} is a known plaintext: it equals {@code len - headerSize}.
 * For each of the 120 permutations the byte offset of {@code Length} is fixed,
 * so solve {@code mask = b[off] ^ (payLen &amp; 0xFF)} and require
 * {@code b[off+1] ^ mask == payLen &gt;&gt; 8}. Vote across packets; the true
 * mask is the only one that survives.</p>
 *
 * <p><b>Outbound packets only</b> for that vote. Inbound {@code Length} is the
 * <i>pre-compression</i> size when {@code FLAG_ZIP} is set, so it does not
 * equal the wire length and would poison the tally.</p>
 *
 * <h3>Why one vote is not enough</h3>
 * <p>The {@code Length} identity pins a byte <i>offset</i>, not a permutation.
 * Every permutation that places the same set of fields before {@code Length}
 * yields identical offsets and therefore identical votes — in the validation
 * capture, ten of the 120 tie exactly. A second pass scores the tied
 * candidates on two facts that need no key:</p>
 * <ul>
 *   <li>{@code SendOption} is in {1, 2, 5} <b>iff</b> the packet is reliable,
 *       and MsgKey already told us which it is;</li>
 *   <li>{@code Flags} never sets a bit outside {@code 0x03}.</li>
 * </ul>
 * <p>A byte read at a wrong offset is essentially uniform, so it fails those
 * ~98% of the time. Measured over the validation capture, six of the 120
 * permutations score a perfect 2020/2020 on the pair — and exactly one of
 * those six is also in the {@code Length} tie class. The intersection is
 * unique and is the live {@code HEAD_SECRET}.</p>
 *
 * <p>The reference implementation only tried swapping {@code SendOption} with
 * {@code Flags} at this step, which happens to work when the elected candidate
 * already had {@code OrderID} in the right slot. Scoring the whole tie class
 * removes that dependence on luck.</p>
 *
 * <p>The tie-break pass accepts <b>both</b> directions: neither of its two
 * facts involves {@code Length}, and reliable packets — the only ones that can
 * place {@code SendOption} and {@code OrderID} — are a small minority of
 * outbound traffic but plentiful inbound.</p>
 *
 * <h3>Session changes</h3>
 * <p>A new match means a new {@code SECRET_KEY} and so a new mask and
 * permutation. CRC-7 cannot detect that — it is computed over the transmitted
 * bytes and stays valid across sessions. The {@code Length} identity can:
 * it holds on 100% of outbound packets while the layout is right and collapses
 * immediately when it is not. {@link #RESOLVE_FAILURE_RUN} consecutive
 * outbound failures drop the solver back to {@link State#VOTING}.</p>
 *
 * <p>There is deliberately no guessing: until {@link #isSolved()} is true,
 * {@link #layout()} returns null and callers keep to whatever fallback they
 * had. Convergence takes {@link #VOTE_MIN_PACKETS} outbound packets plus
 * {@link #TIE_BREAK_PACKETS} of either direction — a few hundred, i.e. a few
 * seconds of match traffic.</p>
 *
 * <p>Allocation: the tally is one {@code int[30720]} held only while voting and
 * released on election. The observe path itself allocates nothing.</p>
 */
public final class SessionKeySolver {

    /** Where the solver is in its two-pass convergence. */
    public enum State {
        /** Accumulating the {@code Length}-identity tally. */
        VOTING,
        /** Mask elected; separating the permutations that tied on byte offsets. */
        TIE_BREAK,
        /** Layout committed and being continuously re-validated. */
        SOLVED
    }

    /** Outbound packets to tally before a mask may be elected. */
    public static final int VOTE_MIN_PACKETS = 64;
    /** Packets (either direction) scored before the permutation is committed. */
    public static final int TIE_BREAK_PACKETS = 256;
    /** Consecutive outbound Length-identity failures that mean the session changed. */
    public static final int RESOLVE_FAILURE_RUN = 24;
    /** Give up on a tally this large and start clean (guards a mid-tally key change). */
    private static final int VOTE_GIVEUP_PACKETS = 4096;
    /** The winner must carry at least this share of the tally. */
    private static final int VOTE_MIN_NUMERATOR = 3;
    private static final int VOTE_MIN_DENOMINATOR = 4;
    /** A tie-break window whose best candidate fails this often means a bad election. */
    private static final int TIE_BREAK_MIN_NUMERATOR = 1;
    private static final int TIE_BREAK_MIN_DENOMINATOR = 2;
    /** Plausible upper bound on a Cmd value; used only to break exact ties. */
    private static final int CMD_PLAUSIBLE_MAX = 8192;

    private static final int MASK_VALUES = 256;

    // ---- precomputed permutation tables (built once) ----

    private static final HeaderField[][] PERMUTATIONS = buildPermutations();
    private static final int PERM_COUNT = PERMUTATIONS.length;

    private static final int[] DATA_LEN_OFF_REL = offsetTable(HeaderField.DATA_LEN, true);
    private static final int[] DATA_LEN_OFF_NON = offsetTable(HeaderField.DATA_LEN, false);
    private static final int[] SEND_OPT_OFF_REL = offsetTable(HeaderField.SEND_OPTION, true);
    private static final int[] SEND_OPT_OFF_NON = offsetTable(HeaderField.SEND_OPTION, false);
    private static final int[] FLAGS_OFF_REL = offsetTable(HeaderField.FLAGS, true);
    private static final int[] FLAGS_OFF_NON = offsetTable(HeaderField.FLAGS, false);
    private static final int[] CMD_OFF_REL = offsetTable(HeaderField.CMD, true);
    private static final int[] CMD_OFF_NON = offsetTable(HeaderField.CMD, false);
    /** OrderID exists only on reliable packets; that is what makes it decisive. */
    private static final int[] ORDER_OFF_REL = offsetTable(HeaderField.ORDER_ID, true);

    // ---- mutable solver state (guarded by this) ----

    private volatile HeaderLayout layout;
    private volatile State state = State.VOTING;

    private int[] votes;
    private int voteTested;

    private int electedMask;
    private int[] candidates;
    private int candidateCount;
    private int[] primaryScore;
    private int[] secondaryScore;
    /**
     * Per-candidate count of reliable packets whose OrderID stepped by exactly
     * one from the previous reliable packet.
     *
     * <p><b>This is the discriminator the solver was missing, and its absence
     * was a real, measured bug.</b> The Length identity cannot separate the two
     * 1-byte or the two 2-byte slots — on NON-reliable packets the permutations
     * {@code (0,1,4,3,2)} and {@code (0,1,2,4,3)} produce identical offsets —
     * and neither existing tie-break helps: SendOption sits at the same offset
     * in both, and the Flags plausibility test ({@code flags & ~0x03 == 0})
     * passes for the WRONG candidate because what it actually reads there is
     * {@code orderId >> 8}, which is 0 for every one of the first 256 reliable
     * packets (OrderID starts near zero; the first packet where that byte
     * exceeds 3 is #1061, long after the 256-packet window commits). So the
     * wrong permutation scored a perfect 256/256 and won on every fresh
     * match.</p>
     *
     * <p>The cost was not subtle: Flags was read from a near-random byte, so
     * ~49% of packets appeared to have the ZIPPED bit set and were discarded
     * unread — 14 911 packets across ~40 commands in one capture, including
     * 6 941 of the 14 197 Cmd 1900 health updates. Nothing on the match wire is
     * actually compressed; the zip bit is set on 0 of 88 413 packets once the
     * header is read correctly.</p>
     *
     * <p>OrderID monotonicity separates them cleanly: measured over the first
     * 255 reliable packets of a real session, the correct candidate scores
     * <b>229/255</b> and the wrong one <b>0/255</b>.</p>
     */
    private int[] orderScore;
    private int[] lastOrderId;
    private int tieBreakScored;

    private int consecutiveIdentityFailures;

    private volatile int packetsObserved;
    private volatile int solveCount;
    private volatile int resolveCount;

    // ---- public API ----

    /** The solved encoding, or null while the session is still unknown. */
    public HeaderLayout layout() {
        return layout;
    }

    /** True once the mask and permutation are committed AND confirmed. */
    public boolean isSolved() {
        return layout != null && !provisional;
    }

    // ---- lobby-declared layout (the fast path) ----
    //
    // The layout does not have to be voted on: the lobby announces it. The
    // XOR mask is SECRET_KEY[0] and the field permutation is field 42 of the
    // same tcp.MatchmakingSussNtf, five ASCII digits. Both arrive 231-274 ms
    // BEFORE the first UDP packet of the session -- necessarily, since field 2
    // of that message is the match server's address.
    //
    // Voting costs 1.1-2.6 s and ~440 packets, during which the whole
    // match-start burst decodes with an unknown layout and Cmd 101 survives at
    // 20.4%. Seeding reads that burst from packet 1.
    //
    // A seed is installed PROVISIONALLY. layout() returns it immediately so
    // parsing can proceed, but isSolved() stays false until the wire confirms
    // it, so no consumer is fed a command id derived from an unverified
    // layout. Confirmation is the Length identity, which is free and exact.
    // The voting path is NOT removed: it is the only route when the engine
    // starts mid-match, and it is what a rejected seed falls back to.

    /** Length-identity agreements required before a seeded layout is trusted. */
    private static final int SEED_CONFIRMS = 8;

    /** True while a seeded layout has not yet been confirmed on the wire. */
    private volatile boolean provisional = false;
    private int seedConfirms;
    private int seedsAccepted, seedsRejected;

    /**
     * Installs a layout announced by the lobby instead of solving for one.
     *
     * <p>Ignored when a layout is already committed, and ignored when the
     * arguments do not form a valid permutation -- in both cases the solver
     * carries on exactly as before.</p>
     *
     * @return true when the seed was installed
     */
    public synchronized boolean seedLayout(int mask, HeaderField[] order) {
        if (order == null || order.length != HeaderField.COUNT) return false;

        // A SEED THAT DISAGREES WITH THE COMMITTED LAYOUT IS A SESSION CHANGE.
        //
        // The layout rotates per game-server session, together with the key.
        // Refusing a new seed because a layout is already committed would pin
        // the PREVIOUS match's layout across the handover -- and since
        // BodyCrypt picks up the new key immediately, that yields packets that
        // decrypt correctly and are then handed to the wrong decoder. Garbage
        // that parses is worse than garbage that does not.
        //
        // So: identical seed, nothing to do. Different seed, the session
        // changed underneath us -- drop everything learned and adopt it.
        HeaderLayout cur = layout;
        if (cur != null && !provisional) {
            if (sameLayout(cur, mask, order)) return false;
            restartVoting();
        }
        try {
            layout = new HeaderLayout(mask, order);
        } catch (IllegalArgumentException e) {
            return false;                                    // not a permutation
        }
        provisional = true;
        seedConfirms = 0;
        consecutiveIdentityFailures = 0;
        state = State.VOTING;   // voting continues underneath as the fallback
        seedsAccepted++;
        com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.PROTO_LAYOUT_SEEDED, "mask=0x" + Integer.toHexString(mask & 0xFF));
        return true;
    }

    /** True when the current layout came from the lobby and is not yet confirmed. */
    public boolean isProvisional() {
        return provisional;
    }

    /** True when {@code l} already encodes exactly this mask and permutation. */
    private static boolean sameLayout(HeaderLayout l, int mask, HeaderField[] order) {
        if (l.mask() != (mask & 0xFF)) return false;
        HeaderField[] cur = l.order();
        if (cur == null || cur.length != order.length) return false;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] != order[i]) return false;
        }
        return true;
    }

    public int seedsAccepted() { return seedsAccepted; }
    public int seedsRejected() { return seedsRejected; }

    /** Current convergence phase. */
    public State state() {
        return state;
    }

    /** The solved mask ({@code SECRET_KEY[0]}), or -1 while unsolved. */
    public int mask() {
        HeaderLayout l = layout;
        return l == null ? -1 : l.mask();
    }

    /**
     * Feeds one packet.
     *
     * <p>Outbound packets drive the tally and the session-change check;
     * inbound packets contribute only to the tie-break pass, and are ignored
     * entirely once the layout is committed.</p>
     */
    public void observe(byte[] pkt, int off, int len, Direction direction) {
        // Lock-free fast path: once solved, inbound packets have nothing to say.
        if (direction == Direction.INBOUND && state == State.SOLVED) return;

        int hdr = MsgKey.headerSizeOf(pkt, off, len);
        if (hdr == MsgKey.NOT_MATCH_TRAFFIC) return;

        synchronized (this) {
            packetsObserved++;
            boolean reliable = (hdr == MsgKey.HEADER_RELIABLE);
            switch (state) {
                case VOTING:
                    if (direction == Direction.OUTBOUND) {
                        tally(pkt, off, len - hdr, reliable);
                    }
                    break;
                case TIE_BREAK:
                    scoreCandidates(pkt, off, reliable);
                    break;
                case SOLVED:
                    if (direction == Direction.OUTBOUND) {
                        checkIdentity(pkt, off, len, hdr, reliable);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Drops everything learned; the next packets start a clean solve. */
    public synchronized void reset() {
        layout = null;
        provisional = false;
        seedConfirms = 0;
        state = State.VOTING;
        votes = null;
        voteTested = 0;
        candidates = null;
        candidateCount = 0;
        primaryScore = null;
        secondaryScore = null;
        tieBreakScored = 0;
        consecutiveIdentityFailures = 0;
        packetsObserved = 0;
    }

    /** One-line status for the dev overlay. */
    public String describe() {
        HeaderLayout l = layout;
        if (l != null) {
            return "net SOLVED " + l.describe()
                    + (resolveCount > 0 ? (" resolves=" + resolveCount) : "");
        }
        State s = state;
        if (s == State.TIE_BREAK) {
            return "net TIE_BREAK " + tieBreakScored + "/" + TIE_BREAK_PACKETS
                    + " (mask 0x" + Integer.toHexString(electedMask).toUpperCase(java.util.Locale.US) + ")";
        }
        return "net VOTING " + voteTested + "/" + VOTE_MIN_PACKETS;
    }

    // ---- pass 1: the Length identity ----

    private void tally(byte[] pkt, int off, int payLen, boolean reliable) {
        if (votes == null) votes = new int[PERM_COUNT * MASK_VALUES];
        int[] offsets = reliable ? DATA_LEN_OFF_REL : DATA_LEN_OFF_NON;
        int lo = payLen & 0xFF;
        int hi = (payLen >>> 8) & 0xFF;
        for (int p = 0; p < PERM_COUNT; p++) {
            int o = off + offsets[p];
            int m = (pkt[o] & 0xFF) ^ lo;
            if ((((pkt[o + 1] & 0xFF) ^ m)) == hi) {
                votes[p * MASK_VALUES + m]++;
            }
        }
        voteTested++;
        if (voteTested >= VOTE_GIVEUP_PACKETS) {
            // Nothing convincing in a very long tally: the session almost
            // certainly changed underneath us. Start clean rather than let
            // two sessions' evidence blend into a wrong answer.
            votes = null;
            voteTested = 0;
            return;
        }
        if (voteTested >= VOTE_MIN_PACKETS && (voteTested & 0x0F) == 0) {
            elect();
        }
    }

    /** Picks the winning (mask, offset class) and moves to the tie-break pass. */
    private void elect() {
        int bestPerm = -1;
        int bestMask = -1;
        int bestCount = 0;
        for (int p = 0; p < PERM_COUNT; p++) {
            int base = p * MASK_VALUES;
            for (int m = 0; m < MASK_VALUES; m++) {
                int c = votes[base + m];
                if (c > bestCount) {
                    bestCount = c;
                    bestPerm = p;
                    bestMask = m;
                }
            }
        }
        if (bestPerm < 0) return;
        if (bestCount * VOTE_MIN_DENOMINATOR < voteTested * VOTE_MIN_NUMERATOR) return;

        // Everything that shares the winner's Length offsets is indistinguishable
        // so far; the tie-break pass separates them.
        int relOff = DATA_LEN_OFF_REL[bestPerm];
        int nonOff = DATA_LEN_OFF_NON[bestPerm];
        int[] tied = new int[PERM_COUNT];
        int n = 0;
        for (int p = 0; p < PERM_COUNT; p++) {
            if (DATA_LEN_OFF_REL[p] == relOff && DATA_LEN_OFF_NON[p] == nonOff) {
                tied[n++] = p;
            }
        }
        electedMask = bestMask;
        candidates = tied;
        candidateCount = n;
        primaryScore = new int[n];
        secondaryScore = new int[n];
        orderScore = new int[n];
        lastOrderId = new int[n];
        java.util.Arrays.fill(lastOrderId, -1);
        tieBreakScored = 0;
        votes = null;
        voteTested = 0;
        state = State.TIE_BREAK;
    }

    // ---- pass 2: SendOption / Flags consistency ----

    private void scoreCandidates(byte[] pkt, int off, boolean reliable) {
        int[] soOff = reliable ? SEND_OPT_OFF_REL : SEND_OPT_OFF_NON;
        int[] flOff = reliable ? FLAGS_OFF_REL : FLAGS_OFF_NON;
        int[] cmdOff = reliable ? CMD_OFF_REL : CMD_OFF_NON;
        int m = electedMask;

        for (int i = 0; i < candidateCount; i++) {
            int p = candidates[i];
            int sendOption = (pkt[off + soOff[p]] & 0xFF) ^ m;
            int flags = (pkt[off + flOff[p]] & 0xFF) ^ m;
            if (SendOption.impliesReliable(sendOption) == reliable
                    && PacketFlags.isPlausible(flags)) {
                primaryScore[i]++;
            }
            int c = cmdOff[p];
            int cmd = ((pkt[off + c] & 0xFF) ^ m) | ((((pkt[off + c + 1] & 0xFF) ^ m)) << 8);
            if (cmd > 0 && cmd < CMD_PLAUSIBLE_MAX) {
                secondaryScore[i]++;
            }
            if (reliable) {
                int o = ORDER_OFF_REL[p];
                int orderId = ((pkt[off + o] & 0xFF) ^ m)
                        | ((((pkt[off + o + 1] & 0xFF) ^ m)) << 8);
                int prev = lastOrderId[i];
                if (prev >= 0 && ((orderId - prev) & 0xFFFF) == 1) {
                    orderScore[i]++;
                }
                lastOrderId[i] = orderId;
            }
        }
        tieBreakScored++;
        if (tieBreakScored >= TIE_BREAK_PACKETS) {
            commit();
        }
    }

    /** Commits the best-scoring candidate, or restarts if none is convincing. */
    private void commit() {
        // ORDER SCORE FIRST. A sequence counter that advances by exactly one
        // cannot be mimicked by a byte read at the wrong offset, whereas both
        // of the older tests are satisfiable by the wrong candidate (see
        // orderScore's comment). Primary and secondary remain as tie-breaks
        // among candidates the order test cannot judge -- e.g. a session with
        // no reliable traffic yet.
        int best = -1;
        for (int i = 0; i < candidateCount; i++) {
            if (best < 0
                    || orderScore[i] > orderScore[best]
                    || (orderScore[i] == orderScore[best]
                        && (primaryScore[i] > primaryScore[best]
                            || (primaryScore[i] == primaryScore[best]
                                && secondaryScore[i] > secondaryScore[best])))) {
                best = i;
            }
        }
        boolean convincing = best >= 0
                && primaryScore[best] * TIE_BREAK_MIN_DENOMINATOR
                   >= tieBreakScored * TIE_BREAK_MIN_NUMERATOR;
        if (!convincing) {
            // The elected mask cannot explain SendOption and Flags anywhere in
            // its tie class, so the election was wrong. Tally again.
            restartVoting();
            return;
        }
        try {
            layout = new HeaderLayout(electedMask, PERMUTATIONS[candidates[best]]);
        } catch (IllegalArgumentException e) {
            restartVoting();
            return;
        }
        candidates = null;
        primaryScore = null;
        secondaryScore = null;
        candidateCount = 0;
        tieBreakScored = 0;
        consecutiveIdentityFailures = 0;
        solveCount++;
        state = State.SOLVED;
    }

    private void restartVoting() {
        layout = null;
        provisional = false;
        seedConfirms = 0;
        candidates = null;
        primaryScore = null;
        secondaryScore = null;
        candidateCount = 0;
        tieBreakScored = 0;
        votes = null;
        voteTested = 0;
        state = State.VOTING;
    }

    // ---- pass 3: continuous validation ----

    private void checkIdentity(byte[] pkt, int off, int len, int hdr, boolean reliable) {
        HeaderLayout l = layout;
        if (l == null) return;
        int declared = l.readShort(pkt, off, HeaderField.DATA_LEN, reliable);
        if (declared == len - hdr) {
            consecutiveIdentityFailures = 0;
            // A seeded layout earns trust here. The true layout scores 100% of
            // outbound packets, so eight agreements cost about 50 ms; a wrong
            // one cannot reach eight because it fails on the first.
            if (provisional && ++seedConfirms >= SEED_CONFIRMS) {
                provisional = false;
                com.nepdon.wire.diag.Diag.hit(com.nepdon.wire.diag.Slot.PROTO_LAYOUT_CONFIRMED);
            }
            return;
        }
        // A provisional layout gets NO grace period. The 24-failure rule below
        // exists to ride out a session change under a previously-good layout;
        // a seed that disagrees with the very first packet was simply wrong,
        // and holding it would feed garbage command ids to consumers.
        if (provisional) {
            provisional = false;
            seedsRejected++;
            com.nepdon.wire.diag.Diag.event(com.nepdon.wire.diag.Slot.PROTO_LAYOUT_SEED_REJECTED, "seed disagreed with first packet");
            restartVoting();
            consecutiveIdentityFailures = 0;
            return;
        }
        if (++consecutiveIdentityFailures >= RESOLVE_FAILURE_RUN) {
            resolveCount++;
            restartVoting();
            consecutiveIdentityFailures = 0;
        }
    }

    // ---- static tables ----

    private static HeaderField[][] buildPermutations() {
        HeaderField[] fields = HeaderField.values();
        int n = fields.length;
        int total = 1;
        for (int i = 2; i <= n; i++) total *= i;

        HeaderField[][] out = new HeaderField[total][];
        int[] cursor = new int[1];
        boolean[] used = new boolean[n];
        permute(fields, used, new HeaderField[n], 0, out, cursor);
        return out;
    }

    /** Lexicographic enumeration by field id, matching the reference tooling. */
    private static void permute(HeaderField[] fields, boolean[] used, HeaderField[] current,
                                int depth, HeaderField[][] out, int[] cursor) {
        if (depth == fields.length) {
            out[cursor[0]++] = current.clone();
            return;
        }
        for (int i = 0; i < fields.length; i++) {
            if (used[i]) continue;
            used[i] = true;
            current[depth] = fields[i];
            permute(fields, used, current, depth + 1, out, cursor);
            used[i] = false;
        }
    }

    private static int[] offsetTable(HeaderField target, boolean reliable) {
        int[] table = new int[PERMUTATIONS.length];
        for (int p = 0; p < PERMUTATIONS.length; p++) {
            table[p] = offsetIn(PERMUTATIONS[p], target, reliable);
        }
        return table;
    }

    private static int offsetIn(HeaderField[] order, HeaderField target, boolean reliable) {
        int off = Crc7.COVERED_FROM;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == target) return off;
            off += order[i].widthFor(reliable);
        }
        return -1;
    }
}
