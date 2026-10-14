# Anti-cheat usability review (RainsAntiCheat 0.9.x)

> **Superseded (moderation 3.0.0).** The detection checks reviewed here are gone; detection is GrimAC's and
> the rest of the anti-cheat lives in the moderation module. Kept for the history of the decisions.


Scope: read-only review of `anticheat-module/` (paths below relative to `src/main/java/de/raindancer/modules/anticheat/` unless noted), `anticheat-standalone/`, and its seams to `moderation-module/` and RainsCore. Perspectives: (a) moderator on duty, (b) admin reviewing hours later, (c) owner configuring.

## Verdict in one paragraph

The detection side and the "never punish on its own" defaults are solid (setbacks/cancel/kick/ban all default off, `AntiCheatSettings.java:249-250`). The review side is the weak spot. The alert line is a dead end (one click, to chat text), replays and evidence are reachable only by typed commands, the moderation module does not know the anti-cheat exists (`grep -i anticheat` in `moderation-module/src/main` finds only a comment in `AutoReportRule`), and combat/world/inventory hits never reach the report queue. The x-ray review screen (`moderation-module/.../screen/XrayReviewMenu.java:40-154`: verdict in "one in 10^n" form, per-signal explanation, click-to-teleport, "show me their tunnels") is the in-house benchmark; the anti-cheat has no equivalent screen.

---

## 1. Alert UX

### What exists
- One chat line per player+check per second, extra hits folded into "×N" (`service/AlertService.java:27,47-67`). Good basic throttle.
- Per-staff on/off (`PlayerSwitch`, persisted, `AlertService.java:28,100-109`; `/ac alerts`, bell in SuspectsMenu `screen/SuspectsMenu.java:89`) and `/ac verbose` (unthrottled, `AlertService.java:70-82`).
- Experimental checks get a distinct gold line (`messages.yml:21`).
- Alert click: name runs `/anticheat info <player>` (`messages.yml:20`).

