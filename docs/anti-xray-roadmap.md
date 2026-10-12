# Anti-X-Ray Roadmap

Goal: the best open-source anti-x-ray for Paper/Folia. Staff decide, the system only reports (auto-punish stays off).
Tags: **[V]** verified by a source (see end), **[I]** our inference, **[C]** read from our code.

## 1. Where we are

Already in `moderation-module` **[C]**:

- **Bait ores** (`HoneypotService`): per-player fake diamond/debris via `sendBlockChange`, sealed in rock, restored when open space comes within a sight distance `r` (`BaitSightRule`: grows with dig speed, haste and ping, capped at 8). "Reached" is counted statistically: baits / eligible rock x blocks newly brought within `r`.
- **Reveal statistics** (`RevealRule`, `OreDensitySampler`): veins revealed per depth band versus sampled world density, per ore kind.
- **Steering** (`HiddenOreRule`, `SteeringRule`): turns toward hidden ore versus a paired hidden-rock control.
- **Combination** (`XrayVerdictRule`): Poisson / binomial tests merged with Fisher's method, reported as "1 in 10^N". `MiningLedger` has half-life decay and timestamped finds.

Strengths: every signal has a computable honest-player null hypothesis, which is rare in OSS (most plugins use ratios with hand-tuned weights). Bait costs no extra packets for honest players, and the server never changes a real block.

Weaknesses (honest list):

- **r > 1 weakens per-bait evidence.** A bait is taken back before the miner is adjacent. For fast diggers (r up to 8) "reached" means "came within r", not "walked into it". An x-ray user's advantage (going straight to the bait) is diluted into a near-miss test with a small effect size, so detection of fast diggers needs far more blocks. **[C/I]**
- **Instamine can still flash** beyond about 250 ms ping (`MAX_DISTANCE`). **[C]**
- **Baits are statistically uniform and not fingerprint-proof.** A client that ignores fakes, or that only mines ores that are plausibly real (exposed to air or adjacent to caves), is invisible to the bait test. **[I]**
- No visibility model (what the player could really see), no gaze signal, no path-efficiency signal, no evidence replay for staff.
- Interaction with Paper anti-xray is undefined (see 3.1).

## 2. Landscape

| System | What it does | Learn / integrate |
|---|---|---|
| Paper anti-xray modes 1/2/3 **[V]** | Mode 1 swaps enclosed ores for stone/netherrack. Mode 2 also adds random fake ores. Mode 3 randomises per layer. `update-radius` 0-2 resends real data when a client interacts with a block. `lava-obscures`, `max-block-height`, `use-permission` bypass. | Cheap first layer; removes resource-pack x-ray. Its fakes are chunk-level and global, so they confuse our reveal stats unless handled (3.1). Its `update-radius` is a fixed version of our `r`. |
| Orebfuscator **[V]** | Packet rewriting, ProximityHider, line-of-sight checks (4.3.3+); Folia supported. | Proximity/LoS reveal model; a reference for "what may the client see". |
| RayTraceAntiXray **[V]** | Async multithreaded server-side ray tracing on top of engine-mode 1. Culling is approximate: "when in doubt it is visible". CPU hungry, no reload. | Its visibility estimate can drive our "was this ore visible" logic (3.2). Bias towards early reveal matches our `r` trade-off. |
| AntiXrayOP, Clairvoyant, XRay Informer, MinerTrack **[V, summaries only]** | Ratio and suspicion scoring (ore ratio, tunnelling, path to ore, ore scarcity, time window); CoreProtect-based; staff-alert-oriented. AntiXrayOP also has per-player hiding and fake-ore decoys. | Path-to-ore and rapid-chain features (3.4, 3.5). Staff-alert UX. We have the stronger statistics. |
| GTL XRayDetect **[V, summary only]** | Buried decoy ores alert staff when mined. | Same idea as ours, without the statistics and (as far as we know) not per-player. |
| Grim, Vulcan, Matrix, Spartan etc. | No x-ray checks found; comparisons point to Paper anti-xray as a separate layer **[V]**. Watchdog is described as behavioural / rate-based, but only by a blog with no method **[unverified]**. | Nothing to integrate; a gap we fill. |

Not found anywhere: any documented technique for suppressing client block-break prediction for fakes. The 1.19+ ack mechanism reverts predicted changes the server did not confirm **[V]**; using it to delay acks would hurt honest breaks **[I]**.

## 3. Roadmap (priority order)

Effort: S < 2 days, M ~ 1 week, L > 2 weeks. Risk = risk to honest players.

### P0 - fix the weak spots of what exists

