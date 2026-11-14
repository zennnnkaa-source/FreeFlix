# Tagged Packet Dump Analyzer — Design Doc

Target: `app/src/main/java/com/fakelag/cryhard8/` (dev/capture + filter + core).
User request: *"make the packet dump much more verbose and better — analyze deeply
where the player fired, when a weapon was fired — as TAGS. Multiple tags in our
system."*

Everything below is **mask-independent only** (per-session XOR mask / positional
keystream rotate every session — see `captures/PROTOCOL_MAP.md`). No rule may
depend on a hardcoded signature byte surviving across sessions.

---

## 1. TAG SYSTEM

### 1.1 Representation

- Tags are **int ids 0..63** packed into a `long` bitmask (one bit per tag).
- Every tag carries a **default confidence tier**: `CONFIRMED / LIKELY / POSSIBLE`.
  A tag instance can be *promoted* at analysis time (e.g. `HIT_CONFIRM` is
  POSSIBLE from the structural pre-filter at capture time, CONFIRMED after the
  cluster-scoring pass picks the session's hit shape).
- Storage is **three parallel masks per record** (`confirmed / likely / possible`),
  so a tag can exist in exactly one tier per packet.
- **A packet can carry many tags at once** — that is the point. Example: an
  inbound headshot during freeze is `IN | FAMILY_S | HIT_CONFIRM(conf) |
  HEADSHOT(conf) | KILL_CANDIDATE(likely) | FREEZE_WINDOW(conf) | DROPPED(conf)`.

### 1.2 Tag table

Legend: **Stage** = CT (capture-time, writer thread, O(1) byte checks only) or
AT (analysis-time, full-capture knowledge). **Conf** = default confidence.

| # | Tag | Stage | Conf | Detection rule (mask-independent) |
|---|-----|-------|------|-----------------------------------|
| 0 | `OUT` | CT | CONFIRMED | `dir == OUT` |
| 1 | `IN` | CT | CONFIRMED | `dir == IN` |
| 2 | `FAMILY_R` | CT | CONFIRMED | existing `GamePacketTaxonomy.family()` (b2==b3 or learned tuple, len ≤ 64) |
| 3 | `FAMILY_S` | CT | CONFIRMED | existing family() (b2 == R-marker ^ 0x01) |
| 4 | `FAMILY_C` | CT | CONFIRMED | existing family() fallback |
| 5 | `MOVEMENT` | CT | CONFIRMED once `isMovementStream()` learned (plen + bytes 2..7 session header); POSSIBLE while learner still warming |
| 6 | `KEEPALIVE` | CT | CONFIRMED | kind == KEEPALIVE_MOVE / HEARTBEAT (R family, steady 100–200 ms cadence) |
| 7 | `TIMER_1HZ` | CT shape / AT confirm | LIKELY | plen 17–19, family R, inter-gap median 900–1100 ms (AT); CT only sets POSSIBLE from length+family |
| 8 | `TIMER_2HZ` | CT shape / AT confirm | LIKELY | plen 10–11, family R, inter-gap median 450–550 ms |
| 9 | `VOICE_OUT` | CT shape / AT confirm | LIKELY | outbound, **non-match endpoint**, plen 20–24 or 60–90, ≥3 adjacent-equal byte pairs (00 00 pairs survive constant XOR: `00 00 ^ m = mm`); AT confirms via sustained bidirectional pair with `VOICE_IN` |
| 10 | `VOICE_IN` | CT shape / AT confirm | LIKELY | inbound counterpart, same shape + pairing rule |
| 11 | `CHUNKED_BULK` | CT | CONFIRMED | OUT plen 32 in runs ≥ 3 within 50 ms, or any plen ≥ 147 (world snapshot blobs) |
| 12 | `HIT_CONFIRM` | CT pre-filter (POSSIBLE) → AT confirm | POSSIBLE→CONFIRMED | CT: structural pre-filter (IN, plen 22–26, last two bytes 00 00, byte[plen-3] ≤ 130). AT: cluster-scoring per shape `(plen, b2, b4, b5)` (existing FreezeDiffAnalyzer algorithm, reused) picks the winning shape → all its packets CONFIRMED, competing shapes keep POSSIBLE |
| 13 | `HEADSHOT` | AT | CONFIRMED / POSSIBLE | zone byte (rotating offset 15–19; found via ≥70% of hits having value ∈ {1,2,3,9}) == 9 → CONFIRMED. Fully-masked sessions: damage ≥ 2.0× same-weapon body median → POSSIBLE |
| 14 | `KILL_CANDIDATE` | AT | LIKELY | any of: byte[plen-4] == 0x14 flag (elevated-damage kill/down marker, PROTOCOL_MAP); damage ≥ 90 AND zone HEAD; engagement cumulative damage crosses 200 followed by ≥ 3 s hit silence |
| 15 | `WEAPON_FIRE` | AT | POSSIBLE→LIKELY | see fire pipeline §2. Outbound fire-candidate that survived corroboration |
| 16 | `FIRE_BURST` | AT | LIKELY | member of a ≥ 2-shot burst: inter-fire gap < 400 ms (per-weapon ROF learned from median gap) |
| 17 | `RELOAD` | AT | LIKELY / POSSIBLE | fire silence 1.2–4 s **plus** ammo-counter upward jump (LIKELY); silence only (POSSIBLE) |
| 18 | `ENEMY_FIRE` | AT | LIKELY | IN plen 34 burst cluster (enemy fire/animation shape) within ±500 ms of our WEAPON_FIRE — incoming-fire context |
| 19 | `HIT_MARKER` | AT | LIKELY | rare inbound shape appearing in shot-spaced isolation (1–10 s gaps) when HIT_CONFIRM shape was not confirmable (fully-masked sessions, 17:21-style) |
| 20 | `MATCH_START` | AT (event) | CONFIRMED | movement stream confirms on an endpoint (steady 15–35 ms gaps ≥ 6 s, ≥ 250 pkts) |
| 21 | `MATCH_END` | AT (event) | CONFIRMED | confirmed movement stream goes silent ≥ 3 s while endpoint stays alive |
| 22 | `MATCH_LIVE` | AT | CONFIRMED | records between MATCH_START and MATCH_END |
| 23 | `ENDPOINT_SWITCH` | AT (event) | CONFIRMED | dominant outbound endpoint (dip,dp) changes with > 5 s of traffic on the new one |
| 24 | `LOBBY` | AT | LIKELY | endpoint alive (heartbeats flowing) but movement stream dead (post-match lobby session, per PROTOCOL_MAP the server re-binds ports) |
| 25 | `SQUAD` | AT | LIKELY | voice channel active concurrently with MATCH_LIVE (teammates) |
| 26 | `FREEZE_WINDOW` | CT | CONFIRMED | `phase == FREEZE` (existing freezeOn plumbing) |
| 27 | `GHOST_WINDOW` | CT | CONFIRMED | ghost filter active at capture time (new plumbing, D4) |
| 28 | `DROPPED` | CT | CONFIRMED | `decision == DROP` |
| 29 | `AMMO_SYNC` | CT | CONFIRMED | OUT plen 18 (the ~1 Hz ammo/sync packet) |
| 30 | `MISS_CANDIDATE` | AT | POSSIBLE | a WEAPON_FIRE with no HIT_CONFIRM in the correlation window (see §2.4 ambiguity) |
| 31 | `FREEZE_BLINDED` | AT | CONFIRMED | a WEAPON_FIRE fired while FREEZE was active — its potential inbound hit-confirm was inside the 21–450 B drop window, so "no hit" is **not** evidence of a miss |

32 tags total — fits one `long` with 32 spare bits.

### 1.3 Where computed

- **Capture-time (hot-path-safe)**: only tags 0–11 + 26–29. All are O(1)
  length/equality checks or values *already computed* in
  `PacketCapture.appendRecord` (family, kind). They run on the **writer
  thread**, not the tun loop — zero hot-path cost change. The only hot-path
  change is reading one extra volatile boolean (`ghostOn`), identical to the
  existing `freezeOn` read.
- **Analysis-time**: everything needing cadence, clustering, or cross-packet
  correlation (12–25, 30, 31). Runs in `TaggedDumpAnalyzer` after `stop()`,
  on the existing background executor in `VpnEngine.stopCapture()`.

---

## 2. WEAPON-FIRE DEEP ANALYSIS (the core ask)

Goal: answer **when** a weapon was fired, **what** weapon, **where** (aim/
position context + which enemy stream was active), and how each shot resolved.

### 2.0 Evidence available (all mask-independent)

1. **OUT ~26 B isolated bursty packets** — fire-aux commands; never share the
   movement header (PROTOCOL_MAP "OUT/26/34 rare bursts = Fire-aux commands").
2. **OUT/24 burst clusters** — normal cadence ~31 ms; combat-action bursts
   have < 15 ms gaps.
3. **Off-beat keepalive extras** — OUT 55–64 B packets arriving *off* the
   100 ms keepalive metronome carry weapon/action commands (calibration:
   weapon id `0297`=AWM, `029B`=LG appeared in these at the shot instant).
4. **OUT/18 ammo counter** — LE u16 at bytes 13–14, decrements ~2 per 2 shots,
   jumps on reload. Mask caveat: arithmetic deltas don't survive XOR, so the
   counter is *session-validated* before use (§2.3 step A1).
5. **IN hit-confirms** (§1.2 tag 12) and **IN/29 hit-marker** isolation shape.
6. **Movement stream** at the fire instant = aim/position snapshot.

### 2.1 Stage F1 — fire-candidate clustering

- Working set: outbound packets that are **not** movement stream, **not**
  on-beat keepalives (within ±25 ms of the learned 100 ms grid), not ACK_SMALL.
- Cluster by shape `(plen, b2, b3, b4, b5)`; score each shape by **burstiness**
  (median intra-cluster gap < 60 ms; clusters separated by > 1 s) and low total
  volume — same cluster-scoring philosophy as the proven hit-shape scorer.
- Qualifying clusters + qualifying OUT/24 micro-bursts (< 15 ms gap) +
  off-beat keepalive extras → **FIRE_CANDIDATE** events with shape id and
  member record indices. Confidence: POSSIBLE.

### 2.2 Stage F2 — corroboration (POSSIBLE → LIKELY)

A candidate is corroborated (tag `WEAPON_FIRE`, LIKELY) when **any** of:

- **AMMO_DELTA**: nearest AMMO_SYNC packet before vs after the candidate shows
  the counter moving down (only when the ammo channel passed session
  validation).
- **OFFBEAT_KEEPALIVE**: an off-beat keepalive within ±150 ms; its
  weapon-tag bytes = the bytes that *differ from the modal on-beat keepalive
  payload* (baseline-diff — mask-independent by construction).
- **ENEMY_ECHO**: an ENEMY_FIRE cluster within ±500 ms.
- **HIT_LINKED**: a HIT_CONFIRM lands inside the correlation window (§2.3) —
  strongest corroboration.

### 2.3 Stage F3 — fire→hit correlation

- Correlation window: `HIT_LATENCY_WINDOW_MS = 500` default (observed IN/24
  mirror RTT ~220 ms), **adaptive**: after ≥ 5 confirmed pairs, re-learn the
  median fire→hit latency, clamped to 150–900 ms.
- Each WEAPON_FIRE takes the **first** HIT_CONFIRM in its window (1:1 match;
  a hit is never consumed twice). Matched pair → the fire's weapon tag is
  cross-checked against the hit's weapon byte (agreement raises both to
  CONFIRMED).
