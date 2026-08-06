package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Difficulty;
import org.bukkit.Material;

import java.time.Duration;
import java.util.List;

/**
 * What a server owner can decide about a Hunger Games tournament.
 *
 * <h2>Why this is a fraction of the old plugin's 272 keys</h2>
 * The plugin this replaces kept 272 leaf keys in one flat catalogue, and only about a third of them were
 * ever something an owner tunes. The rest was wording ({@code announcements.text.*}, twenty entries — that
 * is what {@code messages.yml} is for), per-item tuning for sixty custom items and their crafting recipes
 * (that is a {@code CustomItem}, one per item, and Core already owns the concept), thirty sound bindings
 * and twenty-two particle specs (Core's {@code ui.effect} — asked for by meaning, so a server that rebinds
 * what a countdown sounds like rebinds it for every plugin, not just this one), stored lists that are data
 * rather than settings (the border phases, the supply-drop schedule, the gamemaster roster — each is its
 * own file under {@code store/}).
 *
 * <p>The HTTP API's own five keys — {@code api.enabled}, {@code api.bind-address}, {@code api.port},
 * {@code api.key} and {@code api.read-only} — were originally planned for a Core transport that was
 * never built: RainsCore opens no socket, on purpose, so the shared foundation cannot be pointed at by a
 * scan the way a module can be told to stand its own API down. The whole HTTP API therefore stays in
 * this module, and its settings stay here with it — see
 * {@code de.raindancer.modules.hungergames.service.HttpApiService}'s class javadoc for what that API is
 * and is not for.
 * Filing every one of those here would have made the settings screen a wall nobody reads rather than a menu
 * anybody can find their way through, and it would have meant duplicating machinery — loot tables, cues,
 * wording lookup — that Core already has one working copy of.
 *
 * <p>What is left, in this record, is the roughly ninety keys that are genuinely "how does this server want
 * its tournament to run": how long a round is, how big the arena starts, whether a gamemaster keeps their
 * OP, whether the deathmatch needs confirming. The record <em>is</em> the schema — {@code config.yml}, its
 * comments, its validation and the {@code /settings} screens all come from it, so there is nothing here to
 * keep in step with anything else and no second list to forget.
 *
 * <h2>Why every component still carries its old {@link Key}</h2>
 * Because a server running the plugin this replaces already has a {@code config.yml} with these paths in
 * it — {@code game.duration}, {@code border.max-edge-speed}, and so on, written by the very
 * {@code HgSettings} catalogue this record is ported from. A key that moves is read as absent on the next
 * load and silently replaced by the shipped default: {@code game.duration}'s default is three hours, so a
 * server that had tuned its rounds down to twenty minutes would wake up to three-hour tournaments with no
 * warning and no error in the log. Keeping the old path is what makes an upgrade an upgrade rather than a
 * reset.
 *
 * <h2>Where everything else went</h2>
 * See {@code HungerGamesSettingsMigrationTest}, which reads the plugin's real key list and asserts that
 * every one of the 272 either lives here at its old path or is named, by hand, in a map of where it went
 * instead. That test is the actual authority on the split; the summary above is the reasoning behind it.
 */
