# Lilly SMP — Op commands

`<>` required, `[]` optional. Lengths: `30m`, `2h`, `3d`, `perm`. `[player]` left out = you.

## Start here
| Command | What |
|---|---|
| `/mod <player>` | Everything about a player |
| `/reports` · `/mod report <id>` | Report queue · one report |
| `/staffchat [msg]` | Staff chat (no msg: stay in it) |
| `/vanish [player]` | Invisible |
| `/admin [on\|off\|status\|bypass]` | Admin inventory + mode + bypasses |
| `/hub` | Every feature and its commands |

## Punish
| Command | What |
|---|---|
| `/broke <player> [rule] [what]` | Punish by the rules |
| `/warn <player> [reason]` | Warning |
| `/mute <player> [length] [reason]` · `/unmute <player>` | Mute |
| `/kick <player> [reason]` | Kick |
| `/ban <player> [length] [reason]` · `/unban <player>` | Ban |
| `/freeze <player> [length] [reason]` · `/unfreeze <player>` | No building |
| `/fine <player> <amount> <reason> [victim:<name>]` | Fine |
| `/fine forgive\|revoke <player>` | Drop / undo fine |

## Look into
| Command | What |
|---|---|
| `/history <player>` | Punishments |
| `/mod note <player> <text>` · `/mod notes <player>` | Staff notes |
| `/invsee <player>` | Inventory, also offline |
| `/invsnap [player]` | Inventory history, restore |
| `/chatlog player:<name> last:2h "phrase"` | Search chat |
| `/attempts player:<name>` | What punished players tried |
| `/audit [player] [feature]` | Action log |
| `/seen <player>` · `/players` · `/player <name>` | Profiles |
| `/status` · `/position` · `/ping` · `/effects` · `/hunger` `[player]` | Live info |
| `/near` | Who is near you |
| `/spectate <player>` | Watch through their eyes |

## Anti-cheat (`/ac`)
Detection is GrimAC's; `/ac` collects its flags into levels, alerts and reports. Also in `/reports` → "Anti-cheat suspects",
and on every report card / `/mod <player>`. An x-ray report: "Show their tunnels" takes you there and draws them.

| Command | What |
|---|---|
| `/ac` · `/ac <player>` | Suspects · one player |
| `/ac log <player> [page]` · `/ac info <player>` | Flags · levels |
| `/ac go <player>` · `/ac back` | Go there, come back |
| `/ac alerts` · `/ac verbose` | Alerts on/off · every flag |
| `/ac alert off\|on <check> [global]` · `/ac alert list` | Mute a check |
| `/ac fresh <player>` | Restart measuring, keep evidence |
| `/ac reset <player>` | Delete their record |
| `/ac checks` · `/ac status` | Check list · state |

## Player tools
| Command | What |
|---|---|
| `/heal` · `/feed` · `/breathe` · `/extinguish` `[player]` | Fix up |
| `/fly [player]` · `/speed [player] <0-10>` · `/scale [player] <size>` | Movement / size |
| `/god` · `/ungod` · `/instabreak` · `/instakill` `[player]` | Toggles |
| `/damage` · `/starve` · `/drown` · `/ignite` · `/explode` · `/launch` `<player>` | Hurt |
| `/wipe <player>` | Clear inventory, advancements, XP |
| `/sudo <player> <command\|c:text>` | Run as them |
| `/repair` · `/repairall` `[player]` | Repair |
| `/enchant <ench> [level] [player]` · `/enchant clear [player]` | Enchant |
| `/token sky\|dawn [player] [amount]` | Weather/night tokens |
| `/vein <player> [on\|off]` | Their Veinminer |
| `/role set <player> <role\|none>` · `/role reset <player>` · `/role bypass` | Roles |
| `/quests give <player> <quest>` · `/quests reset <player>` | Quests |

## World
| Command | What |
|---|---|
| `/warp set <name>` · `/warp delete <name>` · `/warp list` | Warps |
| `/setspawn` | Spawn here |
| `/worldgate status` | Nether/End state |
| `/worldgate lock <nether\|end> <drain\|close>` · `open\|evacuate <nether\|end>` | Nether/End access |
| `/orevein [ore] [size]` · `/worldtools` | Bury ore |
| `/mob pack\|wave <creature> [n]` · `/mob stop` | Spawn mobs |
| `/claimadmin` | Claims menu |
| `/claimadmin overview\|bypass\|zone\|flags\|reload` | Claims admin |
| `/claimadmin delete <owner/claim>` · `transfer <owner/claim> <new>` | Claims admin |
| `/claimadmin stick <player>` · `upkeep billnow <all\|player>` | Claims admin |
| `/perf [report\|reports\|show <n>\|fix <n> <finding>\|undo\|alerts]` | Lag |
| `/farms [min]` | Crowded chunks |

## Economy (`/eco`)
| Command | What |
|---|---|
| `/eco give\|take\|set <player> <amount> [reason]` | Balance |
| `/eco reset\|freeze\|unfreeze\|history <player>` | Account |
| `/eco menu` · `/eco tax <percent> [confirm]` | Settings · tax |
| `/eco raffle <prize> <price> [length]` · `/eco giveaway <prize> [length]` | Events |
| `/eco auction stop\|clear` | Auctions |
| `/eco leaderboard\|agent place\|remove` · `/eco dealer place <game>\|remove` | World displays |
| `/eco packs give <player> <pack>` | Packs |

## Chat & server
| Command | What |
|---|---|
| `/chat clear\|freeze` · `/chat slowmode <sec\|off>` | Public chat |
| `/announce <msg>` · `/poll` | Broadcast · poll |
| `/rules edit` · `/rules add <title> \| <text>` · `/rules remove <n>` | Rules |
| `/rules punishment <n> <warn, mute 1h, ban 3d, ban>` | Rule punishments |
| `/nick blocklist` | Blocked nicknames |
| `/maintenance on\|off` · `add\|remove <player>` · `list` | Maintenance |
| `/changelog preview\|publish <id>` | Player news |
| `/settings` · `/settings set <name> <value>` | Server settings |
| `/jobs new\|end <n>\|reload` | Job board |

## Staff
| Command | What |
|---|---|
| `/promote <player> [Trial Mod\|Mod\|Admin]` · `/demote <player>` | Ranks |
| `/protect` · `/unprotect <player>` | Shield from mods (console only) |
| `/lp editor` | LuckPerms web editor |

Anti-cheat never punishes by itself: look at the evidence, then decide.
