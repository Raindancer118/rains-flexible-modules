# Rain's Flexible Modules

Modules any plugin can include as real modules, without duplicating code. Each one is a Maven artifact —
never vendored — and each one also ships as a standalone plugin through one standard wrapper.

**Not committed.** This file is working reference, kept out of git deliberately.

---

## The delivery model

| # | Question | Decision |
|---|---|---|
| 1 | Vendored or depended on? | **Maven artifacts.** The module source is the single source of truth; nothing is copied into a consumer. |
| 2 | How does a module become a plugin? | **One standard wrapper** (`ModulePlugin` + `ModuleBootstrap`) plus a short `paper-plugin.yml`. No per-module plugin class. |
| 3 | How are modules found? | **`ServiceLoader`.** Adding a module to a build is adding a dependency, with no list to keep in step. Shade merges the service files. |
| 4 | Who registers commands? | The host, at **bootstrap** — Paper fires `COMMANDS` before `onEnable`, so a module only *declares* its commands. Every one is wrapped by `ModuleCommands.guarded`. |
| 5 | What happens when one module breaks? | **That module and whatever required it, nothing else.** `ModuleRegistry` unwinds the failed session on every path out. |
| 6 | Where does shared behaviour go? | **RainsCore, always.** If a second plugin could want it, or two plugins would each write their own, it is not module code. |

### The Paper trap that cost an evening

A module plugin must declare RainsCore under **both** `dependencies.bootstrap` and `dependencies.server`,
each with `join-classpath: true`. Paper runs the bootstrap phase with its own dependency tree and its own
classpath, and the bootstrapper is where modules are discovered (commands must be registered there or they
never exist). Declared for `server:` only, every module fails to link with
`ClassNotFoundException: …core.world.protection.LandProvider` and the plugin reports **"this jar contains no
modules"** about a jar that contains one.

Two more, learned the same way:

- **`mvn install` without `clean` ships stale classes.** A green build and a clean boot log both lie. Verify
  the built jar with `javap` before deploying — see *Deployment*.
- **A module's `messages.yml` must live beside its class**, not at the jar root. RainsCore ships one at its
  own root, and `join-classpath` puts Core's resources on the module's classpath — a root lookup is a race
  between two files with the same name.

---

## Warps — `warp-module` / `warp-standalone` (RainsWarps 1.0.0)

The newest module, and the one that pushed the most work *down* into Core rather than writing it here.

**What the module actually is:** who may use which warp, how a server groups them, and four screens. That
is all. The places are Core's (`Poi` of kind `warp`), the warm-up and the teleport are Core's, the cooldown
is Core's, the menu framework and the wording are Core's.

**Three kinds of access, not two** — `model.WarpAccess`: everybody, the staff
(`rainswarps.warp.staff`, one well-known node so an existing staff group grants every staff warp at once),
or one named permission. Three because "staff" and "the people who may reach the build world" are different
groups on every server that has both; with a boolean the second has to be expressed as the first, and then
every builder has to be made staff. Stored as **nothing but the one permission Core already keeps on a
place** — a second "is this staff" tag would be two things to keep in step, and the winner when they
disagree decides whether the staff room is open.

**A restricted warp is hidden, not greyed.** The module's one deliberate exception to the screen grammar,
and it is the point of the feature: greying it tells every player there is a warp called `staffroom`.
`WarpAccessRule.maySee` and `mayUse` are therefore the same answer, so nothing on the page can refuse after
the click. An admin sees and reaches everything — somebody has to be able to look at a broken warp, and an
admin who cannot reach the one they are fixing fixes it by deleting it.

**The screens.** `/warp` opens the player's list; `/warp admin` opens every warp there is, where clicking
one *edits* rather than travels (mixing them meant an admin had to warp to a place to change it, and one
misjudged click sent them across the world). Off that: one warp's page, who-it-is-for, and
`/warp config` — the settings page, where every click writes to disk at once.

**A warp may not be called `list`, `admin`, `config`…** `rules.WarpNameRule` refuses them, and takes the
list from the command rather than keeping a second copy. Core's own `/warp` still has this hole: it reads
the first word as a subcommand before reading it as a name, so a warp called `list` appears in the menu, can
be clicked, and can never be reached.

**The cooldown is charged on arrival, never up front.** Charging first means refunding when a warm-up is
interrupted, and a refund is a blunt clear of the wait that takes whatever else was on it. So: ask
`isReadyToWarp`, travel, `recordUse` when they actually get there. Being knocked out of a warm-up by a
zombie costs nothing.

**What travels with you** (`core.world.teleport.Companions` / `Entourage`, admin-switchable): what you lead
— the dog on the lead, the boat you are towing, whatever is riding with you — and optionally your own tame
animals standing nearby. Two rules no setting can turn off, both found by review rather than by running it:

- **Nothing that carries a player comes.** Paper moves a vehicle with everything riding in it, so towing a
  boat with somebody in it would teleport them without consent — and over a drop, on a PvP server, that is
  a weapon.
- **Somebody else's tame animal never comes, even on your own lead.** Anybody may leash anybody's wolf or
  horse, so a lead alone would make a warp the quickest way to take another player's animals off them. A
  *wild* thing on a lead still comes: villagers in a boat, a llama just caught — nobody owns those.

## Homes and teleport requests — ported from their own plugins

`homes-module` / `homes-standalone` (**RainsHomes 2.0.0**) and `tpa-module` / `tpa-standalone`
(**RainsTPA 2.0.0**). Both were standalone Maven projects with their own repos; both are now modules of
exactly the same shape as claims, moderation, names and warps. Two modules, not one — a server can run
homes without teleport requests.

**Every config path and permission node is the old one, to the character.** Not a nicety: a changed
`@Key` means an owner's configured value is read as absent and silently replaced by the shipped
default, and a renamed node means somebody's granted `homes.limit.10` quietly becomes three. Both are
pinned by a test that reads the schema and the node constants rather than a hand-written list, so
adding a setting cannot break it and cannot silently skip it either.

**The migrations are the risky part, and both fail safe.**

- Homes are places now, so an upgrading server's `homes.yml` is read once into the place store and the
  file **renamed aside, never deleted** — and only once `PoiStore.flush()` has confirmed the write.
  That is why `flush()` now returns a boolean: it knew whether it had written and did not say, and a
  migration that moved its source away on the strength of an import that never landed would have
  deleted the only copy. Two legacy keys that normalise onto one name (`Home` and `home`) are reported
  by name and coordinates rather than skipped quietly.
- `tpa.yml` is read exactly as the old plugin wrote it. A file that will not parse now **stops anything
  writing over it**: it is read as empty, and saving that back would have turned one hand-edited line
  into every block list on the server.

**What is left in each module** is what the feature actually means. Homes: how many somebody may have,
what a home may be called, what block it shows as, three screens. Teleport requests: what a request is,
who may ask whom, and four screens.

**One outgoing request, uncapped incoming.** Asking is something you do; being asked is something that
happens to you, and capping it would let one person block everybody else from reaching somebody by
asking first. A second request to a *different* person displaces the first and the bumped person is
told; a second to the *same* person is refused.

**Being blocked is indistinguishable from being switched off** — one verdict, not two that share
wording, because two would drift the first time somebody edited one. Telling an asker they have been
blocked turns a quiet decision into a confrontation.

## Training mannequins — `mannequin-module` / `mannequin-standalone` (RainsMannequins 1.0.0)

The newest module, and the first built on a Paper 26.2 entity type — `org.bukkit.entity.Mannequin` —
that did not exist when any other module here was written. Nothing in RainsCore wraps it yet; this module
owns the entity outright rather than asking Core for a piece that isn't there.

**Dressed without ever being obtainable.** The whole loadout — armor, weapons, arbitrary enchants beyond
vanilla limits — is written straight into `Mannequin.getEquipment()` and never once touches a `Player`
inventory, a drop, or anything else that could hand it to someone. `MannequinEquipService`'s own test
pins this with Mockito: it scans every call any mock ever receives and fails if `addItem`, `getInventory`,
or any `drop*`-shaped method is invoked at all. The original loadout spec (material + enchants, exactly as
chosen in the GUI) is kept separately from the live `ItemStack` for one reason: durability. A hit that
would break a piece instead rebuilds an identical fresh copy from that spec and re-equips it the same
never-obtainable way — the dummy visibly loses a helmet and gets a new one in the same tick, never a bare
head.

**Killable now — reversed mid-build, on request.** The first design made it flatly invincible
(`setInvulnerable` plus an unconditional `EntityDamageEvent` cancel). Told directly that it should die
like a normal target instead, that became: real health (`MannequinSettings.maxHealth`, default 20 — a
bare player's own max — configurable per server and, via `Mannequin.maxHealthOverride` /
`util.HealthPresets`, per dummy, so one can be set to a boss's pool without a code change for every mob
that gets added later), death clears its drops and dropped exp unconditionally
(`MannequinDeathListener`), and `MannequinService.scheduleRespawn` brings back an identical dummy — same
block, same loadout, same skin — after `MannequinSettings.respawnDelaySeconds` (default one). The stored
record is never deleted on death, only the live entity.

