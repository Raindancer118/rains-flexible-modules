# Rain's Flexible Modules

Whole features, each one a Maven artifact rather than a copied folder. A plugin gets the real thing,
the source stays the single place it is written, and the same code also ships as a plugin of its own
through the standard wrapper — without being written twice.

Sits on [RainsCore](https://github.com/Raindancer118/RainsCore), which is `provided` in every module
and never shaded in.

## What is in here

| | |
|---|---|
| `modules-api` | the contract: what a module is, what a host provides |
| `modules-wrapper` | the standard wrapper — turns any module into a Paper plugin |
| `claims-module` | land claims: `/claim`, the screens, selection, fences, entry fees |
| `moderation-module` | bans, mutes, reports, staff notes, and the screens for them |
| `names-module` | coloured item and mob names: dye a name tag, craft it onto a thing |
| `warp-module` | named places anybody can be sent to, and who may reach which |
| `homes-module` | somewhere of your own to come back to, and how many you may have |
| `tpa-module` | asking somebody if you may come to them, and being asked |
| `farmworld-module` | somewhere to strip-mine that is thrown away and made again — scattered arrivals, and the whole server warned before it goes |
| `<x>-standalone` | a thin shade shell: module + wrapper = one loadable jar |

Two ways to consume a module, and **the module cannot tell which one it is in**:

| | How | Data folder |
|---|---|---|
| **Hosted** | the plugin depends on the artifact, shades it, and enables it through `ModuleHosts.embedded` | `plugins/<Host>/modules/<id>/` |
| **Standalone** | `<x>-standalone` shades module + wrapper; Paper loads it | `plugins/<Name>/` |

## Building

Java 25 and Maven. RainsCore has to be in the local repository first — it is not published anywhere,
so a build that has never seen it fails while *resolving*, which reads as a broken repository rather
than a missing sibling:

```sh
git clone https://github.com/Raindancer118/RainsCore ../RainsCore
mvn -f ../RainsCore/pom.xml install -DskipTests

mvn clean install
```

**`clean install`, not `verify`.** The standalone projects shade the module jars out of the local
repository, so a plain `verify` can shade whatever an earlier build left in `~/.m2` — a jar newer than
its own source, carrying last week's code. That reached a live server once. `StandaloneJarTest`
now checks that what ended up in the jar is what was just compiled.

The plugins land in `claims-standalone/target/`, `moderation-standalone/target/` and
`names-standalone/target/`. CI builds all of them on every push and attaches them to the run.

## Releases and versions

CI publishes on every push to `master`:

| | What |
|---|---|
| `latest` | a rolling pre-release — one link that is always the newest build. Point a test server at it once. |
| `build-<n>` | an immutable pre-release per push, so a jar from three pushes ago is still fetchable. The last 15 are kept. |
| `claims-vX.Y.Z`<br>`moderation-vX.Y.Z`<br>`names-vX.Y.Z` | permanent releases, cut by pushing that tag. |

Every jar carries a `.sha256` beside it, and every one of them needs `RainsCore.jar` next to it — none
of them ever contains a copy of it.

**The tags name the plugin because the plugins version independently.** The reactor is `1.0.0` while
Rain's Extended Claims is `2.0.0`, Rain's Moderation is `2.1.0` and Rain's Coloured Names is `2.0.0`,
so a bare `v2.1.0` could not say which of them it meant. The version in the tag is checked against that plugin's `<plugin.version>`
and the build fails on a mismatch: a release whose tag says 2.1.0 and whose jar says 2.0.0 is one
nobody can reason about six months later.

### What the numbers mean

| Bump | Means |
|---|---|
| `x.y.Z` | Bug fixes and minor additions. |
| `x.Y.0` | A feature release — the feature is *fully done*, not started. |
| `X.0.0` | A refactor, or a whole new suite of features. |

So a half-finished feature does not get a minor bump, and a patch release never introduces one.

## Where the lines are drawn

- **If two plugins could want it, it is not module code.** It goes in RainsCore. World protection and
  flags, menus, chat, messages, prompts, item codecs, logging, scheduling, names — all Core's.
- **A module is commands, screens and its own product decisions.** Nothing else.
- The counter-example, so the rule is not read too widely: a pantry, a bank, a fence and an entry fee
  are what a claim *is*, and they stayed in the module.

Coloured names is the smallest case, and the clearest about what is left over. The settings, the
wording, the menu, the buttons, the Folia-safe scheduling and the atomic write of `config.yml` are all
Core's; what stays in the module is what a name tag *means* — which item dyes, what a grid of tags
makes, and how a gradient is spread over a name. A styled tag is its own record, held in the item's
persistent data under a namespace that has not changed since the standalone plugin, so a server that
removes the module keeps every tag anybody has dyed.

Moderation is the clearest case. The punishments — who is banned, muted or frozen, and until when —
are RainsCore's, so a server that removes `RainsModeration` **keeps every ban it has handed out and
keeps enforcing it**. What lives in the module is the policy: the reasons, the escalation ladders, the
report queue, the notes, and who hears about what.

## Reading further

- `MODULE-LAYOUT.md` — the package structure every module follows, the five interfaces each one
  implements, and what each promises. Start here before adding a module.
- `The Idea.md` — why the arrangement exists at all.

## Licence

Not yet chosen. All rights reserved for now.
