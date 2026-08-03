# Rain's Flexible Modules

Whole features, each one a Maven artifact rather than a copied folder. A plugin gets the real thing, the
source stays the single place it is written, and the same code also ships as a plugin of its own through
the standard wrapper — without being written twice.

Driven by `The Idea.md`. Sits on top of `RainsCore` (see `../RainsCore`), which is the foundation for
everything and is never shaded in.

---

## The delivery model

```
de.raindancer.modules:modules-api        the contract: what a module is, what a host provides
de.raindancer.modules:modules-wrapper    the standard wrapper — turns any module into a Paper plugin
de.raindancer.modules:claims-module      \
de.raindancer.modules:moderation-module   |  the modules themselves
de.raindancer.modules:farmworld-module    |
de.raindancer.modules:teams-module       /
de.raindancer.modules:<x>-standalone     a thin shade shell: module + wrapper = one loadable jar
```

Two ways to consume a module, and the module cannot tell which one it is in:

| | How | Data folder |
|---|---|---|
| **Hosted** | the plugin depends on the artifact, shades it, and enables it through `ModuleHosts.embedded` | `plugins/<Host>/modules/<id>/` |
| **Standalone** | `<x>-standalone` shades module + wrapper; Paper loads it | `plugins/<Name>/` |

`paper-api` and `RainsCore` are **`provided` in every module**. A module carrying its own copy of either
would get a second action bar owner, a second item registry and a second scoreboard arbiter — the exact
problem RainsCore exists to remove.

## Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Vendored or depended on? | **Maven artifacts.** The module source is the single source of truth; nothing is copied into a consumer. |
| 2 | How does a module become a plugin? | **One standard wrapper** (`ModulePlugin` + `ModuleBootstrap`) plus a ~12-line `paper-plugin.yml`. No per-module plugin class. |
| 3 | How are modules found? | **`ServiceLoader`.** Adding a module to a build is adding a dependency, with no list to keep in step. Shade merges the service files. |
| 4 | Who registers commands? | The host, at **bootstrap** — Paper fires `COMMANDS` before `onEnable`, so a module only *declares* its commands. Every one is wrapped by `ModuleCommands.guarded`. |
| 5 | What happens when one module breaks? | **That module and whatever required it, nothing else.** Enforced by `ModuleRegistry`; the failed module's session is unwound on every path out. |
| 6 | Where does shared behaviour go? | **RainsCore, always.** If a second plugin could want it, or two plugins would each write their own, it is not module code. A module is commands, screens and its own product decisions — nothing else. |
| 7 | Are the old GUIs and commands ported? | **No — rebuilt.** On `core.ui.menu`, laid out by topic, deliberately not the same screens as before. |
| 8 | Is proven domain logic rewritten? | **No — moved.** The claim model is working, tested code; it moves into `core.land` with its tests and its semantics intact. Rewriting it would be throwing away years of edge cases to gain nothing. |
| 9 | Are on-disk formats preserved? | **Read, yes.** A server that upgrades keeps its claims, its members and its flags. Writing may move to SQLite via `core.data.sql`, with a one-way migration. |

## Where the claims work goes

`RainsExtendedClaims` is ~42k lines. Split by decision 6 — and towns are out of scope entirely, by
instruction.

**Into `RainsCore` as `de.raindancer.core.land`** — because "may this player build here?" is a question
every plugin on the server asks:

- the model: `Claim`, `ClaimShape`, `ClaimPoint`, `ClaimMember`, `ClaimBan`, `ClaimAudience`,
  `ClaimPermission`, `ClaimFlag`, `FlagPolicy`, `ClaimFeature`, `FeaturePolicy`
- the index and the store: `ClaimRegistry`, `ClaimStorage`, `NoClaimZone`, `ZoneRegistry`
- the answer: a `Land` facade — the claim at a location, and whether somebody may do a thing there
- the enforcement: the protection listeners, because claim data in Core with the enforcement in a
  module means a server that installed one and not the other has unprotected claims

**Into `claims-module`** — the product:

- `/claim` and `/claimadmin`, rebuilt
- every screen, rebuilt on `core.ui.menu`
- selection (stick, flow, visual borders), fences, atmosphere, granted effects, entry fees, eviction,
  cost settlement, equipment rules, pantry, bank, titles, broadcasts

## Status

