# Lilly SMP — Mod commands

`<>` required, `[]` optional. Lengths: `30m`, `2h`, `3d`, `perm`.

## Start here
| Command | What |
|---|---|
| `/mod <player>` | Everything about a player: punish, history, evidence |
| `/reports` | Report queue |
| `/mod report <id>` | Open one report |
| `/staffchat <msg>` | Staff-only chat |
| `/vanish` | Invisible to players |
| `/admin [on\|off\|bypass]` | Admin inventory + mode, with bypasses |

## Punish
| Command | What |
|---|---|
| `/broke <player> [rule] [what happened]` | Punish by the rules (preferred) |
| `/warn <player> <reason>` | Warning on record |
| `/mute <player> [length] [reason]` | Mute |
| `/kick <player> [reason]` | Kick |
| `/ban <player> [length] [reason]` | Ban (mods: limited time) |
| `/fine <player> <amount> <reason>` | Fine · `/fine forgive\|revoke <player>` |
| `/unmute` · `/unban <player>` | Lift (stays on record) |

## Look into
| Command | What |
|---|---|
| `/history <player>` | Past punishments |
| `/mod note <player> <text>` · `/mod notes <player>` | Staff notes |
| `/invsee <player>` | Inventory, also offline |
| `/invsnap <player>` | Inventory history, restore |
| `/chatlog player:<name> last:2h "phrase"` | Search chat |
| `/attempts player:<name>` | What punished players tried |
| `/audit` | Joins, teleports, gamemodes, mod actions |
| `/seen <player>` · `/players` | Last seen, playtime |
| `/spectate <player>` | Watch through their eyes |

## Handle in game
| Command | What |
|---|---|
| `/freeze` · `/unfreeze <player>` | Stop them building |
| `/chat clear` · `/chat freeze` · `/chat slowmode <sec\|off>` | Public chat |
| `/heal` · `/feed` · `/fly <player>` | Fix someone up |
| `/claimadmin` | Claims: overview, delete, transfer, bypass |
| `/worldgate lock\|open\|evacuate <nether\|end>` | Close dimensions |
| `/perf` | Lag: what and where |
| `/orevein [ore] [size]` | Fake ore vein where you look (x-ray bait) |

## Anti-cheat (`/ac` = `/anticheat`)
| Command | What |
|---|---|
| `/ac <player>` | Their checks, log, replays |
| `/ac log <player>` | Flags with time |
| `/ac replay <player>` · `/ac replay stop` | Watch it happen |
| `/ac go <player>` · `/ac back` | Go there, come back |
| `/ac alerts` | Alerts on/off for you |
| `/ac alert off\|on <check>` · `/ac alert list` | Mute one noisy check |
| `/ac fresh <player>` | Restart measuring, keep evidence |
| `/ac exempt <player> <sec>` | Ignore them briefly |

Anti-cheat never punishes on its own. Check the replay, then decide.

## Ranks (admins+)
`/promote <player> [Trial Mod|Mod|Admin]` · `/demote <player>`