- Unmatched fires → `MISS_CANDIDATE` (POSSIBLE) — see §2.4.

### 2.4 Ambiguity handling (fire with no following hit)

A MISS_CANDIDATE is resolved into exactly one of:

| Outcome | Condition | Reporting |
|---|---|---|
| `MISS` | no hit in window, freeze NOT active in window, capture continues ≥ 2× window past the fire | counted in accuracy denominator |
| `FREEZE_BLINDED` | freeze active during the window (inbound 21–450 B — which includes hit-confirms — was being dropped by design) | excluded from accuracy; flagged loudly |
| `SUPPRESSED_HIT` | a hit arrives but damage byte reads 0, or the hit shape is confirmed but its damage field is masked | counted as hit, damage `MASKED` |
| `UNRESOLVED` | capture ended inside the window | excluded from accuracy |

This is the key honesty feature: during freeze, "fired but no hit" is **not**
evidence of missing.

### 2.5 Stage F4 — bursts, reloads, engagements

- **Burst**: consecutive fires with gap < 400 ms → `FIRE_BURST`. Per-weapon
  ROF = median intra-burst gap. ≥ 4 shots with gap < 150 ms → auto weapon;
  gaps > 800 ms → sniper/single-fire profile.
- **Reload**: fire silence 1.2–4 s + ammo counter upward jump (LIKELY) or
  silence alone (POSSIBLE) → `RELOAD` event with before/after ammo.