### Problems (prioritised)
1. **Click action is a dead end.** `/anticheat info` prints raw stats (`AntiCheatCommand.java:106-129`: "kurtosis", "skew", "σ ms", "clock N ms ahead") into chat. No teleport, spectate, replay, evidence or "open menu" action on the alert. Compare zeroac (clickable teleport in the alert) and Vulcan add-on ACManager (spectate on click) from the research below. The hover text is hard-coded English (`messages.yml:20`).
2. **No alert-to-evidence link.** An alert freezes a replay (`ViolationService.java:67-70,154-168`) but the alert never says "replay saved" nor offers `/ac replay <player> <n>`. Replay index `n` is newest-first and shifts with every new replay, so a link built from an old chat line would open the wrong one.
3. **Noise model is per check, not per player.** A fly hack trips Fly+Speed+Strafe+NoFall+Improbable = up to 5 lines/s. There is no per-player digest ("Steve: movement x34 in 20 s, 4 checks"), no "snooze this player for 5 min" (`/ac exempt` is the wrong tool: it stops checking, needs `manage`, `AntiCheatCommand.java:72-81`). Checks with `alertAt` 1 (Phase, MultiAura, Nuker, BadPackets, Improbable, Client, `model/CheckType.java:39,59,70,106`) alert on the very first flag.
4. **Jargon.** Alert line: `failed Fly ×4 · level 6.0 · dy 0.000, expected -0.078 (42 ms)` (`messages.yml:20`; details are raw physics numbers from the rules). "level 6.0" has no scale: the staff member cannot see it means "alert threshold 3, kick at 40". The suspects menu colours levels at fixed 5/20 (`SuspectsMenu.java:60`) regardless of per-check thresholds. A plain-language prefix ("hovering in mid-air for 4 ticks") exists as `CheckType.description()` (`CheckType.java:17`) but is not shown in alerts.
5. **Per-staff controls are binary.** No minimum category/level, no "only players I am watching", no "mute combat". Verbose is not persisted (`AlertService.java:37,112-118`) and silently dies on leave/restart.
6. **No external channel.** No Discord/webhook code anywhere in the module. Grim ships `discord.yml` with threshold-based sends ("every 20 violations", [Grim wiki: Discord webhooks](https://github.com/GrimAnticheat/Grim/wiki/Discord-webhooks)); Vulcan puts ping/TPS/brand/version in punishment webhooks (changelog 2.2.2, via [Vulcan add-on listing](https://docs.bloom.host/plugins/vulcan)). For a staff team that is not online all day this is the main way an auto-report is seen at all. Auto-reports only reach staff through the moderation chat line `reported X (id): text` (`moderation-module/.../service/ReportService.java:108-115`), which is plain text, not clickable.
7. **Bedrock staff.** `<click>`/`<hover>` in chat does not work for Bedrock clients through Geyser, so alert clicks are useless for them. Menus (chest GUIs) work fine, so every chat action needs a `/ac` command fallback that opens a menu (e.g. `/ac <player>`), see item 10 below. `replay` draws dust particles (`visual/ReplayView.java`) which render on Bedrock but look different; untested.
8. **Policy leaks.** `kick-blocked-clients` defaults to **true** (`AntiCheatSettings.java:255`; `PunishService.refuseClient`, `PunishService.java:129-131`) and `dampen-suspects` silently reduces a player's damage "without telling them" (`AntiCheatSettings.java:203-208`). Both are off the "alert and report, staff decide" line; dampen is default off, kick of blocked brands is default on. Defensible for the brand list, but it should be an explicit documented exception, and dampen should at least tell staff each time it kicks in.

### What admins like / dislike elsewhere (cited)
- Grim: `/grim spectate` + `/grim stopspectating` (returns to original position), `/grim verbose`, `/grim profile` (version, sensitivity), `/grim history` with sessions and regex filters ([Grim wiki: Commands](https://github.com/GrimAnticheat/Grim/wiki/Commands)). Takeaway: spectate-and-return and session-based history are the staff workhorses.
- Vulcan ecosystem: in-game config GUI, Discord bot, banwaves ([docs.bloom.host](https://docs.bloom.host/plugins/vulcan)); ACManager adds a visual review menu with a 0-100 confidence score from violation frequency, check variety, ping/TPS stability, spectate on left-click, clear on right-click ([CurseForge ACManager](https://www.curseforge.com/minecraft/bukkit-plugins/acmanager)). Takeaway: staff want a ranked queue with a single confidence number and one-click spectate.
- Matrix: user reviews are mixed and anecdotal, high-ping players flagged for movement ([BuiltByBit reviews](https://builtbybit.com/resources/matrix-anticheat.13999/review/28698/)). Takeaway: false positives are what burns staff trust; keep the lag-discount logic and show it.
- Polar: documents that position corrections near glitched blocks or cancelled move events are not cheating and false positives "can occur due to bugs" ([Polar docs](https://docs.polar.top/detections)). Takeaway: say so in the UI. Spartan: I found no substantive source; not used.

---

## 2. Evidence and review

### What a staff member sees today
- `/ac log <player> [page]`: 10 lines per page, `dd.MM HH:mm:ss check level detail where ping tps` (`AntiCheatCommand.java:147-173`, `messages.yml:45-46`). Server-local absolute time only, no "2 h ago", no clickable paging, no per-line teleport.
- PlayerChecksMenu: every check (34) sorted by level, up to 3 evidence lines in lore, click dumps up to 15 lines to chat and closes the menu (`screen/PlayerChecksMenu.java:161-201`).
- Replay: `/ac replay <player> [n]` teleports the viewer (4,2,4 offset) if more than 48 blocks away and draws a green-to-red path plus white look lines for 20 s, only for the viewer (`AntiCheatCommand.java:176-218`, `visual/ReplayView.java:168,201`).

### Gaps, ordered by impact
1. **Replays are not in any menu.** `ReplayView` is referenced only from the command (`AntiCheatCommand.java:206`). A moderator who does not know `/ac replay <name> <n>` never finds them; PlayerChecksMenu has no replay button; no list of what replays exist (check, time, how long ago).
2. **Evidence is a flat, easily-flooded ring buffer.** `evidence-per-player` default 200 (`AntiCheatSettings.java:250` and `EvidenceLog.add:219-226`); every flag is stored (`ViolationService.java:60-65`), including sub-alert ones. A single minute of fly can overwrite everything older, so an admin reading hours later may see only the tail of the last burst and lose the earlier Reach/KillAura evidence. Same for replays: 5 per player shared across all checks (`model/Replays.java:416-428`, `Replays.add`), one per check per 10 s (`ViolationService.java:160-165`).
3. **No incident grouping.** Flags are never rolled up into "incidents" (start, end, checks involved, flag count, peak level, ping/TPS range). That is what Grim's session history provides and what a report reviewer actually wants ("what happened at 21:14?").
4. **Confidence is explained only for the report, and only as a string.** `ConfidenceRule` builds `fly x30, speed x12 · 42 flags in 38 s · level 25 · last: ...` (`rules/ConfidenceRule.java:116-118`); `AutoReportService` appends ping/tps/location/`/anticheat log name` (`service/AutoReportService.java:212-213`). Missing: why it is trusted (how many independent checks agreed, how many flags were discounted for lag, `LAGGED_WORTH`), what was excluded, how it compares to the player's normal.
5. **Reports show nothing anti-cheat specific.** `ReportMenu` renders `report.text()` as lore with "Reported by the console" (`moderation-module/.../screen/ReportMenu.java:61-67,115-127`), and has "Open their page" (PlayerMenu). PlayerMenu has an X-ray row (`PlayerMenu.java:110,131-135`) but no anti-cheat row. From a report, a moderator needs: open `/ac log` (typed, from text), `/ac replay` (typed), teleport (manual).
6. **Combat/world/inventory never create a report.** `ConfidenceRule.counts` is movement only (`rules/ConfidenceRule.java:29-31`); Reach, KillAura, Scaffold, AutoClicker etc. only produce chat alerts that scroll away. For an admin reviewing later this is the biggest blind spot. Kicks/bans create Core punishment records, so those are findable.
7. **No "normal" to compare with.** The suspects list is an absolute sum of levels (`SuspectsMenu.java:33`); nothing like "this ping band's median", "other 50 players' max in the last hour", or a per-session sparkline. Info shows CPS/kurtosis/skew without any baseline (`AntiCheatCommand.java:124-128`), so the numbers are uninterpretable for staff.
8. **Offline suspects are invisible in the GUI.** SuspectsMenu lists only `tracks().all()` (online players) (`SuspectsMenu.java:32`) while evidence and replays persist for offline UUIDs (`EvidenceLog.everybody()`). A cheater who logged off before the admin looked is reachable only via typed `/ac log <name>`.
9. **Levels are in-memory.** `Violations` decays by wall-clock and is not written to disk (evidence is, `evidence.yml`); after a restart the suspects list is blank even if `evidence.yml` is full.
10. **Evidence can be deleted by one click + confirm** (`PlayerChecksMenu.java:223-232`, also `/ac reset`), including replays, with no undo or archive. Policy-wise this is a persistent-data delete; suggest "archive to `evidence-archive.yml`" or only reset levels, keep evidence N days.
11. **Replay content.** 100 movement frames (about 5 s) of one player, no attack target, no block/place events, no ping/TPS overlay, no slow-motion/steps. Good enough for Fly/Speed, weak for KillAura/Reach (you cannot see the target or the angle).

---

## 3. Commands and menus

- `/ac` opens SuspectsMenu (good). Subcommands: `alerts verbose info log replay exempt unexempt reset checks status` (`AntiCheatCommand.java:33-34`). `/ac <playername>` is not supported and falls to "mistyped subcommand" (`:98-102`), the most natural staff action. `/ac status` is the only console-friendly view.
- Clicks to evidence: `/ac` -> suspect head -> check icon -> chat dump = 3 clicks, then typing for replay. Clicks to a replay: not possible via menu. Clicks to teleport to a suspect: 2 (`PlayerChecksMenu.java:208-216`), but no spectate and no return-to-origin.
- Menu clutter: PlayerChecksMenu lists all 34 checks for everybody, most as grey "Level 0.0" dye with a 3-line description (`:161-182`). Hide level-0 checks by default (toggle to show all), group by category.
- ChecksMenu (the admin switch for on/watch/off) is reached from the *suspects* screen toolbar (`SuspectsMenu.java:87-88`), i.e. staff UI and owner UI are mixed; it writes `disabled-checks`/`silent-checks` through the store (`ChecksMenu.java:79-84`) and so duplicates the `/settings` "Checks" topic.
- Permissions are three coarse nodes, all OP-default (`util/PermissionNodes.java:75-77,87-89`): `command`, `alerts`, `manage`. No separate node for replay/teleport/spectate or for reading versus clearing evidence; a trial mod either gets `manage` (can clear a record and turn checks off) or cannot even exempt a player. Bypass nodes per check exist (`:91-93`), good.
- Consistency with `/mod`: `/mod` has reports queue, claim/release, resolve/reject with prompts (`ReportMenu.java:82-145`), a PlayerMenu hub, and an x-ray evidence menu. The anti-cheat has its own parallel hierarchy (own title style, own breadcrumb, no link in either direction). There is no `/mod` -> "Anti-cheat" tool, no PlayerMenu "Anti-cheat" button, no ReportMenu "Open evidence" button.
- Messages: tone is jokey in `messages.yml` ("Brace for paperwork", "The physics engine sends its regards") with an alternate `messages-serious.yml` that overrides only 17 keys (alert lines themselves are not overridden; `verbose-off` and `replay-shown` fall back to the jokey ones). Real hard-coded English also bypasses messages: all menus (`MiniMessage.deserialize("<dark_gray>Anti-cheat — ...")`, lore strings), hover text, "unknown"/"events only" in info (`AntiCheatCommand.java:119-123`). `WordingTest` does not catch these.
- Tab-complete: `suggest` is fine, but no suggestion of replay indices or page numbers (`:285-303`).

---

## 4. Configuration

### Good
- Safe SMP defaults: `setbacks=false, cancel=false, auto-kick=false, auto-ban=false` (`AntiCheatSettings.java:249-250`); experimental checks only alert; `exempt-bedrock=true`; ping never exempts; TPS gate at 17.
- Rich `@Describe` per setting, grouped in five topics (`:16-22`), `punish-scale` and "watch only" mode.
- Report policy is exposed (flags, window, level, max ping, `:217-246`).

### Weak
1. **No presets.** No lenient/normal/strict or "observe only / SMP / PvP" in the module and none in the Core settings framework (`RainsCore/.../data/settings/` has no preset concept; `ui/chat/Preset.java` is chat styles). The owner must reason about 38 knobs. A preset sets `punish-scale`, `reach-leniency`, `timer-leniency`, `max-cps`, `auto-report-*`, `experimental-checks`, and the silent list.
2. **Settings are numbers without consequences.** E.g. "Violation level still standing" `auto-report-level` (`:237-241`) or "Flags needed" gives no hint what is typical (how many reports per day at 15 vs 25?). A dry-run counter ("with this setting, last 7 days would have produced N reports") would make tuning possible. Evidence is already stored.
3. **Per-check tuning is code-only.** `alertAt`, `kickAt`, `banAt`, `decayPerMinute` live in the `CheckType` enum (`CheckType.java:17-136`). Owners can only scale kick/ban globally (`punish-scale`); `alertAt` cannot be changed at all (the main noise lever), nor per-check "alert only above X".
4. **Disabled/Silent are free-text string lists** in `/settings` (`:134-144`), valid names are only in `/ac checks`; typos are silently ignored (`AntiCheatSettings.contains`). The ChecksMenu is the safe way, but it is hidden under suspects.
5. **No Bedrock nuance.** `exempt-bedrock` switches off *everything*, including combat/world checks that are platform-independent (`ViolationService.java:49`). On an SMP with Geyser, Bedrock reach/fast-break/scaffold/inventory checks do not run at all. Offer "Bedrock: movement only exempt" or per-category.
6. **Dangerous options sit with harmless ones** (topic "What a failed check does" contains alerts, setbacks, kick, ban, dampen, evidence retention). Suggest splitting "Alerts & staff" from "Consequences".
7. **First-run guidance missing.** Nothing tells a fresh owner "start in observe mode for a week; here is how to read it". `/ac status` does not mention that setbacks/cancel are off (checks flagging but not acting is easy to mistake for "broken").
8. **Standalone descriptor** (`anticheat-standalone/src/main/resources/paper-plugin.yml`) is correct (RainsCore required, `folia-supported: true`) but declares no `permissions:`; they are registered in code (`PermissionNodes.java:72,97`), so LuckPerms users cannot see the nodes with a plain tab-complete until the module started. Minor.
9. **Not on Folia:** anti-ESP and "Hide behind walls" say so in the description; OK.

---

## 5. Top 10 changes (value / effort)

| # | Change | Why | Files | Effort |
|---|---|---|---|---|
| 1 | **Actionable alert line**: `[Replay] [Go] [Evidence]` buttons next to the name, plus plain-language reason from `CheckType.description()` on hover; each button just runs a `/ac ...` command that opens a menu (so it also works for Bedrock via typed `/ac <player>`). Add a `/ac <player>` shortcut. | Alert is the entry point of every review; today it ends in a raw-stats chat dump. Bedrock staff cannot click, so a short command is the fallback. | `messages.yml:20-21`, `service/AlertService.java:61-64`, `command/AntiCheatCommand.java:47-103` | S |
| 2 | **Replay & evidence in the player screen**: replace the 34-check list's front page by a summary (top checks, last incident, "Replays (n)" button list with check, age, ping/TPS; click = go-and-draw), hide level-0 checks behind a toggle; add a spectate-with-return button (Grim `/grim spectate`). | Replays are invisible without the typed command; spectate/teleport must be reversible. | `screen/PlayerChecksMenu.java:161-238`, new `screen/ReplaysMenu.java`, `command/AntiCheatCommand.java:176-218` (extract `ReplayLauncher`) | M |
| 3 | **Link moderation and anti-cheat**: PlayerMenu gets an "Anti-cheat" row (level, last flag, replay count; click opens anti-cheat player screen); ReportMenu shows "Open evidence/replay" for machine reports. Do it through a small Core hook like `AutoReports` (static provider) so neither module depends on the other. Make the staff chat line of an auto-report clickable. | Admins review from `/mod reports`; today they must leave it and type a command copied from text. | `moderation-module/.../screen/PlayerMenu.java:110`, `ReportMenu.java:61-73`, `ReportService.java:108-115`, `RainsCore/.../reports/AutoReports.java`, `service/AutoReportService.java:212-215` | M |
| 4 | **Discord webhook** (own `webhook-url`, event types: auto-report filed, kick/ban, optional "alert digest" with min level + cooldown; embed: player, checks, flags, ping, TPS, world/xyz, brand). Follow Grim's threshold-style sends. | Staff off the server never see a report; matches what Grim/Vulcan owners expect. | new `service/WebhookService.java`, `AntiCheatSettings.java` (new keys at end of record, `DEFAULTS` and `withAutoReport` copy at `:248-263`), `AutoReportService.java:215` | M |
| 5 | **Incident grouping + longer-lived evidence**: store flags as incidents (start/end, checks, count, peak level, ping/TPS range, first location) in addition to the ring buffer; keep incidents (e.g. 30 days or 50 per player) separately from the 200 raw lines; keep replays per check (1-2 each) rather than 5 shared. Show "21:14, 38 s, Fly+Speed, 42 flags, ping 48" in `/ac log` and the menu. | Hours later the 200-flag ring buffer is overwritten by the last burst; one line per incident is what a reviewer needs. | `store/EvidenceLog.java:219-226`, `model/Replays.java:416-428`, `service/ViolationService.java:60-70,154-168`, new `model/Incident.java` | L |
| 6 | **Reports for combat/world** (Reach, KillAura, Scaffold, AutoClicker...) via a sibling `CombatConfidenceRule` (distinct checks, repeated over time, lag-discounted like `ConfidenceRule`), and include "why we think so" lines (independent checks agreeing, flags discounted for lag, window). | Only movement can create a report today (`rules/ConfidenceRule.java:29-31`); combat evidence only flickers by in chat. | `rules/ConfidenceRule.java`, `service/AutoReportService.java:192`, new rule + tests | M-L |
| 7 | **Presets + dry-run counter**: `/ac preset observe | smp | strict` (sets the 10-15 tuning keys at once, shown in `/settings` as one choice), and `/ac status` printing "reports last 24 h / alerts last 24 h / checks acting vs watching". Add per-check `alert-at` overrides in config. | Owner can't reason about 38 numbers; safe defaults are fine but unexplained; alertAt is the biggest noise lever and is hard-coded. | `AntiCheatSettings.java:16-246`, `model/CheckType.java`, `rules/ActionRule.java:25`, `command/AntiCheatCommand.java:233-240` | M |
| 8 | **Per-player digest + staff filters**: one combined line per player per 10 s ("Steve: Fly, Speed, NoFall x34, ping 48"), `/ac alerts min <level>`, `/ac alerts mute <player> [min]`, persist verbose. Entries with `alertAt` 1 get a 2-flag minimum for chat (log still keeps them). | Cuts the 3-5 lines/second flood from one hack and the first-flag blips; staff can mute a known-lagging player. | `service/AlertService.java:47-97,112-118`, `model/CheckType.java` (Phase/Nuker/MultiAura/BadPackets), messages | M |
| 9 | **Explain levels and normal**: show "level 6 of 40 (alert 3)" bars/colours from the check's own thresholds, per-player ping band and TPS context, and a confidence line in the player screen (as XrayReviewMenu does: `moderation-module/.../XrayReviewMenu.java:122-134`); replace "kurtosis/skew" with plain words plus the normal-range hint. Include offline suspects in `SuspectsMenu` from `EvidenceLog.everybody()`. | Staff decide on numbers they cannot interpret; offline cheaters are unreachable from the GUI. | `screen/SuspectsMenu.java:32-77`, `screen/PlayerChecksMenu.java:161-182`, `command/AntiCheatCommand.java:106-129`, `messages.yml:40-44` | M |
| 10 | **Permissions, language, archive**: split nodes (`rainsanticheat.review` = log/replay/teleport, `.exempt`, `.reset`, `.checks`); move all hard-coded menu and hover strings into messages (extend `WordingTest` to scan menus); finish `messages-serious.yml`; make "Clear their record" archive instead of delete (or keep `evidence` N days). Document the `kick-blocked-clients` and `dampen-suspects` exceptions to "never punishes". Optionally a "movement only" Bedrock exemption. | Trial mods need read-only review; delete of evidence is irreversible; policy statement vs defaults should be explicit. | `util/PermissionNodes.java:75-93`, `screen/*.java`, `messages*.yml`, `service/ViolationService.java:128-141`, `AntiCheatSettings.java:51-55,181-184,203-213`, `ViolationService.java:49` | M |

Quick wins (all S, can ship without design work): `/ac <player>` shortcut; hide level-0 checks; make the report staff-chat line clickable; persist `verbose`; add `replay saved` hint to alerts; show the thresholds in the alert ("level 6, alert at 3"); make `/ac status` say whether setbacks/cancel are on.

## Test notes
Nothing was run (read-only). Existing tests cover rules and wording (`WordingTest`, `EveryKeyExistsTest`, `AutoReportServiceTest`); no test covers alert throttling output, SuspectsMenu/PlayerChecksMenu content or any Bedrock path, so changes above should add those first (test-first).

## Sources
- Grim commands: https://github.com/GrimAnticheat/Grim/wiki/Commands
- Grim Discord webhooks: https://github.com/GrimAnticheat/Grim/wiki/Discord-webhooks
- Vulcan features (hosting docs): https://docs.bloom.host/plugins/vulcan
- ACManager (review menu and confidence score for Vulcan/Grim/Matrix): https://www.curseforge.com/minecraft/bukkit-plugins/acmanager
- Matrix user reviews (anecdotal): https://builtbybit.com/resources/matrix-anticheat.13999/review/28698/
- Polar detections docs: https://docs.polar.top/detections
- Web search for Spartan found no substantive source; Vulcan's own alert/webhook docs were not directly retrievable, so those comparisons rest on third-party pages and are marked as such.
