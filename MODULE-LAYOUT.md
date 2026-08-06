# How a module is laid out

Every module in this repository has the same package structure and the same interfaces. Not for tidiness —
because somebody who has read one module can then find their way around the next one without reading it, and
because a package name that means the same thing everywhere is a name you can trust.

`claims-module` is the worked example. Copy its shape.

```
de.raindancer.modules.<name>
    <Name>Module.java        the FlexModule: what enables, in what order, and what is stood down
    <Name>Services.java      a record of collaborators, handed to the listeners
    <Name>Settings.java      the @Settings record
    <Name>Commands.java      what the module declares at bootstrap
    I<Name>ScreensOpener     opening a screen, so nothing else depends on the screens

    model/      what the thing *is*                      — plain data, no server
    store/      what holds it and what writes it          — the registry, the files
    rules/      what *decides*                            — I<Name>Rule
    service/    what *does*                               — I<Name>Service
    listener/   the events                                — I<Name>Listener
    screen/     the menus                                 — I<Name>Screen
    command/    the commands                              — I<Name>Command
    selection/  marking something out, where that applies
    visual/     drawing things in the world
    util/       the genuinely generic, and nothing else
```

Two levels under the module root, never three. The same rule RainsCore follows.

---

## The five interfaces, and what each promises

These are not markers. Each one names a contract that is worth keeping, and each is checked by a test.

### `I<Name>Rule` — decides, and does nothing else

- No side effects. Nothing saved, nothing sent, nothing scheduled, and the thing being judged is not changed
  by judging it.
- Safe from any thread. On Folia every region has one, and a rule is asked several times a tick.
- Cheap, or honest about not being — `describe()` is what a diagnostic names, and an expensive rule is ordered
  late in whatever chain holds it.

Why it matters: a rule that acts cannot be asked speculatively, and *"would this be allowed?"* is exactly what
a screen asks to decide whether to grey a button.

Where a module has a genuine chain of reasons — nine ways a claim might be refused — use Core's
`IRule<T>` / `AbstractRule<T>` / `Rules<T>` instead. `AbstractRule` takes its description as a constructor
argument, so no rule can exist without one that reads in a sentence.

### `I<Name>Service` — does, and decides as little as possible

- Takes `settings(<Name>Settings)` and holds a snapshot, **whether or not it currently reads anything from the
  file.** The service that is forgotten when it starts reading something is the one that keeps yesterday's
  numbers until the next restart, and that gets reported as "the config does not work". A service with nothing
  to swap implements it empty and says why.
- Asks a rule rather than working the answer out again. A second set of rules is always the one that is wrong.
- Safe on a region thread, or it schedules onto the right one.

### `I<Name>Listener` — extends Bukkit's `Listener`

- `forget(UUID)`, defaulted. A listener that remembers a player must be told when they leave, or it grows by an
  entry for every player who has ever been on the server. That has happened twice in this repository. A
  listener with nothing to forget overrides it empty, which is a decision rather than an oversight.
- Holds `<Name>Services` and nothing else.

### `I<Name>Screen` — the menu grammar

Five rules, all checked by `ScreenGrammarTest`:

- **Greyed, never hidden.** A button somebody may not use is shown with the reason. Hiding makes the menu a
  different shape per viewer, so nobody can be told "the third one along".
- **An invisible modifier is an unused modifier.** A screen that reads a right or shift click says so in the
  lore of the button that reads it.
- **Nothing irreversible without a confirmation.** The danger slot is flanked by navigation; a misclick has to
  cost a page rather than the thing.
- **Buttons come from Core's `Icons`.** Otherwise the server grows two ideas of what a button looks like.
- **A refusal says something.** A button that fails silently is one a player presses four more times.

### `I<Name>Command` — built at bootstrap

Paper fires `COMMANDS` during the bootstrap phase, so a handler registered in `onEnable` never runs — silently.
A command therefore **captures nothing** and holds a `Supplier`. The state to design for is *registered, module
not running*, which a player reaches three ways: before it starts, after it failed, after it stopped. The host
wraps every command in `ModuleCommands.guarded`.

What earns a subcommand: typing it is faster than clicking, or it takes an argument a menu cannot ask for.
`trust <player>` earns its place; a flag toggle does not, because a flag is a click.

---

## Where things go when it is not obvious

| It… | goes in |
|---|---|
| holds data and answers questions about itself | `model` |
| is the subject a rule judges | `model` |
| indexes, loads or writes | `store` |
| hands the module's data to Core | `store` |
| answers yes/no or picks a value | `rules` |
| changes the world, an inventory, a file | `service` |
| is a `@EventHandler` | `listener` |
| is a `Menu` | `screen` |

If something does not fit, it is usually two things. Split it.

---

## What belongs in RainsCore instead

The rule from `The Idea.md`: **if two plugins could want it, or two plugins would each write their own, it is
not module code.**

Already learnt the hard way here:

- **World protection and flags** — `core.world.protection`. Claims own the claim; Core owns "may this player do
  that here", because a warp, a teleport request, a ghast line and a farm-world regeneration all ask it.
- **The flag screen** — `core.ui.choose.FlagChooser`. The flags are Core's, so a page per plugin is the same
  page three times with three different arrangements.
- **Menus, chat, messages, prompts, item codecs, logging, scheduling, names** — all Core's. A module that
  writes its own is a module whose windows look different from everything else on the server.
- **The confirmation dialog** — `core.ui.menu.ConfirmMenu`. It had been written out three times, in claims,
  in moderation and in warps, with the same two columns and slightly different words. This is the page
  where being identical everywhere *is* the feature: No on the left and Yes on the right is a habit people
  build, and a dialog that swaps them is one they learn to click through and then get wrong exactly once —
  in front of a delete. Each module keeps a two-line `ConfirmScreen` over it so its own `ScreenGrammarTest`
  can still prove that every `danger(` button confirms.