- **Engagement**: fires grouped with gap < 3 s (matches the hit-cluster
  threshold already used in FreezeDiffAnalyzer).

### 2.6 Stage F5 — engagement summaries + "where"

Per engagement: shots, hits, misses, accuracy %, total damage, zone histogram,
weapon tags, headshots, kill candidates, median fire→hit latency.

**"Where the player fired"** — position is not decodable mask-independently, so
the report attaches a **FIRE_CONTEXT** snapshot per fire (clearly labeled
POSSIBLE/raw):

- the last MOVEMENT packet before the fire (its bytes 8+ are live aim/input —
  hex included, aim-byte value and delta),
- the set of enemy-position shapes active in the preceding 2 s (which enemy
  streams were live),
- the player-state burst context (OUT/24 cluster density around the shot).

This gives the analyst per-shot aim context without pretending to decode
coordinates.

---

## 3. REPORT FORMAT

Two companions, same stem `tagged_dump_<stamp>`:

### 3.1 Human-readable timeline — exact section headers

```
==================================================================
 TAGGED PACKET DUMP — Free Fire Max combat & network analysis
==================================================================
== 1. SESSION SUMMARY ==               (duration, records, endpoints, encoding notes, hit-shape score)
== 2. TAG DICTIONARY ==                (tag: default confidence: one-line rule — self-documenting)
== 3. TAG STATISTICS ==                (per tag: count, pkt/s, IN/OUT split, confidence split)
== 4. MATCH / SESSION TIMELINE ==      (MATCH_START/END, ENDPOINT_SWITCH, LOBBY, SQUAD, freeze/ghost windows)
== 5. WEAPON FIRE ANALYSIS ==
== 5a. FIRE CANDIDATES ==              (all candidates, accepted + rejected, with corroboration reasons)
== 5b. FIRE->HIT CORRELATION ==        (per-shot latency table, adaptive window value)
== 5c. BURSTS & RELOADS ==             (per-weapon ROF, reload events with ammo jumps)
== 6. ENGAGEMENTS ==                   (detailed per-engagement timelines, see below)
== 7. ACCURACY & AMBIGUITY LEDGER ==   (MISS vs FREEZE_BLINDED vs SUPPRESSED_HIT vs UNRESOLVED totals)
== 8. HIT LOG ==                       (every HIT_CONFIRM: t, zone, dmg, weapon, linked fire, tags)
== 9. ZONE & DAMAGE SUMMARY ==         (zone histogram, per-weapon damage bands, totals)
== 10. FREEZE/GHOST IMPACT ON COMBAT DATA ==  (which fires/hits were blinded or dropped)
== 11. ANOMALIES & UNRESOLVED SHAPES ==       (unclassified bursty shapes — future calibration targets)
```