- [x] `modules-api` — **154 tests, all green.** Contract, ordering, registry, discovery, guarded
      commands, layout, unwind. Reviewed by `agy`; three real defects found and fixed, each with a
      regression test that fails on the old code:
      1. `ModuleRegistry.commands()` handed out modules' own handlers unwrapped, so the guard existed
         and nothing used it — a module that failed to start answered its command with a
         `NullPointerException`. Now guarded centrally.
      2. `canUse`/`permission` were delegated to the module unguarded. Brigadier calls `canUse` while
         *resolving* a command, so a half-started module threw inside Paper's parser and the player saw
         nothing. Now: not running → usable, so the refusal is reachable and explained; running and
         throwing → not usable, failing closed rather than opening a moderation command to everybody.
      3. `listener()` recorded its undo *after* `registerEvents`, which registers handlers one at a
         time — a listener that threw halfway left handlers attached to a failed module with nothing
         recorded to remove them.
      Verified non-vacuous: two deliberate mutations (unsorted ready-set in `ModuleOrder`, dropped
      unwind on failure in `ModuleRegistry`) were each caught, 7 failures between them.
- [~] `core.land` in RainsCore — the model is in and RainsCore is green at **1268 tests, 28 of them
      land**. Committed as `44c5f4b`.
  - [x] the model, moved not rewritten: `Claim`, `ClaimShape`, `ClaimPoint`, `ClaimMember`, `ClaimBan`,
        `ClaimAudience`, `ClaimPermission`, `ClaimFlag`, `FlagPolicy`, `ClaimFeature`, `FeaturePolicy`,
        `ClaimRegistry`, `ClaimStorage`, `ZoneStorage`, `NoClaimZone`, `ZoneRegistry`, the per-claim
        subsystems (`ClaimFence`, `ClaimBank`, `ClaimPantry`, `PotionStore`, `ClaimEquipment`,
        `ClaimAtmosphere`, `ClaimEffect`, `ClaimTitles`, `EntryFee`, `StyledText`)
  - [x] `Flags` and `Features` — the resolvers, now against the `LandPolicy` interface rather than a
        config class, so the merge rules are testable without a file or a server
  - [x] `LandPolicies` — the in-memory policy, storing only what somebody changed
  - [x] duplication removed on the way in: one logger, one item serialiser (`core.data.nbt.ItemText`
        over `ItemBytes`, which moved out of `moderation.invsee` where a codec did not belong), no
        second `Text` helper, no second settings framework
  - [x] verified against the real thing: `LegacyClaimFormatTest`'s 1.3.0 claim-file fixtures still
        load, which is what proves an existing server keeps its claims
  - [ ] `Land` — the facade every other plugin asks: the claim at a location, and whether somebody may
        do a thing there
  - [ ] the protection listeners. They belong here rather than in the module: claim data in Core with
        the enforcement in a module means a server that installed one and not the other has
        unprotected land
  - [ ] wiring into `RainsCorePlugin` and the `RainsCore` interface
  - [ ] three tests still to port — `FeaturePolicyTest`, `FlagAudienceTest`, `ClaimFeatureTest`. They
        are half about `PluginSettings`' legacy-YAML migration, which is **not** moving into Core, so
        each needs splitting: the resolution half against `LandPolicies`, the migration half staying
        with whatever ends up owning the claims config. The originals are still green in
        `RainsExtendedClaims`, so nothing is lost in the meantime — but Core's land coverage is
        thinner than it will be until this is done
- [ ] two gaps found in Core that belong in Core rather than in a module, both needed by claims:
      **no title API** (a player has one title slot — the same arbitration problem `ActionBars` solves)
      and **no block-outline API** (claims, farm worlds and warps all want to show a border)
- [ ] `claims-module`
- [ ] `modules-wrapper`, `<x>-standalone`
- [ ] `moderation-module`, `farmworld-module`, `teams-module`
- [ ] `core.social` in RainsCore (the shared half of teams)
- [ ] `RainsSMPCore` and `TheHungerGames` consuming the modules

## Working agreements

- **Test first.** The test class is written and run red before the implementation exists. Extensive
  means edge cases, null/blank, concurrency, failure paths, bounds, idempotency, persistence
  round-trips — not one happy path per method.
- **`agy` reviews each milestone** as a second opinion. Its findings are judged, not obeyed; what is
  accepted gets a regression test first.
- **No proof-stubs.** Nothing is reported as done that is not built, tested and checked against real
  behaviour.
