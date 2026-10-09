package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * The speedrun lobby's own settings: which world it runs in and what ends a run. Its own
 * {@code SettingsStore}/{@code speedrun.yml} rather than a growing {@code CoreConfig}, the same way
 * {@code FarmWorldConfigMenu} keeps its module's settings apart from the rest of the server's.
 *
 * <p>The GUI writes through this record's own {@code SettingsStore} — {@code set}/{@code cycle} — so
 * a click and a hand-edited {@code speedrun.yml} can never disagree.
 *
 * <h2>Why these sit at the top of {@code /settings} rather than under "Server settings"</h2>
 * They used to be one page at {@code config/speedrun}, filed beside Core's own chat, logging, packs
 * and safety pages. That is where a handful of server knobs belong; a whole game mode is not a knob,
 * and next to {@code manhunt}, which had a root of its own, it read as though Speedrun's settings had
 * gone missing entirely — which is exactly how it was reported. So {@code speedrun} is a root now,
 * with the three questions it actually holds behind it: the race, the hazard, and the start line.
 *
 * <p><b>Nothing moved in the file.</b> A topic path is where a setting is <em>shown</em>; the
 * {@code @Key} is where it is stored, and no key changed, so an existing {@code config.yml} keeps
 * every value it had.
 */
@Settings(id = "speedrun", topics = {
        @Topic(path = "speedrun", title = "Speedrun", icon = Material.NETHER_STAR,
                description = "Which world races run in, what ends one, and what is in the way."),
        @Topic(path = "speedrun/race", title = "The race", icon = Material.WRITABLE_BOOK,
                description = "The world it runs in, and what ends a run."),
        @Topic(path = "speedrun/hazard", title = "The creeper hazard", icon = Material.CREEPER_HEAD,
                description = "Creepers where a racer mines or loots. Off until a host turns it on."),
        @Topic(path = "speedrun/start", title = "The start point", icon = Material.LODESTONE,
                description = "Where /starthere put the line every run begins on."),
        @Topic(path = "speedrun/explosives", title = "What players may blow up", icon = Material.TNT,
                description = "Beds, respawn anchors, TNT and end crystals — each one switchable per "
                        + "dimension, so a ruleset can ban bed bombing without banning the dragon fight."),
        @Topic(path = "speedrun/lobby", title = "The lobby itself", icon = Material.LIME_CONCRETE,
                description = "Who may start a run, what can hurt anybody waiting for one, and what "
                        + "happens the moment one ends."),
        @Topic(path = "speedrun/seeds", title = "Seeds", icon = Material.WHEAT_SEEDS,
                description = "Which seed the next world is made from: a new one each time, always the "
                        + "same, or one of a pool. Set-seed runs are ranked apart from random ones."),
        @Topic(path = "speedrun/splits", title = "Splits and records", icon = Material.CLOCK,
                description = "The milestones a run is split at, how they are shown, and which runs "
                        + "count toward personal bests and records."),
        @Topic(path = "speedrun/economy", title = "Entry fee and prizes", icon = Material.GOLD_INGOT,
                description = "What a run costs to enter, and how the fees are paid out to the winners."),
        @Topic(path = "speedrun/practice", title = "Practice", icon = Material.TARGET,
                description = "Start a run with a kit to practise one part of it. Practice runs are "
                        + "kept, and ranked on their own."),
})
public record SpeedrunSettings(

        @In("speedrun/race") @Title("Game mode")
        @Describe("Which game is played in this lobby. Empty is a plain race; 'manhunt' is Runners "
                + "against Hunters, and needs RainsManhunt installed. A mode keeps everything a race "
                + "already has — this world, the compass, the countdown, the clock and the reset — "
                + "and only adds what makes it a different game.")
        String gameMode,

        @In("speedrun/race") @Title("Lobby world")
        @Describe("The Bukkit world the lobby, and every run, takes place in.")
        String worldName,

        @In("speedrun/race") @Title("Advancement goal")
        @Describe("The advancement that ends a run, as 'namespace:path'. Empty means none.")
        String advancementKey,

        @In("speedrun/race") @Title("Clear advancements at the start")
        @Describe("Whether every racer's advancements are wiped as a run starts. On — the default — "
                + "a repeat racer plays the same game a first-timer does: the screen empty, every "
                + "toast still to come, and a goal that can actually be granted again. Off leaves "
                + "everybody's saved progress alone, and only the goal itself is cleared.")
        boolean clearAdvancementsOnStart,

        @In("speedrun/race") @Title("Death policy")
        @Describe("Whether a death ends the run, and whether one death is enough.")
        SpeedrunDeathPolicy deathPolicy,

        @In("speedrun/race") @Title("Require the exit portal")
        @Describe("When the advancement goal is the vanilla dragon kill, whether killing it is only "
                + "the first half — a run does not end until a participant then steps into the exit "
                + "portal, the way an actual dragon-kill speedrun is judged. Has no effect on any other "
                + "advancement goal, or when death alone ends the run.")
        boolean requireExitPortalAfterDragon,

        @In("speedrun/hazard") @Title("Creeper chance (block break)") @Range(min = 0, max = 100)
        @Describe("Chance, in percent, that a racer breaking a block during a run spawns a creeper "
                + "right where it broke. 0 turns the hazard off; 100 is the old plain on/off toggle's "
                + "'on'.")
        int creeperSpawnChanceOnBreakPercent,

        @In("speedrun/hazard") @Title("Charged creeper chance (block break)") @Range(min = 0, max = 100)
        @Describe("Of a creeper spawned by breaking a block, the chance in percent that it is charged "
                + "(powered) instead of an ordinary one.")
        int chargedCreeperChanceOnBreakPercent,

        @In("speedrun/hazard") @Title("Creeper chance (container open)") @Range(min = 0, max = 100)
        @Describe("Chance, in percent, that a racer opening a chest or other container during a run "
                + "spawns a creeper right where it stands — set separately from the block-break chance, "
                + "since opening loot chests is a very different risk than mining.")
        int creeperSpawnChanceOnContainerPercent,

        @In("speedrun/hazard") @Title("Charged creeper chance (container open)") @Range(min = 0, max = 100)
        @Describe("Of a creeper spawned by opening a container, the chance in percent that it is "
                + "charged (powered) instead of an ordinary one.")
        int chargedCreeperChanceOnContainerPercent,

        @In("speedrun/start") @Title("Start point set")
        @Describe("Whether /starthere has set a start point. Off means nobody is teleported when a "
                + "countdown begins — racers start wherever they were standing when it caught them.")
        boolean startPointSet,

        @In("speedrun/start") @Title("Start X")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startX,

        @In("speedrun/start") @Title("Start Y")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startY,

        @In("speedrun/start") @Title("Start Z")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startZ,

        @In("speedrun/start") @Title("Start yaw")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startYaw,

        @In("speedrun/start") @Title("Start pitch")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startPitch,

        @In("speedrun/lobby") @Title("Only staff get the start block")
        @Describe("Whether the green block is handed only to somebody holding rainsspeedrun.start — "
                + "operators, by default — and only they may press one. Off, everybody in the lobby "
                + "is handed one and anybody can start the next run.")
        boolean startBlockStaffOnly,

        @In("speedrun/lobby") @Title("Nobody can be hurt in the lobby")
        @Describe("Whether damage to anybody standing in the lobby world is refused while no run is "
                + "under way — including damage another player deals. Off, waiting for a run to start "
                + "is as dangerous as anywhere else.")
        boolean lobbyProtected,

        @In("speedrun/lobby") @Title("Nothing explodes in the lobby")
        @Describe("Whether an explosion in the lobby world is refused outright while no run is under "
                + "way, so a creeper cannot take the start line — or anybody standing on it — with it. "
                + "Nothing is refused once a run has actually begun.")
        boolean lobbyExplosionsBlocked,

        @In("speedrun/lobby") @Title("Show the clock to onlookers")
        @Describe("Whether the run's clock is on the action bar of everybody in the lobby world "
                + "rather than the racers alone, so somebody who arrived mid-run sees how long it has "
                + "been going.")
        boolean showTimerToOnlookers,

        @In("speedrun/lobby") @Title("Reset for another run when one ends")
        @Describe("Whether a finished run remakes the world by itself and hands the lobby items back, "
                + "instead of waiting for every racer to leave the server first.")
        boolean restartWhenRunEnds,

        @In("speedrun/lobby") @Title("Wait before that reset (seconds)") @Range(min = 0, max = 300)
        @Describe("How long a finished run is left standing before the world is remade — long enough "
                + "to read who won and look at where it ended, short enough that nobody is waiting.")
        int restartAfterSeconds,

        @In("speedrun/race") @Title("Set the time when a run starts")
        @Describe("Whether the lobby world's clock is set when a run begins. Off, a run starts at "
                + "whatever time of day the world happens to be at.")
        boolean setTimeOnStart,

        @In("speedrun/race") @Title("The time a run starts at") @Range(min = 0, max = 24000)
        @Describe("Which tick of the Minecraft day a run starts at, when the setting above is on. "
                + "1000 is a normal morning; 0 is sunrise, 6000 midday, 13000 nightfall.")
        int startTimeTicks,

        @In("speedrun/explosives") @Title("Beds explode in the Nether")
        @Describe("Whether clicking a bed in the run's Nether does what it does in vanilla. Off, the "
                + "click simply does nothing — the classic 'no bed bombing' rule.")
        boolean bedExplosionsInNether,

        @In("speedrun/explosives") @Title("Beds explode in the End")
        @Describe("The same for the run's End, set separately: a bed is a different weapon in the "
                + "dragon fight than it is in the Nether.")
        boolean bedExplosionsInTheEnd,

        @In("speedrun/explosives") @Title("Respawn anchors explode in the Overworld")
        @Describe("Whether a respawn anchor used in the run's Overworld does what it does in vanilla. "
                + "An anchor is the mirror of a bed: it works in the Nether and explodes everywhere "
                + "else.")
        boolean anchorExplosionsInOverworld,

        @In("speedrun/explosives") @Title("Respawn anchors explode in the End")
        @Describe("The same for the run's End, set separately.")
        boolean anchorExplosionsInTheEnd,

        @In("speedrun/explosives") @Title("TNT explodes in the Overworld")
        @Describe("Whether TNT — including a minecart with TNT in it, which is the same trick — goes "
                + "off in the run's Overworld.")
        boolean tntInOverworld,

        @In("speedrun/explosives") @Title("TNT explodes in the Nether")
        @Describe("The same for the run's Nether.")
        boolean tntInNether,

        @In("speedrun/explosives") @Title("TNT explodes in the End")
        @Describe("The same for the run's End.")
        boolean tntInTheEnd,

        @In("speedrun/explosives") @Title("End crystals explode in the Overworld")
        @Describe("Whether an end crystal goes off in the run's Overworld.")
        boolean endCrystalsInOverworld,

        @In("speedrun/explosives") @Title("End crystals explode in the Nether")
        @Describe("The same for the run's Nether.")
        boolean endCrystalsInNether,

        @In("speedrun/explosives") @Title("End crystals explode in the End")
        @Describe("The same for the run's End — the one that decides whether the dragon can be "
                + "respawned, and whether her healing crystals can be shot down.")
        boolean endCrystalsInTheEnd,

        @In("speedrun/lobby") @Title("Players may change the world before a run")
        @Describe("Whether an ordinary player can break or place a block, use a bucket, open a "
                + "chest or use any other block anywhere on the server while no run is under way. "
                + "Off — the default — nobody can strip the start line, loot, or scout the map, in "
                + "the wait before a race. Anybody holding rainsspeedrun.admin is exempt, "
                + "since somebody has to be able to build the lobby.")
        boolean breakingBlocksBeforeRuns,

        @In("speedrun/lobby") @Title("Monsters hunt players before a run")
        @Describe("Whether a mob may pick a player as its target while no run is under way, anywhere "
                + "on the server. Off — the default — everything ignores everybody until the clock "
                + "starts, so waiting for a race is not a fight.")
        boolean monstersHuntBeforeRuns,

        @In("speedrun/seeds") @Title("Seed mode")
        @Describe("RANDOM makes every new world from a fresh seed. FIXED always uses 'seed'. POOL picks "
                + "one of 'seed-pool' at random for each new world.")
        SpeedrunSeedMode seedMode,

        @In("speedrun/seeds") @Title("Seed")
        @Describe("The seed FIXED uses — a number, or a word the way the create-world screen reads one.")
        String seed,

        @In("speedrun/seeds") @Title("Seed pool")
        @Describe("The seeds POOL picks from, separated by commas or spaces.")
        String seedPool,

        @In("speedrun/splits") @Title("Pearls to collect") @Range(min = 1, max = 64)
        @Describe("How many ender pearls the racers together have to pick up for the 'Pearls' split.")
        int pearlTarget,

        @In("speedrun/splits") @Title("Where splits are shown")
        @Describe("SIDEBAR, BOSSBAR, ACTIONBAR or OFF — for everybody who has not picked their own "
                + "with the button in the menu.")
        SpeedrunHudMode hudDefault,

        @In("speedrun/splits") @Title("Announce splits in chat")
        @Describe("Whether every split is said in chat, with how it compares to your personal best and "
                + "the server record.")
        boolean splitAnnouncements,

        @In("speedrun/splits") @Title("Celebrate gold splits")
        @Describe("A title and a sound when a split beats the best time anybody ever reached it in.")
        boolean goldSplitCelebration,

        @In("speedrun/splits") @Title("Resumed and edited runs rank")
        @Describe("Whether a run picked up with /speedrunresume, or whose clock was set by hand, counts "
                + "toward personal bests and records. Off — the default — keeps them in the history, "
                + "flagged, and off the leaderboards.")
        boolean rankEditedRuns,

        @In("speedrun/practice") @Title("Practice kit")
        @Describe("NONE for a real run. Any kit is handed to every racer at the start and makes the run "
                + "a practice run: BLAZE_AND_PEARLS, EYES_OF_ENDER or DRAGON_FIGHT.")
        SpeedrunPracticeKit practiceKit,

        @In("speedrun/lobby") @Title("Setup finished")
        @Describe("Set by the setup assistant. Off, every admin is offered the assistant when they join.")
        boolean setupDone,

        @In("speedrun/lobby") @Title("Players who join a run under way")
        @Describe("OFF — the default — leaves somebody who joins mid-run alone: they look on. SPECTATE puts "
                + "them in spectator mode in the run's world until it is over. RACE makes them a racer from "
                + "that moment: a clean slate, the practice kit, the game's own items (Manhunt's compasses "
                + "for their side), a safe spot near the start, the clock and the sidebar. A late racer's "
                + "own result never ranks. Somebody who was racing in this run and comes back is never a "
                + "latecomer. While a hunt keeps the server's whitelist closed, nobody new gets in at all.")
        SpeedrunLateJoin lateJoin,

        @In("speedrun/economy") @Title("Entry fee")
        @Describe("What every racer pays when a run starts, written like 12.50 or 1.5k. 0 is free, and then "
                + "no economy plugin is needed at all. The fees of a run form its prize pot.")
        @Key("economy.entry-fee")
        String entryFee,

        @In("speedrun/economy") @Title("House cut (percent)") @Range(min = 0, max = 100)
        @Describe("The part of the pot that is not paid out. It stays with the server.")
        @Key("economy.house-cut-percent")
        int houseCutPercent,

        @In("speedrun/economy") @Title("Prize split")
        @Describe("How the rest of the pot is shared by place, as weights: 100 is winner takes all, 60,30,10 "
                + "gives first, second and third place that share. A winning side or team shares its place "
                + "evenly. With fewer places than weights the unused weights are dropped.")
        @Key("economy.prize-split")
        String prizeSplit

) {

    /** The settings as they stood before entry fees existed — {@code economy.*} at its defaults. */
    public SpeedrunSettings(String gameMode, String worldName, String advancementKey,
                            boolean clearAdvancementsOnStart, SpeedrunDeathPolicy deathPolicy,
                            boolean requireExitPortalAfterDragon, int creeperSpawnChanceOnBreakPercent,
                            int chargedCreeperChanceOnBreakPercent, int creeperSpawnChanceOnContainerPercent,
                            int chargedCreeperChanceOnContainerPercent, boolean startPointSet, double startX,
                            double startY, double startZ, double startYaw, double startPitch,
                            boolean startBlockStaffOnly, boolean lobbyProtected, boolean lobbyExplosionsBlocked,
                            boolean showTimerToOnlookers, boolean restartWhenRunEnds, int restartAfterSeconds,
                            boolean setTimeOnStart, int startTimeTicks, boolean bedExplosionsInNether,
                            boolean bedExplosionsInTheEnd, boolean anchorExplosionsInOverworld,
                            boolean anchorExplosionsInTheEnd, boolean tntInOverworld, boolean tntInNether,
                            boolean tntInTheEnd, boolean endCrystalsInOverworld, boolean endCrystalsInNether,
                            boolean endCrystalsInTheEnd, boolean breakingBlocksBeforeRuns,
                            boolean monstersHuntBeforeRuns, SpeedrunSeedMode seedMode, String seed, String seedPool,
                            int pearlTarget, SpeedrunHudMode hudDefault, boolean splitAnnouncements,
                            boolean goldSplitCelebration, boolean rankEditedRuns, SpeedrunPracticeKit practiceKit,
                            boolean setupDone, SpeedrunLateJoin lateJoin) {
        this(gameMode, worldName, advancementKey, clearAdvancementsOnStart, deathPolicy,
                requireExitPortalAfterDragon, creeperSpawnChanceOnBreakPercent,
                chargedCreeperChanceOnBreakPercent, creeperSpawnChanceOnContainerPercent,
                chargedCreeperChanceOnContainerPercent, startPointSet, startX, startY, startZ, startYaw,
                startPitch, startBlockStaffOnly, lobbyProtected, lobbyExplosionsBlocked,
                showTimerToOnlookers, restartWhenRunEnds, restartAfterSeconds, setTimeOnStart,
                startTimeTicks, bedExplosionsInNether, bedExplosionsInTheEnd, anchorExplosionsInOverworld,
                anchorExplosionsInTheEnd, tntInOverworld, tntInNether, tntInTheEnd, endCrystalsInOverworld,
                endCrystalsInNether, endCrystalsInTheEnd, breakingBlocksBeforeRuns, monstersHuntBeforeRuns,
                seedMode, seed, seedPool, pearlTarget, hudDefault, splitAnnouncements, goldSplitCelebration,
                rankEditedRuns, practiceKit, setupDone, lateJoin, "0", 0, "100");
    }

    /** The settings as they stood before late joining existed — {@code late-join} OFF. */
    public SpeedrunSettings(String gameMode, String worldName, String advancementKey,
                            boolean clearAdvancementsOnStart, SpeedrunDeathPolicy deathPolicy,
                            boolean requireExitPortalAfterDragon, int creeperSpawnChanceOnBreakPercent,
                            int chargedCreeperChanceOnBreakPercent, int creeperSpawnChanceOnContainerPercent,
                            int chargedCreeperChanceOnContainerPercent, boolean startPointSet, double startX,
                            double startY, double startZ, double startYaw, double startPitch,
                            boolean startBlockStaffOnly, boolean lobbyProtected, boolean lobbyExplosionsBlocked,
                            boolean showTimerToOnlookers, boolean restartWhenRunEnds, int restartAfterSeconds,
                            boolean setTimeOnStart, int startTimeTicks, boolean bedExplosionsInNether,
                            boolean bedExplosionsInTheEnd, boolean anchorExplosionsInOverworld,
                            boolean anchorExplosionsInTheEnd, boolean tntInOverworld, boolean tntInNether,
                            boolean tntInTheEnd, boolean endCrystalsInOverworld, boolean endCrystalsInNether,
                            boolean endCrystalsInTheEnd, boolean breakingBlocksBeforeRuns,
                            boolean monstersHuntBeforeRuns, SpeedrunSeedMode seedMode, String seed, String seedPool,
                            int pearlTarget, SpeedrunHudMode hudDefault, boolean splitAnnouncements,
                            boolean goldSplitCelebration, boolean rankEditedRuns, SpeedrunPracticeKit practiceKit,
                            boolean setupDone) {
        this(gameMode, worldName, advancementKey, clearAdvancementsOnStart, deathPolicy,
                requireExitPortalAfterDragon, creeperSpawnChanceOnBreakPercent,
                chargedCreeperChanceOnBreakPercent, creeperSpawnChanceOnContainerPercent,
                chargedCreeperChanceOnContainerPercent, startPointSet, startX, startY, startZ, startYaw,
                startPitch, startBlockStaffOnly, lobbyProtected, lobbyExplosionsBlocked,
                showTimerToOnlookers, restartWhenRunEnds, restartAfterSeconds, setTimeOnStart,
                startTimeTicks, bedExplosionsInNether, bedExplosionsInTheEnd, anchorExplosionsInOverworld,
                anchorExplosionsInTheEnd, tntInOverworld, tntInNether, tntInTheEnd, endCrystalsInOverworld,
                endCrystalsInNether, endCrystalsInTheEnd, breakingBlocksBeforeRuns, monstersHuntBeforeRuns,
                seedMode, seed, seedPool, pearlTarget, hudDefault, splitAnnouncements, goldSplitCelebration,
                rankEditedRuns, practiceKit, setupDone, SpeedrunLateJoin.OFF, "0", 0, "100");
    }

    /** The settings as they stood before seeds, splits and practice existed — every new one at its default. */
    public SpeedrunSettings(String gameMode, String worldName, String advancementKey,
                            boolean clearAdvancementsOnStart, SpeedrunDeathPolicy deathPolicy,
                            boolean requireExitPortalAfterDragon, int creeperSpawnChanceOnBreakPercent,
                            int chargedCreeperChanceOnBreakPercent, int creeperSpawnChanceOnContainerPercent,
                            int chargedCreeperChanceOnContainerPercent, boolean startPointSet, double startX,
                            double startY, double startZ, double startYaw, double startPitch,
                            boolean startBlockStaffOnly, boolean lobbyProtected, boolean lobbyExplosionsBlocked,
                            boolean showTimerToOnlookers, boolean restartWhenRunEnds, int restartAfterSeconds,
                            boolean setTimeOnStart, int startTimeTicks, boolean bedExplosionsInNether,
                            boolean bedExplosionsInTheEnd, boolean anchorExplosionsInOverworld,
                            boolean anchorExplosionsInTheEnd, boolean tntInOverworld, boolean tntInNether,
                            boolean tntInTheEnd, boolean endCrystalsInOverworld, boolean endCrystalsInNether,
                            boolean endCrystalsInTheEnd, boolean breakingBlocksBeforeRuns,
                            boolean monstersHuntBeforeRuns) {
        this(gameMode, worldName, advancementKey, clearAdvancementsOnStart, deathPolicy,
                requireExitPortalAfterDragon, creeperSpawnChanceOnBreakPercent,
                chargedCreeperChanceOnBreakPercent, creeperSpawnChanceOnContainerPercent,
                chargedCreeperChanceOnContainerPercent, startPointSet, startX, startY, startZ, startYaw,
                startPitch, startBlockStaffOnly, lobbyProtected, lobbyExplosionsBlocked,
                showTimerToOnlookers, restartWhenRunEnds, restartAfterSeconds, setTimeOnStart,
                startTimeTicks, bedExplosionsInNether, bedExplosionsInTheEnd, anchorExplosionsInOverworld,
                anchorExplosionsInTheEnd, tntInOverworld, tntInNether, tntInTheEnd, endCrystalsInOverworld,
                endCrystalsInNether, endCrystalsInTheEnd, breakingBlocksBeforeRuns, monstersHuntBeforeRuns,
                SpeedrunSeedMode.RANDOM, "", "", 12, SpeedrunHudMode.SIDEBAR, true, true, false,
                SpeedrunPracticeKit.NONE, false, SpeedrunLateJoin.OFF, "0", 0, "100");
    }

    /** The advancement key {@link #requireExitPortalAfterDragon} looks for — vanilla's own dragon kill. */
    public static final String DRAGON_KILL_ADVANCEMENT = "minecraft:end/kill_dragon";

    /**
     * The world name a fresh install ships with — {@code SpeedrunModule.enable} creates it itself if
     * nothing by this name is loaded yet, which is exactly what makes this a dedicated name rather than
     * the server's own primary world.
     *
     * <p><b>Found the hard way:</b> the previous default was the primary world itself, {@code "world"}
     * — chosen because nothing here ever created a world, so a placeholder name nobody had made yet
     * meant {@link SpeedrunLobby#start} answered {@code WORLD_MISSING} for a reason nobody saw, and
     * the compass simply did nothing. Fixing the missing-world problem this way, instead, uncovered a
     * second one: the primary world can never actually be unloaded, at all, ever — Bukkit refuses
     * unconditionally — so every reset on an install that had not renamed it away from the default
     * failed too, just later and with a less obvious cause. A dedicated name the module creates itself
     * has neither problem.
     */
    public static final String DEFAULT_WORLD_NAME = "speedrun";

    /**
     * What a fresh install ships with: the vanilla dragon kill, nobody's death ends it, and the portal
     * requirement on, since a run that stops timing the instant the dragon dies is not how anybody
     * actually races this goal.
     *
     * <p><b>Every creeper chance is zero.</b> The hazard used to default to 100% on both block breaks
     * and container opens, which meant installing this module changed the game for anybody who raced
     * before finding the setting — mining a block spawned a creeper, every time. A hazard is something
     * a host turns on for a particular evening, not the shape of a plain speedrun, so the default is
     * off and the whole feature costs nothing until somebody asks for it.
     */
    public static final SpeedrunSettings DEFAULTS = new SpeedrunSettings(
            "", DEFAULT_WORLD_NAME, DRAGON_KILL_ADVANCEMENT, true, SpeedrunDeathPolicy.OFF, true, 0, 0, 0, 0,
            false, 0, 0, 0, 0, 0,
            true, true, true, true, true, 10, true, (int) SpeedrunPreparation.DAY_START,
            true, true, true, true, true, true, true, true, true, true,
            false, false,
            SpeedrunSeedMode.RANDOM, "", "", 12, SpeedrunHudMode.SIDEBAR, true, true, false,
            SpeedrunPracticeKit.NONE, false, SpeedrunLateJoin.OFF, "0", 0, "100");

    /** Whether a game mode is chosen at all — an empty id is the plain race. */
    /** The same settings in {@code name} — how a run keeps its world while {@code world-name} changes. */
    public SpeedrunSettings withWorldName(String name) {
        return new SpeedrunSettings(gameMode, name, advancementKey, clearAdvancementsOnStart, deathPolicy,
                requireExitPortalAfterDragon, creeperSpawnChanceOnBreakPercent,
                chargedCreeperChanceOnBreakPercent, creeperSpawnChanceOnContainerPercent,
                chargedCreeperChanceOnContainerPercent, startPointSet, startX, startY, startZ, startYaw,
                startPitch, startBlockStaffOnly, lobbyProtected, lobbyExplosionsBlocked,
                showTimerToOnlookers, restartWhenRunEnds, restartAfterSeconds, setTimeOnStart,
                startTimeTicks, bedExplosionsInNether, bedExplosionsInTheEnd, anchorExplosionsInOverworld,
                anchorExplosionsInTheEnd, tntInOverworld, tntInNether, tntInTheEnd, endCrystalsInOverworld,
                endCrystalsInNether, endCrystalsInTheEnd, breakingBlocksBeforeRuns, monstersHuntBeforeRuns,
                seedMode, seed, seedPool, pearlTarget, hudDefault, splitAnnouncements, goldSplitCelebration,
                rankEditedRuns, practiceKit, setupDone, lateJoin, entryFee, houseCutPercent, prizeSplit);
    }

    /** The same settings with the entry fee, the house cut and the prize split changed. */
    public SpeedrunSettings withEconomy(String fee, int cutPercent, String split) {
        return new SpeedrunSettings(gameMode, worldName, advancementKey, clearAdvancementsOnStart, deathPolicy,
                requireExitPortalAfterDragon, creeperSpawnChanceOnBreakPercent,
                chargedCreeperChanceOnBreakPercent, creeperSpawnChanceOnContainerPercent,
                chargedCreeperChanceOnContainerPercent, startPointSet, startX, startY, startZ, startYaw,
                startPitch, startBlockStaffOnly, lobbyProtected, lobbyExplosionsBlocked,
                showTimerToOnlookers, restartWhenRunEnds, restartAfterSeconds, setTimeOnStart,
                startTimeTicks, bedExplosionsInNether, bedExplosionsInTheEnd, anchorExplosionsInOverworld,
                anchorExplosionsInTheEnd, tntInOverworld, tntInNether, tntInTheEnd, endCrystalsInOverworld,
                endCrystalsInNether, endCrystalsInTheEnd, breakingBlocksBeforeRuns, monstersHuntBeforeRuns,
                seedMode, seed, seedPool, pearlTarget, hudDefault, splitAnnouncements, goldSplitCelebration,
                rankEditedRuns, practiceKit, setupDone, lateJoin, fee, cutPercent, split);
    }

    public boolean hasGameMode() {
        return gameMode != null && !gameMode.isBlank();
    }

    /** Whether the configured goal is specifically the vanilla dragon kill — the only goal
     *  {@link #requireExitPortalAfterDragon} means anything for. */
    public boolean isDragonKillGoal() {
        return DRAGON_KILL_ADVANCEMENT.equals(advancementKey);
    }

    /** Whether an advancement goal is actually set — an empty key is "none", not a bad one. */
    public boolean hasAdvancementGoal() {
        return advancementKey != null && !advancementKey.isBlank();
    }

    /** Whether death ends the run at all. */
    public boolean hasDeathCondition() {
        return deathPolicy != null && deathPolicy != SpeedrunDeathPolicy.OFF;
    }

    /** Whether there is anything at all that could end a run started with this configuration. */
    public boolean hasEndCondition() {
        return hasAdvancementGoal() || hasDeathCondition();
    }

    /**
     * The time of day {@link SpeedrunPreparation} puts the world at when a run begins, or
     * {@link SpeedrunPreparation#LEAVE_THE_TIME_ALONE} when the host turned that off — one answer
     * rather than two settings to read in the right order at every call site.
     */
    public long timeAtStart() {
        if (!setTimeOnStart) {
            return SpeedrunPreparation.LEAVE_THE_TIME_ALONE;
        }
        return Math.max(0, Math.min(24000, startTimeTicks));
    }

    /** The wait before a finished run remakes the world, in ticks — see {@link #restartWhenRunEnds}. */
    /** The ender pearls the 'Pearls' split waits for, kept within its range. */
    public int pearlsToCollect() {
        return Math.max(1, Math.min(64, pearlTarget));
    }

    public SpeedrunHudMode hudDefaultOrSidebar() {
        return hudDefault == null ? SpeedrunHudMode.SIDEBAR : hudDefault;
    }

    public SpeedrunPracticeKit kit() {
        return practiceKit == null ? SpeedrunPracticeKit.NONE : practiceKit;
    }

    public SpeedrunLateJoin lateJoinOrOff() {
        return lateJoin == null ? SpeedrunLateJoin.OFF : lateJoin;
    }

    public long restartDelayTicks() {
        return Math.max(0, Math.min(300, restartAfterSeconds)) * 20L;
    }
}