Engagement block format (grep-able: every event line starts with `[t=`, carries
`tags=[...]`):

```
-- Engagement #3 [t=+12.340s .. t=+14.020s] weapon=0xF2 shots=9 hits=6 acc=66.7% dmg=342 head=2
   [t=+12.340s] FIRE  seq=1042 weapon=0xF2 corrob=OFFBEAT_KEEPALIVE+AMMO_DELTA(30->28) conf=LIKELY tags=[WEAPON_FIRE,FIRE_BURST]
   [t=+12.510s] HIT   seq=1103 zone=HEAD dmg=1103 latency=+170ms tags=[HIT_CONFIRM,HEADSHOT,KILL_CANDIDATE]
   [t=+12.980s] FIRE  seq=1061 weapon=0xF2 corrob=AMMO_DELTA(28->26) conf=LIKELY tags=[WEAPON_FIRE]
   [t=+13.010s] MISS  seq=1061 no_hit_within=500ms tags=[MISS_CANDIDATE]
   [t=+15.900s] RELOAD ammo=6->30 silence=2100ms conf=LIKELY tags=[RELOAD]
   AIM_CONTEXT @seq=1042: aim_byte=0x37 (d=+0x04) enemies_active=3 move_hdr=OK
```

Section 5a lists rejected candidates too (`REJECTED reason=ON_BEAT` /
`REASON=MOVEMENT_HDR` / `REASON=STEADY_CADENCE`) so tuning is possible from
reports alone.