@Settings(id = "hungergames", topics = {
        @Topic(path = "hungergames", title = "Hunger Games", icon = Material.GOLDEN_APPLE),
        @Topic(path = "hungergames/round", title = "The round", icon = Material.CLOCK),
        @Topic(path = "hungergames/arena", title = "Arena", icon = Material.STONE_BRICKS),
        @Topic(path = "hungergames/startup", title = "Start-up sequence", icon = Material.SHULKER_SHELL),
        @Topic(path = "hungergames/lobby", title = "Lobby", icon = Material.GLASS),
        @Topic(path = "hungergames/border", title = "Border", icon = Material.BARRIER),
        @Topic(path = "hungergames/deathmatch", title = "Deathmatch", icon = Material.NETHERITE_SWORD),
        @Topic(path = "hungergames/supply-drops", title = "Supply drops", icon = Material.CHEST),
        @Topic(path = "hungergames/monsters", title = "Monster waves", icon = Material.ZOMBIE_HEAD),
        @Topic(path = "hungergames/teams", title = "Teams", icon = Material.WHITE_BANNER),
        @Topic(path = "hungergames/sponsors", title = "Sponsors", icon = Material.NETHER_STAR),
        @Topic(path = "hungergames/gamemasters", title = "Gamemasters", icon = Material.COMMAND_BLOCK),
        @Topic(path = "hungergames/loot", title = "Loot", icon = Material.SHULKER_BOX),
        @Topic(path = "hungergames/protection", title = "Protection", icon = Material.SHIELD),
        @Topic(path = "hungergames/announcements", title = "Announcements", icon = Material.BELL),
        @Topic(path = "hungergames/api", title = "HTTP API", icon = Material.REDSTONE_TORCH),
        @Topic(path = "hungergames/items", title = "Items", icon = Material.NETHER_STAR),
})
public record HungerGamesSettings(

        // ───────────────────────────────────────────────────────────── general

        @In("hungergames") @Title("Allowed before /init")
        @Describe("Player names allowed onto the server before an admin runs /init. Nobody else may join "
                + "while the arena does not exist yet, so this is how the person setting the tournament up "
                + "gets in ahead of everybody else.")
        @Key("game.pre-init-admins")
        List<String> preInitAdmins,

        // ───────────────────────────────────────────────────────────── the round

        @In("hungergames/round") @Title("Round length (minutes)") @Range(min = 5, max = 20_160)
        @Describe("How long a tournament runs before it is called on time rather than on a winner. Five "
                + "minutes is the floor for a reason: a round is not just play time, it is countdown, "
                + "grace period and the border's own phases stacked on top, and a round shorter than that "
                + "cannot fit them.")
        @Key("game.duration")
        int gameDurationMinutes,

        @In("hungergames/round") @Title("Grace period (seconds)") @Range(min = 0, max = 3_600)
        @Describe("How long tributes are invulnerable after the round starts. Long enough and the opening "
                + "scramble at the cornucopia is a footrace rather than a fight; zero removes the "
                + "protection entirely.")
        @Key("game.grace-period")
        int gracePeriodSeconds,

        @In("hungergames/round") @Title("Countdown (seconds)") @Range(min = 3, max = 300)
        @Describe("How long the countdown before the round starts runs for, once everybody is on their "
                + "platform. Below three seconds is not a countdown anybody can react to; it is a surprise.")
        @Key("game.countdown")
        int countdownSeconds,

        @In("hungergames/round") @Title("Preparation share (%)") @Range(min = 0, max = 90)
        @Describe("The share of the round's total length spent before the border starts its first phase. "
                + "Ninety percent is the ceiling because a border with no time left to move at all is a "
                + "border setting nobody chose on purpose.")
        @Key("game.prep-time-percent")
        int prepTimePercent,

        @In("hungergames/round") @Title("Difficulty once running")
        @Describe("The world's difficulty from the moment the round starts. NORMAL by default, because a "
                + "Hunger Games round with no hostile mobs at all is missing half of what makes the border "
                + "phases tense.")
        @Key("game.difficulty")
        Difficulty gameDifficulty,

        @In("hungergames/round") @Title("Difficulty while preparing")
        @Describe("The world's difficulty during preflight and the lobby, before the round itself starts. "
                + "PEACEFUL by default, so nobody gets killed by a zombie while still choosing a team.")
        @Key("game.preflight-difficulty")
        Difficulty preflightDifficulty,

        @In("hungergames/round") @Title("On elimination")
        @Describe("What happens to a tribute the moment they are eliminated. SPECTATOR lets them keep "
                + "watching the round they were just in; KICK and BAN are for servers that want an "
                + "elimination to mean leaving.")
        @Key("game.death-action")
        DeathAction deathAction,

        @In("hungergames/round") @Title("Disconnect elimination (minutes)") @Range(min = 0, max = 1_440)
        @Describe("Eliminates a tribute who has been disconnected longer than this. Zero switches the rule "
                + "off entirely, which is also why a disconnected player otherwise stays ALIVE and "
                + "un-eliminated indefinitely — winner logic has to work whether or not everybody is online "
                + "to see it decided.")
        @Key("game.disconnect-elimination")
        int disconnectEliminationMinutes,

        @In("hungergames/round") @Title("Server downtime during a round")
        @Describe("Whether time the server was offline counts as elapsed round time when a mid-round "
                + "session is restored. PAUSE is the safe default: a server that crashed for an hour should "
                + "not hand back a round with an hour less time left than the players remember agreeing to.")
        @Key("game.restart.offline-time-policy")
        OfflineTimePolicy offlineTimePolicy,

        @In("hungergames/round") @Title("Deop OP tributes on start")
        @Describe("Whether a tribute who is a server operator is automatically deopped the moment the "
                + "round moves to RUNNING. Without this, an admin playing in their own tournament can use "
                + "OP commands nobody else at the table can.")
        @Key("game.admin-participants.deop-on-start")
        boolean adminDeopOnStart,

        @In("hungergames/round") @Title("Re-op on elimination")
        @Describe("Whether a tribute deopped for the round above gets their OP status back the moment "
                + "they are eliminated, rather than waiting for the round to finish.")
        @Key("game.admin-participants.reop-on-elimination")
        boolean adminReopOnElimination,

        @In("hungergames/round") @Title("Restore OP on finish")
        @Describe("Whether every saved OP status is restored once the round is over, as a final "
                + "safety net independent of the two settings above.")
        @Key("game.admin-participants.reop-on-finish")
        boolean adminReopOnFinish,

        @In("hungergames/round") @Title("Creative mode on elimination")
        @Describe("Whether an eliminated admin tribute is switched to creative mode rather than left in "
                + "whatever gamemode elimination found them in.")
        @Key("game.admin-participants.set-creative-on-elimination")
        boolean adminCreativeOnElimination,

        @In("hungergames/round") @Title("Teleport to centre on elimination")
        @Describe("Whether an eliminated admin tribute is teleported above the arena's centre, out of the "
                + "way of tributes still playing.")
        @Key("game.admin-participants.teleport-to-center-on-elimination")
        boolean adminTeleportCenterOnElimination,

        @In("hungergames/round") @Title("Height above centre") @Range(min = 0, max = 320)
        @Describe("How far above the arena's centre the teleport above lands them, in blocks.")
        @Key("game.admin-participants.center-y-offset")
        int adminCenterYOffset,

        @In("hungergames/round") @Title("Keep a round log")
        @Describe("Whether kills, drops, purchases and admin actions are written to a log file for the "
                + "round, so a dispute the next day can be settled by reading what actually happened rather "
                + "than by memory.")
        @Key("events.round-log.enabled")
        boolean roundLogEnabled,

        @In("hungergames/round") @Title("One file per round")
        @Describe("Whether each round gets its own log file. Off pools every round into one file, which "
                + "is harder to hand somebody asking about a single evening.")
        @Key("events.round-log.file-per-round")
        boolean roundLogFilePerRound,

        @In("hungergames/round") @Title("Log coordinates")
        @Describe("Whether kill and event coordinates are written into the log. Off by default: exact "
                + "positions are rarely needed to settle a dispute and are one more thing to redact before "
                + "sharing a log publicly.")
        @Key("events.round-log.include-coordinates")
        boolean roundLogIncludeCoordinates,

        // ───────────────────────────────────────────────────────────── arena

        @In("hungergames/arena") @Title("Minimum platform gap") @Range(min = 0, max = 64)
        @Describe("The smallest gap, in blocks, left between neighbouring tribute platforms when the arena "
                + "is laid out.")
        @Key("arena.platform-min-gap")
        int platformMinGap,

        @In("hungergames/arena") @Title("Platform width") @Range(min = 1, max = 64)
        @Describe("The width, in blocks, of the platform schematic each tribute starts on — used to work "
                + "out how far apart platforms need to be.")
        @Key("arena.platform-width")
        int platformWidth,

        @In("hungergames/arena") @Title("Underground room height") @Range(min = 1, max = 64)
        @Describe("The interior height, in blocks, of the circular room below the arena that the launch "
                + "tubes lead from.")
        @Key("arena.underground-room-height")
        int undergroundRoomHeight,

        @In("hungergames/arena") @Title("Underground room extra radius") @Range(min = 0, max = 64)
        @Describe("How far the underground room extends past the ring of platforms above it, in blocks.")
        @Key("arena.underground-room-extra-radius")
        int undergroundRoomExtraRadius,

        @In("hungergames/arena") @Title("Tube depth") @Range(min = 1, max = 320)
        @Describe("How deep the launch tubes run, in blocks, when the schematic's own height cannot be "
                + "read — a fallback rather than the normal source of truth.")
        @Key("arena.tube-depth")
        int tubeDepth,

        @In("hungergames/arena") @Title("Block Nether portals")
        @Describe("Whether Nether portals outside the allowed radius below are blocked from lighting at "
                + "all, so a tribute cannot build a shortcut out of the arena.")
        @Key("arena.block-nether-portals")
        boolean blockNetherPortals,

        @In("hungergames/arena") @Title("Nether portal radius") @Range(min = 0, max = 1_000)
        @Describe("The radius, in blocks around the arena centre, where a Nether portal is still allowed "
                + "when the setting above is on.")
        @Key("arena.nether-allow-radius")
        int netherAllowRadius,

        @In("hungergames/arena") @Title("Block End portals")
        @Describe("Whether End portals are blocked entirely, so a round cannot be shortcut into the End.")
        @Key("arena.block-end-portals")
        boolean blockEndPortals,

        // ───────────────────────────────────────────────────────────── the start-up sequence

        @In("hungergames/startup") @Title("Lamp delay (ticks)") @Range(min = 1, max = 200)
        @Describe("How long between one ring of the underground room's redstone lamps lighting and the "
                + "next. The lights come on from the middle outwards while tributes are standing at the "
                + "foot of their tubes, and this is how fast that ripple travels — five ticks is a quarter "
                + "of a second per ring.")
        @Key("startup.lamp-delay")
        int startupLampDelay,

        @In("hungergames/startup") @Title("Pause before levitation (ticks)") @Range(min = 0, max = 1_200)
        @Describe("How long the lights are left to finish before the first tribute is lifted. Two seconds "
                + "by default, which is the beat between the room being lit and anybody moving.")
        @Key("startup.levitation-start-delay")
        int startupLevitationStartDelay,

        @In("hungergames/startup") @Title("Between tributes (ticks)") @Range(min = 0, max = 200)
        @Describe("How long between one tribute being launched up their tube and the next. Tributes go up "
                + "one at a time rather than all at once, so the crowd watching sees them arrive in "
                + "sequence — at zero they all rise together and the sequence is over in one second.")
        @Key("startup.player-levitation-delay")
        int startupPlayerLevitationDelay,

        @In("hungergames/startup") @Title("Levitation strength") @Range(min = 0, max = 10)
        @Describe("How strongly a tribute is lifted up their tube. Higher is faster; too high and they "
                + "overshoot the platform before the arrival check catches them.")
        @Key("startup.levitation-amplifier")
        int startupLevitationAmplifier,

        // ───────────────────────────────────────────────────────────── lobby

        @In("hungergames/lobby") @Title("Height above arena") @Range(min = 1, max = 320)
        @Describe("How far above the arena's centre the lobby floats, in blocks — high enough that "
                + "tributes waiting there cannot see the arena being built underneath them.")
        @Key("lobby.height-offset")
        int lobbyHeightOffset,

        @In("hungergames/lobby") @Title("Lobby width") @Range(min = 1, max = 256)
        @Describe("The width, in blocks, of the glass box tributes wait in before a round.")
        @Key("lobby.width")
        int lobbyWidth,

        @In("hungergames/lobby") @Title("Lobby depth") @Range(min = 1, max = 256)
        @Describe("The depth, in blocks, of the lobby's glass box.")
        @Key("lobby.depth")
        int lobbyDepth,

        @In("hungergames/lobby") @Title("Lobby height") @Range(min = 1, max = 256)
        @Describe("The interior height, in blocks, of the lobby's glass box.")
        @Key("lobby.height")
        int lobbyHeight,

        @In("hungergames/lobby") @Title("Wall material")
        @Describe("What the lobby's walls are built from. Glass by default, so tributes waiting inside can "
                + "see the arena and each other rather than staring at a blank box.")
        @Key("lobby.block-type")
        Material lobbyBlockType,

        // ───────────────────────────────────────────────────────────── border

        @In("hungergames/border") @Title("Starting diameter") @Range(min = 1, max = 1_000_000)
        @Describe("The world border's diameter, in blocks, when a round starts. Applied to both the "
                + "Overworld and, scaled, the Nether.")
        @Key("border.initial-size")
        int borderInitialSize,

        @In("hungergames/border") @Title("Smallest the border may ever be") @Range(min = 1, max = 100_000)
        @Describe("The absolute floor on the border's size, in blocks. No phase, however it is configured, "
                + "may shrink the border below this — it is the one number every phase's own target is "
                + "checked against.")
        @Key("border.minimum-size")
        double borderMinimumSize,

        @In("hungergames/border") @Title("Fastest an edge may move") @Range(min = 0, max = 100)
        @Describe("The fairness ceiling, in blocks per second per edge. This is not how fast the border "
                + "usually moves — it is the speed no phase, however aggressively configured, is allowed to "
                + "exceed. The default of 1.25 is set by the tribute who cannot run: somebody walled in by "
                + "stone digs out at about 1.33 blocks per second with an iron pickaxe, so anything above "
                + "that makes a hillside a death sentence rather than an obstacle. Raise it for a flat "
                + "arena, or deliberately, if hiding in a hole should not be a strategy.")
        @Key("border.max-edge-speed")
        double borderMaxEdgeSpeed,

        @In("hungergames/border") @Title("Scale the Nether border")
        @Describe("Whether the Nether's border is kept at the usual eighths scale of the Overworld's, "
                + "moving with it automatically.")
        @Key("border.scale-nether")
        boolean borderScaleNether,

        @In("hungergames/border") @Title("Preparation warnings (minutes before)")
        @Describe("How many minutes before the border's first phase a warning is announced, one entry per "
                + "warning. Several entries give tributes more than one chance to notice the clock.")
        @Key("border.prep-warnings")
        List<String> borderPrepWarnings,

        @In("hungergames/border") @Title("Shrink warning (seconds)") @Range(min = 0, max = 3_600)
        @Describe("How many seconds before each individual shrink begins that a warning is given.")
        @Key("border.shrink-warning")
        int borderShrinkWarning,

        // ───────────────────────────────────────────────────────────── deathmatch

        @In("hungergames/deathmatch") @Title("Deathmatch feature enabled")
        @Describe("Whether the deathmatch can be triggered at all this round, manually or otherwise.")
        @Key("deathmatch.enabled")
        boolean deathmatchEnabled,

        @In("hungergames/deathmatch") @Title("Manual start only")
        @Describe("Off starts the deathmatch automatically once two tributes remain. On, a gamemaster has "
                + "to call it — useful for a tournament where the finish is meant to be an occasion, not an "
                + "automatic trigger nobody in the crowd sees coming.")
        @Key("deathmatch.manual-only")
        boolean deathmatchManualOnly,

        @In("hungergames/deathmatch") @Title("Target border size") @Range(min = 1, max = 100_000)
        @Describe("The diameter, in blocks, the border is set to for the deathmatch.")
        @Key("deathmatch.target-border-size")
        int deathmatchTargetBorderSize,

        @In("hungergames/deathmatch") @Title("Warning before start (seconds)") @Range(min = 0, max = 3_600)
        @Describe("How many seconds' warning is given before the deathmatch actually begins.")
        @Key("deathmatch.warning-seconds")
        int deathmatchWarningSeconds,

        @In("hungergames/deathmatch") @Title("Teleport to centre")
        @Describe("Whether every surviving tribute is teleported to the arena's centre when the deathmatch "
                + "starts, rather than being left to walk there through whatever remains of the border.")
        @Key("deathmatch.teleport-to-center")
        boolean deathmatchTeleportToCenter,

        @In("hungergames/deathmatch") @Title("Teleport height offset") @Range(min = 0, max = 320)
        @Describe("How far above the centre the teleport above lands tributes, in blocks.")
        @Key("deathmatch.teleport-y-offset")
        int deathmatchTeleportYOffset,

        @In("hungergames/deathmatch") @Title("Grace after teleport (seconds)") @Range(min = 0, max = 3_600)
        @Describe("A short window of invulnerability after the deathmatch teleport, so nobody is hit "
                + "before they can even see who else arrived.")
        @Key("deathmatch.grace-after-teleport")
        int deathmatchGraceAfterTeleportSeconds,

        @In("hungergames/deathmatch") @Title("Require confirmation")
        @Describe("Whether starting the deathmatch shows a confirmation screen first, so it cannot be "
                + "triggered by a misclick in the heat of the moment.")
        @Key("deathmatch.require-confirmation")
        boolean deathmatchRequireConfirmation,

        @In("hungergames/deathmatch") @Title("Phases it may be started in")
        @Describe("The game phases in which a gamemaster is allowed to start the deathmatch. RUNNING only "
                + "by default — starting one during the lobby would not mean anything.")
        @Key("deathmatch.allowed-phases")
        List<String> deathmatchAllowedPhases,

        @In("hungergames/deathmatch") @Title("Broadcast warning and start")
        @Describe("Whether the deathmatch warning and start are announced to everyone.")
        @Key("deathmatch.broadcast-enabled")
        boolean deathmatchBroadcastEnabled,

        @In("hungergames/deathmatch") @Title("Play sounds")
        @Describe("Whether the deathmatch warning and start play sounds, in addition to any broadcast.")
        @Key("deathmatch.sound-enabled")
        boolean deathmatchSoundEnabled,

        // ───────────────────────────────────────────────────────────── supply drops

        @In("hungergames/supply-drops") @Title("Supply drops enabled")
        @Describe("Whether timed Capitol supply drops happen at all this round. The times themselves are "
                + "data, not a setting, and live in the module's own schedule store beside the border "
                + "phases.")
        @Key("events.supply-drops.enabled")
        boolean supplyDropsEnabled,

        @In("hungergames/supply-drops") @Title("Warning before landing (seconds)") @Range(min = 0, max = 3_600)
        @Describe("How many seconds' notice is given before a supply drop lands.")
        @Key("events.supply-drops.warning-seconds")
        int supplyDropWarningSeconds,

        @In("hungergames/supply-drops") @Title("Crates per drop") @Range(min = 1, max = 10)
        @Describe("How many crates land at each scheduled drop time.")
        @Key("events.supply-drops.drop-count")
        int supplyDropCount,

        @In("hungergames/supply-drops") @Title("Nearest landing distance") @Range(min = 0, max = 100_000)
        @Describe("The closest, in blocks from the arena centre, a supply drop is allowed to land.")
        @Key("events.supply-drops.radius-min")
        int supplyDropRadiusMin,

        @In("hungergames/supply-drops") @Title("Furthest landing distance") @Range(min = 1, max = 100_000)
        @Describe("The furthest, in blocks from the arena centre, a supply drop is allowed to land.")
        @Key("events.supply-drops.radius-max")
        int supplyDropRadiusMax,

        @In("hungergames/supply-drops") @Title("Overworld only")
        @Describe("Whether supply drops are restricted to landing in the Overworld.")
        @Key("events.supply-drops.only-overworld")
        boolean supplyDropOnlyOverworld,

        @In("hungergames/supply-drops") @Title("Announce coordinates")
        @Describe("Whether the landing coordinates are named in the broadcast, rather than only the "
                + "warning that a drop is coming.")
        @Key("events.supply-drops.announce-coordinates")
        boolean supplyDropAnnounceCoordinates,

        @In("hungergames/supply-drops") @Title("Coordinate fuzz") @Range(min = 0, max = 1_000)
        @Describe("How far, in blocks, the announced coordinates may be off from the true landing spot. "
                + "Zero announces the exact location; anything higher turns the announcement into a rough "
                + "direction rather than a marker on the map.")
        @Key("events.supply-drops.coordinate-fuzz")
        int supplyDropCoordinateFuzz,

        @In("hungergames/supply-drops") @Title("Beacon at the landing site")
        @Describe("Whether a light beam marks where a supply drop has landed.")
        @Key("events.supply-drops.beacon-enabled")
        boolean supplyDropBeaconEnabled,

        @In("hungergames/supply-drops") @Title("Base material")
        @Describe("What the three-by-three base under a supply drop's beacon is built from.")
        @Key("events.supply-drops.base-material")
        Material supplyDropBaseMaterial,

        @In("hungergames/supply-drops") @Title("Protect the crate and beacon")
        @Describe("Whether the supply-drop crate, beacon and base are protected from being broken. Opening "
                + "the crate stays possible either way — this only stops it, and what it stands on, being "
                + "mined out from under it. Gamemasters can still take it apart.")
        @Key("events.supply-drops.protected")
        boolean supplyDropProtected,

        @In("hungergames/supply-drops") @Title("Firework on landing")
        @Describe("Whether a firework marks the moment a supply drop lands.")
        @Key("events.supply-drops.firework-enabled")
        boolean supplyDropFireworkEnabled,

        @In("hungergames/supply-drops") @Title("Particles on landing")
        @Describe("Whether particle effects mark the landing site in addition to, or instead of, the "
                + "firework above.")
        @Key("events.supply-drops.particles-enabled")
        boolean supplyDropParticlesEnabled,

        // ───────────────────────────────────────────────────────────── monster waves

        @In("hungergames/monsters") @Title("Default monster")
        @Describe("The monster type a gamemaster gets when they call a wave without naming one — a Bukkit "
                + "entity type name such as ZOMBIE, SKELETON or SPIDER.")
        @Key("events.monster-waves.default-mob")
        String monsterWaveDefaultMob,

        @In("hungergames/monsters") @Title("Monsters per wave") @Range(min = 1, max = 100)
        @Describe("How many monsters spawn in a single wave.")
        @Key("events.monster-waves.count-per-wave")
        int monsterWaveCountPerWave,

        @In("hungergames/monsters") @Title("Waves") @Range(min = 1, max = 100)
        @Describe("How many waves a gamemaster's call spawns in total.")
        @Key("events.monster-waves.wave-count")
        int monsterWaveWaveCount,

        @In("hungergames/monsters") @Title("Seconds between waves") @Range(min = 1, max = 3_600)
        @Describe("How long between one wave and the next.")
        @Key("events.monster-waves.interval-seconds")
        int monsterWaveIntervalSeconds,

        @In("hungergames/monsters") @Title("Spawn spread") @Range(min = 0, max = 64)
        @Describe("How far, in blocks, a wave's monsters are scattered around the chosen spawn point "
                + "rather than stacked on the exact same block.")
        @Key("events.monster-waves.spread")
        int monsterWaveSpread,

        // ───────────────────────────────────────────────────────────── gamemasters

        @In("hungergames/gamemasters") @Title("Gamemaster mode enabled")
        @Describe("Whether the gamemaster mode can be turned on at all.")
        @Key("gamemaster.enabled")
        boolean gamemasterEnabled,

        @In("hungergames/gamemasters") @Title("Mode on activation")
        @Describe("The game mode somebody is switched to the moment they turn on gamemaster mode.")
        @Key("gamemaster.default-mode")
        GamemasterMode gamemasterDefaultMode,

        @In("hungergames/gamemasters") @Title("Keep OP")
        @Describe("Whether gamemasters keep their operator status, unlike the tributes handled by the "
                + "admin-participant settings above.")
        @Key("gamemaster.keep-op")
        boolean gamemasterKeepOp,

        @In("hungergames/gamemasters") @Title("Allow the teleport menu")
        @Describe("Whether gamemasters may use the menu that teleports them to any tribute.")
        @Key("gamemaster.allow-teleport-menu")
        boolean gamemasterAllowTeleportMenu,

        @In("hungergames/gamemasters") @Title("Hide from player counts")
        @Describe("Whether gamemasters are left out of displays that count how many players remain, so "
                + "watching staff do not read as tributes still alive.")
        @Key("gamemaster.hide-from-player-count")
        boolean gamemasterHideFromPlayerCount,

        @In("hungergames/gamemasters") @Title("How gamemasters are recognised")
        @Describe("PERMISSION checks only the permission node; LIST checks only the roster kept in the "
                + "gamemaster store; BOTH accepts either. The roster itself is data, not a setting, and "
                + "lives in its own store.")
        @Key("gamemaster.permission-mode")
        GamemasterPermissionMode gamemasterPermissionMode,

        // ───────────────────────────────────────────────────────────── loot

        // ───────────────────────────────────────────────────────────── teams

        @In("hungergames/teams") @Title("Largest team") @Range(min = 0, max = 100)
        @Describe("The most tributes one team may hold. Zero means no limit at all — which is what a "
                + "free-for-all with nominal teams wants, and what a duo tournament very much does not.")
        @Key("teams.max-size")
        int teamMaxSize,

        @In("hungergames/teams") @Title("Most teams") @Range(min = 0, max = 100)
        @Describe("The most teams that may exist at once. Zero means no limit. Bear the colour count in "
                + "mind: with exclusive colours on, more teams than there are identities is a team nobody "
                + "can create.")
        @Key("teams.max-teams")
        int teamMaxTeams,

        @In("hungergames/teams") @Title("Allow switching")
        @Describe("Whether somebody already on a team may move to another one, up until teams freeze. Off "
                + "makes the first choice final, which is what a drafted tournament wants.")
        @Key("teams.allow-switching")
        boolean teamAllowSwitching,

        @In("hungergames/teams") @Title("Captains")
        @Describe("Whether each team has a captain who may rename it, recolour it and admit members.")
        @Key("teams.captain-enabled")
        boolean teamCaptainEnabled,

        @In("hungergames/teams") @Title("Players may create teams")
        @Describe("Whether tributes may make their own teams from the team page, rather than only joining "
                + "ones a gamemaster has set up.")
        @Key("teams.players-can-create")
        boolean teamPlayersCanCreate,

        @In("hungergames/teams") @Title("Players may pick the colour")
        @Describe("Whether tributes and captains may choose their team's colour and emblem, or whether "
                + "that is a gamemaster's to assign.")
        @Key("teams.players-choose-color")
        boolean teamPlayersChooseColour,

        @In("hungergames/teams") @Title("Teams freeze from")
        @Describe("The phase from which teams can no longer be changed. STARTUP by default, which is the "
                + "last moment before tributes are taken to their platforms — after that, changing a team "
                + "would move somebody who is already standing somewhere.")
        @Key("teams.lock-phase")
        String teamLockPhase,

        // ───────────────────────────────────────────────────────────── sponsors: the system

        @In("hungergames/sponsors") @Title("Sponsors enabled")
        @Describe("Whether the sponsor system runs at all — tokens and beacons together. Off silences "
                + "everything below it regardless of their own settings.")
        @Key("sponsors.enabled")
        boolean sponsorsEnabled,

        // ───────────────────────────────────────────────────────────── sponsors: tokens

        @In("hungergames/sponsors") @Title("Tokens enabled")
        @Describe("Whether tributes earn sponsor tokens for surviving.")
        @Key("sponsors.tokens.enabled")
        boolean sponsorTokensEnabled,

        @In("hungergames/sponsors") @Title("Token material")
        @Describe("What a sponsor token looks like in an inventory.")
        @Key("sponsors.tokens.material")
        Material sponsorTokenMaterial,

        @In("hungergames/sponsors") @Title("Token name")
        @Describe("The token item's display name. MiniMessage, so <gold>…</gold> rather than a section sign.")
        @Key("sponsors.tokens.display-name")
        String sponsorTokenName,

        @In("hungergames/sponsors") @Title("Token lore")
        @Describe("The token item's description lines, one per entry.")
        @Key("sponsors.tokens.lore")
        List<String> sponsorTokenLore,

        @In("hungergames/sponsors") @Title("Between token waves (minutes)") @Range(min = 1, max = 1_440)
        @Describe("How long between one round of sponsor tokens and the next.")
        @Key("sponsors.tokens.interval")
        int sponsorTokenIntervalMinutes,

        @In("hungergames/sponsors") @Title("Tokens per wave") @Range(min = 1, max = 64)
        @Describe("How many tokens each surviving tribute is given per wave.")
        @Key("sponsors.tokens.amount-per-interval")
        int sponsorTokenAmountPerInterval,

        @In("hungergames/sponsors") @Title("First tokens after (minutes)") @Range(min = 0, max = 1_440)
        @Describe("How long into a round the first sponsor tokens arrive. Long enough that the opening "
                + "scramble is fought with what the arena provided rather than with what was bought.")
        @Key("sponsors.tokens.first-token-after")
        int sponsorTokenFirstAfterMinutes,

        @In("hungergames/sponsors") @Title("Most tokens per tribute") @Range(min = 0, max = 10_000)
        @Describe("The most any one tribute may earn over a whole round. Zero means no cap.")
        @Key("sponsors.tokens.max-per-player")
        int sponsorTokenMaxPerPlayer,

        @In("hungergames/sponsors") @Title("Only living tributes")
        @Describe("Whether tokens go only to tributes who are still in the round. Off pays spectators, "
                + "which is only sensible if they can spend them on somebody.")
        @Key("sponsors.tokens.only-alive-players")
        boolean sponsorTokenOnlyAlive,

        @In("hungergames/sponsors") @Title("Tell the recipient")
        @Describe("Whether a tribute is told personally when tokens arrive.")
        @Key("sponsors.tokens.announce-personal")
        boolean sponsorTokenAnnouncePersonal,

        @In("hungergames/sponsors") @Title("Broadcast milestones")
        @Describe("Whether token milestones are announced to everybody. Off by default: how rich a tribute "
                + "is happens to be tactical information.")
        @Key("sponsors.tokens.broadcast-milestones")
        boolean sponsorTokenBroadcastMilestones,

        @In("hungergames/sponsors") @Title("Tokens drop on death")
        @Describe("Whether a tribute's unspent tokens drop like any other item when they die — so whoever "
                + "killed them can spend them.")
        @Key("sponsors.tokens.drop-on-death")
        boolean sponsorTokenDropOnDeath,

        @In("hungergames/sponsors") @Title("Clear on elimination")
        @Describe("Whether tokens are destroyed when a tribute is eliminated. Takes precedence over "
                + "dropping them.")
        @Key("sponsors.tokens.clear-on-elimination")
        boolean sponsorTokenClearOnElimination,

        @In("hungergames/sponsors") @Title("Reset the count each round")
        @Describe("Whether the per-tribute earned-token count resets when a new round starts.")
        @Key("sponsors.tokens.clear-on-round-reset")
        boolean sponsorTokenClearOnRoundReset,

        // ───────────────────────────────────────────────────────────── sponsors: beacons

        @In("hungergames/sponsors") @Title("Beacons enabled")
        @Describe("Whether sponsor beacons — the stations tokens are spent at — appear at all.")
        @Key("sponsors.beacons.enabled")
        boolean sponsorBeaconsEnabled,

        @In("hungergames/sponsors") @Title("How beacons appear")
        @Describe("DISABLED places none; CENTER puts one near the cornucopia; RANDOM_TIMED scatters them "
                + "through the round on a schedule; MANUAL leaves it entirely to a gamemaster.")
        @Key("sponsors.beacons.spawn-mode")
        BeaconSpawnMode sponsorBeaconSpawnMode,

        @In("hungergames/sponsors") @Title("One at the middle on start")
        @Describe("Whether a beacon is placed near the cornucopia the moment the round starts, whatever "
                + "the mode above says. Combines with RANDOM_TIMED.")
        @Key("sponsors.beacons.center-on-start")
        boolean sponsorBeaconCentreOnStart,

        @In("hungergames/sponsors") @Title("Beacon block")
        @Describe("What a sponsor beacon is built from.")
        @Key("sponsors.beacons.material")
        Material sponsorBeaconMaterial,

        @In("hungergames/sponsors") @Title("Beacon base")
        @Describe("What the three-by-three base under a sponsor beacon is built from.")
        @Key("sponsors.beacons.base-material")
        Material sponsorBeaconBaseMaterial,

        @In("hungergames/sponsors") @Title("Protect beacons")
        @Describe("Whether a sponsor beacon and its base are safe from being broken. Using one stays "
                + "possible either way; gamemasters can still take it apart.")
        @Key("sponsors.beacons.protected")
        boolean sponsorBeaconProtected,

        @In("hungergames/sponsors") @Title("Nearest beacon distance") @Range(min = 0, max = 100_000)
        @Describe("The closest to the middle a scattered beacon may appear, in blocks.")
        @Key("sponsors.beacons.radius-min")
        int sponsorBeaconRadiusMin,

        @In("hungergames/sponsors") @Title("Furthest beacon distance") @Range(min = 1, max = 100_000)
        @Describe("The furthest from the middle a scattered beacon may appear, in blocks. Keep it inside "
                + "the border's own size at that point in the round, or the beacon lands outside the arena.")
        @Key("sponsors.beacons.radius-max")
        int sponsorBeaconRadiusMax,

        @In("hungergames/sponsors") @Title("Beacon schedule")
        @Describe("When beacons appear in RANDOM_TIMED mode, one entry per spawn — written the way people "
                + "say it: 15m, 35m, 1h.")
        @Key("sponsors.beacons.spawn-schedule")
        List<String> sponsorBeaconSchedule,

        @In("hungergames/sponsors") @Title("Most beacons at once") @Range(min = 1, max = 100)
        @Describe("The most sponsor beacons that may stand at the same time. A new one past this replaces "
                + "the oldest rather than adding to the pile.")
        @Key("sponsors.beacons.max-active")
        int sponsorBeaconMaxActive,

        @In("hungergames/sponsors") @Title("Announce beacon spawns")
        @Describe("Whether a beacon appearing is announced to everybody.")
        @Key("sponsors.beacons.announce-spawn")
        boolean sponsorBeaconAnnounceSpawn,

        @In("hungergames/sponsors") @Title("Announce beacon coordinates")
        @Describe("Whether the announcement names where the beacon is, rather than only that one exists.")
        @Key("sponsors.beacons.announce-coordinates")
        boolean sponsorBeaconAnnounceCoordinates,

        @In("hungergames/sponsors") @Title("Beacon coordinate fuzz") @Range(min = 0, max = 1_000)
        @Describe("How far the announced beacon coordinates may be off, in blocks. Zero announces the exact "
                + "spot; anything higher makes it a direction to search in rather than a marker.")
        @Key("sponsors.beacons.coordinate-fuzz")
        int sponsorBeaconCoordinateFuzz,

        @In("hungergames/sponsors") @Title("Beacon particles")
        @Describe("Whether particles mark an active sponsor beacon, so it can be found from a distance.")
        @Key("sponsors.beacons.particles-enabled")
        boolean sponsorBeaconParticles,

        @In("hungergames/sponsors") @Title("Beacon spawn sound")
        @Describe("Whether a sound plays when a sponsor beacon appears.")
        @Key("sponsors.beacons.sound-enabled")
        boolean sponsorBeaconSound,

        // ───────────────────────────────────────────────────────────── sponsors: the shop

        @In("hungergames/sponsors") @Title("Shop enabled")
        @Describe("Whether the shop at a sponsor beacon can be opened at all.")
        @Key("sponsors.shop.enabled")
        boolean sponsorShopEnabled,

        @In("hungergames/sponsors") @Title("Shop entries")
        @Describe("What the shop sells, one entry per line as 'id|reward|cost|name'. The reward is "
                + "MATERIAL:COUNT, EFFECT:TYPE:SECONDS:STRENGTH, ITEM:CUSTOM_ID:COUNT or "
                + "POTION:VARIANT:TYPE:COUNT. Editable from the shop admin screen, which is easier to get "
                + "right than this line is.")
        @Key("sponsors.shop.items")
        List<String> sponsorShopItems,

        @In("hungergames/loot") @Title("Container scan radius") @Range(min = 1, max = 1_000)
        @Describe("How far, in blocks around the arena centre, containers are searched for and filled "
                + "with loot when the arena is prepared.")
        @Key("loot.scan-radius")
        int lootScanRadius,

        @In("hungergames/loot") @Title("Container scan height") @Range(min = 1, max = 320)
        @Describe("The vertical range, in blocks up and down from the arena centre, that the container "
                + "scan covers.")
        @Key("loot.scan-y-range")
        int lootScanYRange,

        @In("hungergames/loot") @Title("Loot editor enabled")
        @Describe("Whether the in-game loot table editor is available at all.")
        @Key("loot.editor.enabled")
        boolean lootEditorEnabled,

        @In("hungergames/loot") @Title("Allow edits mid-round")
        @Describe("Whether loot tables can still be edited while a round is running, rather than only "
                + "between rounds.")
        @Key("loot.editor.allow-runtime-edits")
        boolean lootEditorAllowRuntimeEdits,

        @In("hungergames/loot") @Title("Back up before saving")
        @Describe("Whether a backup of the loot data is made every time the editor saves, so a bad edit "
                + "can be undone by hand.")
        @Key("loot.editor.backup-before-save")
        boolean lootEditorBackupBeforeSave,

        @In("hungergames/loot") @Title("Maximum test rolls") @Range(min = 1, max = 10_000)
        @Describe("The most simulated rolls the editor's test tool will run in one go, so somebody testing "
                + "a table cannot ask for a number large enough to stall the server.")
        @Key("loot.editor.max-test-rolls")
        int lootEditorMaxTestRolls,

        @In("hungergames/loot") @Title("Allow test-give")
        @Describe("Whether the editor may hand test loot straight into the tester's own inventory.")
        @Key("loot.editor.allow-test-give")
        boolean lootEditorAllowTestGive,

        @In("hungergames/loot") @Title("Allow test chest")
        @Describe("Whether the editor may fill a chest at the tester's own location with test loot.")
        @Key("loot.editor.allow-test-chest")
        boolean lootEditorAllowTestChest,

        // ───────────────────────────────────────────────────────────── protection

        @In("hungergames/protection") @Title("Cornucopia radius") @Range(min = 1, max = 1_000)
        @Describe("The radius, in blocks around the arena centre, that the cornucopia protection settings "
                + "below apply to.")
        @Key("protection.cornucopia-radius")
        int cornucopiaRadius,

        @In("hungergames/protection") @Title("Protect before the round runs")
        @Describe("Whether the cornucopia area is protected from building and breaking during preflight "
                + "and the lobby, before the round itself starts.")
        @Key("protection.cornucopia.before-running")
        boolean protectCornucopiaBeforeRunning,

        @In("hungergames/protection") @Title("Protect while the round runs")
        @Describe("Whether the cornucopia area stays protected once the round is RUNNING. Off by default: "
                + "the whole point of the cornucopia is that tributes fight over what is in it, which means "
                + "chests being opened and, usually, broken.")
        @Key("protection.cornucopia.during-running")
        boolean protectCornucopiaDuringRunning,

        @In("hungergames/protection") @Title("Protect after the round finishes")
        @Describe("Whether the cornucopia area is protected again once the round has finished.")
        @Key("protection.cornucopia.after-game")
        boolean protectCornucopiaAfterGame,

        @In("hungergames/protection") @Title("Bypass permission")
        @Describe("The permission node that lets somebody past the protection rules above. Kept "
                + "configurable because a server may already use this exact node for something else and "
                + "not want the tournament fighting it for the name.")
        @Key("protection.bypass-permission")
        String protectionBypassPermission,

        // ───────────────────────────────────────────────────────────── announcements

        @In("hungergames/announcements") @Title("Announcements enabled")
        @Describe("Whether the announcement system runs at all. Off silences every announcement below, "
                + "regardless of their own settings.")
        @Key("announcements.enabled")
        boolean announcementsEnabled,

        @In("hungergames/announcements") @Title("Show in chat")
        @Describe("Whether announcements are shown in chat, in addition to whichever of the channels below "
                + "are also on.")
        @Key("announcements.use-chat")
        boolean announceUseChat,

        @In("hungergames/announcements") @Title("Show as a title")
        @Describe("Whether important announcements are also shown as an on-screen title.")
        @Key("announcements.use-title")
        boolean announceUseTitle,

        @In("hungergames/announcements") @Title("Show on the action bar")
        @Describe("Whether announcements are also shown on the action bar, the strip just above the "
                + "hotbar.")
        @Key("announcements.use-actionbar")
        boolean announceUseActionbar,

        @In("hungergames/announcements") @Title("Killfeed enabled")
        @Describe("Whether kills and eliminations are announced as they happen.")
        @Key("announcements.killfeed-enabled")
        boolean announceKillfeedEnabled,

        @In("hungergames/announcements") @Title("Remaining-tribute announcements")
        @Describe("Whether an announcement is made whenever the number of tributes remaining drops below "
                + "one of the thresholds below.")
        @Key("announcements.remaining-players-enabled")
        boolean announceRemainingPlayersEnabled,

        @In("hungergames/announcements") @Title("Thresholds")
        @Describe("The tribute counts that trigger the remaining-tribute announcement above, one per "
                + "entry — for example ten, then five, then three, then two.")
        @Key("announcements.remaining-players-thresholds")
        List<String> announceRemainingPlayersThresholds,

        // ───────────────────────────────────────────────────────────── HTTP API

        @In("hungergames/api") @Title("HTTP API enabled")
        @Describe("Whether the embedded HTTP API listens at all. Off by default: a tournament runs "
                + "perfectly well with nobody dialling in from outside, and a socket nobody asked for is "
                + "a socket somebody eventually finds. See HttpApiService's class javadoc for what this "
                + "API is for and, just as importantly, what it is not — there is no TLS, so it belongs "
                + "behind a closed network or a reverse proxy and never on the open internet.")
        @Key("api.enabled")
        boolean apiEnabled,

        @In("hungergames/api") @Title("Bind address")
        @Describe("The address the HTTP API listens on. 127.0.0.1 by default, so the API is reachable "
                + "only from the machine the server itself runs on — a dashboard or overlay on another "
                + "machine needs an explicit, deliberate change here, rather than being reachable because "
                + "nobody thought about it.")
        @Key("api.bind-address")
        String apiBindAddress,

        @In("hungergames/api") @Title("Port") @Range(min = 1024, max = 65_535)
        @Describe("The TCP port the HTTP API listens on.")
        @Key("api.port")
        int apiPort,

        @In("hungergames/api") @Title("API key")
        @Describe("The value every request must present in its X-API-Key header (or as a Bearer token). "
                + "Blank means none is configured yet — a forty-character key is generated and written "
                + "back here the first time the API starts, so a server never runs with an empty key that "
                + "would let nobody, or worse, everybody, in.")
        @Key("api.key")
        String apiKey,

        @In("hungergames/api") @Title("Read-only")
        @Describe("Blocks every endpoint that is not a GET, whatever the caller's key. A dashboard that "
                + "may look but must never touch — a stream overlay, a public status page — is safer for "
                + "this being flipped centrally than for every endpoint remembering to check it itself.")
        @Key("api.read-only")
        boolean apiReadOnly,

        // ───────────────────────────────────────────────────────────── items (per-item tuning)

        @In("hungergames/items") @Title("Fiendfinder glow duration (seconds)") @Range(min = 1, max = 600)
        @Describe("How long a fiendfinder makes its target glow for. Longer gives the holder a wider "
                + "window to close in; too long turns a brief reveal into a target that can be tracked "
                + "for the rest of the fight.")
        @Key("items.fiendfinder.glow-duration")
        int fiendfinderGlowDuration,

        @In("hungergames/items") @Title("Fiendfinder search radius") @Range(min = 0, max = 1_000)
        @Describe("How far the fiendfinder searches for a target, in blocks. Zero removes the limit "
                + "entirely, which on a large border can mean revealing somebody far outside the fight "
                + "the holder is actually in.")
        @Key("items.fiendfinder.search-radius")
        int fiendfinderSearchRadius,

        @In("hungergames/items") @Title("Smoke bomb radius") @Range(min = 1, max = 100)
        @Describe("How far the smoke bomb's blindness and slowness reach, in blocks. Too wide catches "
                + "allies streets away from the fight it was thrown into; too narrow and it does nothing "
                + "for an escape.")
        @Key("items.smoke-bomb.radius")
        int smokeBombRadius,

        @In("hungergames/items") @Title("Smoke bomb: enemy effect (seconds)") @Range(min = 1, max = 120)
        @Describe("How long caught enemies are blinded and slowed by a smoke bomb. Too short and the "
                + "thrower's escape is undone the moment the effect wears off; the shipped default of "
                + "three, rather than the old plugin's six, is what a season of real tournaments settled "
                + "on.")
        @Key("items.smoke-bomb.enemy-duration")
        int smokeBombEnemyDuration,

        @In("hungergames/items") @Title("Smoke bomb: self-invisibility (seconds)") @Range(min = 0, max = 120)
        @Describe("How long the thrower is themselves fully invisible, armour included. Zero switches "
                + "this half of the item off entirely, leaving only the effect on enemies.")
        @Key("items.smoke-bomb.self-invisibility")
        int smokeBombInvisSeconds,

        @In("hungergames/items") @Title("Medikit: regeneration (seconds)") @Range(min = 0, max = 600)
        @Describe("How long the regeneration granted by a medikit lasts. Too short and a badly wounded "
                + "tribute is left no better off than before they used it.")
        @Key("items.medikit.regen-seconds")
        int medikitRegenSeconds,

        @In("hungergames/items") @Title("Medikit: regeneration strength") @Range(min = 1, max = 10)
        @Describe("The potion level of the regeneration a medikit grants. Higher heals faster; too high "
                + "turns a rescue item into a full heal on demand.")
        @Key("items.medikit.regen-level")
        int medikitRegenLevel,

        @In("hungergames/items") @Title("Medikit: absorption (seconds)") @Range(min = 0, max = 600)
        @Describe("How long the extra hearts (Absorption) granted by a medikit last. Meant to carry the "
                + "holder through the fight they just used it to survive, not indefinitely.")
        @Key("items.medikit.absorption-seconds")
        int medikitAbsorptionSeconds,

        @In("hungergames/items") @Title("Medikit: absorption strength") @Range(min = 1, max = 10)
        @Describe("The potion level of the Absorption a medikit grants — each level is one pair of extra "
                + "hearts. Too high makes the medikit worth more tokens than the shop's own price accounts "
                + "for.")
        @Key("items.medikit.absorption-level")
        int medikitAbsorptionLevel,

        @In("hungergames/items") @Title("Medikit: use countdown (seconds)") @Range(min = 0, max = 60)
        @Describe("How long a medikit takes to heal after it is used, with any damage taken in that "
                + "window cancelling the heal. Zero makes it instant; a server's own tuning of this "
                + "survives being read back even though this build's medikit still heals the instant it "
                + "is used rather than running the countdown — see CombatItemService's class note for why "
                + "the countdown itself needs a scheduler this record must not depend on.")
        @Key("items.medikit.countdown-seconds")
        int medikitCountdownSeconds,

        @In("hungergames/items") @Title("Lightning strike: range") @Range(min = 1, max = 500)
        @Describe("How far the lightning strike's targeting reaches, in blocks. Too short and the item "
                + "cannot be aimed at anything worth using it on; too far and it strikes targets the "
                + "holder could not plausibly have picked out.")
        @Key("items.lightning.range")
        int lightningRange,

        @In("hungergames/items") @Title("Lightning strike: bolt count") @Range(min = 1, max = 50)
        @Describe("How many bolts one lightning strike calls down. Too few and a target can simply step "
                + "out from under the first bolt; too many turns the item into a guaranteed kill on "
                + "anything it catches.")
        @Key("items.lightning.bolt-count")
        int lightningBoltCount,

        @In("hungergames/items") @Title("Lightning strike: spread") @Range(min = 0, max = 50)
        @Describe("How far the bolts of a lightning strike scatter around the target, in blocks. Zero "
                + "stacks every bolt on the same spot; too wide and most of them land on nothing.")
        @Key("items.lightning.spread")
        int lightningSpread,

        @In("hungergames/items") @Title("Lightning strike: bonus damage") @Range(min = 0, max = 40)
        @Describe("The extra damage each bolt deals, in half-hearts, on top of Bukkit's own lightning "
                + "damage. Too high turns a single strike into a kill nobody could see coming or fight "
                + "back against.")
        @Key("items.lightning.bonus-damage")
        int lightningBonusDamage,

        @In("hungergames/items") @Title("Lightning strike: damage radius") @Range(min = 1, max = 50)
        @Describe("How far a bolt's damage, fire and knock-up reach from where it lands, in blocks.")
        @Key("items.lightning.damage-radius")
        int lightningDamageRadius,

        @In("hungergames/items") @Title("Lightning strike: burn duration (ticks)") @Range(min = 0, max = 600)
        @Describe("How long a struck target is left burning, in ticks. Zero switches the burn off "
                + "entirely, leaving only the strike's own damage.")
        @Key("items.lightning.fire-ticks")
        int lightningFireTicks,

        @In("hungergames/items") @Title("Lightning strike: bolt delay (ticks)") @Range(min = 0, max = 100)
        @Describe("How long between one bolt and the next, in ticks. Zero fires every bolt at once, which "
                + "reads as a single flash rather than a storm arriving.")
        @Key("items.lightning.bolt-delay")
        int lightningBoltDelay,

        @In("hungergames/items") @Title("Lightning strike: knock-up")
        @Describe("Whether a struck target is tossed upward. Off leaves the strike purely damage, with "
                + "none of the vulnerability a knock-up creates.")
        @Key("items.lightning.knockup")
        boolean lightningKnockup,

        @In("hungergames/items") @Title("Hermes' boots: flight (seconds)") @Range(min = 1, max = 60)
        @Describe("How long Hermes' boots keep the holder airborne. Too long and flight becomes a way to "
                + "simply out-run the border rather than clear a single obstacle.")
        @Key("items.hermes-boots.flight-seconds")
        int hermesFlightSeconds,

        @In("hungergames/items") @Title("Hermes' boots: warning (seconds)") @Range(min = 0, max = 60)
        @Describe("How long before flight ends the warning sound starts. Zero removes the warning "
                + "entirely, leaving the holder to fall out of the sky with no notice.")
        @Key("items.hermes-boots.warning-seconds")
        int hermesWarningSeconds,

        @In("hungergames/items") @Title("Krückauwasser: radius") @Range(min = 1, max = 50)
        @Describe("How far krückauwasser's nausea and blindness reach once it lands, in blocks. Too wide "
                + "catches the thrower's own allies as often as the enemy it was aimed at.")
        @Key("items.krueckau.radius")
        int krueckauRadius,

        @In("hungergames/items") @Title("Krückauwasser: nausea (seconds)") @Range(min = 1, max = 120)
        @Describe("How long krückauwasser's nausea lasts.")
        @Key("items.krueckau.nausea-seconds")
        int krueckauNauseaSeconds,

        @In("hungergames/items") @Title("Krückauwasser: blindness (seconds)") @Range(min = 0, max = 120)
        @Describe("How long krückauwasser's blindness lasts. Zero removes it entirely, leaving only the "
                + "nausea — which would make the item's own description of 'mild blindness' false.")
        @Key("items.krueckau.blindness-seconds")
        int krueckauBlindnessSeconds,

        @In("hungergames/items") @Title("Aura of protection: duration (seconds)") @Range(min = 1, max = 60)
        @Describe("How long the aura of protection stays up once activated. Too long turns a defensive "
                + "item into one that can hold a whole fight on its own.")
        @Key("items.aura.duration-seconds")
        int auraDurationSeconds,

        @In("hungergames/items") @Title("Aura of protection: radius") @Range(min = 1, max = 50)
        @Describe("How far the aura of protection reaches from the holder, in blocks.")
        @Key("items.aura.radius")
        int auraRadius,

        @In("hungergames/items") @Title("Aura of protection: damage") @Range(min = 0, max = 40)
        @Describe("How much damage each pulse of the aura deals to a caught enemy, in half-hearts.")
        @Key("items.aura.damage")
        int auraDamage,

        @In("hungergames/items") @Title("Aura of protection: pulse interval (ticks)") @Range(min = 1, max = 100)
        @Describe("How often the aura pulses while it is up, in ticks. Too long between pulses lets a "
                + "fast attacker dash through the aura between hits.")
        @Key("items.aura.interval-ticks")
        int auraInterval,

        @In("hungergames/items") @Title("Aura of protection: knockback") @Range(min = 0, max = 50)
        @Describe("How hard each pulse shoves a caught enemy away. Stored as a whole number that is "
                + "really tenths — a value of 6 here means a knockback of 0.6 — because that is the unit "
                + "the shipped config.yml already uses, and reading it back as a plain 6 would make the "
                + "aura's shove ten times stronger than anybody intended.")
        @Key("items.aura.knockback")
        int auraKnockback,

        @In("hungergames/items") @Title("Aura of protection: affect mobs")
        @Describe("Whether the aura also strikes hostile mobs, not only players. Off leaves mobs free to "
                + "stand inside the aura's radius untouched.")
        @Key("items.aura.affect-mobs")
        boolean auraAffectMobs,

        @In("hungergames/items") @Title("Grappling hook: range") @Range(min = 1, max = 200)
        @Describe("How far the grappling hook reaches, in blocks. Too short leaves most gaps in the arena "
                + "uncrossable; too far turns it into a way to close almost any distance in one pull.")
        @Key("items.grappling.range")
        int grapplingRange,

        @In("hungergames/items") @Title("Grappling hook: pull strength") @Range(min = 1, max = 50)
        @Describe("How hard the grappling hook pulls the holder towards its target. Stored as tenths, the "
                + "same reason as the aura's knockback above — a value of 14 here is a pull strength of "
                + "1.4, not 14.")
        @Key("items.grappling.power")
        int grapplingPower,

        @In("hungergames/items") @Title("Repulse: radius") @Range(min = 1, max = 50)
        @Describe("How far repulse's shockwave reaches from the holder, in blocks.")
        @Key("items.repulse.radius")
        int repulseRadius,

        @In("hungergames/items") @Title("Repulse: strength") @Range(min = 1, max = 50)
        @Describe("How hard repulse throws everybody it catches. Stored as tenths — a value of 12 here is "
                + "a strength of 1.2 — for the same reason the aura's knockback and the grappling hook's "
                + "pull are.")
        @Key("items.repulse.strength")
        int repulseStrength,

        @In("hungergames/items") @Title("Repulse: slow (seconds)") @Range(min = 0, max = 60)
        @Describe("How long repulse slows whoever it throws. Zero removes the slow entirely, leaving only "
                + "the shove.")
        @Key("items.repulse.slow-seconds")
        int repulseSlowSeconds,

        @In("hungergames/items") @Title("Repulse: affect mobs")
        @Describe("Whether repulse also shoves hostile mobs, not only players.")
        @Key("items.repulse.affect-mobs")
        boolean repulseAffectMobs,

        @In("hungergames/items") @Title("Feast: golden apples") @Range(min = 0, max = 20)
        @Describe("How many golden apples a Capitol feast hands out, on top of its regeneration. Zero "
                + "removes them entirely, leaving only the regeneration and the food refill.")
        @Key("items.feast.golden-apples")
        int feastGoldenApples,

        @In("hungergames/items") @Title("War kit: armour material")
        @Describe("The armour tier a war kit equips — LEATHER, CHAINMAIL, IRON, GOLDEN, DIAMOND or "
                + "NETHERITE. A name this build does not recognise falls back to iron rather than leaving "
                + "the holder with no armour at all.")
        @Key("items.war-kit.material")
        String warKitMaterial,

        @In("hungergames/items") @Title("Leap: power") @Range(min = 1, max = 50)
        @Describe("How hard leap catapults the holder. Stored as tenths — a value of 15 here is a power "
                + "of 1.5 — the same reason as the aura's knockback, the grappling hook's pull and "
                + "repulse's strength above.")
        @Key("items.leap.power")
        int leapPower,

        @In("hungergames/items") @Title("Exmatrikulator: duration (seconds)") @Range(min = 1, max = 60)
        @Describe("How long one activation of the exmatrikulator's lightning aura lasts.")
        @Key("items.exmatrikulator.duration-seconds")
        int exmatrikulatorDuration,

        @In("hungergames/items") @Title("Exmatrikulator: radius") @Range(min = 1, max = 50)
        @Describe("How far one volley of the exmatrikulator's aura reaches, in blocks.")
        @Key("items.exmatrikulator.radius")
        int exmatrikulatorRadius,

        @In("hungergames/items") @Title("Exmatrikulator: volley interval (ticks)") @Range(min = 1, max = 100)
        @Describe("How often, while its aura is up, the exmatrikulator fires another volley, in ticks. Too "
                + "long between volleys turns a sustained aura into a handful of isolated strikes.")
        @Key("items.exmatrikulator.interval-ticks")
        int exmatrikulatorInterval,

        @In("hungergames/items") @Title("Exmatrikulator: bonus damage") @Range(min = 0, max = 40)
        @Describe("The bonus damage each bolt in an exmatrikulator volley deals, in half-hearts.")
        @Key("items.exmatrikulator.bonus-damage")
        int exmatrikulatorDamage,

        @In("hungergames/items") @Title("Exmatrikulator: max targets per volley") @Range(min = 1, max = 50)
        @Describe("The most targets one volley of the exmatrikulator strikes. Too high and a single "
                + "activation next to a crowd can wipe it outright.")
        @Key("items.exmatrikulator.max-targets")
        int exmatrikulatorMaxTargets,

        @In("hungergames/items") @Title("Exmatrikulator: burn duration (ticks)") @Range(min = 0, max = 600)
        @Describe("How long a target struck by the exmatrikulator is left burning, in ticks. Zero "
                + "switches the burn off, leaving only the volley's own damage.")
        @Key("items.exmatrikulator.fire-ticks")
        int exmatrikulatorFireTicks,

        @In("hungergames/items") @Title("Exmatrikulator: module names")
        @Describe("The module names a death message may blame, one per entry. An empty list produces a "
                + "death message with no module named.")
        @Key("items.exmatrikulator.modules")
        List<String> exmatrikulatorModules,

        @In("hungergames/items") @Title("Exmatrikulator: death messages")
        @Describe("The death-message templates the exmatrikulator picks from at random, each carrying the "
                + "placeholders %killer% and %modul%. Kept at the old plugin's own German wording, since a "
                + "server that tuned these lines expects to see them again rather than a fixed English "
                + "replacement.")
        @Key("items.exmatrikulator.death-messages")
        List<String> exmatrikulatorDeathMessages,

        @In("hungergames/items") @Title("Exmatrikulator: crafting recipe")
        @Describe("The exmatrikulator's crafting recipe: three rows of three materials each, '-' for an "
                + "empty slot. A malformed recipe here leaves the item uncraftable rather than crashing "
                + "anything.")
        @Key("items.exmatrikulator.recipe")
        List<String> exmatrikulatorRecipe,

        @In("hungergames/items") @Title("Stupidness protector: heal on rescue") @Range(min = 1, max = 40)
        @Describe("How much health a stupidness protector restores when it saves its holder, in "
                + "half-hearts.")
        @Key("items.stupidness-protector.heal-health")
        int stupidnessHealHearts,

        @In("hungergames/items") @Title("Stupidness protector: regeneration (seconds)") @Range(min = 0, max = 60)
        @Describe("How long the regeneration granted by a stupidness protector's rescue lasts.")
        @Key("items.stupidness-protector.regen-seconds")
        int stupidnessRegenSeconds,

        @In("hungergames/items") @Title("Stupidness protector: fire resistance (seconds)")
        @Range(min = 0, max = 120)
        @Describe("How long the fire resistance granted by a stupidness protector's rescue lasts — long "
                + "enough to walk back out of the lava that nearly ended the round for its holder.")
        @Key("items.stupidness-protector.fire-resistance-seconds")
        int stupidnessFireResistSeconds,

        @In("hungergames/items") @Title("Stupidness protector: shove radius") @Range(min = 0, max = 50)
        @Describe("How far a stupidness protector's rescue shoves enemies away from the holder, in "
                + "blocks. Zero switches the shove off, leaving only the heal.")
        @Key("items.stupidness-protector.shove-radius")
        int stupidnessShoveRadius,

        @In("hungergames/items") @Title("Stupidness protector: shove strength") @Range(min = 0, max = 50)
        @Describe("How hard a stupidness protector's rescue shoves enemies away. Stored as tenths — a "
                + "value of 12 here is a strength of 1.2 — the same reason as every other ×0.1 setting on "
                + "this page.")
        @Key("items.stupidness-protector.shove-strength")
        int stupidnessShoveStrength

) {

    /** What happens to a tribute the moment they are eliminated. */
    public enum DeathAction {
        /** Stays on the server, watching the rest of the round as a spectator. */
        SPECTATOR,
        /** Removed from the server. */
        KICK,
        /** Removed from the server and barred from returning. */
        BAN
    }

    /** How server downtime is treated when a mid-round session is restored after a restart. */
    public enum OfflineTimePolicy {
        /** Downtime does not count as round time — the safe default. */
        PAUSE,
        /** Downtime counts as round time that has already elapsed. */
        COUNT
    }

    /** The game mode somebody is switched to the moment they turn on gamemaster mode. */
    public enum GamemasterMode {
        SPECTATOR,
        CREATIVE
    }

    /** How sponsor beacons find their way into the arena. */
    public enum BeaconSpawnMode {
        /** None at all. */
        DISABLED,
        /** One near the cornucopia. */
        CENTER,
        /** Scattered through the round, on the configured schedule. */
        RANDOM_TIMED,
        /** Only where a gamemaster puts one. */
        MANUAL
    }

    /** How a gamemaster is recognised. */
    public enum GamemasterPermissionMode {
        /** Only the {@code hungergames.gamemaster} permission counts. */
        PERMISSION,
        /** Only the roster in the gamemaster store counts. */
        LIST,
        /** Either counts. */
        BOTH
    }

    /**
     * What a server that has never touched {@code config.yml} gets.
     *
     * <p>Mostly the values {@code HgSettings} — the catalogue this record replaces — itself defaulted to.
     * That is not nostalgia: a server already running the old plugin has a {@code config.yml} written from
     * those defaults, and this record has to read that file back the same way it was written, or an upgrade
     * silently reopens every setting nobody happened to have an opinion about.
     *
     * <h2>Where they differ, and why</h2>
     * A handful come from the tournament this was ported for rather than from the old catalogue, because
     * they had been tuned over real evenings and the old default was the one nobody wanted:
     *
     * <ul>
     *   <li><b>{@code teams.max-size} is 10</b>, not 2. Duos were the old default and the tournament runs
     *       larger teams.</li>
     *   <li><b>{@code border.minimum-size} is 50</b> and {@code deathmatch.target-border-size} matches it,
     *       so the endgame is a fifty-block ring rather than a hundred-block one.</li>
     *   <li><b>{@code sponsors.tokens.amount-per-interval} is 2</b>; one token a wave was too slow to make
     *       the shop matter.</li>
     *   <li><b>Beacons</b> are gold-based, reach 700 blocks out and four may stand at once.</li>
     *   <li><b>The shop's twelve entries</b> are the real list, including the nine custom items — the old
     *       default sold two placeholder potions instead.</li>
     * </ul>
     *
     * <p><b>{@code game.duration} is deliberately not among them</b> and stays at three hours. Round length
     * is the one number every other timing is derived from — the border's phases, the drops, the deathmatch
     * floor — and a server that inherits somebody else's idea of an evening gets a tournament whose whole
     * schedule is somebody else's.
     *
     * <h2>The two item defaults that also differ, and why</h2>
     * <ul>
     *   <li><b>{@code items.smoke-bomb.enemy-duration} is 3</b>, not the old plugin's 6. A season of real
     *       tournaments settled on three seconds of blindness and slowness as long enough to cover an escape
     *       without stretching a single throw into half a fight.</li>
     *   <li><b>{@code items.medikit.countdown-seconds} is 2</b>, not the old plugin's 3. The same server had
     *       tuned the medikit's cast time down by a second, and both values arrived in the same live
     *       {@code config.yml} that made this whole class of key real in the first place.</li>
     * </ul>
     */
    public static final HungerGamesSettings DEFAULTS = new HungerGamesSettings(
            List.of(),
            180, 60, 20, 30, Difficulty.NORMAL, Difficulty.PEACEFUL, DeathAction.SPECTATOR, 0,
            OfflineTimePolicy.PAUSE, true, true, true, true, true, 10, true, true, false,
            4, 3, 4, 5, 12, true, 20, true,
            5, 40, 10, 2,
            100, 20, 20, 5, Material.GLASS,
            2500, 50.0D, 1.25D, true, List.of("10", "5", "1"), 30,
            true, true, 50, 60, false, 2, 10, true, List.of("RUNNING"), true, true,
            true, 60, 1, 30, 250, true, true, 0, true, Material.IRON_BLOCK, true, true, true,
            "ZOMBIE", 6, 5, 15, 4,
            true, GamemasterMode.SPECTATOR, true, true, true, GamemasterPermissionMode.PERMISSION,
            10, 0, true, false, true, true, "STARTUP",
            true,
            true, Material.NETHER_STAR, "<gold>Sponsor Token",
            List.of("<gray>Earned by surviving.", "<gray>Spend it at a sponsor beacon."),
            10, 2, 10, 0, true, true, false, true, false, true,
            true, BeaconSpawnMode.CENTER, true, Material.BEACON, Material.GOLD_BLOCK, true,
            30, 700, List.of("15m", "35m", "60m"), 4, true, true, 0, true, true,
            true, List.of(
                    "bread_pack|BREAD:8|1|Food parcel",
                    "arrows|ARROW:16|1|Arrows",
                    "iron_ingot|IRON_INGOT:2|2|Iron",
                    "fiendfinder|ITEM:FIENDFINDER:1|4|Fiendfinder",
                    "smoke_bomb|ITEM:SMOKE_BOMB:1|6|Smoke bomb",
                    "lightning_strike|ITEM:LIGHTNING_STRIKE:1|12|Lightning strike",
                    "hermes_boots|ITEM:HERMES_BOOTS:2|9|Hermes' boots",
                    "krueckauwasser|ITEM:KRUECKAUWASSER:1|7|Krückauwasser",
                    "stupidness_protector|ITEM:STUPIDNESS_PROTECTOR:1|5|Stupidness protector",
                    "leap|ITEM:LEAP:1|4|Leap",
                    "medikit|ITEM:MEDIKIT:1|12|Medikit",
                    "aura_of_protection|ITEM:AURA_OF_PROTECTION:1|8|Aura of protection"),
            50, 30, true, true, true, 100, true, true,
            20, true, false, false, "hungergames.protection.bypass",
            true, true, true, true, true, true, List.of("10", "5", "3", "2"),
            false, "127.0.0.1", 8567, "", false,
            15, 0, 6, 3, 3,
            6, 2, 60, 2, 2,
            80, 6, 3, 8, 4, 80, 3, true,
            4, 3,
            4, 12, 0,
            5, 4, 6, 10, 6, true,
            40, 14,
            6, 12, 2, true,
            2, "IRON",
            15,
            5, 8, 4, 6, 5, 40,
            List.of("Mathematik I", "Mathematik II", "Theoretische Informatik",
                    "Programmierung", "Rechnungswesen", "Statistik",
                    "Datenbanken", "Software Engineering", "BWL"),
            List.of("wurde exmatrikuliert, als er im Drittversuch in %modul% gegen %killer% versagte.",
                    "wurde nach nicht bestandener Klausur in %modul% von %killer% exmatrikuliert.",
                    "scheiterte in %modul% an Prüfer %killer% und wurde exmatrikuliert.",
                    "hat den Drittversuch in %modul% gegen %killer% vergeigt — exmatrikuliert.",
                    "wurde von Prüfungsausschuss %killer% wegen %modul% exmatrikuliert."),
            List.of("LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD",
                    "NETHERITE_INGOT DIAMOND_BLOCK NETHERITE_INGOT",
                    "LIGHTNING_ROD DIAMOND_BLOCK LIGHTNING_ROD"),
            8, 8, 10, 5, 12);

    // ------------------------------------------------------------------ read back safely

    /**
     * The round length, clamped.
     *
     * <p>The store clamps what it reads from the file, but a {@code HungerGamesSettings} can also be
     * built in code — by a test, or by a host handing in its own — and this is what stops a round
     * shorter than the countdown and grace period it has to contain from ever reaching a
     * {@code GameTimer}. A round with less than five minutes on the clock is not a shorter tournament,
     * it is one that ends before it visibly starts.
     */
    public Duration roundDuration() {
        return Duration.ofMinutes(Math.max(5, Math.min(20_160, gameDurationMinutes)));
    }

    /** The grace period, clamped to something that cannot be negative. */
    public Duration gracePeriod() {
        return Duration.ofSeconds(Math.max(0, Math.min(3_600, gracePeriodSeconds)));
    }

    /**
     * The countdown, clamped.
     *
     * <p>A negative countdown is not a faster start, it is a number a scheduler counts down through
     * zero without ever firing on — so this is clamped to the same three-to-three-hundred range the
     * old plugin enforced at the point it was set, rather than trusting a file to have kept it there.
     */
    public int countdown() {
        return Math.max(3, Math.min(300, countdownSeconds));
    }

    /**
     * How long a disconnected tribute is given before elimination, or empty when the rule is off.
     *
     * <p>Zero is not "eliminate immediately" — it is the old plugin's way of switching the whole rule
     * off, since a disconnected player otherwise stays ALIVE indefinitely. That distinction has to
     * survive the read-back, so this returns {@link Duration#ZERO} for "off" rather than clamping it
     * up to some minimum that would turn "off" into "instantly".
     */
    public Duration disconnectElimination() {
        return Duration.ofMinutes(Math.max(0, Math.min(1_440, disconnectEliminationMinutes)));
    }

    /** The deathmatch's post-teleport grace period, clamped to something that cannot be negative. */
    public Duration deathmatchGraceAfterTeleport() {
        return Duration.ofSeconds(Math.max(0, Math.min(3_600, deathmatchGraceAfterTeleportSeconds)));
    }

    /**
     * The border's fairness ceiling, clamped to always be positive.
     *
     * <p>{@code BorderSettings}' own constructor already refuses zero or negative — this is what stops
     * a {@code config.yml} edited by hand with {@code max-edge-speed: 0} from ever reaching that
     * constructor at all, since the failure there is an {@link IllegalArgumentException} at the point
     * a round tries to start rather than something caught when the file was read.
     */
    public double borderEdgeSpeed() {
        return Math.max(0.1D, Math.min(100.0D, borderMaxEdgeSpeed));
    }

    /** How long between sponsor token waves. */
    public Duration sponsorTokenInterval() {
        return Duration.ofMinutes(Math.max(1, sponsorTokenIntervalMinutes));
    }

    /** How long into a round the first sponsor tokens arrive. */
    public Duration sponsorTokenFirstAfter() {
        return Duration.ofMinutes(Math.max(0, sponsorTokenFirstAfterMinutes));
    }

    /**
     * The phase from which teams are frozen.
     *
     * <p>Stored as a string because that is what the old {@code config.yml} holds, and read back leniently:
     * a phase name nobody recognises falls back to {@code STARTUP} rather than throwing. The alternative is
     * a typo in a config file stopping the plugin, which is a worse answer than the sensible default plus a
     * line in the log.
     */
    public de.raindancer.modules.hungergames.model.GamePhase teamsFreezeFrom() {
        try {
            return de.raindancer.modules.hungergames.model.GamePhase
                    .valueOf(teamLockPhase == null ? "STARTUP"
                            : teamLockPhase.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException noSuchPhase) {
            return de.raindancer.modules.hungergames.model.GamePhase.STARTUP;
        }
    }

    /** The border's absolute floor, clamped to never be negative. */
    public double borderFloor() {
        return Math.max(0.0D, borderMinimumSize);
    }

    // ------------------------------------------------------------------ tenths, read back as decimals

    /**
     * The aura of protection's knockback, as an actual multiplier rather than the whole number the field
     * stores.
     *
     * <p>{@link #auraKnockback}, like the four fields below it, is an {@code int} rather than a
     * {@code double} because that is what the shipped {@code config.yml} already holds — the old plugin's
     * own settings UI had no field for a decimal, so it stored "1.4" as the integer 14 and divided by ten
     * wherever the number was actually used. Changing the stored type to a {@code double} here would read a
     * live server's {@code knockback: 6} back as a knockback of six, not 0.6 — ten times stronger than
     * anybody tuned it to be, with nothing in the file or the log to say so. Dividing by ten in an accessor,
     * rather than "fixing" the field itself, is what keeps that number the size it was always meant to be.
     */
    public double auraKnockbackStrength() {
        return auraKnockback / 10.0D;
    }

    /** The grappling hook's pull strength, as a multiplier. See {@link #auraKnockbackStrength()} for why
     *  {@link #grapplingPower} is stored as a whole number rather than this. */
    public double grapplingPowerStrength() {
        return grapplingPower / 10.0D;
    }

    /** Repulse's strength, as a multiplier. See {@link #auraKnockbackStrength()} for why
     *  {@link #repulseStrength} is stored as a whole number rather than this. */
    public double repulseStrengthMultiplier() {
        return repulseStrength / 10.0D;
    }

    /** Leap's power, as a multiplier. See {@link #auraKnockbackStrength()} for why {@link #leapPower} is
     *  stored as a whole number rather than this. */
    public double leapPowerStrength() {
        return leapPower / 10.0D;
    }

    /** The stupidness protector's shove strength, as a multiplier. See {@link #auraKnockbackStrength()} for
     *  why {@link #stupidnessShoveStrength} is stored as a whole number rather than this. */
    public double stupidnessShoveStrengthMultiplier() {
        return stupidnessShoveStrength / 10.0D;
    }

    // ------------------------------------------------------------------ one component at a time

    /**
     * The same settings with the round length changed.
     *
     * <p>There are five of these rather than a full builder because a record with ninety-three
     * components has a positional constructor, and anything that spells all ninety-three out is a
     * mis-ordering waiting to happen. A caller that wants to vary one thing should not have to restate
     * the rest — so only the handful of components a settings screen actually lets somebody click
     * through get their own {@code with…}, the same way {@code ClaimSettings} carries three for its
     * sixty-five.
     */
    public HungerGamesSettings withGameDurationMinutes(int minutes) {
        return new HungerGamesSettings(
                preInitAdmins, minutes, gracePeriodSeconds, countdownSeconds, prepTimePercent,
                gameDifficulty, preflightDifficulty, deathAction, disconnectEliminationMinutes,
                offlineTimePolicy, adminDeopOnStart, adminReopOnElimination, adminReopOnFinish,
                adminCreativeOnElimination, adminTeleportCenterOnElimination, adminCenterYOffset,
                roundLogEnabled, roundLogFilePerRound, roundLogIncludeCoordinates, platformMinGap,
                platformWidth, undergroundRoomHeight, undergroundRoomExtraRadius, tubeDepth,
                blockNetherPortals, netherAllowRadius, blockEndPortals, startupLampDelay,
                startupLevitationStartDelay, startupPlayerLevitationDelay, startupLevitationAmplifier,
                lobbyHeightOffset, lobbyWidth, lobbyDepth, lobbyHeight, lobbyBlockType, borderInitialSize,
                borderMinimumSize, borderMaxEdgeSpeed, borderScaleNether, borderPrepWarnings,
                borderShrinkWarning, deathmatchEnabled, deathmatchManualOnly, deathmatchTargetBorderSize,
                deathmatchWarningSeconds, deathmatchTeleportToCenter, deathmatchTeleportYOffset,
                deathmatchGraceAfterTeleportSeconds, deathmatchRequireConfirmation,
                deathmatchAllowedPhases, deathmatchBroadcastEnabled, deathmatchSoundEnabled,
                supplyDropsEnabled, supplyDropWarningSeconds, supplyDropCount, supplyDropRadiusMin,
                supplyDropRadiusMax, supplyDropOnlyOverworld, supplyDropAnnounceCoordinates,
                supplyDropCoordinateFuzz, supplyDropBeaconEnabled, supplyDropBaseMaterial,
                supplyDropProtected, supplyDropFireworkEnabled, supplyDropParticlesEnabled,
                monsterWaveDefaultMob, monsterWaveCountPerWave, monsterWaveWaveCount,
                monsterWaveIntervalSeconds, monsterWaveSpread, gamemasterEnabled, gamemasterDefaultMode,
                gamemasterKeepOp, gamemasterAllowTeleportMenu, gamemasterHideFromPlayerCount,
                gamemasterPermissionMode, teamMaxSize, teamMaxTeams, teamAllowSwitching,
                teamCaptainEnabled, teamPlayersCanCreate, teamPlayersChooseColour, teamLockPhase,
                sponsorsEnabled, sponsorTokensEnabled, sponsorTokenMaterial, sponsorTokenName,
                sponsorTokenLore, sponsorTokenIntervalMinutes, sponsorTokenAmountPerInterval,
                sponsorTokenFirstAfterMinutes, sponsorTokenMaxPerPlayer, sponsorTokenOnlyAlive,
                sponsorTokenAnnouncePersonal, sponsorTokenBroadcastMilestones, sponsorTokenDropOnDeath,
                sponsorTokenClearOnElimination, sponsorTokenClearOnRoundReset, sponsorBeaconsEnabled,
                sponsorBeaconSpawnMode, sponsorBeaconCentreOnStart, sponsorBeaconMaterial,
                sponsorBeaconBaseMaterial, sponsorBeaconProtected, sponsorBeaconRadiusMin,
                sponsorBeaconRadiusMax, sponsorBeaconSchedule, sponsorBeaconMaxActive,
                sponsorBeaconAnnounceSpawn, sponsorBeaconAnnounceCoordinates, sponsorBeaconCoordinateFuzz,
                sponsorBeaconParticles, sponsorBeaconSound, sponsorShopEnabled, sponsorShopItems,
                lootScanRadius, lootScanYRange, lootEditorEnabled, lootEditorAllowRuntimeEdits,
                lootEditorBackupBeforeSave, lootEditorMaxTestRolls, lootEditorAllowTestGive,
                lootEditorAllowTestChest, cornucopiaRadius, protectCornucopiaBeforeRunning,
                protectCornucopiaDuringRunning, protectCornucopiaAfterGame, protectionBypassPermission,
                announcementsEnabled, announceUseChat, announceUseTitle, announceUseActionbar,
                announceKillfeedEnabled, announceRemainingPlayersEnabled,
                announceRemainingPlayersThresholds, apiEnabled, apiBindAddress, apiPort, apiKey,
                apiReadOnly, fiendfinderGlowDuration, fiendfinderSearchRadius, smokeBombRadius,
                smokeBombEnemyDuration, smokeBombInvisSeconds, medikitRegenSeconds, medikitRegenLevel,
                medikitAbsorptionSeconds, medikitAbsorptionLevel, medikitCountdownSeconds, lightningRange,
                lightningBoltCount, lightningSpread, lightningBonusDamage, lightningDamageRadius,
                lightningFireTicks, lightningBoltDelay, lightningKnockup, hermesFlightSeconds,
                hermesWarningSeconds, krueckauRadius, krueckauNauseaSeconds, krueckauBlindnessSeconds,
                auraDurationSeconds, auraRadius, auraDamage, auraInterval, auraKnockback, auraAffectMobs,
                grapplingRange, grapplingPower, repulseRadius, repulseStrength, repulseSlowSeconds,
                repulseAffectMobs, feastGoldenApples, warKitMaterial, leapPower, exmatrikulatorDuration,
                exmatrikulatorRadius, exmatrikulatorInterval, exmatrikulatorDamage, exmatrikulatorMaxTargets,
                exmatrikulatorFireTicks, exmatrikulatorModules, exmatrikulatorDeathMessages,
                exmatrikulatorRecipe, stupidnessHealHearts, stupidnessRegenSeconds,
                stupidnessFireResistSeconds, stupidnessShoveRadius, stupidnessShoveStrength);
    }

    /** The same, for the countdown — the value a host is likeliest to want to shorten for a rehearsal. */
    public HungerGamesSettings withCountdownSeconds(int seconds) {
        return new HungerGamesSettings(
                preInitAdmins, gameDurationMinutes, gracePeriodSeconds, seconds, prepTimePercent,
                gameDifficulty, preflightDifficulty, deathAction, disconnectEliminationMinutes,
                offlineTimePolicy, adminDeopOnStart, adminReopOnElimination, adminReopOnFinish,
                adminCreativeOnElimination, adminTeleportCenterOnElimination, adminCenterYOffset,
                roundLogEnabled, roundLogFilePerRound, roundLogIncludeCoordinates, platformMinGap,
                platformWidth, undergroundRoomHeight, undergroundRoomExtraRadius, tubeDepth,
                blockNetherPortals, netherAllowRadius, blockEndPortals, startupLampDelay,
                startupLevitationStartDelay, startupPlayerLevitationDelay, startupLevitationAmplifier,
                lobbyHeightOffset, lobbyWidth, lobbyDepth, lobbyHeight, lobbyBlockType, borderInitialSize,
                borderMinimumSize, borderMaxEdgeSpeed, borderScaleNether, borderPrepWarnings,
                borderShrinkWarning, deathmatchEnabled, deathmatchManualOnly, deathmatchTargetBorderSize,
                deathmatchWarningSeconds, deathmatchTeleportToCenter, deathmatchTeleportYOffset,
                deathmatchGraceAfterTeleportSeconds, deathmatchRequireConfirmation,
                deathmatchAllowedPhases, deathmatchBroadcastEnabled, deathmatchSoundEnabled,
                supplyDropsEnabled, supplyDropWarningSeconds, supplyDropCount, supplyDropRadiusMin,
                supplyDropRadiusMax, supplyDropOnlyOverworld, supplyDropAnnounceCoordinates,
                supplyDropCoordinateFuzz, supplyDropBeaconEnabled, supplyDropBaseMaterial,
                supplyDropProtected, supplyDropFireworkEnabled, supplyDropParticlesEnabled,
                monsterWaveDefaultMob, monsterWaveCountPerWave, monsterWaveWaveCount,
                monsterWaveIntervalSeconds, monsterWaveSpread, gamemasterEnabled, gamemasterDefaultMode,
                gamemasterKeepOp, gamemasterAllowTeleportMenu, gamemasterHideFromPlayerCount,
                gamemasterPermissionMode, teamMaxSize, teamMaxTeams, teamAllowSwitching,
                teamCaptainEnabled, teamPlayersCanCreate, teamPlayersChooseColour, teamLockPhase,
                sponsorsEnabled, sponsorTokensEnabled, sponsorTokenMaterial, sponsorTokenName,
                sponsorTokenLore, sponsorTokenIntervalMinutes, sponsorTokenAmountPerInterval,
                sponsorTokenFirstAfterMinutes, sponsorTokenMaxPerPlayer, sponsorTokenOnlyAlive,
                sponsorTokenAnnouncePersonal, sponsorTokenBroadcastMilestones, sponsorTokenDropOnDeath,
                sponsorTokenClearOnElimination, sponsorTokenClearOnRoundReset, sponsorBeaconsEnabled,
                sponsorBeaconSpawnMode, sponsorBeaconCentreOnStart, sponsorBeaconMaterial,
                sponsorBeaconBaseMaterial, sponsorBeaconProtected, sponsorBeaconRadiusMin,
                sponsorBeaconRadiusMax, sponsorBeaconSchedule, sponsorBeaconMaxActive,
                sponsorBeaconAnnounceSpawn, sponsorBeaconAnnounceCoordinates, sponsorBeaconCoordinateFuzz,
                sponsorBeaconParticles, sponsorBeaconSound, sponsorShopEnabled, sponsorShopItems,
                lootScanRadius, lootScanYRange, lootEditorEnabled, lootEditorAllowRuntimeEdits,
                lootEditorBackupBeforeSave, lootEditorMaxTestRolls, lootEditorAllowTestGive,
                lootEditorAllowTestChest, cornucopiaRadius, protectCornucopiaBeforeRunning,
                protectCornucopiaDuringRunning, protectCornucopiaAfterGame, protectionBypassPermission,
                announcementsEnabled, announceUseChat, announceUseTitle, announceUseActionbar,
                announceKillfeedEnabled, announceRemainingPlayersEnabled,
                announceRemainingPlayersThresholds, apiEnabled, apiBindAddress, apiPort, apiKey,
                apiReadOnly, fiendfinderGlowDuration, fiendfinderSearchRadius, smokeBombRadius,
                smokeBombEnemyDuration, smokeBombInvisSeconds, medikitRegenSeconds, medikitRegenLevel,
                medikitAbsorptionSeconds, medikitAbsorptionLevel, medikitCountdownSeconds, lightningRange,
                lightningBoltCount, lightningSpread, lightningBonusDamage, lightningDamageRadius,
                lightningFireTicks, lightningBoltDelay, lightningKnockup, hermesFlightSeconds,
                hermesWarningSeconds, krueckauRadius, krueckauNauseaSeconds, krueckauBlindnessSeconds,
                auraDurationSeconds, auraRadius, auraDamage, auraInterval, auraKnockback, auraAffectMobs,
                grapplingRange, grapplingPower, repulseRadius, repulseStrength, repulseSlowSeconds,
                repulseAffectMobs, feastGoldenApples, warKitMaterial, leapPower, exmatrikulatorDuration,
                exmatrikulatorRadius, exmatrikulatorInterval, exmatrikulatorDamage, exmatrikulatorMaxTargets,
                exmatrikulatorFireTicks, exmatrikulatorModules, exmatrikulatorDeathMessages,
                exmatrikulatorRecipe, stupidnessHealHearts, stupidnessRegenSeconds,
                stupidnessFireResistSeconds, stupidnessShoveRadius, stupidnessShoveStrength);
    }

    /** The same, for how a tribute's elimination is handled. */
    public HungerGamesSettings withDeathAction(DeathAction action) {
        return new HungerGamesSettings(
                preInitAdmins, gameDurationMinutes, gracePeriodSeconds, countdownSeconds, prepTimePercent,
                gameDifficulty, preflightDifficulty, action, disconnectEliminationMinutes,
                offlineTimePolicy, adminDeopOnStart, adminReopOnElimination, adminReopOnFinish,
                adminCreativeOnElimination, adminTeleportCenterOnElimination, adminCenterYOffset,
                roundLogEnabled, roundLogFilePerRound, roundLogIncludeCoordinates, platformMinGap,
                platformWidth, undergroundRoomHeight, undergroundRoomExtraRadius, tubeDepth,
                blockNetherPortals, netherAllowRadius, blockEndPortals, startupLampDelay,
                startupLevitationStartDelay, startupPlayerLevitationDelay, startupLevitationAmplifier,
                lobbyHeightOffset, lobbyWidth, lobbyDepth, lobbyHeight, lobbyBlockType, borderInitialSize,
                borderMinimumSize, borderMaxEdgeSpeed, borderScaleNether, borderPrepWarnings,
                borderShrinkWarning, deathmatchEnabled, deathmatchManualOnly, deathmatchTargetBorderSize,
                deathmatchWarningSeconds, deathmatchTeleportToCenter, deathmatchTeleportYOffset,
                deathmatchGraceAfterTeleportSeconds, deathmatchRequireConfirmation,
                deathmatchAllowedPhases, deathmatchBroadcastEnabled, deathmatchSoundEnabled,
                supplyDropsEnabled, supplyDropWarningSeconds, supplyDropCount, supplyDropRadiusMin,
                supplyDropRadiusMax, supplyDropOnlyOverworld, supplyDropAnnounceCoordinates,
                supplyDropCoordinateFuzz, supplyDropBeaconEnabled, supplyDropBaseMaterial,
                supplyDropProtected, supplyDropFireworkEnabled, supplyDropParticlesEnabled,
                monsterWaveDefaultMob, monsterWaveCountPerWave, monsterWaveWaveCount,
                monsterWaveIntervalSeconds, monsterWaveSpread, gamemasterEnabled, gamemasterDefaultMode,
                gamemasterKeepOp, gamemasterAllowTeleportMenu, gamemasterHideFromPlayerCount,
                gamemasterPermissionMode, teamMaxSize, teamMaxTeams, teamAllowSwitching,
                teamCaptainEnabled, teamPlayersCanCreate, teamPlayersChooseColour, teamLockPhase,
                sponsorsEnabled, sponsorTokensEnabled, sponsorTokenMaterial, sponsorTokenName,
                sponsorTokenLore, sponsorTokenIntervalMinutes, sponsorTokenAmountPerInterval,
                sponsorTokenFirstAfterMinutes, sponsorTokenMaxPerPlayer, sponsorTokenOnlyAlive,
                sponsorTokenAnnouncePersonal, sponsorTokenBroadcastMilestones, sponsorTokenDropOnDeath,
                sponsorTokenClearOnElimination, sponsorTokenClearOnRoundReset, sponsorBeaconsEnabled,
                sponsorBeaconSpawnMode, sponsorBeaconCentreOnStart, sponsorBeaconMaterial,
                sponsorBeaconBaseMaterial, sponsorBeaconProtected, sponsorBeaconRadiusMin,
                sponsorBeaconRadiusMax, sponsorBeaconSchedule, sponsorBeaconMaxActive,
                sponsorBeaconAnnounceSpawn, sponsorBeaconAnnounceCoordinates, sponsorBeaconCoordinateFuzz,
                sponsorBeaconParticles, sponsorBeaconSound, sponsorShopEnabled, sponsorShopItems,
                lootScanRadius, lootScanYRange, lootEditorEnabled, lootEditorAllowRuntimeEdits,
                lootEditorBackupBeforeSave, lootEditorMaxTestRolls, lootEditorAllowTestGive,
                lootEditorAllowTestChest, cornucopiaRadius, protectCornucopiaBeforeRunning,
                protectCornucopiaDuringRunning, protectCornucopiaAfterGame, protectionBypassPermission,
                announcementsEnabled, announceUseChat, announceUseTitle, announceUseActionbar,
                announceKillfeedEnabled, announceRemainingPlayersEnabled,
                announceRemainingPlayersThresholds, apiEnabled, apiBindAddress, apiPort, apiKey,
                apiReadOnly, fiendfinderGlowDuration, fiendfinderSearchRadius, smokeBombRadius,
                smokeBombEnemyDuration, smokeBombInvisSeconds, medikitRegenSeconds, medikitRegenLevel,
                medikitAbsorptionSeconds, medikitAbsorptionLevel, medikitCountdownSeconds, lightningRange,
                lightningBoltCount, lightningSpread, lightningBonusDamage, lightningDamageRadius,
                lightningFireTicks, lightningBoltDelay, lightningKnockup, hermesFlightSeconds,
                hermesWarningSeconds, krueckauRadius, krueckauNauseaSeconds, krueckauBlindnessSeconds,
                auraDurationSeconds, auraRadius, auraDamage, auraInterval, auraKnockback, auraAffectMobs,
                grapplingRange, grapplingPower, repulseRadius, repulseStrength, repulseSlowSeconds,
                repulseAffectMobs, feastGoldenApples, warKitMaterial, leapPower, exmatrikulatorDuration,
                exmatrikulatorRadius, exmatrikulatorInterval, exmatrikulatorDamage, exmatrikulatorMaxTargets,
                exmatrikulatorFireTicks, exmatrikulatorModules, exmatrikulatorDeathMessages,
                exmatrikulatorRecipe, stupidnessHealHearts, stupidnessRegenSeconds,
                stupidnessFireResistSeconds, stupidnessShoveRadius, stupidnessShoveStrength);
    }

    /** The same, for the border's fairness ceiling. */
    public HungerGamesSettings withBorderMaxEdgeSpeed(double blocksPerSecond) {
        return new HungerGamesSettings(
                preInitAdmins, gameDurationMinutes, gracePeriodSeconds, countdownSeconds, prepTimePercent,
                gameDifficulty, preflightDifficulty, deathAction, disconnectEliminationMinutes,
                offlineTimePolicy, adminDeopOnStart, adminReopOnElimination, adminReopOnFinish,
                adminCreativeOnElimination, adminTeleportCenterOnElimination, adminCenterYOffset,
                roundLogEnabled, roundLogFilePerRound, roundLogIncludeCoordinates, platformMinGap,
                platformWidth, undergroundRoomHeight, undergroundRoomExtraRadius, tubeDepth,
                blockNetherPortals, netherAllowRadius, blockEndPortals, startupLampDelay,
                startupLevitationStartDelay, startupPlayerLevitationDelay, startupLevitationAmplifier,
                lobbyHeightOffset, lobbyWidth, lobbyDepth, lobbyHeight, lobbyBlockType, borderInitialSize,
                borderMinimumSize, blocksPerSecond, borderScaleNether, borderPrepWarnings,
                borderShrinkWarning, deathmatchEnabled, deathmatchManualOnly, deathmatchTargetBorderSize,
                deathmatchWarningSeconds, deathmatchTeleportToCenter, deathmatchTeleportYOffset,
                deathmatchGraceAfterTeleportSeconds, deathmatchRequireConfirmation,
                deathmatchAllowedPhases, deathmatchBroadcastEnabled, deathmatchSoundEnabled,
                supplyDropsEnabled, supplyDropWarningSeconds, supplyDropCount, supplyDropRadiusMin,
                supplyDropRadiusMax, supplyDropOnlyOverworld, supplyDropAnnounceCoordinates,
                supplyDropCoordinateFuzz, supplyDropBeaconEnabled, supplyDropBaseMaterial,
                supplyDropProtected, supplyDropFireworkEnabled, supplyDropParticlesEnabled,
                monsterWaveDefaultMob, monsterWaveCountPerWave, monsterWaveWaveCount,
                monsterWaveIntervalSeconds, monsterWaveSpread, gamemasterEnabled, gamemasterDefaultMode,
                gamemasterKeepOp, gamemasterAllowTeleportMenu, gamemasterHideFromPlayerCount,
                gamemasterPermissionMode, teamMaxSize, teamMaxTeams, teamAllowSwitching,
                teamCaptainEnabled, teamPlayersCanCreate, teamPlayersChooseColour, teamLockPhase,
                sponsorsEnabled, sponsorTokensEnabled, sponsorTokenMaterial, sponsorTokenName,
                sponsorTokenLore, sponsorTokenIntervalMinutes, sponsorTokenAmountPerInterval,
                sponsorTokenFirstAfterMinutes, sponsorTokenMaxPerPlayer, sponsorTokenOnlyAlive,
                sponsorTokenAnnouncePersonal, sponsorTokenBroadcastMilestones, sponsorTokenDropOnDeath,
                sponsorTokenClearOnElimination, sponsorTokenClearOnRoundReset, sponsorBeaconsEnabled,
                sponsorBeaconSpawnMode, sponsorBeaconCentreOnStart, sponsorBeaconMaterial,
                sponsorBeaconBaseMaterial, sponsorBeaconProtected, sponsorBeaconRadiusMin,
                sponsorBeaconRadiusMax, sponsorBeaconSchedule, sponsorBeaconMaxActive,
                sponsorBeaconAnnounceSpawn, sponsorBeaconAnnounceCoordinates, sponsorBeaconCoordinateFuzz,
                sponsorBeaconParticles, sponsorBeaconSound, sponsorShopEnabled, sponsorShopItems,
                lootScanRadius, lootScanYRange, lootEditorEnabled, lootEditorAllowRuntimeEdits,
                lootEditorBackupBeforeSave, lootEditorMaxTestRolls, lootEditorAllowTestGive,
                lootEditorAllowTestChest, cornucopiaRadius, protectCornucopiaBeforeRunning,
                protectCornucopiaDuringRunning, protectCornucopiaAfterGame, protectionBypassPermission,
                announcementsEnabled, announceUseChat, announceUseTitle, announceUseActionbar,
                announceKillfeedEnabled, announceRemainingPlayersEnabled,
                announceRemainingPlayersThresholds, apiEnabled, apiBindAddress, apiPort, apiKey,
                apiReadOnly, fiendfinderGlowDuration, fiendfinderSearchRadius, smokeBombRadius,
                smokeBombEnemyDuration, smokeBombInvisSeconds, medikitRegenSeconds, medikitRegenLevel,
                medikitAbsorptionSeconds, medikitAbsorptionLevel, medikitCountdownSeconds, lightningRange,
                lightningBoltCount, lightningSpread, lightningBonusDamage, lightningDamageRadius,
                lightningFireTicks, lightningBoltDelay, lightningKnockup, hermesFlightSeconds,
                hermesWarningSeconds, krueckauRadius, krueckauNauseaSeconds, krueckauBlindnessSeconds,
                auraDurationSeconds, auraRadius, auraDamage, auraInterval, auraKnockback, auraAffectMobs,
                grapplingRange, grapplingPower, repulseRadius, repulseStrength, repulseSlowSeconds,
                repulseAffectMobs, feastGoldenApples, warKitMaterial, leapPower, exmatrikulatorDuration,
                exmatrikulatorRadius, exmatrikulatorInterval, exmatrikulatorDamage, exmatrikulatorMaxTargets,
                exmatrikulatorFireTicks, exmatrikulatorModules, exmatrikulatorDeathMessages,
                exmatrikulatorRecipe, stupidnessHealHearts, stupidnessRegenSeconds,
                stupidnessFireResistSeconds, stupidnessShoveRadius, stupidnessShoveStrength);
    }

    /** The same, for whether the deathmatch feature runs at all this round. */
    public HungerGamesSettings withDeathmatchEnabled(boolean enabled) {
        return new HungerGamesSettings(
                preInitAdmins, gameDurationMinutes, gracePeriodSeconds, countdownSeconds, prepTimePercent,
                gameDifficulty, preflightDifficulty, deathAction, disconnectEliminationMinutes,
                offlineTimePolicy, adminDeopOnStart, adminReopOnElimination, adminReopOnFinish,
                adminCreativeOnElimination, adminTeleportCenterOnElimination, adminCenterYOffset,
                roundLogEnabled, roundLogFilePerRound, roundLogIncludeCoordinates, platformMinGap,
                platformWidth, undergroundRoomHeight, undergroundRoomExtraRadius, tubeDepth,
                blockNetherPortals, netherAllowRadius, blockEndPortals, startupLampDelay,
                startupLevitationStartDelay, startupPlayerLevitationDelay, startupLevitationAmplifier,
                lobbyHeightOffset, lobbyWidth, lobbyDepth, lobbyHeight, lobbyBlockType, borderInitialSize,
                borderMinimumSize, borderMaxEdgeSpeed, borderScaleNether, borderPrepWarnings,
                borderShrinkWarning, enabled, deathmatchManualOnly, deathmatchTargetBorderSize,
                deathmatchWarningSeconds, deathmatchTeleportToCenter, deathmatchTeleportYOffset,
                deathmatchGraceAfterTeleportSeconds, deathmatchRequireConfirmation,
                deathmatchAllowedPhases, deathmatchBroadcastEnabled, deathmatchSoundEnabled,
                supplyDropsEnabled, supplyDropWarningSeconds, supplyDropCount, supplyDropRadiusMin,
                supplyDropRadiusMax, supplyDropOnlyOverworld, supplyDropAnnounceCoordinates,
                supplyDropCoordinateFuzz, supplyDropBeaconEnabled, supplyDropBaseMaterial,
                supplyDropProtected, supplyDropFireworkEnabled, supplyDropParticlesEnabled,
                monsterWaveDefaultMob, monsterWaveCountPerWave, monsterWaveWaveCount,
                monsterWaveIntervalSeconds, monsterWaveSpread, gamemasterEnabled, gamemasterDefaultMode,
                gamemasterKeepOp, gamemasterAllowTeleportMenu, gamemasterHideFromPlayerCount,
                gamemasterPermissionMode, teamMaxSize, teamMaxTeams, teamAllowSwitching,
                teamCaptainEnabled, teamPlayersCanCreate, teamPlayersChooseColour, teamLockPhase,
                sponsorsEnabled, sponsorTokensEnabled, sponsorTokenMaterial, sponsorTokenName,
                sponsorTokenLore, sponsorTokenIntervalMinutes, sponsorTokenAmountPerInterval,
                sponsorTokenFirstAfterMinutes, sponsorTokenMaxPerPlayer, sponsorTokenOnlyAlive,
                sponsorTokenAnnouncePersonal, sponsorTokenBroadcastMilestones, sponsorTokenDropOnDeath,
                sponsorTokenClearOnElimination, sponsorTokenClearOnRoundReset, sponsorBeaconsEnabled,
                sponsorBeaconSpawnMode, sponsorBeaconCentreOnStart, sponsorBeaconMaterial,
                sponsorBeaconBaseMaterial, sponsorBeaconProtected, sponsorBeaconRadiusMin,
                sponsorBeaconRadiusMax, sponsorBeaconSchedule, sponsorBeaconMaxActive,
                sponsorBeaconAnnounceSpawn, sponsorBeaconAnnounceCoordinates, sponsorBeaconCoordinateFuzz,
                sponsorBeaconParticles, sponsorBeaconSound, sponsorShopEnabled, sponsorShopItems,
                lootScanRadius, lootScanYRange, lootEditorEnabled, lootEditorAllowRuntimeEdits,
                lootEditorBackupBeforeSave, lootEditorMaxTestRolls, lootEditorAllowTestGive,
                lootEditorAllowTestChest, cornucopiaRadius, protectCornucopiaBeforeRunning,
                protectCornucopiaDuringRunning, protectCornucopiaAfterGame, protectionBypassPermission,
                announcementsEnabled, announceUseChat, announceUseTitle, announceUseActionbar,
                announceKillfeedEnabled, announceRemainingPlayersEnabled,
                announceRemainingPlayersThresholds, apiEnabled, apiBindAddress, apiPort, apiKey,
                apiReadOnly, fiendfinderGlowDuration, fiendfinderSearchRadius, smokeBombRadius,
                smokeBombEnemyDuration, smokeBombInvisSeconds, medikitRegenSeconds, medikitRegenLevel,
                medikitAbsorptionSeconds, medikitAbsorptionLevel, medikitCountdownSeconds, lightningRange,
                lightningBoltCount, lightningSpread, lightningBonusDamage, lightningDamageRadius,
                lightningFireTicks, lightningBoltDelay, lightningKnockup, hermesFlightSeconds,
                hermesWarningSeconds, krueckauRadius, krueckauNauseaSeconds, krueckauBlindnessSeconds,
                auraDurationSeconds, auraRadius, auraDamage, auraInterval, auraKnockback, auraAffectMobs,
                grapplingRange, grapplingPower, repulseRadius, repulseStrength, repulseSlowSeconds,
                repulseAffectMobs, feastGoldenApples, warKitMaterial, leapPower, exmatrikulatorDuration,
                exmatrikulatorRadius, exmatrikulatorInterval, exmatrikulatorDamage, exmatrikulatorMaxTargets,
                exmatrikulatorFireTicks, exmatrikulatorModules, exmatrikulatorDeathMessages,
                exmatrikulatorRecipe, stupidnessHealHearts, stupidnessRegenSeconds,
                stupidnessFireResistSeconds, stupidnessShoveRadius, stupidnessShoveStrength);
    }
}