**3.1 Paper anti-xray coexistence** - M, risk low, no dependencies
- What: detect Paper `engine-mode` per world and adapt. In mode 2/3 the world holds random fake ores in packets, so our reveal stats and `HiddenOreRule` would count fakes. Either use the real block (server world, not packet; our `WorldGrid` reads the real block already **[C]**) or document that stats are unaffected.
- Why: most servers run mode 1 or 2, so the combination must be correct. Our baits must not be placed where Paper already shows a fake, and a bait restore must not overwrite Paper's obfuscated block **[I]**: restore to what Paper would send (obfuscated block) rather than the real ore, or the restore itself leaks an ore.
- Verify with a live test of `sendBlockChange` restore under mode 1/2 (not yet known).
- Fit: a `WorldAntiXrayInfo` helper read in `HoneypotService.fakeFor` / `restore`.

**3.2 Effect-size honest "reached" (reach tiers)** - M, risk none
- What: track, per bait, the closest distance the miner's open space came, and the final fate: "taken back at distance d", "dug to". Weight evidence by d: p-values from the adjacent-only subset are separate from the within-r count (two Poisson tests combined by Fisher). For r = 1 the test is exact as today.
- Why: addresses the r > 1 dilution directly. An x-ray user who beelines to a bait passes within r and also continues straight towards it; the follow-up direction after the restore is the signal **[I]**.
- Also: after a bait is restored, check whether the dig keeps heading for that position (a "heading to the bait position" check, comparable to steering) **[I]**.
- Fit: extend `Reach` with distances; `MiningLedger` gets per-tier counters; `XrayVerdictRule` adds one Signal.

**3.3 Bait anti-fingerprinting** - M, risk low
- What: baits must look like real ore to a ray-tracing client: match local ore statistics (kind per depth band from `OreDensity`: diamond mostly deepslate bands, debris only in the Nether at its real heights, vein shapes of 1-4 blocks, not single blocks); host-rock variant must match (deepslate ore in deepslate). Randomise per player with a salted seed so cross-player comparison reveals nothing.
- Why: a cheat that learns "single diamond with no neighbours deep in rock" can filter baits **[I]**. Single-block veins are real for diamond (many are 1-3 blocks) but clustering is common **[I]**.
- Fit: replace the single-position `fakeFor` with a `BaitShape` generator; ledger counts per vein, not per block.

### P1 - new signals

**3.4 Path efficiency to hidden ore** - M, risk medium (cave systems, strip-mining)
- What: for each real vein the player reaches, compute how directly the tunnel approached it from the last time the path was non-adjacent to anything open, versus a simulated honest miner (random or strip-pattern) on the same terrain. Reports "excess directness".
- Why: AntiXrayOP / MinerTrack use similar "path to ore" ideas **[V, summaries]**; our version has a null model. Steering already covers turns; this covers straight tunnels towards ore.
- False positives: players following known ore (seen from caves, lava, torch light). Mitigate with `SeenOres` (exists **[C]**) and exposure checks (3.5).

**3.5 Visibility model** - L, risk low, depends on 3.1 for mode awareness
- What: a cheap server-side "could the player have seen this ore" check for every vein they reach: LoS from the player's recent positions to any exposed face (cached raycast, bounded count per tick), limited to lit / open caves. Veins reachable only through unseen rock count as hidden hits, others are excluded from the reveal test.
- Why: removes the biggest honest false positive in reveal stats (cave-exposed ores, visible veins) **[I]**. RayTraceAntiXray / Orebfuscator show feasibility and performance profile **[V]**, but they hide blocks, we only classify.
- Fit: new `VisibilityRule` next to `RevealRule`; reuse `BlockGrid`.
- Folia: raycasts only on the player's region thread, budgeted.

**3.6 Gaze signal** - M, risk medium
- What: sample the player's look direction (packet-less: on movement/rotation events or a 50 ms sample on the entity thread) and test whether it dwells on hidden ore or baits within, say, 20 blocks through solid rock, versus controls (same pairing idea as `HiddenOreRule`). Combine as a binomial test.
- Why: x-ray users look at ores through walls **[I]**. Also works for baits that were never reached, which fixes the dilution in 1.
- Risk: honest players look around randomly; pairing with rock controls and a requirement of dwell time makes it fair in theory but needs calibration on real data.

**3.7 Branch-mining pattern recognition** - M, risk high
- What: recognise legit strip / branch / grid mining so the other signals are normalised (honest branch mining reveals more rock per block, so expectation rises). Use the revealed-count normalisation we have, plus a feature "regular spacing" as a negative weight.
- Why: it is the main honest reason for high ore rates **[I]**. Make it reduce suspicion only for the reveal test, never for baits.

### P2 - bait strategy

**3.8 Adaptive bait density** - S-M, risk low
- Raise density for players with a borderline score, lower it for cleared players, bounded by per-region performance budgets. Keep `expected` computed from the density actually in force at that moment (ledger already stores expected, so changes are safe **[C]**).