### 3.2 Machine-readable JSONL companion (`tagged_dump_<stamp>.jsonl`)

One JSON object per line, `type`-discriminated:

```json
{"type":"meta","version":1,"records":22118,"dropped_by_cap":0,"started":"...","hit_shape":"26|E6E6F3E6","hit_shape_score":0.83}
{"type":"tag_stat","tag":"HIT_CONFIRM","conf":"CONFIRMED","count":37,"rate_per_s":0.05,"in":37,"out":0}
{"type":"match","event":"START","t_ms":3200,"endpoint":"148.222.80.93:10012"}
{"type":"match","event":"END","t_ms":742000,"reason":"MOVEMENT_SILENT_3S"}
{"type":"endpoint_switch","t_ms":...,"from":"...","to":"..."}
{"type":"fire","t_ms":12340,"seq":1042,"weapon":"0xF2","corrob":["OFFBEAT_KEEPALIVE","AMMO_DELTA"],"burst_id":7,"conf":"LIKELY","freeze_blinded":false,"tags":["WEAPON_FIRE","FIRE_BURST"]}
{"type":"hit","t_ms":12510,"seq":1103,"zone":"HEAD","zone_masked":false,"dmg":1103,"weapon":"0xF2","fire_seq":1042,"latency_ms":170,"tags":["HIT_CONFIRM","HEADSHOT","KILL_CANDIDATE"]}
{"type":"miss","t_ms":13010,"seq":1061,"outcome":"MISS","window_ms":500,"freeze_blinded":false}
{"type":"reload","t_ms":15900,"ammo_before":6,"ammo_after":30,"silence_ms":2100,"conf":"LIKELY"}
{"type":"engagement","id":3,"start_ms":12340,"end_ms":14020,"shots":9,"hits":6,"misses":3,"acc_pct":66.7,"dmg":342,"zones":{"HEAD":2,"TORSO":3,"MASKED":1},"weapons":["0xF2"],"headshots":2,"median_latency_ms":170}
{"type":"freeze_window","start_ms":...,"end_ms":...,"fires_blinded":2,"hits_dropped":1}
{"type":"anomaly","t_ms":...,"shape":"27|...","count":4,"note":"bursty unclassified"}
```

---

## 4. IMPLEMENTATION PLAN (ordered diffs)

### D1 — `filter/GamePacketTaxonomy.java`: add mask-independent helpers

```java
/** Count of adjacent equal byte pairs (i j where data[i]==data[i+1]); survives constant XOR. */
public static int adjacentEqualPairCount(byte[] data, int off, int plen)

/** Voice-frame shape prefilter: plen 20..24 or 60..90 with >= 3 adjacent-equal pairs. */
public static boolean isVoiceFrameShape(byte[] data, int off, int plen)

/** LE u16 at bytes 13..14 (OUT/18 ammo-sync); caller validates the channel first. */
public static int ammoCounterLE(byte[] data, int off, int plen)
```

