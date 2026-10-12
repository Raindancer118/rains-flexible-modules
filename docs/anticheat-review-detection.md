# Anticheat detection review (anticheat-module 0.10.1)

Scope: read-only review of `anticheat-module/src/main/java/de/raindancer/modules/anticheat/` (~7.8k lines) plus test inventory. Nothing was executed against a live server. Statements are tagged **[V]** verified in code, **[I]** inference or calculation from code, **[U]** unverified external fact. Paths below are relative to the anticheat package; line numbers refer to the current files.

External sources (weak; the open-source competitors' internals are mostly not documented publicly):
- Grim README: claims a "1:1 replication of the player's possible movements", a per-player world replica queued until the player has acknowledged the change, checks on the Netty thread, headline thresholds reach 3.01 / timer 1.005 / speed 0.01 % / antikb 99.99 %. https://github.com/GrimAnticheat/Grim
- Grim wiki lists movement, reach, nofall, timer, antiknockback, noslow, scaffold/place, badpackets, crash checks. https://github.com/GrimAnticheat/Grim/wiki/Checks
- Polar is described as cloud/ML behaviour analysis (closed source); Vulcan, Matrix, Intave, Verus: no public technical detail found. https://mineguard.pro/blog/best-minecraft-anticheats-2026 (vendor blog, treat as marketing). Comparisons to those four below are therefore from general knowledge and marked [I]/[U].

---

## 1. Coverage

Pipeline [V]: `PacketTap` (Netty, read-only) -> `MoveSample` queue -> `MovementEngine.tick` once per tick per player (`Scheduling.entityTimer`) -> pure rules (`rules/*`) -> `Buffer` -> `ViolationService.flag` -> `ActionRule`. Defaults [V, `AntiCheatSettings.DEFAULTS`]: alerts on, **setbacks off, cancel off, kick/ban off**; `exempt-bedrock` **on**, `min-tps` 17, `timer-leniency` 150, `reach-leniency` 0.3, `max-cps` 20.

| Category | Status | Where / how | Verdict |
|---|---|---|---|
| Fly (gravity/hover/jump height/step) | Yes | `VerticalRule.judge`, called `MovementEngine.moved` L288-310 | Good for vanilla air; blanket skip in "special" blocks (see 2.2) |
| Speed | Yes | `HorizontalRule.judge/limit`, `MovementEngine.judgeHorizontal` L418-480 | Upper-bound model, tolerance compounds (2.1) |
| Strafe | Yes | `AirControlRule.judge` (vector diff vs 0.91 carry + 0.026), engine L333-342 | Good idea; precise enough |
| NoSlow | Yes | `HorizontalRule` outcome NO_SLOW, `SlowdownRule`, `SlowState.rapid` | OK for item use/sneak; not for soul sand/honey/web |
| Step | Partly | step bound in `VerticalRule` (ticks==1 && onGround) + `Physics.standing` | Honoured through `STEP_HEIGHT` attribute; no separate check |
| Phase | Yes | `PhaseRule.judge` (sweep, level moves only) + inside-block test L262-278 | Vertical/diagonal clips (`|dy|>0.01`) are not swept: `PhaseRule.java` L34 |
| NoFall | Yes | engine L312-327 (client onGround vs server collision) | Ok; restores fall distance (after the fact) |
| Timer | Yes | `TimerBalance` fed by `ServerboundClientTickEnd`, `judgeTimer` L191 | Good, modern; 1000 ms lag credit bound |
| Jesus | Weak | only as relabel of Fly failure when `overLiquid && |dy|<0.05` L304 | Walking *in* water is skipped entirely |
| Elytra | Weak | `judgeGlide` L482: only "hover/climb 8 ticks without firework" | No speed/direction model at all |
| Boat/entity fly | Weak | `MovementListener.onVehicleMove`: boats, climb >4 ticks | No horizontal speed, no horse/strider/ghast/minecart; `ServerboundMoveVehicle` not read |
| Velocity / AntiKB | Weak | `MotionRule.knockbackTaken`, only the **vertical** part, and only if sentY >= 0.2 | Horizontal AntiKB (the common kind) undetected |
| Scaffold | Yes (heuristic) | `WorldService.placed` L137-219 + `PlaceRule` (face visible, looked-at, against air/liquid) | No tower/rotation-snap/sprint-bridge pattern |
| Tower | No | - | Covered only indirectly by Fly |
| FastPlace | Weak | `WorldService.placed` L156: `>20/s || burst>=4/50 ms` | A "1 block per tick" fastplace gives ~20/s = not > 20 |
| FastBreak | Yes | `BreakRule` + `broke` L107-129 (blatant <50 %, 1 s cumulative balance) | Good |
| Nuker | Weak | >=3 STARTs in a tick (`PacketTap` L470) | No angle/line-of-sight-to-crosshair; no break-facing check |
| GhostHand / interact through walls | Partly | `WorldService.canSee` (any of 7 points visible) for breaks and **containers only** (`isContainer` name list, L286) | Doors, levers, buttons, signs, beds, anvils, entities (Interact packet) not covered |
| Reach (melee) | Yes | `CombatRule.reach` = distance eye->AABB, 3.0 + 0.3, boxes from 200 ms + ping history, 4 eye candidates | Lenient: effective 3.5-4 blocks (2.3) |
| Reach (block) | Yes | `WorldService.judgeReach` slack 0.5 + last move | Lenient (+~1 block) |
| Hitbox | Yes | `CombatRule.hitbox` (ray vs grown box, 5 rotations) settled 60 ms later L662 | OK |
| KillAura | Partly | `invalidAttack`, MultiAura (packet-level, >=2 targets/tick), NoSwing, WallHit, Post (exp.) | No target-switch/angle/consistency analysis |
| Aim / rotation analysis | Weak | `AimRule`: pitch GCD vs sensitivity step (exp.), 360 wrap | Single heuristic family; controller/spyglass FP (2.5) |
| AutoClicker / CPS | Weak | `ClickRule.tooFast` (>20 cps) and `tooEven` (40 intervals, all identical tick count, >=7 cps) | Misses jitter/butterfly/drag click statistics; `stats()` computes kurtosis/skew but nothing judges them |
| Criticals | Yes | `MotionRule.critical` (height <0.15 and no rise) | Basic (misses packet-level "fake fall") |
| AutoBlock | Partly | `invalidAttack`: attack while `isHandRaised` | Fine in modern MC (no sword block) |
| InvMove | Yes | `judgeInventory` L152 (container open >400 ms + walking) and click-while-walking | Reasonable |
| Chest stealer / fast click | Yes (exp.) | `InventoryListener.onClick` >25/s or >=6/50 ms | Very lenient; no first-click-delay/order/pattern |
| AutoTotem | Yes (exp.) | pop -> off-hand totem <150 ms | OK |
| AutoFish | Yes (exp.) | `WorldListener.onFish` | OK |
| BadPackets | Weak | pitch >90, duplicate rotation, double sprint, same slot (`PacketTap` L391-396, 486, 514) | Small set (Grim has dozens) |
| Ping spoof | Yes | `PingRule.spoofed` keep-alive vs own pong RTT | Nice |
| Blink / packet-hold | **No** | - | Only Timer sees bursts after a hold |
| Post | Yes (exp.) | `PacketTap.post` | OK |
| Client brand/channels | Yes | `ConnectionListener.checkBrand` | Trivially spoofable, informational |
| Anti-ESP | Yes (off) | `EspShield` | Not a detection |

Missing entirely: Blink/lag-switch, boat/vehicle horizontal speed, elytra speed/direction, horizontal AntiKB, swimming/water speed (Dolphin/Jesus-in-water), Spider on plain walls beyond ladder cap, FastClimb on vines (cap 0.2 only if `m.special` on ladder), Step/HighJump as separate categories, Inventory-click rotation/sprint-in-GUI, Interact-through-wall for entities, Baritone-like path bots, Fastuse/FastEat/FastBow (no item-use timing check), self-damage/Fly-via-damage, Crash/exploit packets (oversized book, NBT, bad slot ids), Derp/`Freecam` positions are server-side invisible.

---

## 2. Correctness and false-positive/bypass risks

### 2.1 Tolerances compound because the model is chained on the client's last move **[I, computed from code]**
`HorizontalRule.limit` (L71-90) bases the next-tick bound on `move.lastHd()` (the player's *actual* previous speed) and then allows `+TOLERANCE 0.015` (L60). A cheater who always stays 0.015 over the per-tick bound re-seeds the next bound with that excess.
- Air (carry 0.91, accel 0.026): fixed point = (0.026+0.015)/0.09 ~ 0.46 blocks/tick vs 0.29 vanilla steady state, i.e. roughly +50 % sustained air speed passes.
- Ground sprint (0.6 block, carry 0.546): (0.13+0.015)/0.454 ~ 0.32 vs ~0.29, ~ +10 %.
Same shape in `VerticalRule` (TOLERANCE 0.02 on `dy > expected`, L58): gliding "25 % slower fall" (terminal ~-2.9 instead of -3.92) is not flagged. Grim's approach avoids this by comparing to a prediction from *its own* velocity state, not the client's.
Fix: carry the *predicted* (clamped) speed rather than the observed one, or apply tolerance only to the observation, not to the next-tick base (`lastHd = min(lastHd, limit)`).

### 2.2 Blanket exemption "special" skips Fly, Speed, Strafe, NoFall, Jesus **[V]**
`Surroundings.at` L89-100 sets `special` for liquid, `Tag.CLIMBABLE` (ladders, vines, cave vines, scaffolding), **all trapdoors** (L90), cobweb, sweet berry bush, powder snow, bubble column, honey. `MovementEngine.moved` L282/288/313/329/334 then skip nearly every check, plus one extra tick through `m.special`.
- Speed and Fly *in water* are never judged (no swim/Dolphin's Grace/Depth Strider model). Water hack, "Jesus in water", jet-ski: undetected.
- Any trapdoor/vine/berry bush within 0.1 of the box turns off all movement checks. A cheater can place one trapdoor on his path; vines grow naturally on cave walls.
- Cheaper partial coverage is possible: keep Speed with a generous upper bound in water (water horizontal speed is bounded by ~0.2 b/t even with Dolphin's Grace), and keep Fly/NoFall for "web" and "powder snow".

### 2.3 Exemptions are unrationed and stackable **[V]**
`PlayerTrack.exemption()` returns *any* active reason, and `moved` L254 skips all checks. Windows: ELYTRA 1000 ms per glide toggle (`MovementListener.onGlide`), ELYTRA 3500 ms per firework boost (L103), EFFECT 1500 ms on any speed/jump/levitation/slow-falling effect change (L139), VEHICLE 1000/1500 ms per enter/exit, PISTON 1500 ms for any player within 4 blocks of any piston, TELEPORTED 200 ms, RIPTIDE 3000 ms, BLOCK_UNDERFOOT 600 ms after breaking a block under the feet (`WorldService.broke` L98). A client can trigger several of these on demand (glide toggle in air, mount/dismount a boat, break the block below) to hold permanent immunity. Unlike `Excuses` for BlockChanges, these have no budget. **[I]** exploitability depends on server items (elytra, boats).
Suggested: per-reason budget (e.g. 3 grants per minute, then no new window), and shorter windows tied to the actual state (glide: exempt only while `isGliding()`).

### 2.4 Latency compensation is partial **[V]**
- Good: own `ClientboundPing` behind block updates and knockback (`PacketTap.write` L344-365, `PendingBlocks`), median RTT of own pings as `track.ping` (never exempts, capped by `max-ping`), `PING_SPOOF` check.
- But the *world* the move is judged against is the live server world at engine-tick time (`Surroundings.at` in `moved` L240), not a per-player replica as of the acknowledged state. Compensation exists only for blocks within `BLOCK_REACH = 3` (L276) of the *last accepted* position; entities (boats, shulkers, minecarts, happy ghasts) are looked up live (`nearRideableGround`, `pushers`) and just widen tolerance when a check fails (L302-303). Moving entities (boats, pistons, falling blocks, mobs pushing) at 100+ ms ping will still produce FPs; they are currently masked by loose bounds and exemptions rather than modelled.
- Moves are queued on the Netty thread and judged up to 1 tick later on the region thread; between those, `blocksInFlux` uses `BlockChanges.changedSince(..., now - ping - 150ms)` (L414), and `Excuses` rations 12 in a row (`Excuses.IN_A_ROW`). That is a sound heuristic; still, ping is capped (`max-ping` 600) and uses a median, so spiky jitter (Wi-Fi, mobile) above the median is not covered.
- TPS drops: `Bukkit.getTPS()[0] < min-tps(17)` pauses movement/timer/break checks (`MovementEngine` L130, `WorldService` L106) **[V]**. Reach/hitbox/clicks are not paused; they rely on timestamps taken from `System.currentTimeMillis`, which is fine since they are packet-timed.
- Timer: after a >1 s *client-side* stall the bursted ticks produce up to `stall - 1000 ms` positive balance (`TimerBalance.tick` L85-94, credit 1000 ms) **[I]**; leniency 150 ms means a 1.2 s network hiccup flags Timer. `settle()` after flag limits repeats; check live logs for Timer flags with high ping.

### 2.5 Bedrock/Geyser **[V]**
`Tracks.isBedrock` (MSB==0, Floodgate UUID). `ViolationService.runs` L49 returns `false` for **every** check when `exemptBedrock` is on (default). The setting text (AntiCheatSettings L51-55) only promises to skip *movement* checks. Combat/world/inventory checks are also off for Bedrock players, so a hacked-Bedrock client has no checks at all. Needs: restrict the exemption to `CheckType.versionBound()`/movement + packet-order checks, and keep reach/hitbox-with-bigger-leniency/fastbreak/scaffold-visibility/nuker for Bedrock. **[U]** Geyser reports touch/controller input and quantises rotations differently; GCD-based AimRule must stay off for them.

### 2.6 Attributes and 1.20.5+/26.x mechanics
Handled [V]: `MOVEMENT_SPEED` (includes sprint/speed/soul-speed modifiers when server state agrees; `judgeHorizontal` L432), `GRAVITY`, `JUMP_STRENGTH`, `STEP_HEIGHT`, `SNEAKING_SPEED`, `ENTITY_/BLOCK_INTERACTION_RANGE`, `ATTACK_RANGE` item component (`CombatService` L90), `USE_EFFECTS` (speed multiplier / can sprint, L422), jump boost and slow falling, `getWidth/getHeight` (so `SCALE` indirectly). Gaps:
- `MOVEMENT_EFFICIENCY`, `WATER_MOVEMENT_EFFICIENCY`, `SAFE_FALL_DISTANCE`, `FALL_DAMAGE_MULTIPLIER`: not read. `Physics.NO_FALL_MIN = 1.5` is a constant, so ok for default; a larger `safe_fall_distance` only makes NoFall stricter than needed but never FP-prone because only claims of ground over air are judged.
- `GRAVITY` below default with slow falling: handled by retry L297. Gravity > 0.08 scaled by `SCALE`: attribute is read, ok.
- Wind charge / mace smash / riptide / spear **lunge** (1.21.11+) / elytra fireworks impulses: only knockback through `PlayerVelocityEvent` (`MovementListener.onVelocity` L83) is excused. **[U]** whether Paper fires `PlayerVelocityEvent` for explosion-packet knockback (wind charge) and for lunge impulses is unverified (a web search found nothing definitive). If it does not, a wind-charge jump (up to ~+1.5 b/t vertical) fails `VerticalRule` as "rising against gravity". Recommend a live test with a wind charge, mace smash and a spear lunge on the SMP and watch `/ac log`. Riptide has an exemption (3000 ms, L108); wind charge/mace have none.
- Mace: `critical` check requires fall (`MotionRule.critical`), a mace smash after wind-charge launch has `fallSinceTop` valid; fine. Sword sweeping/crit on a "falling" jump: ok.
- Levitation: Fly skipped, Speed still judged with air bound: ok. Soul speed: only via attribute modifier (if the server exposes it as a modifier **[U]**).
- Honey/powder snow/cobweb/bubble/scaffold: skipped as "special" (2.2); no FP but no coverage. Slime: bounce excused only when the player stood on slime/bed (`m.bouncy && m.onGround`, L293); bounce *chains* (second bounce while airborne from a lower height) pass through `fallSpeed` carry, ok.
- Ice: friction constants 0.98/0.989 [V]; ground friction uses block under the *previous* position, which is vanilla's order.
- Sneaking at edges / step-up / half slabs: the 0.9/0.10 fixes are in `Physics.standing`, `Surroundings.GROUND_REACH 0.03`, `COLLISION_EPSILON`. Still open per Project.md: "a walking player the server sometimes sees airborne".

### 2.7 Buffers can saturate on systematic model errors **[I]**
Fly buffer: limit 1, forgiveness 0.02 (L306-308, `report` L500). A player standing on an unmodelled shape who fails once per second needs 50 clean ticks to forgive one failure; second failure within ~2.5 s flags. This matches the observed "49 of 51 flagged" history. Because Fly/Speed flag with weight 1 and `alertAt 3`, three fails alert. For FP safety prefer a leaky bucket with larger forgiveness per clean *tick* and a time-based decay, and require a minimum *ratio* (e.g. 4 fails in last 40 moves).

### 2.8 Smaller issues
- `MovementEngine.moved` L254: `exemption != null` also **resets** `m.lastDx/Dy/Hd` to NaN via `remember(..., exempt=true)`, fine; but the first move after an exemption gets no vertical/horizontal check (one free tick per exemption) **[I]**.
- `freelyMoving` (L772) treats `isClimbing() && isSneaking()` as free: sneaking on any ladder/vine = no checks (also `Tag.CLIMBABLE` in 2.2).
- Setbacks (off by default) happen after the server has accepted the move: `teleportAsync` at the next tick (L594) and `setVelocity(new Vector())`; cheats such as reach/fall-damage-skip are already applied. Grim cancels at packet level on the Netty thread **[U]**.
- `MovementEngine.setback` target `m.legit` is set only when `here.ground()` (L544); mid-air flags near a cliff set back to the last ground spot, which is acceptable.
- `PacketTap.channelRead` swallows exceptions and never throws (good). Reflection-name dependence (`ServerboundPunchPacket`, `ServerboundClientTickEndPacket`...) -> switches off on version drift; good design, but a startup assertion that the tap actually saw a move packet within N seconds would catch silent loss.
- `CombatService.invalidAttack` `isHandRaised()` can be true one tick after release (server state), possible rare FP **[I]**.
- `WorldService.judgeReach` for `used(...)` includes `RIGHT_CLICK_BLOCK` with items like `endersight`/buckets at range: uses block interaction range (4.5): fine.
- `AutoReportService` counts MOVEMENT category only; combat flags never produce a report.
- Thread safety: `m.velocities` is a `ConcurrentLinkedDeque<double[]>`; array cells mutated from Netty (`velocity[6]`, `[7]`) under `wire` and read from the region thread under `track` -> not guarded by the same lock **[V]** (benign for doubles on 64-bit JVM, but non-volatile visibility). `PlayerTrack.Packets.*` plain fields are written under `wire` and read in `PacketTap` only.

---

## 3. Architecture vs prediction-based engines

**It is threshold/bound-based with physics-informed upper bounds, not a simulation.** [V] Each rule computes a *maximum* given one-step-old state: `VerticalRule` (v' = (v-g)*0.98, plus jump/step/push/bounce allowances), `HorizontalRule` (carry*friction + max accel in any direction, "best input every tick"), `AirControlRule` (vector diff bounded by 0.026 * sum of carries). It reads client-reported input flags (`PlayerInputEvent`/`ServerboundPlayerInput`) only to relax (`keysHeld`, `forward`, sprint legality), never to *predict a unique motion*.

Compared with Grim **[I from the README + general knowledge; Grim's exact thresholds unverified]**:
| | This module | Grim-style prediction |
|---|---|---|
| Per tick output | boolean "within bound" | predicted velocity per *every possible input combination* -> smallest offset |
| Tolerance | 0.015 (h), 0.02 (v), compounding (2.1) | ~1e-4 order offsets, resets to predicted state |
| World | live server world + ping heuristics for 3-block change window | per-player replica, changes applied on acknowledgement |
| Entity physics (boats, pushes, vehicles) | exemption/skip | modelled (claimed in README) |
| Water, ladders, webs, bubble columns, elytra, riptide | skipped | simulated |
| Detection of a 1-10 % speed cheat | no | yes |
| Cost | cheap | heavy but on Netty thread |

What it would take to move key checks to prediction:
1. **Movement state machine** (S0): position, velocity vector (x,y,z), onGround, in-water/lava flags, sprint/sneak/use-item state, effects, attributes, fall distance - all derived from the packet stream and acknowledged server effects (knockback, teleports via `ClientboundPlayerPosition` + `ServerboundAcceptTeleportation`, effects via pong-synchronised `ClientboundUpdateMobEffect`).
2. **Input enumeration**: with `ServerboundPlayerInput` (forward/back/left/right/jump/sneak/sprint are already read, `PacketTap` L200) the possible inputs per tick are known; take the actual flags as ground truth plus a +-1-tick slack set (client input arrives one tick before the move) - up to 3x3x2x2 small set. Simulate `Player.travel`: input vector normalised, speed, `moveRelative`, friction by block under, gravity, drag, jump, then collision **resolution** (`Entity.collide`: axis order Y,X,Z with step-up). Collision resolution is the heavy part; use `World#hasCollisionsIn` / `VoxelShape` via NMS reflection or Paper's `CollisionUtil`.
3. **Replica of the world per player** (or: snapshot at last acknowledged transaction via `PendingBlocks`, extended to all nearby chunks not just 3 blocks).
4. **Medium models**: water (swimming, depth strider, dolphin), lava, ladders, powder snow, cobweb, honey, bubble columns, scaffolding, elytra, riptide, levitation. Each is a branch in `Player.travel`; Grim spent years on this.
5. **Vehicles** (`ServerboundMoveVehicle`) - separate simulators per entity type.

Worth it? For a 50-player SMP with owner policy "never punish automatically", **a full Grim-class engine is not the best ROI**. Do a **partial prediction layer** for the hottest cheats: (a) ground/air horizontal with exact input flags (kills the compounding problem and the "assume best input" slack), (b) vertical with velocity-state instead of `lastDy` chain, (c) elytra (gravity+look vector), (d) horizontal knockback offset. Keep bounds for media you do not simulate. Effort: M-L for (a)-(c) with a proper test harness (record real vanilla moves, assert prediction offset < 1e-3), L+ for full parity. Alternative: embed/bridge GrimAC through its API for movement and use this module for combat/world/report workflow - **[I]** license is GPL-3.0 on the repo, check compatibility with how this module is distributed.

---

## 4. Performance on Folia/Paper

**[V] per-move cost** (`Surroundings.at`, called once per move packet at the end of each tick batch): 3 `hasCollisionsIn` boxes + up to ~3x3 x 4 `world.getBlockAt(...).getType()` and `getBlockData()` for every non-air block (L76-95; allocation of `CraftBlock` and, for waterlogging, `CraftBlockData`) + `Tag.*.isTagged` lookups. Extra `Surroundings.at` in `adopt`. `PhaseRule` sweep: moves > 0.5 blocks horizontal and level are swept with 0.1 steps, each a `hasCollisionsIn` with a new `BoundingBox` (a 4-block move = 40 collision queries; 64-block clips = 183). `blocksInFlux` computes `BlockChanges.changedSince` over the move AABB for *every* move. Allocations per move: `MoveSample`, `ReplayFrame` (+ `getName()`), `Surroundings`, `VerticalRule.Move`, `HorizontalRule.Move`, boxes, `Location`s in `adopt`/`remember` (new `Location` when legit), `ArrayList drained`, `Locale String.format` only on flags. Estimated 20-50 µs/move **[I]** -> 50 players x ~20 moves/s = 1000 moves/s ~ 2-5 % of one core. Acceptable. Cache `Material`->flags in a `EnumMap`/bit table instead of `Tag.isTagged` and avoid `getBlockData()` unless the type can be waterlogged.

**Allocation hot spots** worth removing: `recordPosition` allocates a `double[6]` every tick per player (`MovementEngine` L733); `CheckType.values()` clone every second per player in `refreshPermissions` (L756) plus `hasPermission` x ~45 checks per player per second (permission plugin cost, e.g. LuckPerms contexts) - cache a permission snapshot or use `PermissionAttachment`-based events.

**Combat**: `throughWall` does up to 5 `rayTraceBlocks`, `canSee` up to 7 per break/open; `getNearbyEntities` in `nearRideableGround`/`pushers` run only on failure paths. Fine.

**Thread-safety**
- One timer per player on the entity scheduler [V] (`MovementEngine.start`), shared state guarded by `synchronized (track)`; Netty thread uses `track.wire` only; `Buffer` synchronized. Design is consistent and honest about it (comment in `PlayerTrack`).
- **Folia hazards [I]:**
  1. `Bukkit.getTPS()` is called in the per-player tick (`MovementEngine` L130), in `ViolationService.flag` L64/L85, `WorldService.broke` L106, `AntiCheatCommand` L234. Folia has no global TPS ("every region ticks on its own thread", TickRadar docs; the public `getTPS()` behaviour on Folia is **[U]**; if it throws, every tick fails and `catch (RuntimeException)` at L92 logs a warning per player per tick). Use the region TPS where supported or fall back to `Server#getAverageTickTime`/own tick-duration measure.
  2. `world.getBlockAt`, `hasCollisionsIn`, `getNearbyEntities`, `isChunkLoaded` around a player standing near a **region border** touch chunks owned by another region thread. Folia generally forbids cross-region world access **[U for each call]**; the plugin declares no `folia-supported` per Project.md L1885 ("Core declares no folia-supported"). So the "Folia-compatible" claim should be treated as untested for this module. Needs a real Folia run with players on a region border.
  3. `player.getPing()`, `getAttribute`, `hasPotionEffect` are on the player's own region = fine.
  4. `Scheduling.globalLater` + `Bukkit.getPlayer` for re-arming: fine.
  5. `player.setVelocity`/`teleportAsync` in `setback` are legal on Folia from the entity thread.
- `EvidenceLog`/`ReplayStore` flush on `asyncTimer` every 3 s: ok.

---

## 5. Top 10 recommended improvements (value/effort)

| # | What | Why | Files | Effort | FP risk |
|---|---|---|---|---|---|
| 1 | **Fix tolerance compounding**: base the next-tick bound on `min(observedHd, previousLimit)` (and same for vertical), keep tolerance only on observation | Removes the ~+10 % ground / +50 % air undetected speed window; cheap | `MovementEngine.judgeHorizontal/remember`, `HorizontalRule.limit`, `VerticalRule.judge` | S | Medium (raises detection; run `AntiCheatHonestScenarioTest` + a week in alert-only first) |
| 2 | **Horizontal AntiKB + wind charge/mace/lunge velocity sources**: judge horizontal part of confirmed knockback (use the pong confirmation already there); excuse all impulse sources (verify `PlayerVelocityEvent` for explosion packets, add `EntityKnockbackEvent`/explosion handlers) | Biggest combat-cheat hole; wind charges/mace/spear lunge are likely FP sources on a 26.x SMP | `MotionRule.knockbackTaken`, `MovementEngine.settleVelocities`, `MovementListener.onVelocity` | M | Medium (test live: wind charge, mace, lunge, explosions) |
| 3 | **Exemption budgets and state-bound windows** (glide, effect, vehicle, piston, underfoot) | Closes on-demand immunity; same pattern as `Excuses` | `PlayerTrack.exempt/exemption`, `MovementListener` | S | Low |
| 4 | **Bedrock: exempt only movement/packet checks**, keep combat/world with larger leniency | Today Bedrock cheaters are unchecked; settings text vs code mismatch | `ViolationService.runs` L49, `AntiCheatSettings` L51 | S | Low-Medium (Geyser reach/rotation quirks; start alert-only) |
| 5 | **Unskip media: water Speed/Fly, elytra speed/direction, boat/entity horizontal speed** with generous upper bounds | Biggest movement-coverage hole (2.2, Elytra row, vehicle row) | `Surroundings.special` split (liquid vs climbable vs web), `MovementEngine.judgeGlide`, new `ServerboundMoveVehicle` reader in `PacketTap`, `VehicleRule` | M | Medium (water currents, bubble columns, dolphin's grace, 26.x happy ghast) |
| 6 | **Reach: ray vs hitbox with exact 3.0 + 0.0x**, track victim positions by acknowledged ping (`track.ping` already measured) instead of +200 ms blanket, drop the 4-eye union to the actual tick | Today effective reach ~3.5-4; tight reach is the most visible hack | `CombatRule.reach/closest`, `CombatService.eyes/boxes`, `PlayerTrack.Combat.history` (add per-tick snapshots keyed by tick) | M | Medium (needs good ping data; use leniency scaling with jitter) |
| 7 | **Statistical autoclicker** (kurtosis/skew/outliers/repeated-sample ratio over 100 clicks, double-click patterns) using already computed `ClickRule.Stats` | Current check only catches blatant/constant clickers | `ClickRule`, `ClickService` | S | Low-Medium (alert-only, tune on real SMP data) |
| 8 | **Blink/packet-hold + more BadPackets**: detect >N ticks without any packet followed by burst, `ClientTickEnd` gaps, wrong `ticksSinceTickEnd`, invalid slots, sprint/sneak command contradictions, double use/dig, flying packet with NaN/huge deltas | Cheap packet-layer checks that need no physics | `PacketTap.read`, `TimerBalance` | S-M | Low |
| 9 | **Ghost-hand/break-facing/nuker upgrade**: ray from crosshair through blocks (`BlockBreak` must be on the ray within tolerance), interact-through-wall for doors/levers/buttons/entities, nuker by angle | Closes world holes; reuse `WorldService.canSee` and rotation history | `WorldService.broke/used`, `PlaceRule`, `PacketTap` (Interact) | M | Low-Medium |
| 10 | **Folia and perf hygiene**: replace `Bukkit.getTPS()` with a region-safe helper, cache permissions, enum-cached block flags, drop per-tick `double[]`, add a "tap alive" startup assertion; run on a real Folia server near a region border | Required before claiming Folia support; cheap perf wins | `MovementEngine` L130/L733/L756, `Surroundings`, `ViolationService` L64/85, `WorldService` L106, `AntiCheatCommand` L234 | S-M | None |

Bigger bet (beyond top 10): a **prediction layer for ground/air horizontal + vertical + elytra** with exact input flags (section 3), using the existing e2e/`AntiCheatHonestScenarioTest` and a recorded-vanilla-moves corpus as the safety net. Effort L; only worthwhile after items 1, 3 and 5 are in, since those capture most of the value.

### Tests
The suite is mostly pure-rule unit tests (2.3k lines, `src/test`) plus e2e scenarios outside this module (`AntiCheatScenarioTest`, `AntiCheatHonestScenarioTest`, per Project.md). There are no tests for `Surroundings` special-block handling, exemption stacking, Bedrock gating of combat checks, or wind charge/mace/lunge/elytra scenarios; each of items 1-5 should start with such a test (test-first policy).

### What I did not verify
Live behaviour of Paper 26.3 events (`PlayerVelocityEvent` for explosions/lunge), `Bukkit.getTPS()` on Folia, cross-region world reads, and the numeric predictions in 2.1 on a real client (they are algebra on the code's constants; recommend replaying a recorded sprint-jump through `HorizontalRule` with +0.015 steps to confirm).