- **Going somewhere** — `core.world.teleport`. The warm-up, "you moved so it is cancelled", finding somewhere
  safe to land, the teleport itself, and what travels with the player. Written twice before this existed —
  in the teleport requests and in homes, identically down to the helper that decides whether somebody has
  moved — and warps would have made three. A module supplies a `Trip` and a `TravelWatcher`, which is the
  wording; Core does the rest. `Departures` and `Entourage` hold the decisions and take no server, so the
  two rules that matter most — nobody is dragged along without consenting, and nobody's animals are taken —
  are tested rather than tried once by hand.
- **Waiting between goes** — `core.platform.util.Cooldowns`. The check-then-record version had been written
  five times, and every copy could let two clicks in the same millisecond both through.
- **Going back** — `core.world.teleport.Returns`. "The last place I was" is asked by warps, homes and
  teleport requests alike, and `Travel` records one on every arrival it performs. It used to live in the
  teleport-request plugin because that is where `/back` was *typed*, with the predictable result: a home
  teleport recorded nothing, so `/back` after `/home` took somebody to wherever their last request had
  been from.
- **Picking a number** — `core.ui.choose.AmountChooser`. Six places had grown their own: nudge buttons at
  ±1 and ±10, which is forty clicks to set a fee of four hundred, or a chat prompt, which closes the menu
  and loses whatever was half-configured. Worth reaching for whenever a range is wider than a couple of
  dozen — the farm worlds' border runs to sixty thousand blocks and its scatter radius to a hundred
  thousand, and neither can be nudged by any single step that is not either unusable or unable to express
  half the values.
- **Lengths of time** — `core.world.time.Times`. Parses and describes what people actually type
  (`2min`, `1h30m`, `2 weeks`, `perm`), and `isForEver` knows the eight words somebody means by "never".
  `moderation.punishment.Durations` is a four-line alias kept for the moderation code that already calls
  it; **new code should say `Times`**. Every plugin that took a length of time had written a hundred
  lines of this, each understanding a slightly different three units.
- **Sounds and particles** — `core.ui.effect.Effects`, asked for **by meaning** (`Cues.TELEPORT`,
  `Cues.COUNTDOWN`, `Cues.NO`) rather than by sound. A module that named a `Sound` is the one whose noises
  are the only thing on the server that does not follow when an owner rebinds a cue.
- **A bar above the hotbar** — `core.ui.bossbar.BossBars`. A player has three slots at most and several
  plugins want one, so who wins is arbitration nobody can do alone. `showShared` takes the audience on
  every call, which is the right shape for anything tied to *where somebody is standing*.
- **"How many may this player have"** — `core.platform.permission.NumberedLimit`, for a node with a
  number on the end. Reading it with `hasPermission("x.limit." + n)` per number is wrong on Bukkit: an
  *undeclared* node defaults to true for an operator, so every admin held `homes.limit.100` and quietly
  had a hundred homes on a server configured for three. That fix existed in one plugin and had already
  been copied into a second before it was moved here.

- **A named, coloured group of players** — `core.social.team`. `Team`, `TeamId`, `TeamColour`, the roster
  (`Teams`), the outcome vocabulary (`TeamOutcome`), and the rules that vary as a value (`TeamPolicy`, with
  `tournament()`, `match()`, `clans()`, `party()`). A tournament's teams, a bedwars match's teams, a clan and
  a party are the same thing wearing four names: membership, exclusive colours, captains, random assignment,
  and a refusal that has to become a sentence. Written separately, each copy gets one of the hard parts
  subtly wrong — the one where a colour clash silently reassigns instead of refusing, the one where two
  clicks in a tick both pass the size check, the one where a rename loses the members.
  <br>The setting that makes one registry able to serve all four is `exclusiveColours`: a tournament and a
  match must have colours exclusive, because telling teams apart is the entire point, and a server with two
  hundred clans cannot possibly, because the seventeenth clan could never be founded. What is **not** in the
  policy is whether teams are editable right now — that is a fact about the moment rather than a rule, it
  changes several times during one round, and it is a `BooleanSupplier` the host answers.
  <br>What stays in a module is the phase at which *its* teams freeze and who counts as eligible to be in
  one. Hunger Games keeps `TeamRules` for exactly that, with a `toPolicy()`.

The counter-example, so the rule is not read too widely: a pantry, a bank, a fence and an entry fee are what a
claim *is*. They stayed in the module, and an earlier attempt to put them in Core is what this document exists
to stop happening again.

And the honest note on how the team model got here, since every other entry above was extracted only after
being written two or three times: this one was moved on the strength of **one** consumer plus a stated
intention to write clans and bedwars. That is the exception, and it was the right call because the thing moved
is a *model and a policy* rather than a workflow — the sixteen colours mapped onto dye, text and armour are
the same sixteen for every consumer there will ever be, and the compromises in that table (there is no brown
in Adventure's palette; Minecraft has two pinks in dye and one in text) are precisely what a second author
would get differently.

---

## Tests a module is expected to have

- `PackageGrammarTest` — each package holds what its name says. A package name stops being a promise one class
  at a time.
- `ScreenGrammarTest` — the five screen rules above.
- `<Name>SettingsTest` — every default spelled out. A positional record constructor with fifty components is a
  mis-ordering waiting to happen, and two swapped `int`s compile perfectly.
- A concurrency test wherever a store is read while it is written. Both of the ones written here reproduced a
  real bug within a handful of rounds.
