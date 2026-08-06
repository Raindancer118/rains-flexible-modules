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

---

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

## Deployment — the claims testserver

Pelican server `RainsSMPCore-Test`, uuid `4e01e711-d8fd-4cd0-ae10-b8c7803ab706`, on node2/VM121.
Public: **`mc-test.nak-inf.de:25568`** (allocation `10.0.0.121:25571`).

```
ssh mango → ssh root@10.0.0.121
volume: /var/lib/pelican/volumes/4e01e711-d8fd-4cd0-ae10-b8c7803ab706
```

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

---

## Known issues

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
