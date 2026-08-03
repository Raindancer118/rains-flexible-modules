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

The plugins land in `claims-standalone/target/` and `moderation-standalone/target/`. CI builds both on
every push and attaches them to the run.

## Where the lines are drawn

- **If two plugins could want it, it is not module code.** It goes in RainsCore. World protection and
  flags, menus, chat, messages, prompts, item codecs, logging, scheduling, names — all Core's.
- **A module is commands, screens and its own product decisions.** Nothing else.
- The counter-example, so the rule is not read too widely: a pantry, a bank, a fence and an entry fee
  are what a claim *is*, and they stayed in the module.

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