**A real redstone signal, not a light show.** An owner can wire a mannequin to a barrel so a comparator
reads how hard the last hit landed, 0–15, using vanilla's own container-fullness formula rather than an
invented scale — `MannequinRedstoneService` computes exactly how many filler items produce a given
signal level and the reverse, both pinned against known vanilla reference points. A first version of that
formula used the wrong divisor (15 instead of vanilla's 14 steps between the 16 signal levels) and was
caught by its own test before this was ever built against a server. It pulses rather than holding state,
because a barrel still full from one hard hit would read as still-hard after three weak ones that
followed it. `SignalStrengthRule` scales a hit's damage onto that 0–15 range linearly against a
configurable one-shot threshold (default 20, the same bare-player baseline); the worked example given for
this feature — a hit that would one-shot an unarmored player but not one in maxed netherite should read
about 12 — falls straight out of that plain linear scale without needing a curve.

**Bound to its block.** `setImmovable(true)` on every spawn, and the stored location is what gets
re-placed on every world load — not wherever physics would otherwise have left it. No piston, no water,
no explosion moves it.

**Blocks with a shield it's given, drinks a potion dropped on it.** The shield check
(`ShieldBlockRule`) is a pure function over has-shield, cooldown state and attacker range, wired through
Core's `Scheduling.globalTimer` rather than reacting only after a hit already landed — blocking has to be
up before the swing connects to matter. A dropped potion is never picked up in the inventory sense: the
pickup event is cancelled and its effects are read straight off the `ItemStack` and applied directly, so
a harmful potion behaves exactly like any other damage source now that the dummy can actually be hurt.

**What could not be verified without a live server:** the parts of `MannequinEquipService` and
`model.ItemSpec` that construct real `ItemStack`s / `Enchantment` constants, which lazily reach for
`io.papermc.paper.registry.RegistryAccess` — unavailable to any unit test in this reactor, for any
module. Verified by code review and a clean compile against the real API instead; the pure decision logic
each of those depends on (durability accumulation, the break threshold, the redstone math) is fully
unit-tested on its own.

**Shared like a claim, without borrowing claims-module's model.** `Mannequin.trusted` is a plain
`Set<UUID>` alongside `owner` — no roles, no permission grid, because a dummy has exactly one thing
worth delegating (open its own edit page) rather than a claim's fourteen. `Mannequin.mayManage(uuid)`
is the one check both the command layer (`MannequinCommand.withMannequin`/`withAnyMannequin`) and every
screen route through, and `MannequinRegistry.accessibleBy` is what `/mannequin` and its own list menu
show — owned and shared mannequins together, badged "Yours" / "Shared with you" the same way
`ClaimListMenu` badges trusted claims. `ShareMenu` is the trust screen itself, owner-only to change,
mirroring `claims-module`'s `MembersMenu` down to the `PlayerChooser` exclude-list pattern.

**A mannequin can belong to one claim — genuinely optional, resolved through Bukkit's own
`ServicesManager`, never a hard dependency.** `mannequin-module` declares `claims-module` as a
`provided` Maven dependency (compiles against `Claim`/`ClaimServices`, never shades a copy in), but
every reference to a claims-module type lives inside `de.raindancer.modules.mannequin.claims` and only
behind `ClaimIntegration.tryLink`/`tryRegisterMenu`, which catch `Throwable` broadly and hand back
`ClaimLink.NONE` (a no-op) when no claims plugin is actually registered. The rest of this module —
`MannequinServices.claimLink()`, the "part of a claim" toggle on `MannequinEditMenu`, the whole
`claims` package — never imports a claims-module class directly. This is deliberately *not* the
`chained-module`→`speedrun-module` shape (a real, required dependency between two modules that are
always installed together): a training dummy has never needed a claim, and still does not on a
server running mannequins with no claims plugin at all.

**The submenu runs the other way, through claims-module's own extension point.** `claims-module`
gained `extension.ClaimMenuExtension` / `ClaimMenuButton` / `ClaimMenuExtensions` — a small, static,
process-wide registry any module can add a button to (`ClaimMenu`'s RULES band asks it fresh on every
render, same as everything else on that page), and `claims-module` never learns who registered.
Symmetric to `ClaimLink`: one seam lets mannequin ask "what claim is this", the other lets claims ask
"does anybody else have something to show here" — together they let two modules integrate with
neither one depending on the other in both directions at once. `ClaimServices` itself is registered
with Bukkit's `ServicesManager` in `ClaimsModule.enable()` and unregistered through `context.closeWith`,
which is what makes the lookup work whether the two modules are hosted in the same plugin jar or two
entirely separate ones (`RainsMannequins.jar` beside `RainsExtendedClaims.jar`) — `ModuleContext`'s own
`module(String)` lookup only ever sees modules sharing a host, and this had to work for both.

## Farm worlds — `farmworld-module` / `farmworld-standalone` (RainsFarmWorlds 1.0.0)

The newest module, and the one where Core already had the whole engine. `core.world.farm` owns the three
linked worlds, the portal linking that keeps a farm portal inside the farm world, the schedule, the
recorded times, and `FarmWorldState.mayDelete` — the one pure function standing between a typed command
and a deleted server. Core also ships a plain `/farmworld`. **This module is what that command is
missing**, and it is exactly two things:

- **Arrivals are scattered.** Core's command puts everybody at the world's spawn, and then the first
  hundred blocks are bare within a day: every arrival after that is a five-minute walk before they can
  start, so the farm world is a corridor and the only fix left is regenerating it more often — which
  throws away everybody's work to solve a problem that was never about the far parts of the map.
  `model.Scatter` picks the point, and **the radius comes out of a square root** because a ring twice as
  far out holds twice the ground. Drawn straight from the generator, half of all arrivals land inside
  half the radius. Pinned by drawing twenty thousand points and counting which side of the
  equal-area split they fall on.
- **The server is warned first.** Core regenerates and tells whoever is standing there *as it happens*.
  `NoticeService` watches the same clock and speaks before it acts — and **never regenerates anything**:
  two timers both deciding a farm world is due is two regenerations racing, and the loser deletes a
  folder the winner has already recreated. `ReuseTest` fails the build if anything here calls `due(` or
  `regenerateWhatIsDue`.

**The owner picks the first warning; five minutes and one minute are not theirs to switch off.** Those
two are "start walking back" and "put it somewhere it survives", and an hour's notice given once is
notice nobody remembers hearing. `NoticeRule` answers with the **tightest** lead not yet given, not the
widest — found by a test: a server restarted with ninety seconds left would otherwise announce fifteen
minutes, record that, and then fire the other two notices on the next two ticks.

**The last five minutes are a boss bar and a ticking countdown**, for whoever is *in* the farm world.
Chat is for somebody deciding whether to set off; somebody already three hundred blocks down a tunnel
has scrolled it past. Core's `BossBars` (shared bar, audience recomputed every tick, so walking out
takes the bar with you) and Core's `Cues.COUNTDOWN` / `COUNTDOWN_DONE` on the last ten seconds — never a
named `Sound`, so a server that rebinds what a countdown sounds like rebinds this too. The timer runs
**once a second**, which is what a moving bar needs.

**Greyed, never hidden — the opposite of warps, deliberately.** A staff warp's *name* is the secret; a
farm world's is not. It is one of two or three places the whole server talks about, so somebody who
hears about the donor world daily and cannot see it on their list learns only that their list is wrong.
`FarmAccessRule.maySee` is therefore wider than `mayUse`, and the greyed button carries the module's own
refusal wording rather than a second sentence written in the screen.

**The per-farm-world permission is derived, never stored** — `rainsfarmworlds.world.<name>`, default
true. Core's `WorldSet` has no field for one, so storing it would mean a second file beside Core's
`farmworlds.yml`: two records of which farm worlds exist, and one of them deciding what gets deleted.

**`FarmWorldNameRule` does not re-implement Core's dangerous-name check** — it *asks*
`WorldSet.of(name)` and catches the refusal, because a second copy can be more permissive than the first
and the more permissive of two answers is the one that runs. What it adds is the words `/farm` reads as
instructions (same list the command switches on) and the ceiling of eight.

**Both entrances to regeneration confirm.** The menu through `ConfirmScreen`, the command through a
typed `confirm` that tab completion deliberately does not offer — the console has no inventory to show a
dialog in. A guard on one of two entrances is not a guard.

> The danger-slot test caught itself being useless: it matched the word "confirm" anywhere near
> `danger(`, and the button's own lore says *"This is the button the confirmation exists for."* Deleting
> the confirmation left the test green. It now strips string literals before scanning, verified by
> mutating the fix and watching it fail.

## The Hunger Games — `hungergames-module` / `hungergames-standalone` (TheHungerGames 2.0.0)

**ARCHIVED — do not work on this module.** Explicitly parked by the user (2026-08-15); left as-is,
excluded from any repo-wide sweep (Folia work, refactors, audits) unless the user asks for it by name.
The notes below are kept for reference only.

**In progress.** Ported from the standalone Gradle project at `~/Projekte/SE Projects/TheHungerGames`
(205 files, ~32k lines). Version 2.0.0 continues the old plugin's line rather than starting a new one:
this is the same feature for the same servers, so an upgrading server's `config.yml`, `teams.yml`,
`whitelist.yml` and `session.yml` are files this still has to read.

**Roughly 40% of the old plugin was not ported at all**, because RainsCore already had it: its own menu
framework, `Items` builder, `AnvilInput`, confirm dialog, number editor and material picker; its own
`Messages`; its own settings system (an 835-line hand-written catalogue plus a `config.yml` that
duplicated it); its own sound and effect services; its own name resolver; its own loot manager; its own
custom items; its own monster waves; its own chat input; its own write-to-a-temporary-then-move. What is
left is the game.

**`config.yml` had 272 leaf keys; 141 of them are settings this module carries.** The first pass said
"~90" and wrote 39 of the rest off as `teams.*` and `sponsors.*`, "not ported in this wave". **A copy of a
live server's own `config.yml` settled that**: it had tuned nine of those keys. `teams.max-size: 10` would
silently have become 2 — duos, in a tournament that plays teams of ten — and the sponsor shop's real
twelve-entry list, nine of them custom items, would have been replaced by two placeholder potions, leaving
players buying things that did not exist. The four `startup.*` keys that time the launch sequence came back
for the same reason, and the 52 `items.*` keys after that. **The lesson is checked in**: that file is now a
test fixture and `TheLiveConfigSurvivesTest` fails if it stops round-tripping. Every synthetic migration
test had been green the whole time — a fixture written by the author of the importer tests what they thought
about, not what somebody actually did.

The 131 that genuinely went elsewhere are wording (`announcements.text.*`, twenty lines — now
`messages.yml`), thirty sound bindings and twenty-two particle specs (now Core cues — **imported, not
abandoned**, see below), and stored lists that are data rather than values — the border phases, the
supply-drop and beacon schedules, the gamemaster roster, the sponsor shop (each its own file under
`store/`). `HungerGamesSettingsMigrationTest` reads the real old key list and fails unless every one of
the 272 is either present **at its old path** or named by hand in a map saying where it went. A changed
`@Key` is read as absent and silently replaced by the shipped default: `game.duration` defaults to three
hours, so a server that had tuned rounds to twenty minutes would wake up to three-hour tournaments with
nothing in the log.

> Two traps found in the source's own config handling. The shipped `resources/config.yml` is **not** the
> schema — it is a dead v0.16 migration alias table, and `YamlConfigBackend` proves the live paths are the
> dotted ones in `HgSettings`. And the first key count was wrong (274) because a grep for dotted string
> literals caught a permission node and a resource-pack sound key. The fixture is now generated by parsing
> the `register(...)` calls rather than typed.

**Wording is English, and the Panem theme is kept to the word.** The old plugin announced in German;
every other module here is English, and a server where one plugin speaks German and six do not is the
inconsistency players notice. The theme is not the language, so the cannon, the Capitol, the gamemakers,
the protection period, the tributes and the cornucopia all stay — and where the German quoted the books,
the English quotes them back.

**WorldEdit is a real dependency, `provided` and `required: true`.** The arena — cornucopia, platforms,
starting tubes — is pasted from `.schem` files, and the old plugin already depended on WorldEdit to read
them. Writing a Sponge schematic parser into Core was considered and rejected: two live format versions,
a palette, block states and block entities, and a parser that is subtly wrong builds an arena that is
subtly wrong in front of forty people. This is the "reuse the established library" rule, not the "put it
in Core" one.

**The HTTP admin API stays wholly in the module.** Transport and routes alike. Core gets no network code
and opens no socket, which keeps the shared foundation's security surface where it was. The cost is
accepted: if a second plugin ever wants an HTTP surface, *that* is when the transport is lifted.

**Sound and particle cues are Core's, and Core had to grow to hold them.** The module was calling
`effects.play(uuid, "hungergames:countdown")` and three other names, and **none of them was defined
anywhere** — Core answers an unknown cue by logging one warning and playing nothing, so a whole tournament
ran in silence. Nothing could have caught it: it compiles, it does not throw, and silence on a Minecraft
server is indistinguishable from a resource pack that failed to load. `NothingIsSilentTest` now reads the
module's own source and fails the build for a cue that is played but not defined.

The tuned bindings could not be held either. Core's `Effect` carried one `SoundCue`, and the live file has
a fifteen-sound cannon, a nine-sound elimination and six layers of boots, written as
`ENTITY_GENERIC_EXPLODE@0.4~1.6>200^3` — volume, pitch, delay, repeat. So **`SoundSequence` and
`ParticleSequence` went into Core**, reading exactly that notation, and `Effect` now holds layers of both
while `sound()` and `particles()` still answer with the first of each so nothing written against the old
shape changed meaning. `Effects.delayedPlaybackVia(...)` is how a delayed layer reaches the scheduler; left
unset it plays everything at once, which is the old behaviour rather than a new failure, and is what lets a
test assert a cannon has fifteen sounds in it without a server.