**3.9 Early-warning bait types** - S
- Mix: ancient debris only in the Nether, diamond, and an occasional emerald in mountain biomes only. Match `OreDensity` per band so that bait kinds look plausible.

### P3 - staff experience

**3.10 Evidence replay** - L, risk none
- What: store the ledger's trail with timestamps (`finds` already timestamped **[C]**), plus a short ring buffer of position/look samples around each bait/vein hit. A staff command replays or shows a path with markers; optionally exports JSON.
- Why: staff decide, so evidence must be inspectable. No other OSS plugin offers this **[I]**.
- Fit: new `XrayEvidenceService` view; storage with a cap per player and the existing decay.

**3.11 Config presets** - S
- `relaxed`, `default`, `strict` bundles of report score, honeypot count/radius, half-life. Documented trade-offs.

### P4 - quality and operations

**3.12 Performance budgets on Folia** - M
- Budgets: max probe count, max raycasts per tick, max chunk reads; metrics (`/xray stats`) with timings per module; ownership checks already used (`isOwnedByCurrentRegion` **[C]**). Make every new check skip when over budget instead of queueing.

**3.13 Test strategy** - M-L, should accompany every item above
- Unit tests on rules with fixed seeds (Poisson/Fisher boundaries, `BaitSightRule` table of tools x ping).
- Simulation harness: simulated honest miners (strip, branch, cave, random) and simulated cheaters (beeline to ore, beeline to baits, ignores fakes) over a generated world; report the false-positive rate per 1000 hours and time-to-detect. This also tunes the thresholds.
- Bot e2e: a headless client bot (e.g. a Mineflayer script) on a real Paper+Folia test server that mines with instamine at several artificial pings and records whether a bait is ever visible. This directly verifies the flash fix and mode 1/2 coexistence.
- Live test plugin (see the `minecraft-dev` skill) for `sendBlockChange` / ack behaviour.

## 4. Deliberately not doing

- **Auto-ban / auto-punish.** Policy; and the statistics are evidence, not proof.
- **Delaying or forging block-change acks.** No documented technique **[V: nothing found]**, harms honest players' break feel, risks desyncs **[I]**.
- **Visible-to-honest fakes** (e.g. Paper mode 2 style fakes in air-exposed spots). They corrupt reveal statistics and show honest players ores that are not there.
- **Our own chunk-packet obfuscation engine.** Paper + RayTraceAntiXray already do it well; we should coexist, not duplicate. (Orebfuscator and RayTraceAntiXray need ProtocolLib / PacketEvents; we avoid the dependency.)
- **Raw ore-ratio alerts as a primary signal.** They are the main false-positive source in other plugins (any lucky or branch-mining player); we keep them only as a context field.
- **Resource-pack / texture detection.** Not detectable server-side.

## 5. Open questions for the owner

1. Paper engine-mode: do you run mode 1/2 or RayTraceAntiXray? That decides how soon 3.1 matters and whether baits restore to real or obfuscated blocks.
2. Acceptable honest-player false-positive target (e.g. 1 per 10,000 players-hours)? It sets thresholds in the simulation harness.
3. Is a headless-bot e2e test (Mineflayer / Node) acceptable as a test dependency, or must tests stay Java-only?
4. Evidence storage: how long may per-player path and look data be kept? GDPR: this is behavioural data; we need a retention period and a mention in the privacy policy.
5. Should the gaze signal (3.6) be allowed to feed the reported score, or only be shown to staff as context until calibrated?
6. Public release goals: should the statistics (rules, ledger, verdict) be split into a standalone library module so that other plugins can use them?

## Sources

- Paper anti-xray guide: https://docs.papermc.io/paper/anti-xray/
- Paper world configuration: https://docs.papermc.io/paper/reference/world-configuration/
- RayTraceAntiXray: https://github.com/stonar96/RayTraceAntiXray
- Orebfuscator: https://dev.bukkit.org/projects/orebfuscator
- Block prediction / ack (PaperMC commit): https://ayakael.net/mirrors/papermc/src/commit/9271ee7643614663a6d93323a9e8db2d52123166
- Client implementation (Azalea): https://git.matdoes.dev/mat/azalea/commit/1b348ceeffc61e49b19f2982e7a9de479c1678de
- AntiXrayOP: https://www.boxtoplay.com/en-bb/minecraft-hosting/minecraft-server/modrinth-plugins/antixrayop-52638
- Clairvoyant: https://modrinth.com/mod/clairvoyant
- XRay Informer: https://www.boxtoplay.com/en/minecraft-hosting/minecraft-server/curseforge-plugins/curse-xray-informer-426
- MinerTrack: https://moddex.gg/mod/minertrack
- GTL XRayDetect: https://modrinth.com/mod/gtl_xraydetect
- Anticheat comparison: https://mineguard.pro/blog/best-minecraft-anticheats-2026