### D2 — new `dev/capture/PacketTag.java`

```java
public final class PacketTag {
    public enum Confidence { CONFIRMED, LIKELY, POSSIBLE }

    public static final int TAG_OUT = 0;
    // ... tags 1..31 per the table in section 1.2 ...
    public static final int TAG_COUNT = 32;

    /** Corroboration bits for FireEvent.corrobMask. */
    public static final int CORROB_AMMO_DELTA = 1;
    public static final int CORROB_OFFBEAT_KEEPALIVE = 2;
    public static final int CORROB_ENEMY_ECHO = 4;
    public static final int CORROB_HIT_LINKED = 8;

    public static long bit(int tag);
    public static String name(int tag);
    public static Confidence defaultConfidence(int tag);
    /** "HIT_CONFIRM|HEADSHOT" for a single-tier mask. */
    public static String maskNames(long mask);
    /** "HIT_CONFIRM!|MISS_CANDIDATE?" — ! confirmed, ~ likely, ? possible. */
    public static String maskNames(long confirmed, long likely, long possible);
}
```

### D3 — `dev/capture/CaptureRecord.java`: add capture-time tag mask

**Decision — extend `CaptureRecord` vs new `TaggedCaptureRecord`:** extend.
A subclass would fork the record list type (PacketCapture's single-writer
`ArrayList<CaptureRecord>`), force a 120k-element copy to "upgrade" records for
analysis, and save only 8 bytes. A parallel analysis-side structure (D5) already
holds analysis-time tags, so the record itself only needs the capture-time mask.

```java
public final long captureTags;              // new last constructor param (default 0 via overload)
public String captureTagNames();            // PacketTag.maskNames(captureTags)
```

Single construction site (`PacketCapture.appendRecord`) — no other callers.

### D4 — `dev/capture/PacketCapture.java`: compute capture tags + ghost phase

```java
private final AtomicBoolean ghostOn = new AtomicBoolean(false);
public void setGhostPhase(boolean on);                    // called from VpnEngine.noteFilterToggled()

/** Writer-thread only: O(1) mask-independent tag bits (tags 0..11, 26..29). */
private static long computeCaptureTags(Dir dir, int family, int kind, int plen,
                                       byte[] payload, boolean freezeOn, boolean ghostOn,
                                       Decision decision)
```

- `Pending` gains `final boolean ghostOn` (read in `record()` exactly like
  `freezeOn` — one volatile read, no hot-path signature changes to
  `recordOutbound`/`recordInbound`).
- `writeRawCapture`: append one additive column `tags=<names>` at end of line
  (capture-time tags only). Additive — existing parsers keep working; note the
  format-version comment bump.

### D5 — new `dev/capture/CombatCorrelator.java` (package-private)

The §2 pipeline. Pure function of the record list; no I/O.

```java
final class CombatCorrelator {
    static final int HIT_LATENCY_WINDOW_MS = 500;   // adaptive after >=5 confirmed pairs, clamped 150..900
    static final int SHOT_GAP_MAX_MS = 400;         // same burst
    static final int ENGAGE_GAP_MAX_MS = 3000;      // same engagement
    static final int RELOAD_SILENCE_MIN_MS = 1200, RELOAD_SILENCE_MAX_MS = 4000;

    CombatCorrelator(List<CaptureRecord> records)
    void run();                                   // stages F1..F5 in order

    List<FireEvent> fires();                      // accepted WEAPON_FIRE events
    List<FireEvent> rejectedCandidates();         // for section 5a
    List<HitEvent> hits();
    List<Engagement> engagements();
    List<ReloadEvent> reloads();
    boolean ammoChannelValid();                   // did OUT/18 pass session validation

    long[] confirmedMasksByIndex();               // per-record analysis-time CONFIRMED bits
    long[] likelyMasksByIndex();
    long[] possibleMasksByIndex();

    static final class FireEvent {
        long tsMs; int seq; int weaponTag; int corrobMask; int burstId;
        int engagementId; int recordIdx; boolean freezeBlinded;
        PacketTag.Confidence confidence;
    }
    static final class HitEvent {
        long tsMs; int seq; int zone; int damage; int weaponTag; boolean zoneMasked;
        int fireSeq;          // -1 when unlinked
        long latencyMs;       // -1 when unlinked
    }
    static final class Engagement {
        int id; long startMs, endMs; int shots, hits, misses, suppressed, blinded;
        int totalDamage, headshots; int[] zoneCounts;
        java.util.List<Integer> weaponTags; double accuracyPct; long medianFireToHitMs;
    }
    static final class ReloadEvent {
        long tsMs; int ammoBefore, ammoAfter, silenceMs; PacketTag.Confidence confidence;
    }
}
```