**The session reads its own store in its constructor.** It wrote itself to disk on every mutation and
`restore(...)` sat unused — nothing ever called `SessionStore.load()`. A server restarted mid-round came
back with no phase, no tributes, no teams and no kills, with forty people still connected and standing in
an arena the plugin no longer believed existed; the wiring even asked `phase() == RUNNING` to decide whether
to resume the clock, a condition that could not be true because the load that would have made it true never
happened. Reading in the constructor is deliberate over "the wiring calls `load()` at the right moment": the
bug *was* nobody calling it, and a session that cannot be constructed in a state that has forgotten is a
stronger guarantee than a call somebody has to remember.

**Invariants that had to survive the port**, all of them now covered by tests: UUID is the only player
identity and a name is a display cache; a disconnected tribute stays ALIVE until something eliminates
them, so the winner logic is offline-safe; team colours are exclusive and a clash is refused rather than
reassigned; the border's maximum edge speed is a fairness ceiling and a conflict produces computed options
applied only on confirmation; the session survives a restart mid-round; `/allow` grants no OP; sponsor
tokens and beacons are recognised by PDC key, never by material.

## The Core / module boundary

The rule the user set: **if something could use or need something in RainsCore, it goes in RainsCore
centrally — never in the module.**

**Teams went to Core, and are the one deliberate exception to "extract only after it is written twice."**
`core.social.team` — `Team`, `TeamId`, `TeamColour`, `Teams` (the roster), `TeamOutcome`, and `TeamPolicy`
carrying the rules that vary, with `tournament()`, `match()`, `clans()` and `party()` presets. A
tournament's teams, a bedwars match's teams, a clan and a party are the same thing wearing four names.
Moved on one consumer plus a stated intention to write clans and bedwars, which was the right call because
what moved is a *model and a policy* rather than a workflow: the sixteen colours mapped onto dye, text and
armour are the same sixteen for every consumer there will ever be, and the compromises in that table
(there is no brown in Adventure's palette, and Minecraft has two pinks in dye but one in text) are exactly
what a second author would get differently. The flag that makes one roster serve all four is
`exclusiveColours` — a tournament must have colours exclusive because telling teams apart is the whole
point, and a server with two hundred clans cannot, because the seventeenth clan could never be founded.
What stays in a module: the phase at which *its* teams freeze, and who counts as eligible. Hunger Games
keeps `TeamRules` for that, with a `toPolicy()`. Whether teams are editable right now is **not** policy —
it is a fact about the moment, changes several times a round, and is a `BooleanSupplier` the host answers.
This took RainsCore to **1.5.0**.

**In RainsCore** — `de.raindancer.core.world.protection` (under `world`, deliberately not a new top-level
package):

- `LandAction` — the permission vocabulary. No wording; `nameKey()` / `descriptionKey()` only.
- `LandFlag`, `LandFlagGroup`, `FlagPolicy`, `LandAudience` — the flags themselves. **No claim-specific
  wording in the enum**; the claims module supplies the words when it shows them.
- `Land`, `LandFlags`, `FlagRules`, `LandPolicies`, `LandPolicyStore` — the resolver and the admin's
  decisions on disk.
- `ProtectedArea` / `LandProvider` — the seam. Core owns *the question*, not the data.
- the protection listeners — because claim data in Core with enforcement in a module means a server with one
  and not the other has unprotected claims.

**In `claims-module`** — what a claim *is* and what it offers: the model, the store, the screens, the
commands, selection, fences, atmosphere, effects, entry fees, eviction, equipment, pantry, bank, titles,
broadcasts. Towns are **out of scope entirely**.

Counter-example worth keeping: the pantry, the bank and the fence are what a claim *is*. They are not Core's.

## Chat — `chat-module` / `chat-standalone` (RainsChat 1.0.0)

Everything that happens to a line typed into public chat: the format template, @-mentions, a caps and
repeat filter, a per-player cooldown, `/chathistory` for catching up after being away, the staff tools —
`/chat clear`, `/chat freeze`, `/chat slowmode` — and `/announce`, a banner every online player sees and
hears. Added 2026-08-17, and @-mentions moved here from `essentials-module` the same day — it had only
just landed there before the user asked for a chat module to own "all the chat messages and formatting",
and a mention is exactly that.

**`/chathistory` is the one thing here with its own file on disk** — `ChatHistoryStore`, a capped rolling
window of recent lines (default 200, server-wide) plus one "last seen leaving" timestamp per player,
written through Core's `YamlStore` the same write-to-a-temporary-then-move way every other store in this
reactor does. Flushed on a quit rather than on every message — a quit is already far rarer than a chat
line, and piggybacking the write on it avoids turning a busy chat into a write storm. A hint on join
("you missed 12 messages") is as far as this goes unprompted; the actual catch-up is one command away,
deliberately, rather than a wall of scrollback dumped into a fresh join alongside the MOTD and everything
else already competing for it. Personal-data note: this **is** a small, capped chat log — names beside
what people said, kept until the cap rolls it off — bounded on purpose rather than kept forever, the same
shape as scrolling back in any chat client.

**`/announce` plays `Cues.NOTIFY`, Core's own "a message that should not be scrolled past", rather than
choosing a sound.** The same "module holds the logic, Core holds the handling" rule below applies here too
— a plugin picking its own notification sound is a second, competing idea of what an announcement sounds
like the moment another plugin also has one, and `effects.playForAll(...)` already exists for exactly this.

**The rule the user set for this one specifically: the module holds the logic, Core holds the handling.**
Concretely — nothing here reinvents a player's prefix, suffix or name colour (`Identities`, already Core's,
set once by essentials-module's `NicknameService`), how a message reaches an `Audience` (`Chat`), who is
allowed to see whom (`Vanish`), or how wording is stored and filled (`Messages`). What is actually new code
in this module is decision logic with no Bukkit in it — `ChatQualityRules` (caps/repeat/cooldown/slowmode,
pure functions over a string and a `long`), `ChatQualityService` (the same, with per-player state and an
injectable clock for tests), `MentionService` (who a line names, respecting vanish) and `FormatService`
(the rendered `Component`, built from `Identities.chatName` plus this module's own template). `ChatListener`
is deliberately thin: it reads the event, asks the services in order, and turns a refusal or a render into
what `AsyncChatEvent` wants back — nothing in it decides anything a test can't already prove without a
server.

**Renderer, not `event.message()`.** The raw message is left untouched; `event.renderer(...)` replaces only
how it is *shown*. A cancel-and-resend (`ChatChannelListener`'s pattern for a private channel) would have
meant reimplementing whatever else on a server formats chat instead of composing with it — and there is
nothing else here to compose with today, which is the point: a renderer degrades to "no other plugin
touched this" instead of silently winning a fight nobody knew was happening.

**Mention matching and rendering both run synchronously on the async chat thread**, on purpose. Paper's
renderer has no later tick to defer to — it has to be set before the handler returns — so `Server#getPlayerExact`
is called right there rather than bounced through `Scheduling.global` the way `ChatChannelListener` bounces
its own (heavier) audience lookup. Justified by `Chat`'s own javadoc: building and sending a component needs
no region thread, and a single online-player lookup is not the world-touching work Folia-safety is about.

**Format is a MiniMessage template an owner edits (`<name>` / `<message>`), not a fixed layout** — the same
"words live in `messages.yml`, code never hardcodes wording" rule the rest of this reactor already follows,
applied to the one string that decides how every line on the server looks.

**A message is never re-parsed as markup**, in three places: `Links.linkify` builds a URL's styled component
with `Component.text`, never `MiniMessage.deserialize`; `FormatService` highlights a mention token the same
way; and the format template itself receives the name and the message through `Chat.formatted` (component
insertion) rather than string concatenation into the template. All three are the same trap `WordingContract`
polices for `messages.yml` itself, aimed instead at a player's own typed text.

**`/chat freeze` and `/chat slowmode <seconds>` are runtime-only, not settings.** A freeze or a raised
slowmode is a reaction to a specific moment; surviving a restart would mean a server that comes back with
chat still frozen for a reason nobody online remembers, fixed by a command nobody thought to run. The
*default* slowmode is a setting — `/chat slowmode` overrides it until `/chat slowmode off` or a restart puts
it back.

## Client maps — `xaeromap-module` / `xaeromap-standalone` (RainsXaeroMap 1.0.0)

The newest module, and the only one whose entire output goes to a client this repository does not
write. Two features, either switchable off, and a server can want one without the other.

**A map per world.** Xaero's Minimap and Xaero's World Map file their cached map data under an id the
server tells them; told nothing, they fall back on the server address, so every world on a Bukkit
server is the *same* world as far as the map is concerned — the nether's tunnels drawn over the
overworld's coastline, a farm world overwriting the survival map, and a portal showing a map of
somewhere else. Nothing on the player's side can fix it. `XaeroWorldId` is five bytes on
`xaerominimap:main` and `xaeroworldmap:main`, keyed on the **world's uuid, not its name** — a farm
world that is deleted and generated again comes back with a new uuid and therefore a fresh, empty map,
which is the honest answer since the old map is of terrain that no longer exists.

**Claims on that map, over somebody else's protocol.** Neither Xaero mod has any way for a server to
hand it a coloured region. What both of them *do* have is an implementation of Open Parties and Claims'
client API — that is how a modded server draws chunk claims on a minimap, and it is the only route onto
that map that does not need a client mod written and shipped for this plugin. So `model.OpacPackets`
speaks OPAC at network version 6: a plugin message on `openpartiesandclaims:main`, one byte naming the
packet, then a **nameless** NBT tag. Every claim on this server arrives with its own name and its own
colour.

**What a player needs for that half is Open Parties and Claims installed beside their minimap** — it is
the mod that reads this protocol, and the one Xaero's maps draw claims out of. Worth being straight
about rather than implying a bare minimap is enough: a player without it is never sent a claim at all,
which costs them nothing and is the same silence as having no map mod. The per-world map needs neither
and works for everybody.

> **The packet deliberately never sent is OPAC's own packet 0**, a version handshake whose *client*
> handler disconnects the player outright on a mismatch. A server guessing that number wrong kicks
> everybody running the mod, over a mod they may not know they have. `regionsStart` is the probe
> instead: the mod's handler for it simply echoes it back, which is how the server learns it is talking
> to a real claims-capable client — and it costs a player nothing when it is not. Nothing is sent to
> anybody who has not answered it. `OpacPacketsTest` fails the build if any packet this module can
> produce has index 0.

**Three things about this protocol are unforgiving, and each one fails silently.** The tag must be
nameless (`util.NbtOut` — Bukkit exposes no NBT writer, and every library that does either drags NMS in
or still writes the pre-1.20.2 named form, which decodes as a compound with none of the fields in it);
a uuid must be four ints or `getUUID` throws and the packet is discarded; and a region's 1024 palette
indices must be packed exactly the way `SimpleBitStorage` unpacks them — values never straddling a
`long`, an array of exactly the right length, and a bit width from OPAC's own set of 1, the even
numbers, and 11. A client rejecting any of that logs nothing on either side and simply draws nothing.
There is no live test that would notice, which is why `NbtOutTest` reads the bytes back with a
**second, independently written** decoder rather than the writer's own.

**Homes and warps arrive as an offer, not a push.** Neither Xaero mod has any way for a server to put
a waypoint on a client's map — the only thing that exists is the mod's own chat-share feature, where a
message consisting of *nothing but* `xaero-waypoint:…` is caught by the client and turned into a
button. So `/xaeromap homes` and `/xaeromap warps` hand a player their places one clickable line each
(`model.XaeroShare`), and two things follow that are visible in the design rather than hidden:

- **Only to clients that have the mod.** Without it, the raw line is shown to the player exactly as
  written. `store.MapClients` remembers who registered one of the Xaero channels — the same signal the
  per-world map already waits for — and nothing goes to anybody else. That is also a *different*
  question from the claims one: a player with the minimap but no OPAC gets waypoints and no claims,
  which is exactly right.
- **One click per place, so it is a command rather than a join handler.** Ten homes is ten buttons, and
  a wall of eleven lines on every login is worse than a command typed once a month.

**Neither `homes-module` nor `warp-module` is a dependency of any of that**, because a home and a warp
are both a `Poi` in RainsCore's own store (kinds `home` and `warp`). One `PlaceLookup` over
`core.places()` serves both — and any other plugin that files its places there. Who may be offered what
is `rules.WaypointVisibilityRule`, and it asks *the permission stored on the place*, which is where
`warp-module` keeps all three of its access kinds: a second copy of that rule could be more generous
than the first, and a waypoint is coordinates. Handing the staff warp's coordinates to somebody who may
not use it is not undone by refusing the teleport afterwards.

> The share line is built defensively because its one failure mode is ugly rather than silent: the
> client counts the ten colon-separated fields *before* anything else, so a place named `base:2` would
> produce an eleven-field line, be ignored, and appear in the player's chat as raw text. Colons and
> newlines come out of a name, and the name is cut to the 32 characters the client draws.

**A map draws chunks; a claim is a polygon.** `rules.ChunkCoverageRule` decides which claim owns a
shared chunk — most of it wins, ties go to the older claim, and the last resort is the claim id, purely
so two syncs of an unchanged server cannot disagree with each other and make the map flicker. A claim
clipping one column of a chunk still paints all 256 of them, so the threshold is the server's
(`claims.chunk-coverage-percent`, default 1). `claims.ClaimsModuleSource` measures the coverage:
exactly for a rectangle, and on a 4 × 4 grid per chunk otherwise — with the corners checked as a
fallback, because a two-block corridor is a real shape somebody draws and it slips between the sampled
columns entirely.

**Colour is per viewer, not per claim.** Yours and the ones you are trusted on are the server's two
configured colours; everybody else's takes a hue derived from the owner's uuid, because on a map where
every stranger's claim is the same shade four neighbours read as one enormous claim — which loses the
one thing the map is for. The uuid is mixed before the modulo: two players who joined seconds apart
differ in a handful of bits, and taken straight they land on neighbouring hues. Saturation and value are
fixed, so nothing is ever the black that vanishes against a cave or the white that vanishes against
snow. The HSB conversion is six lines rather than `java.awt`, which is `java.desktop` dragged into a
headless server; `ReuseTest` fails the build if anything here imports it, and the rule's own test checks
the arithmetic against the standard library's from the test side, where it is allowed.

**Only the difference is sent.** `store.ClaimMirror` holds, per player, which claims and which chunks
that client has been told about; a refresh sends what changed and nothing else, so a quiet server costs
nothing per tick and a claim being made costs a handful of packets. Two deliberate splits in that:
asking for the difference records nothing, and the *sender* records what actually went out chunk by
chunk — so a send cut short by the budget, or by somebody disconnecting mid-sync, leaves that client
behind by exactly the part that did not arrive rather than by nothing at all. A change too large to
send that way goes out as whole regions instead, built from the **whole** picture of every region it
touches rather than from the difference, because a region packet replaces all 1024 of its chunks at
once and one built from the difference would blank every unchanged claim sharing it. The budget is
therefore spent a region at a time: a region cannot be sent in halves.

**`store.SyncIndexTable` never reuses a handle**, even after a claim is deleted. Hand out an index some
other claim used to have and every chunk a client still holds from the old palette is relabelled with
the new claim's name — a working-looking map, in somebody else's living room. A *transferred* claim gets
a fresh identity for the same reason: the mod keys a claim's name and colour on (owner, sub-index), so a
claim that keeps its pair is drawn under its previous owner's name.

**The map is read-only, and that is a decision rather than an omission.** The mod's own claim key sends
a serverbound request; answering it would be a second way to claim land on this server, one that knows
nothing about who may claim, what it costs or how large a claim may be. Claim limits are never sent
either, which leaves the mod's claiming UI with nothing to offer. `ReuseTest` fails the build if
anything here touches claims-module's mutating surface.

**Claims are genuinely optional**, the same shape `mannequin-module` uses and for the same reason: every
claims-module type lives behind `claims.ClaimIntegration`, reached through Bukkit's `ServicesManager`
(not `ModuleContext#module`, which only sees modules sharing a host), resolved **lazily** so a claims
plugin that enables after this one is still found, and `PackageGrammarTest` fails if a claims-module
class is named anywhere outside that package. Without a claims plugin the module still gives every world
its own map.

**Two listeners, and neither one is the join event.** A client's mods register their plugin-message
channels *after* the join event fires, and a packet sent to a channel the client has not registered is
dropped with no error anywhere — the classic version of this bug is a plugin that sends on join, looks
correct, and does nothing. `PlayerRegisterChannelEvent` is the client saying it is listening.
`PlayerRespawnEvent` is there beside `PlayerChangedWorldEvent` because dying in the nether and
respawning at a bed in the overworld is not always reported as a world change, and a client that missed
one writes the wrong world's map into its own cache permanently.

**`/xaeromap` exists because everything else here is invisible from the server.** The packets either
arrive and draw something no admin can see, or are dropped by a client with no mod installed — and
nothing distinguishes those two. `status` reports how many connected players are running a map mod that
answered the probe, which is the one fact separating "broken" from "nobody has the mod". Bare
`/xaeromap` is a player resyncing their own map and defaults to everyone, as do `homes` and `warps`;
`status` and `resync` are staff.

---

---

## Walls and roads — `wallsroads-module` / `wallsroads-standalone` (RainsWallsAndRoads 1.0.0)

The module that had been "work in progress" for months, and the reason was not that it was half
written: **it did not compile, and had been commented out of the reactor since 18.08.2026.** It was
written against a RainsCore API that never landed — `world.geometry.ColumnPolygon`/`Polyline`,
`world.build.BatchBuilder`/`BuildSnapshot`/`BukkitGround`, the four `world.selection` marking classes
and `world.visual.OutlineRenderer`, none of which existed in any released Core. So 1.0.0 began one
level down, in **RainsCore 1.31.0**, where that API now lives — which is where it belonged anyway
under this project's own Core/module rule.

### What is Core's, and why each piece is

- **`world.geometry`** — `ColumnPolygon` (a closed ring of *columns*: x/z with no height, because a
  shape is decided on the ground and given a height by whoever builds it) and `Polyline` (the open
  counterpart). Separate types rather than one with a flag, because a ring has an inside and a path
  has two ends, and every method serving both would begin by asking which it was.
  - **Corner rounding clamps per corner to half the shorter edge.** Unclamped, two arcs meeting on one
    edge overshoot and the shape folds inside out — a wall that crosses itself and encloses the wrong
    side.
  - **`Polyline.smoothed` is Chaikin with both ends pinned.** A road that decided for itself where it
    started would miss the gate it was drawn to.
- **`world.build`** — `BatchBuilder` (a queue placed a batch per tick; a town wall is tens of thousands
  of blocks and one pass is a freeze for everybody online) and `BuildSnapshot`.
  - **A refused or already-correct placement is never recorded.** Undoing one writes a block nobody
    put there.
  - **Restoring runs in reverse**, so a position covered twice ends on what was first there.
  - `BukkitGround` places **without physics**: a wall going up with physics on collapses its own
    gravel, pops its own torches and floods itself where it cut through water.
- **`world.selection`** — the tool is recognised by **persistent data, never by its name**; on a server
  whose marking stick is a plain stick, name-matching makes every stick in the world a marking tool.
  The **off-hand event for the same physical click is ignored** (or every corner is added twice), and a
  click in a world other than the one the marking began in is not part of it.
- **`world.visual.OutlineRenderer`** — drawn to the one player marking, never to the world: a preview
  spawned into the world is a light show everybody in render distance has to watch.

### What the module actually is

**A road decides what to do per *route*, not per column** — `RouteProfiler`. "Is this a bridge?"
cannot be answered by looking at one column: a road over a six-block stream is a bridge and the same
road over an ocean is a tunnel, and the only difference is how long the crossing is. Four passes, in
this order and no other: what the ground says → smoothed → **dips filled** (a gap is spanned, not
descended into) → **grade capped**. Filling before capping matters — capped first, the road begins
diving into a ravine and the fill then spans the dive rather than the ravine. Each column's kind falls
out of its *final* height: `GROUND`, `BRIDGE`, `TUNNEL`, `GLASS_TUNNEL`.

**A gap wider than `max-bridge-span` is deliberately not spanned.** A road does not fly across a whole
valley; it goes down into it, and a bridge whose far end is out of sight is not a bridge.

**`TerrainReader` replaced `topSolidY`, which is why roads used to be built over treetops.** The old
scan took the first non-air block from Y=320 down, and a leaf is not air. It also put roads on tall
grass, on snow layers that melt, and on the surface of the sea. Now foliage, ground cover and snow are
seen through; water and lava are reported as what they are. Caves below the surface never become the
ground, because the scan is top-down and stops at the first real one.

**Glass tunnels under long, deep water** (`sea-tunnel-min-length` 24, `sea-tunnel-min-depth` 6, both
settings). The crossing rests on the sea bed with a glass shell and air inside; short or shallow water
is bridged instead.

**The shell is derived from the space, not drawn as walls-and-a-ceiling.** Per cross-section it was
watertight on a straight and not on a bend: two cross-sections meeting at an angle leave a face on the
outside of the join that no wall-and-ceiling rule ever sees. Under the sea that is not cosmetic —
water flows on the next tick, so one missing block floods the tunnel overnight, and no static test of
a placement queue can catch it because a queue does not simulate fluid. So the interior of the whole
route is worked out first and every face of it that is neither more interior nor road surface gets a
block, pinned by a diagonal ocean crossing asserting exactly that.

**Clearing is always queued before structure.** A tunnel lining placed before its bore still has the
hill inside it, and glass placed before the water is cleared is a box full of sea.

**`Occupancy` — the bug that mattered most.** Without it a second road paving across the first
recorded *the first road's surface* as "what was here before"; tearing the second up then restored a
hole through a road nobody touched. A structure may now only place into blocks that are free or
already its own, so a crossing belongs to whichever got there first. Rebuilt at load from what each
structure's snapshot says it covered, so it never needs a file of its own to fall out of step with.

**Standing structures are protected, and that is not a courtesy.** A build is undone from a snapshot
of what was there before; let anybody mine a wall and tearing it down afterwards fills their hole with
blocks that were never there. Explosions are *filtered*, not cancelled — cancelling stops the half of
the blast that was over open ground too.

**Profiles, not eight material choosers.** `RoadProfile` is plain/lit/grand, `WallProfile` is
plain/town/fortress, each one button that cycles. A wall profile adds footings that chase the ground,
a walkway on the **inside** face (outside is a step for whoever is besieging you), battlements whose
merlon pattern is derived from the column's own coordinates so two runs meeting at a corner line up
rather than restarting the pattern, and hollow towers at the marked corners.

**Shut and sealed are separate flags**, because they are undone by different people for different
reasons: a guard opens a shut gate, an owner unseals a bricked-up one. Right-clicking a gate works it,
subject to the wall's own "anybody may work the gates" switch (**true by default** — somebody who
walled a village has not thereby said the village is closed). The night curfew is watched on the
**change of day**, never re-applied on a timer, or a gate somebody deliberately opened at midnight is
slammed shut a tick later.

**A wall may be charged for** (`charge-materials`, off by default). A four-thousand-block wall
conjured from nothing is creative mode in survival clothes. The build is **truncated to what the
builder can pay for** rather than refused whole; clearing stays free and always affordable, or a
tunnel stops half-bored with the hill still in the road.

**Roads are quicker to walk than the ground beside them** (`road-speed-bonus`, on). A road network
that is only decoration gets built once; one that is genuinely the quicker way is a thing a server
keeps extending. Checked on block change only — a move event fires several times a second per player.

**Two seams, both to modules this one does not require.** `claims`: a sign can be pointed at a claim
and works out the distance, which is the first use this module has ever made of a `ClaimLink` it has
had all along. `map`: gates and road ends offered as waypoints — **points only, deliberately**, since
the client protocol carries waypoints and a road is a line and a wall a polygon; drawing either would
mean pretending it is a claim. `xaeromap-module` now registers its services with Bukkit's
`ServicesManager` so there is something to ask.

**Signs stand beside the road, one above its surface** — the old code placed them at the paving's own
height, which is inside the road — face along it in sixteenths (four compass points look askew next to
a curve), and **remember what they replaced**, so taking one down no longer punches a hole in whatever
it stood on.

**Deployed 25.08.2026 to the 26.2-Testserver** as `RainsWallsAndRoads-1.0.0.jar` alongside
`RainsCore-1.31.0.jar` (which replaced 1.19.1). Standalone, and deliberately **not** in the YeukSMP
bundle — that was asked for directly, and the bundle stayed at 1.17.2. Boot: `Done (18.541s)`, **zero
warnings and zero exceptions**, the module up with its four permissions registered and its config
written with every routing setting in it.

One warning appeared on the first of the two boots and was fixed between them: `open-creation`
collided with `mannequin-module`'s setting of the same name, so this module's is now `open-marking` —
renamed rather than left colliding, since nothing has ever shipped with the old key.

**Still worth knowing: the module is *running*, not *exercised*.** Nobody has yet marked a wall out on
that server and watched it go up, so every claim above about how a road looks is a claim about a build
nobody has watched happen.

**The deploy procedure changed on request, 25.08.2026:** old jars are **deleted, not moved aside**, and
the `deploy-backup-*` / `mannequin-update-*` folders that had accumulated in the plugins directory were
removed. `wipe-backup-20260813-151047` was **kept** despite the same instruction, because it is not a
jar backup: it holds `core.db`, `audit.db` and the moderation notes/reports/staff files from the 13.08
wipe. That is real data and a separate decision.

## Manhunt — `manhunt-module` / `manhunt-standalone` (RainsManhunt 1.0.0)

The newest module, and the second on the `chained-module`→`speedrun-module` shape: a real, required
Maven dependency (`provided`, never shaded) on speedrun-module's engine, `RainsSpeedrun` declared as a
second required host in `manhunt-standalone`'s `paper-plugin.yml`, and its own `SpeedrunSession` built
fresh per run rather than reaching into speedrun-module's own singleton lobby — a server can run
`/speedrun` and `/manhunt` as two entirely separate things.

**Two Core teams, not a module-private pair of sets.** `ManhuntTeams` wraps `core.social.team.Teams`
with `TeamPolicy.match(0, 2)` — exactly two teams, unlimited size each, exclusive colours (lime
Runners, red Hunters), nobody player-created. `Teams.create` refuses while its own `frozen` supplier
answers true, which meant a caller wiring `frozen` to "a hunt is running" bricked the constructor
itself — a hunt cannot be running before its two teams exist to run it. Fixed with a one-shot
bootstrap flag that unfreezes construction regardless of what the caller's supplier says, found by a
test that froze from the start on purpose (`ManhuntTeamsTest.rolesCannotChangeWhileFrozen`) and got
`NO_SUCH_TEAM` instead of `FROZEN`.

**A win condition per side, independently configurable — the two are not branches of one choice.**
`ManhuntSettings.RunnerWinCondition` (`PORTAL_EXIT` — leaving The End at all, watched the same
dual-event way speedrun-module's own `DragonExitEndCondition` does, minus the dragon-kill gate; or
`ADVANCEMENT`, any configured advancement key) and `HunterWinCondition` (`ALL_RUNNERS_DEAD`, a
Runner-filtered `DeathEndCondition.ALL`; or `TIMEOUT`, a cancellable one-shot global-region timer).
`ManhuntService.start` arms whichever pair the current settings name; `SpeedrunSession.finish` only
ever keeps the first to actually fire, so arming both sides at once is safe by construction.

**The head start is a movement freeze, not a location.** `HunterHoldListener` cancels the Hunters'
own `PlayerMoveEvent` for `hunter-release-delay-seconds` after the Runners are already loose — the
same `HIGHEST`-priority, same-block-is-not-a-move trick `SpeedrunCountdown.onMove` already uses for
the whole roster during its countdown, applied here to one side only, afterwards.

**`/whitelist` — the real Bukkit server whitelist, not a match roster**, taking over the bare vanilla
name on purpose: `open` and `close` are the two new words, gated by this module's own
`rainsmanhunt.manhunt.whitelist` (a Runner may hold this without holding vanilla's admin-only
`bukkit.command.whitelist`), and every other word — `add`, `remove`, `list`, `on`, `off`, `reload`, or
nothing at all — passes straight through to `minecraft:whitelist` unchanged, so no existing admin
workflow on a server installing this module breaks. `close` snapshots everybody currently online onto
the whitelist before turning the flag on and never removes an existing entry; `open` only ever flips
the flag.

**Chaos actions are one command and one menu over the same service, not two implementations of "throw
something at the hunt".** `ChaosService` gates every `ChaosAction` behind a single cooldown
(`chaos-cooldown-seconds`) and every action is cosmetic or reversible by design —
`LIGHTNING_ON_A_RUNNER` is `strikeLightningEffect` (no damage, no fire), every potion effect is short
and named, `SWAP_POSITIONS` only ever relocates the living. `/manhunt chaos <action>` runs from the
console; `/manhunt chaos` with no argument opens `ManhuntChaosMenu` for a player. `PotionEffectType`'s
static fields reach for Paper's registry the same way `mannequin-module`'s `ItemSpec` does — untestable
in a plain unit test in this reactor, so `ChaosServiceTest` exercises the cooldown gate and the
targeting logic through `LIGHTNING_ON_A_RUNNER`, which touches no registry, and the potion-effect
actions are verified by code review and the live boot instead.

**Still worth knowing: the module has never actually run on a live server.** Everything above is
proven by 60 unit/integration tests plus the shaded jar's own `StandaloneJarTest` (no second RainsCore
or speedrun-module class inside `RainsManhunt.jar`, service file intact, descriptor correct) and a
clean `mvn -o clean install` across the whole reactor — nobody has yet started a hunt and watched a
Runner walk through the exit portal.

**Nine achievements over RainsCore's `Achievements`, seven of them curated into the GUI band, two
command-only.** `ManhuntAchievements` is `ChaosService`'s own shape applied to a different Core
service: one class that owns every rule about *when* something is earned, so a menu click and a
console command award through the identical call rather than each re-deriving "did they win, and
which side". `first-hunt` fires on any run that actually starts; `runner-portal` /
`runner-advancement` / `hunter-elimination` / `hunter-timeout` are read straight off
`SpeedrunOutcome.reason()` — `"portal-exit"`, `"advancement:…"`, `"all-runners-dead"`, `"timeout"` —
with `"manual"` and `"plugin-disable"` deliberately mapping to nobody, because stopping a hunt by hand
is not winning it; `chaos-agent` and `gatekeeper` are the two visible enough to want an icon but small
enough not to need secrecy. `open-doors` and `chaos-veteran` are `hidden(true)` — reachable only by
earning them or by `/manhunt achievements`' full console listing — because a server showing "Open the
whitelist again" as a permanent button in the same band as "Win as a Runner" would read as the game
asking to be gamed for an easy point, not as a milestone.

**Wired into `ManhuntService` by two hooks, not by widening its constructor.** `onStart(Consumer<Set
<UUID>>)` and `onFinished(BiConsumer<Set<UUID>, SpeedrunOutcome>)` are single-slot callbacks — default
no-ops — called from exactly the two moments `start()` already reaches: right before it returns
`STARTED`, and inside the `fresh.onFinish` lambda that already calls `announceFinish`. Achievements are
therefore something `ManhuntModule.enable()` bolts on from the outside
(`liveManhunt.onStart(manhuntAchievements::awardFirstHunt)`), the same "compose in the wiring class,
not in the service" reasoning `ChaosService` already gets for free by taking a `ManhuntService`
reference instead of being handed constructor flags for every feature that will ever want to react to
a run starting or ending. `ManhuntServiceTest`'s existing constructor-based assertions did not change;
three new cases in a `Hooks` nested class cover firing, not firing on a refused start, and firing with
the right roster and outcome on finish.

**Five settings get a quick-access icon in `ManhuntOptionsMenu`; the other six stay in `/settings`
alone.** Every field of `ManhuntSettings` already renders in the server's generic settings GUI, because
`@Topic` already declares a `Material` per field — building a second, parallel settings screen inside
Manhunt would be a second copy of that rendering logic for no reason. What Manhunt's own menu adds is
convenience: the win conditions, the map-reset and whitelist-on-start flags, and the seed policy are
the five an admin changes between matches, not once at setup, so they get a one-click cycle
(`SettingsStore.cycle` + `save()`, the exact call the generic menu's `SettingsMenu.onClick` already
makes for `CYCLED`) without leaving the Manhunt lobby. There is exactly one place any of these nine
settings can change — the shared `SettingsStore` — whichever menu the click came through.

**`ManhuntGoalMenu` is a curated seven-icon picker for the one settings field a GUI is actually the
right tool for: `runnerAdvancementKey`.** It mirrors `ManhuntAchievements`' own "seven in the GUI, the
rest by command" reasoning, not by coincidence — a full vanilla advancement tree in one nine-slot band
is a wall of icons, not a choice, so the menu shows exactly `end/kill_dragon`, `end/elytra`, `end/root`,
`nether/root`, `husbandry/balanced_diet`, `adventure/kill_all_mobs` and `nether/all_effects`, resolved
live via `Bukkit.getAdvancement` rather than hand-picked `Material`s. Each button's icon, title and
description come straight off the advancement's own `AdvancementDisplay` — `icon()`, `title()` and
`description()`, all `Component`s in this Paper version, not the legacy `String` the older
`org.bukkit.advancement.AdvancementDisplay` interface still returns — restyled through `Icons.name`/
`Icons.loreLine` so a real vanilla item reads like every other button in these menus rather than in the
game's own uncoloured text. An advancement a stripped-down datapack does not have, or one with no
display at all (recipe unlocks have none), is simply dropped from the band instead of guessed at.
Clicking a button sets `runner-advancement-key` **and** flips `runner-win` to `ADVANCEMENT` in the same
`SettingsStore` write — picking a goal is obviously meant to make it the active one, even though nobody
asked for that half in words. `/manhunt goal <advancement-key>`, tab-completed over every advancement
`Bukkit.advancementIterator()` knows about (curated or not, capped at the existing 50-suggestion limit),
is the actual "everything else" answer for this one field — the generic `/settings` chat-typing flow
still edits it too, but is no longer the intended path now that both a curated picker and a
fully-completed command exist.

**Runners can be locked to admin-only, with `/manhunt assign` as the escape hatch.**
`runnerSelfJoinEnabled` (default `true`) gates the self-service half of `/manhunt join runner` and
`ManhuntLobbyMenu`'s own "Join Runners" band — Hunters are never affected, on purpose, since the
setting exists for servers that want to hand-pick who chases rather than who runs. A locked band is
shown greyed via `Icons.locked(...)` with a reason rather than hidden, the same "a live-looking button
that errors teaches distrust" reasoning already documented on `Icons.locked` itself. The lock has one
deliberate hole: `/manhunt assign <player> <side>`, gated by the same `ADMIN` node as `start`/`stop`,
calls `teams.joinRunners`/`joinHunters` directly and never consults the setting at all — an admin's own
explicit action is not the thing being locked, the *player's own* self-service click is.

**The waiting lobby is pure geometry with no memory of who is in it, for the same reason
`LobbyBoxService` in `hungergames-module` documents for its own source.** `ManhuntLobbyBox` is a
Bukkit-free record-and-arithmetic class — a cube (three independent `abs(...) <= radius` checks, not a
sphere) around a configured spawn point, re-derived fresh from the live `ManhuntSettings` on every
question rather than cached. A stateful "am I holding this player in the lobby" flag can drift from
reality the moment another plugin teleports somebody away or the server restarts mid-hunt; asking pure
geometry against the player's actual current position cannot. `ManhuntLobbyListener` is the thin Bukkit
half — `relocateIfWaiting` and `releaseIfHeld` are plain methods, not event handlers, because there is
no Bukkit event for "joined a Manhunt side" in this reactor's own established convention, so both
`ManhuntCommand.join()`/`assign()` and both of `ManhuntLobbyMenu`'s join bands call them directly, right
where each already calls `teams.joinRunners`/`joinHunters`. Adventure mode is the same free
build-and-break block `hungergames-module`'s glass lobby already relies on. The listener is registered
once, at `ManhuntModule.enable()`, rather than per-run like `HunterHoldListener` — a player can join a
side at any time, not only while a hunt is going.

**A hunt start is also a clean slate: health, hunger, potion effects and gamemode, for everybody still
online.** `ManhuntService.start()` resets every online participant to full health (off
`Attribute.MAX_HEALTH`, the same constant `RainsCore`'s `BukkitPlayerAdminSink` already reads it
through), full food and saturation, clears every active potion effect (copied first — removing through
the live view throws `ConcurrentModificationException`), and drops them out of Adventure mode if the
waiting lobby left them in it — the same guarded `if (gameMode == ADVENTURE) setGameMode(SURVIVAL)`
`ManhuntLobbyListener.releaseIfHeld` uses, inlined here rather than duplicated through a second call,
since this loop already exists for the rest of the reset and a run starting is the one moment both
concerns are true at once. Reads through `plugin.getServer().getPlayer(id)` rather than the static
`Bukkit.getPlayer(id)` the rest of this class uses for the post-run announcement — the mocked `Server`
already stood up in `ManhuntServiceTest`'s `@BeforeEach` answers `null` for that without every existing
`start()` test needing its own `mockStatic(Bukkit.class)` just for this one loop. A no-op in every
current test for the same reason; not given its own assertions, since forcing `Bukkit.getPlayer` to
answer a real mocked `Player` would mean widening scaffolding several existing, unrelated test classes
share for a single new call site.

## GUI conventions

Everything below was asked for explicitly. Most of it is pinned by `ScreenGrammarTest` so it cannot drift
back a button at a time.

### Layout

- **Rebuilt on `core.ui.menu`, deliberately not the old screens.** "Make it the best it could be."
- **Buttons that open a page sit at least two columns apart** (1 · 3 · 5 · 7), so a pane falls between each
  pair. A wall of adjacent buttons is unreadable. *A `+`/`−` value pair may be adjacent* — the two halves of
  one decision belong side by side.
- **At most one submenu per menu item.** A category may hold things; a thing may not be another category.
  Three levels means nobody can say where anything lives.
- **The claim page is two doors and the claim itself:**
  - **People** (the viewer's own skinned head) → trusted · everybody else · kept out
  - **Configuration** (comparator) → claim flags · greetings · depth · perks · fence
  - on the page: redraw the border, name and icon, and the owner's ignore-own-rules toggle
  - toolbar: **the manual**, entry fee, bank
- **The claim list carries the manual in the bottom-centre slot, always** — including with no claims, since
  the people most in need of it are the ones without one yet.
- **No "What is this?" Book & Quill anywhere.** Core draws it whenever a screen returns help lines, so every
  `helpLines()` override was removed rather than emptied.
- **The info book is off the claim page.** The header tile and the buttons already say what the claim is.

### Titles

- A chest title has **146 pixels** (`Brand.TITLE_PIXELS`). The frame is 176 wide with the text drawn 8 in, so
  160 is the edge — filling it produced titles pressed against the border.
- **Never repeat the brand.** It is already prefixed: a page called "Claims — server" renders as
  `Claims » Claims — server`.
- **Keep page names short enough not to be clipped at all.** "Where nobody may claim" became "No-claim
  zones"; the *button* that opens it keeps the long wording, because a lore line has the width of the screen.
- The title carries a breadcrumb: `RSC » Server › All claims`. Only the immediate parent, and only when both
  names fit whole — the page you are *on* is never the half that gets cut.

### Icons

- **A head that stands for a person wears that person's face.** `Icons.head(uuid, …)`, never
  `Icons.of(Material.PLAYER_HEAD, …)`, which is Steve on every server.
- Sculk catalyst for "hidden underground" height. Blaze rod for the no-claim-zone tool — deliberately *not*
  the configured claim-stick material, so an admin can tell from their hotbar which they are marking.

### Interaction

- **Use Core's choosers. Never hand-roll one, never ask for a name in chat.**
  `de.raindancer.core.ui.choose`: `PlayerChooser`, `ItemChooser`, `FlagChooser`, `EffectChooser`,
  `SoundChooser`, `ParticleChooser`.
  - **Every** player is picked with `PlayerChooser` — typing a name means exact spelling, a typo looks like
    somebody who never joined, a renamed player is untypeable, and answering closes the menu.
  - The claim icon comes from `ItemChooser` — "hold the item you want" meant owning it, so an icon you did
    not have was unreachable.
  - A duration or a reason may still be a chat prompt: "three days" has nothing to enumerate.
- **Open child screens directly with `this` as parent.** The `screens()` opener is for entry points from
  commands and passes `null` — and a parentless menu **draws no Back button at all**.
- **A right or shift click must be advertised in the button's lore.** Any phrasing; the test is
  case- and hyphen-agnostic.
- **An empty list that names the way out must be able to act on it** — `emptyAction()` in `PaginatedMenu`.
- **A button in the danger slot must confirm if it is irreversible.** That slot is flanked by navigation. A
  harmless button there (the manual) is fine.
- **Screens exclude rather than refuse.** A chooser that never offers an owner beats a button that refuses
  after the click. *But the invariant still lives in the model* — see below.
- **A button that opens a chooser must not be asked for from `decorate()`** unless the framework flush after
  it is intact. `band()` / `toolbar()` / `cell()` buffer; Core now flushes again after `decorate()`.

### Flags in the UI

- **Grouped into subgroups** by `LandFlagGroup`, in Core's own order — the same order the owner's chooser
  uses, the admin screen uses, and the file is written in.
- **A flag the server disabled does not appear at all**, not greyed out.
- `/claimadmin flags` (or the Flags button) sets **policy** (available · forced on · forced off · disabled)
  and **what a new claim starts with**. Left click cycles the policy, right click flips the default; the
  default is greyed under any policy that never consults it. **Every click writes to disk immediately** — a
  protection setting that is live now and gone after a restart is found only when somebody blows a hole in a
  claim.

### Messages

- **Every placeholder a message asks for must be supplied where it is sent, and nothing supplied in vain.**
  An unfilled `<count>` is printed as written, mid-sentence, and reads as a broken plugin. Pinned by
  `PlaceholdersAreFilledTest`, which checks both directions — a value passed to a message with nowhere to put
  it is usually the same typo seen from the other side.
- `EveryMessageExistsTest` is the other half: every key the code sends has wording. A key can exist, be
  found, and still say `<count>` to somebody's face.
- **Wording lives in the module's `messages.yml`**, loaded through `Messages.defineFrom` as a *floor*, so the
  owner's file and a host's own bundled wording both win.
- Help text that shows command syntax must not use placeholder-shaped words — write "somebody", not
  `<player>`, or the test cannot tell instruction from mistake.
- Border notices go to the **action bar** by default (`notifications.use-action-bar`), and read as they did
  in the old plugin: *"You are entering &lt;claim&gt;, owned by &lt;owner&gt;"*, leaving in dark grey so the
  two are told apart at a glance.

### Handing out items

- **Never `addItem` a tool directly — go through `core.content.items.ToolGift`.** A blaze rod in an inventory
  completes *Into Fire*, so handing out the no-claim tool was handing out a nether milestone and announcing it
  to the server. `ToolGift` reads what the player already earned *before* giving the item and revokes only
  what appeared during the hand-over.
- A full inventory drops the item at their feet. Swallowing it makes the command look broken to exactly the
  people most likely to have a full inventory.
- **A refusal must not charge the player.** An ender pearl is spent on the throw and the teleport is a
  separate event, so a refused arrival refunds the pearl — on the player's own scheduler, since a teleport can
  arrive on a region thread that does not own their inventory.

---

## Command conventions

- **`/claim` bare opens the claim list**, wherever the player is standing. That has always been the front
  door. It must not depend on location: a command that means one thing inside a claim and another outside is
  one nobody can describe to somebody else.
- **The old names still answer:** `/claim` · `claims` · `rec`, and `/claimadmin` · `reca` · `recadmin` ·
  `cadmin`. People type what they typed last week.
- **A subcommand earns its place** when typing beats clicking, when it takes an argument a menu cannot ask
  for, or when nothing else reaches it. Flag toggles are clicks. `stick` and `accept` stay under the third
  clause.
- **An unknown subcommand prints the help**, never one line into silence.
- Destructive things route through the confirmation screen rather than acting on the spot.
- `/claim manual` and `/claimadmin manual` open the book; the player edition is given once and opened every
  time, recognised by title because its contents depend on what the server enables.

---

## Code conventions

- **Test first, always.** Then verify the test is not vacuous — mutate the fix and watch it fail.
- **Run `agy` over my own work regularly.** It has found real defects every time; it has also been wrong,
  so every finding is checked against the code before acting on it.
- **Packages, not a flat heap:** `command` · `listener` · `model` · `rules` · `screen` · `selection` ·
  `service` · `store` · `util` · `visual`. **Every plugin module uses the same structure.**
- **`rules` holds rules and nothing else.** Every class implements `IClaimRule` or extends Core's
  `AbstractRule`, has `Rule` in its name, and does not save, send or schedule — a rule must be askable
  speculatively, because "would this be allowed?" is what a screen asks to grey a button.
- **Interfaces for screens, services, listeners and commands too** — `IClaimScreen`, `IClaimService`,
  `IClaimCommand`.
- **The model does not reach for the running server.** One narrow exception: asking *whether there is a
  server at all*, which is how `ClaimFence` avoids needing one (`Tag` throws from its static initialiser).
- **Invariants live at the state change, not at the call site.** An exclusion list in a screen is a UI
  affordance; `Claim.ban()` refusing an owner is the rule. Guards on the two obvious entrances are not a
  guard — find every path, then check whether one calls another.
- Comments explain **why**. Never restate the line below.

---

## Storage

**Still YAML**: one file per claim in `plugins/RainsExtendedClaims/claims/<uuid>.yml`.

> **Outstanding:** the decision was "nvmd we're doing sql". Not implemented. `ClaimSchema` and
> `ClaimDatabase` were written and then removed while the 1.2.2 migration was the priority. The design that
> was agreed: a document column, so the migration is the existing tested reader rather than a second format.

**Migration is load-bearing and proven both ways:**

- **1.3.0 – 1.5.3** — fixtures taken from those real tags (`LegacyClaimFormatTest`).
- **1.2.2** — the oldest version in the repository, predating the CHANGELOG. The fixture was extracted from
  commit `02b705d`'s own `saveClaim`, not written from memory: a fixture that agrees with today's writer
  proves nothing. Covers no `data-version`, bare-boolean flags with the exemptions the listeners used to
  apply in code, `pantry.feed-visitors`, `equipment.equip-visitors`, split payment figures, and a
  read → write → read round trip (`From122Test`).

`@Key` on every `ClaimSettings` component preserves the old config paths, so an upgrading server keeps its
limits, costs and fence height. **One deliberate departure:** `notifications.use-action-bar` now defaults on.

---

## Identity and versioning

### What a version number means

| Bump | When | Example |
|---|---|---|
| `x.y.**Z**` | bug fixes and small additions | a refused pearl is refunded |
| `x.**Y**.0` | a full feature, finished | the manual, or the admin claim browser |
| `**X**.0.0` | a major refactor | 2.0.0 — the plugin rebuilt on RainsCore |

A feature that is half-built does not earn a minor bump; it earns nothing until it works, which is the same
rule as "no feature is done until it is verified". A release whose number promises more than it delivers is
the one people stop trusting.

- The plugin is **Rain's Extended Claims** (`RainsExtendedClaims.jar`), and keeps its **data folder name** —
  an upgrading server has claims in `plugins/RainsExtendedClaims/`.
- It continues **REC's own version line**, not the reactor's: **2.0.0**, because it is the plugin rebuilt on
  a new foundation rather than a release of the old one.
- Work continues in **Rain's Extended Claims' repo**.
- Commits use `Raindancer118` / `tom560stieh@gmail.com`. **Claude is never mentioned in a commit.**

---

## CI

Both repositories build a jar on **every push, on every branch**, plus pull requests and by hand from the
Actions tab. The artifact is named after the commit, so a downloaded jar can always be traced back to the
source it came from.

- **RainsCore** → `RainsCore-<sha>` (the library; the shaded and `original-` jars are excluded).
- **The reactor** → `RainsExtendedClaims-<sha>` (the shaded, ready-to-install plugin). Module jars are
  libraries and are deliberately not uploaded: a jar in a downloads folder that cannot be installed is worse
  than no jar.

Test reports upload **even when the build fails**, which is the case they are actually needed for.

> **The cross-repo dependency.** `de.raindancer:RainsCore` is published nowhere, so it resolves from the local
> `~/.m2` and nothing else — fine on a laptop that has just built it, fatal on a runner that has never seen it.
> The reactor's workflow therefore checks RainsCore out and `install`s it first, with its tests skipped, since
> RainsCore's own workflow already runs them. If RainsCore is ever made private, add a read PAT as a secret and
> uncomment the `token:` line. Publishing to GitHub Packages instead would be tidier and costs a token in both
> repositories.

## `/commands` — the directory, and why it is Core's

A player's question — "what can I type?" — spans every plugin, and no plugin can answer it. So
`RainsCore.commands()` is a `CommandDirectory` every plugin reports into, and `CommandBook` lays it out
as a written book. **Reported, not discovered**: Bukkit's command map holds every registered name, but
with Brigadier's own one-liner, no options, no permission a book can filter on, and every vanilla
command mixed in.

**A module declares its commands once.** `ModuleCommand` carries `taking(...)` (usage lines) and
`needing(node)` alongside the name and description Paper registers from, and `ModulePlugin` reports that
same list to the directory as the module enables. There is no second list, and therefore no way for a
new command to be missing from the book — which is exactly what a hand-maintained one would guarantee.

**`CoreCommands.commandList` registers once and declines afterwards.** Every standalone module plugin
asks, because none can know whether another is installed; Paper would namespace the losers and a server
with six of them would show six identical `/commands`. The latch is a static in Core, which works
precisely because `join-classpath: true` means one loaded copy of that class for all of them.

**A command a reader may not run is absent, not greyed.** `promote`/`demote`/`protect` are given nodes
nothing registers and nothing grants — `hasPermission` then answers true for an operator and false for
everybody else, which is the filter wanted.

---

## Wording sections must be *signed*

`Messages.defineFrom` has a one-argument form. A section nobody signed falls back to the global prefix,
which is whatever plugin called `prefixFrom` last — so `nether is set, here.` went out branded
`Moderation »`. Four of six modules used it. **Always pass `context.chat().brand()::chatPrefix`**;
`WordingContract.wordingIsSignedWithThisModulesBrand` now fails the build if you do not.

---

## Deployment — the Fachschaft testserver

**Superseded 12.08.2026:** the old `RainsSMPCore-Test` (uuid `4e01e711-…`) no longer exists — its volume
is gone from node2. The current testserver is Pelican server id **2**, name **`26.2-Testserver`**, uuid
**`0242f025-cb63-4389-b1b3-3b288c26de16`**, still on node2/VM121. Found by tinkering
`App\Models\Server::all()` on the panel VM (`ssh pelican`) when the documented uuid 404'd — worth doing
again first if this drifts, rather than trusting this file blindly.

```
ssh mango → ssh root@10.0.0.121
volume: /var/lib/pelican/volumes/0242f025-cb63-4389-b1b3-3b288c26de16
plugins dir also has: YeukSMP (unrelated, leave alone), bStats, spark
```

Owner uid/gid on that node is **988:988** (`pelican` user) — same as before.

**Wings power API auth**: `Authorization: Bearer <token>` using only the bare `token:` value from
`/etc/pelican/config.yml` (top level, not `api:`) — **not** `token_id.token`, that 403s.
`POST http://127.0.0.1:8080/api/servers/<uuid>/power` with `{"action":"restart"}`, answers **202**.

**12.08.2026 deploy** (RainsCore 1.16.0 + RainsChained 1.0.0, first install of the chained module here):
built both with `mvn -o clean install` (no skipped tests), verified the shaded `RainsChained-1.0.0.jar`
has zero `de/raindancer/core/` classes and both `dependencies.bootstrap`/`server` entries for RainsCore
in its `paper-plugin.yml`, old `RainsCore-1.10.0.jar` moved aside (not deleted) to
`deploy-backup-20260812-201618/`, sha256 verified node==local, restarted, **Done (36.005s), zero
exceptions/warnings** in the fresh boot log. RainsCore's own built-in modules (claims, homes, moderation,
names, rtp, tpa, warps) came up too — that's expected, RainsCore ships those itself; only chained was new.

**Both jars are needed.** REC has RainsCore as a *required* dependency and SMPCore is self-contained (zero
`de/raindancer/core/` classes in its jar), so RainsCore was not on that server before.

**SMPCore's own claims module is switched off** there — `modules.claims.enabled: false`. Two claim systems on
one world means two sets of protection listeners on the same event, and whichever loses is a silent hole.

The procedure, in order, because skipping any of it has produced a wrong deploy:

1. `mvn -o clean install` — **never `install` without `clean`**.
2. **Verify the built jar with `javap`**, not the build log: the change is present, no second RainsCore, the
   service file survived the shade.
3. `scp` both jars, `chown 988:988`.
4. Wings power API: `POST http://127.0.0.1:8080/api/servers/<uuid>/power` with the token from
   `/etc/pelican/config.yml`, body inline as `-d "{\"action\":\"restart\"}"` — **heredoc quoting does not
   survive nested SSH**, and the node's root shell is **fish**, so multi-step work goes in a script.
   The token is at the **top level** of that file, not indented under `api:` — match it as
   `^\s*token:\s*\K\S+` rather than assuming an indent. It answers **202**, not 204.
   *Everything* multi-step goes in a script: even `V=/path` on one line fails, because fish wants
   `set V /path`. Write it, `scp` it through mango, run `bash /tmp/it.sh`.
5. **Confirm the sha256 on the node matches local**, then read the *new* boot log — check the timestamp, or
   you are reading the previous boot.

**13.08.2026, fifth deploy — RainsCore 1.18.0 + RainsSpeedrun 1.0.0, same version numbers, new content:**
Four speedrun features landed in one deploy: (1) a live `m:ss` clock on every racer's action bar
(`SpeedrunTimerDisplay`, low priority so it never fights a claim/home message for the slot); (2) the
dragon-kill goal now only ends the run once a participant actually steps into the exit portal
afterward, not the instant the advancement fires (`DragonExitEndCondition`, toggled by the new
`requireExitPortalAfterDragon` setting, on by default); (3) movement is frozen in the lobby world the
whole time it is `READY`, not only during the five-second countdown, so nobody can wander off with the
compass and block before pressing start; (4) every run now starts from standard conditions —
full health, full hunger, no leftover potion effects or fire, the world set to morning, every hostile
mob and dropped item cleared (`SpeedrunPreparation`, via Core's `PlayerAdmin`) — regardless of whether
the map was freshly regenerated or just resumed. Also in this RainsCore build: a `/world switch|regen`
building block (`CoreCommands.worlds`, `rainscore.world.switch`/`.regen`, op-only like every other
undeclared Core permission) and a fix so a module's settings are forgotten when its session unwinds —
found from a real screenshot of "Homes" still listed in `/settings` on a server that never installs it;
root cause was `SettingsRegistry.add` never having a matching `remove` on enable-failure or disable.
**`/world` is not reachable on this server** — Core registers no commands itself by design, and no
installed plugin's bootstrapper (YeukSMP is foreign, not to be touched) opts into the new building
block yet; it exists and is unit-tested, not live-wired here.

Built with `mvn -o clean install` in both repos (1995 RainsCore tests, 104 speedrun-module tests, all
green), jars verified with `javap`/`jar tf` before shipping (zero embedded `de/raindancer/core/` classes
in `RainsSpeedrun-1.0.0.jar`, both `paper-plugin.yml` dependency phases present), old jars moved to
`deploy-backup-20260813-*/` rather than deleted, sha256 confirmed node==local for both jars, Wings
restart answered 202, fresh boot log: `Done (40.063s)`, zero exceptions, only the three already-known
`death-policy`/`world-name`/`advancement-key` collision warnings between `speedrun` and `chained`.

A fifth fix — `moderation-module`'s staff-permissions screen listing every module's permission nodes
even when the owning module is not installed (`StaffRank.grantableNodesOn(Server)`, filtering to nodes
Bukkit actually has registered) — is code-complete and unit-tested but **not deployed here**:
`RainsModeration` is not installed on this test server at all (it runs YeukSMP's own bundled
"Moderation 2.11.0" instead, an unrelated codebase) — nothing to verify live against.

---

## Known issues

- **FIXED — auto-equip (and effects, and the pantry) had no way for an owner to say who they serve,
  despite the data model already supporting it.** Raised directly: "I don't want to just gift every
  visitor a totem." `ClaimFeature.AUTO_EQUIP` was already `audienceAware = true`, `Claim.featureServes`/
  `setFeatureAudience` already existed, and `EquipService`/`AmbienceService` already checked
  `FeatureRules.appliesTo` before doing anything — the whole enforcement chain was real. What was
  missing was any menu or command that ever called `setFeatureAudience`: `PerksMenu`'s perk buttons only
  toggled the on/off switch, so a fresh claim's auto-equip (and effects, and the pantry) served
  **everyone, owner through visitor, with no way to narrow it** — the exact opposite of "opt in."
  Fixed by giving `PerksMenu.perk()` a right click for any `audienceAware()` feature, opening a new
  `AudiencePage` that is `FlagChooser.TierPage`'s three-tier toggle (owner/trusted/visitor) applied to
  perks instead of flags — the same mechanism the owner already knows from narrowing a flag, not a
  second one to learn. 28 new tests in `PerksMenuTest` (mirroring `EffectsMenuTest`'s click-type
  parameterisation) pin which clicks open it; `claims-module`'s full suite (335 tests) and the shaded
  `RainsExtendedClaims-2.1.0.jar` build stayed green.

  **Deployed 13.08.2026, sixth deploy.** Released as `claims-standalone` 2.1.0 → **2.2.0** and
  `moderation-standalone` 2.16.0 → **2.16.1** (the second fix rode along: `StaffRank`'s per-player
  permission-grant screen listing every module's nodes regardless of whether that module was
  installed — same class of bug, different screen, see `StaffRank.grantableNodesOn(Server)`).
  RainsCore bumped 1.18.0 → **1.19.0** for the settings-unwind fix and `/world switch|regen` from
  earlier the same day. Neither `RainsExtendedClaims` nor `RainsModeration` is installed on the
  26.2-Testserver standalone — both fixes shipped inside **YeukSMP 1.6.0 → 1.7.8**, the bundle that
  already depends on both modules, avoiding the two-claims-systems conflict a standalone
  `RainsExtendedClaims` install would have created alongside YeukSMP's own bundled claims. CI now
  auto-publishes and auto-tags any module whose version property has no matching tag yet (root
  `pom.xml`), the same fix RainsCore's own workflow already had for its single version — bumping a
  property and pushing is now the entire release step, nothing to tag by hand except `yeuksmp` itself
  (`maven.deploy.skip=true`, a terminal jar with no Maven coordinate anything depends on — tagged
  `yeuk-v1.7.8` for its GitHub release only). Also: the reactor now resolves RainsCore from
  `packages.tstieh.de` (`<repository>` id `packages-releases` in the root pom) instead of a sibling
  checkout built by hand in CI on every run — that workaround only existed because nothing was
  publishing RainsCore anywhere the modules repo could read it from, and RainsCore's own workflow
  already had been. Old jars backed up to `deploy-backup-20260813-*/` rather than deleted, sha256
  confirmed node==local for both jars, Wings restart 202, fresh boot log: `Done (35.673s)`, zero
  exceptions, only the three already-known settings-name collisions.

- **FIXED, but a real incident — the speedrun lobby's join handler cleared any player's inventory
  anywhere on the server.** `SpeedrunLobbyListener.onJoin` (introduced 12.08.2026 in 1.17.0) checked
  only `lobby.state() == READY` before clearing a joining player's inventory and handing them the
  lobby's two items — it never checked that the player was actually joining *into* the configured
  lobby world. `READY` is true almost all the time on a server that never runs the feature, so this
  fired on every ordinary join, into whatever world the server actually spawns people in. A player
  lost real gear (full netherite kit) to this before it was caught, on a server outside this
  project's own infrastructure — reported 13.08.2026. Fixed the same day in 1.17.3 by also checking
  `player.getWorld().getName().equals(lobby.config().worldName())`, with a regression test
  (`SpeedrunLobbyListenerTest.leavesInventoryAloneOutsideTheLobbyWorld`) that reproduces exactly this
  scenario. No backup of the lost items existed on the affected server; nothing could be restored.
  **Lesson, worth generalising:** any feature that acts on "every player who joins" needs its
  activation condition to include *where*, not just *whether* — a state check alone is not scoping.

- **RainsCore's farm-world regeneration deletes nothing, and reports success.** Found 04.08.2026 by
  running it on the test server. `/farm regen testmine confirm` said *"testmine is new"* twice, and
  `md5sum` of `region/r.0.0.mca` was identical both times — as was the folder's creation timestamp.
  Nothing was logged, because the delete branch was never entered.

  `FarmWorlds.regenerateOne` looks for the world at `Bukkit.getWorldContainer()/<name>`. **Paper 26.x
  puts additional worlds under `<level-name>/dimensions/<namespace>/<name>`** — here
  `world/dimensions/minecraft/testmine` — so `Files.exists` is false and the whole delete is skipped,
  after which `create()` reloads the same region files.

  Worth fixing carefully rather than quickly: the folder should come from `World.getWorldFolder()`,
  read *before* the unload, and `FarmWorldState.mayDelete` has to change with it — it currently
  requires the folder to sit directly in the server directory (`server.equals(folder.getParent())`),
  which the dimensions layout breaks. That function is the one thing standing between a typed command
  and a deleted server, so it does not get edited without tests against the new shape.

  **The farm-world module itself is unaffected** — it deliberately never deletes anything, and
  `ReuseTest` fails the build if it tries. Every part it owns works: creation, the schedule, the
  permission node, `/farm info`, the wording and the brand.

- **`FarmWorldState.flushTimes` writes to the database on the server thread.** Core's own SQL guard
  says so on every regeneration: *"A database write ran on the thread running the world, from
  …FarmWorldState.flushTimes:354"*. `FarmWorlds.regenerate()` calls `state.flush()` synchronously.

- **Twelve settings names collide with the warps module** — `warmup-seconds`, `cooldown-seconds`,
  `bring-*`, `safe-arrival-radius`, `hurt-cancels-warmup`. Each module has its own `config.yml`, so
  nothing is actually shared; the collision is in Core's flat `/settings` namespace, which warns 48
  times on boot and resolves a bare name to whichever plugin registered first. `farmworlds:warmup-seconds`
  works. Renaming the keys would make each module's own file read worse to fix a clash in a different
  namespace, so this is Core's arbitration to improve rather than the modules' names to mangle.

- **Three more settings names collide, this time between RainsCore's own new built-in speedrun lobby
  and the `chained` module** — `death-policy`, `advancement-key`, `world-name`. Found 12.08.2026 on the
  first boot after deploying RainsCore 1.17.0's speedrun lobby: `chained-module` had already built its
  own "what ends a run" (advancement/death) and "which world to reset" settings for its two-player race,
  independently of Core's brand-new general-purpose lobby, and both happened to name the concept the
  same way. Same shape as the warps collision above and the same verdict: each has its own file
  (`speedrun.yml` vs `chained`'s own config), nothing is actually shared, and `speedrun:death-policy` /
  `chained:death-policy` both work. Not renamed for the same reason as above.

- ~~RainsTPA and RainsHomes still have their own copy of the warm-up.~~ Fixed: both are modules now and
  both use Core's `Travel`, so there is one implementation of "stand still or it is cancelled" rather
  than three. The old `RainsTPA` and `RainsHomes` repositories are dormant, not archived — worth doing
  deliberately rather than leaving two repos that build a plugin nobody should install any more.
- **The migrations have still never actually migrated anything.** Both modules are now running on the
  test server (04.08.2026) and both came up clean — but that server had no `homes.yml` and no `tpa.yml`,
  so the import paths ran against nothing. What is proven is that the plugins boot, register and enable;
  what is *not* is that an upgrading server's data comes across. That wants a copy of a real data folder
  dropped in deliberately.
- **The test server had 78 stale `RainsCore.jar.bak-*` files** (58 MB) before this deploy, deleted
  04.08.2026. Nothing writes those now — they were left behind by hand-deploys. Worth watching whether
  they come back.
- ~~The download shelf lists three RainsCore versions.~~ Fixed by RainsCore's own publish job on the
  1.1.0 push — the per-repository prune took 1.0.0 and 1.0.1 with it. The shelf is now exactly one jar
  per plugin, and `bin/render.sh` needs a `row` line per plugin or the jar sits there unlisted, which is
  what had happened to Coloured Names.

- **SQL storage is not implemented.** See *Storage*.
- **`Messages.mergeMissing`** exists and is wired in; new keys are appended to an existing `messages.yml`
  with the owner's wording, comments and ordering untouched, and the old file copied aside first.
- The **manual** omits pages for capabilities restored after it was ported. Its test only proves it never
  names a command that does not exist, so it cannot rot into lying — but it is thinner than it should be.
- `ClaimInfoMenu` is reachable only by an admin, through the claim browser.

## Fixed, and worth not re-breaking

Each of these has a test named after the symptom:

- **Fence could not be taken down** — `enabled` was set only at claim creation, so the button read "Not
  built" for ever and every click rebuilt. The flag belongs to the code doing the world work.
- **Fence blocks orphaned** — skipped columns in unloaded chunks were forgotten anyway, and the record is the
  only thing that knows where a fence block is.
- **Build/teardown race** — three passes against one; per-claim turn, released in a `finally`. `sync()` is a
  third door and takes the same turn, so it calls `buildNow` rather than refusing itself.
- **Bypass was breakable** — it was remembered by each listener separately. It lives in `LandFlags`, the one
  thing all fourteen ask. A bypass remembered in fourteen places works in thirteen.
- **A pearl inside your own claim was treated as arriving** — the gate never looked at where the teleport came
  *from*. Moving within an area, and leaving it, are never refused.
- **Flying in was never checked** — `ELYTRA_FLIGHT` only fired when a glide *started*. The glide is stopped on
  crossing in, and the fall the plugin caused is forgiven for eight seconds, or the flag becomes a border that
  kills whoever flies into it.
- **Jump re-fired the enter notice** — two causes: the quiet period defaulted to zero, and the vertical grace
  was two blocks when a claim whose ceiling is the ground already has you above it.
- **`atmosphere.blocked` had been dropped** — nineteen harmful effects an owner could otherwise apply to
  visitors. A griefing hole, restored at its original config path.
- **Sixteen capabilities had no route at all** — the model method existed, storage read and wrote it, nothing
  called it. Kick, timed bans, co-owners, per-member admin rights, grantable permissions, effect selection,
  auto-equip rules and stock, pantry threshold, title styling and timing, and the admin operations.