Ammo-channel session validation (mask-safe): bytes 13..14 of consecutive OUT/18
packets must form ≥ 70% monotone-decreasing steps of magnitude ≤ 8 with rare
large upward jumps; otherwise the channel is ignored and AMMO_DELTA corrob /
RELOAD-from-ammo are disabled (confidence falls back to other signals).

### D6 — new `dev/capture/TaggedDumpAnalyzer.java`

```java
public final class TaggedDumpAnalyzer {
    public TaggedDumpAnalyzer(List<CaptureRecord> records, long droppedByCap)

    /** Runs the full pipeline; writes tagged_dump_<stamp>.txt + .jsonl; returns the .txt (or null). */
    public File analyze(File dir)

    File writeTextReport(File dir, ReportModel model)   // sections 1..11 of section 3.1
    File writeJsonl(File dir, ReportModel model)        // section 3.2

    /** Reusable by tests / future UI. */
    ReportModel buildModel();
}
```

Internal pipeline order: `buildModel()` → session/endpoint pass (tags 20–25) →
`CombatCorrelator.run()` → merge tag masks (record-level captureTags ∪ analysis
masks) → freeze/ghost window impact pass (tags 30–31, section 10) → render text
+ JSONL.

### D7 — `core/VpnEngine.java`: wire it in

- In `stopCapture()`'s background runnable, after the FreezeDiffAnalyzer line:

```java
File tagged = new TaggedDumpAnalyzer(capture.snapshot(), capture.droppedByCapacity()).analyze(dir);
```

  (plus a Prefs slot for `KEY_CAPTURE_LAST_TAGGED`, mirroring the existing two.)
- In `noteFilterToggled()` (and `startCapture()`), add
  `capture.setGhostPhase(filterEngine.isKindActive(FilterKind.GHOST));`

### D8 (optional, follow-up) — `dev/capture/FreezeDiffAnalyzer.java`

Once the tagged dump is validated on a few real captures, replace
`writeCombatLog`'s inline duplicated logic with a call into
`CombatCorrelator` (or delete the section and point readers at the tagged
dump). Keep until then — zero regression risk while the new pipeline beds in.

### Memory & perf budget

| Item | Size | Where |
|---|---|---|
| `CaptureRecord.captureTags` (long) | +8 B × 120k = **+0.96 MB** | capture (writer thread) |
| 3 parallel `long[]` analysis masks | 24 B × 120k = **2.9 MB transient** | analysis only |
| Event lists (fires/hits/engagements) | ~48 B × ~10k ≈ **0.5 MB** | analysis only |
| Text + JSONL output | bounded by event count, streamed via BufferedWriter | file I/O after stop |

Worst case stays under ~20 MB against today's ~15 MB peak; all growth is off
the hot path. Capture-time tag computation is a handful of compares on data the
writer thread already touches.

### Risks / notes

- Raw-capture column addition is additive but bumps the informal format —
  update the `# columns:` header comment.
- Weapon-tag bytes from off-beat keepalives are baseline-diff derived — they
  are session-relative ids, not global weapon ids; the report prints them as
  `0x..` with the cross-game damage-band classes from PROTOCOL_MAP as advisory
  labels only.
- `KILL_CANDIDATE` remains LIKELY at best until a kill-feed shape is
  calibrated; section 11 surfaces candidates for that calibration.
