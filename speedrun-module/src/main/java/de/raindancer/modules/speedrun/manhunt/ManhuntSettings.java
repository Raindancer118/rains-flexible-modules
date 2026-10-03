package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What a server owner can change about a hunt.
 *
 * <h2>Why so few</h2>
 * The module this replaces had forty-five settings, and most of them existed because it owned things
 * the speedrun lobby already owns: its own world, its own spawn, its own seed, its own countdown, its own
 * map reset. A mode owns none of that any more — the world, the goal, the countdown, the hazard and
 * the reset are {@code speedrun.yml}'s, in one place, for both games. What is left here is what only
 * a hunt has: the compass, who may pick a side, and the door.
 */
@Settings(id = "manhunt", topics = {
        @Topic(path = "speedrun/manhunt", title = "Manhunt", icon = Material.TARGET,
                description = "Runners against Hunters, played in the speedrun lobby."),
        @Topic(path = "speedrun/manhunt/tracker", title = "The tracking compass", icon = Material.COMPASS,
                description = "What the Hunters' compass follows, and how closely."),
        @Topic(path = "speedrun/manhunt/sides", title = "The sides", icon = Material.LIME_BANNER,
                description = "Who may put themselves on the Runner side."),
        @Topic(path = "speedrun/manhunt/doors", title = "The door", icon = Material.IRON_DOOR,
                description = "What a hunt does to the server whitelist."),
        @Topic(path = "speedrun/manhunt/start", title = "The start", icon = Material.CLOCK,
                description = "Where everybody stands, and how long the Runners get before the chase."),
        @Topic(path = "speedrun/manhunt/variants", title = "Variants", icon = Material.TOTEM_OF_UNDYING,
                description = "Lives, respawn waits, glowing Runners — the rules a hunt can be played by."),
        @Topic(path = "speedrun/manhunt/show", title = "What everybody sees", icon = Material.SPYGLASS,
                description = "The head-start bar and the end-of-hunt summary."),
        @Topic(path = "speedrun/manhunt/stats", title = "Stats and ratings", icon = Material.WRITABLE_BOOK,
                description = "Each player's hunt stats and rating, and how the sides are balanced."),
})
public record ManhuntSettings(

        @In("speedrun/manhunt/tracker") @Title("Across dimensions")
        @Describe("What the needle does while the Runner it follows is in another dimension. "
                + "LAST_PORTAL points at the door they went through, which is in the Hunter's own "
                + "world; NAME_WORLD only names where they are; HIDDEN says neither.")
        CrossWorldTracking trackerCrossWorld,

        @In("speedrun/manhunt/tracker") @Title("Hunters may aim their own")
        @Describe("Whether right-clicking the compass cycles it to the next Runner, or the nearest. "
                + "Off, every needle follows whoever is nearest and nobody may look away.")
        boolean trackerHunterMayChoose,

        @In("speedrun/manhunt/tracker") @Title("Show the distance")
        @Describe("Whether a Hunter holding the compass is shown how many blocks away the Runner is, "
                + "on the action bar.")
        boolean trackerShowDistance,

        @In("speedrun/manhunt/tracker") @Title("Re-aim every (ticks)") @Range(min = 2, max = 200)
        @Describe("How often every needle is re-aimed. 20 ticks is a second; 10 is twice a second, "
                + "which is what a chase actually needs.")
        int trackerRefreshTicks,

        @In("speedrun/manhunt/tracker") @Title("Team compass")
        @Describe("Whether everybody in a hunt also gets a second compass — a recovery compass — that "
                + "points at their own side: Runners at Runners, Hunters at Hunters. It works in the "
                + "Nether and the End too. Right-click cycles; sneak + right-click opens a list.")
        boolean trackerTeamCompass,

        @In("speedrun/manhunt/tracker") @Title("Team compass item")
        @Describe("RECOVERY_COMPASS (default) aims without the item ever changing and works in every "
                + "dimension. COMPASS looks like a plain compass, but its target is kept in the item, "
                + "so it redraws in your hand whenever the teammate enters a new block.")
        TeamCompassItem trackerTeamCompassItem,

        @In("speedrun/manhunt/tracker") @Title("Particle trail")
        @Describe("Whether a Hunter holding the compass sees a dotted line of particles leading "
                + "towards whoever — or whichever door — it is pointing at. Only they see it.")
        boolean trackerParticleTrail,

        @In("speedrun/manhunt/tracker") @Title("Runners get a compass too")
        @Describe("Whether every Runner also carries a tracking compass — pointing at the Hunters: "
                + "the nearest, or one they pick. Off, Runners get no tracking compass at all.")
        boolean runnerCompass,

        @In("speedrun/manhunt/tracker") @Title("Runners' structure compass")
        @Describe("Whether every Runner gets one compass per hunt that they point at a kind of "
                + "structure of their choice — never a stronghold. It finds the nearest one, shows "
                + "no distance, and disappears when dropped or once they are within 20 blocks. "
                + "Off, nobody is handed one.")
        boolean runnerStructureCompass,

        @In("speedrun/manhunt/sides") @Title("Sides may change mid-hunt")
        @Describe("Whether a player can move between Runners and Hunters while a hunt is actually "
                + "being played. Off, the two sides are fixed the moment the clock starts and only "
                + "/manhunt assign can move anybody. On, a Runner who gives up becomes a Hunter with "
                + "a compass, and a Hunter who takes up the chase loses theirs.")
        boolean sideSwitchingMidHunt,

        @In("speedrun/manhunt/sides") @Title("Runners may choose themselves")
        @Describe("Whether a player can put themselves on the Runner side. Off, only an admin can, "
                + "through /manhunt assign — everybody else is chasing.")
        boolean runnerSelfJoin,

        @In("speedrun/manhunt/sides") @Title("Hunters only punch each other")
        @Describe("Whether a Hunter hitting another Hunter may only do it with a bare fist. On, a "
                + "sword, an axe, a bow or anything else held or thrown does nothing to a teammate.")
        boolean huntersFistsOnly,

        @In("speedrun/manhunt/doors") @Title("Close the whitelist on start")
        @Describe("Whether starting a hunt shuts the server to anybody not already online, and opens "
                + "it again when the hunt ends. Only ever re-opens a whitelist this closed itself.")
        boolean closeWhitelistOnStart,

        @In("speedrun/manhunt/start") @Title("Runners' head start (seconds)") @Range(min = 0, max = 600)
        @Describe("How long the Hunters stand still and touch nothing once the run starts, while the "
                + "Runners are already loose. 0 lets everybody go at once.")
        int hunterHeadStartSeconds,

        @In("speedrun/manhunt/start") @Title("Start in a circle")
        @Describe("Whether everybody is placed evenly around one circle for the countdown, facing "
                + "the middle — Runners together, Hunters together. The circle grows with the "
                + "number of players, around the start point or the world's spawn.")
        boolean startInCircle,

        @In("speedrun/manhunt/sides") @Title("A Runner away this long is caught (seconds)") @Range(min = 0, max = 3600)
        @Describe("How long a Runner may stay logged out during a hunt before they count as caught — "
                + "the last of them ends the hunt as the Hunters' win. Coming back in time costs "
                + "nothing. 0 never catches anybody for being away. Hunters may be away as long as "
                + "they like: the hunt simply goes on without them.")
        int runnerOfflineGraceSeconds,

        @In("speedrun/manhunt/variants") @Title("Runner lives") @Range(min = 1, max = 10)
        @Describe("How many deaths it takes to catch a Runner. 1 is the classic hunt: the first death "
                + "is the last. A Runner with lives to spare respawns and runs on.")
        int runnerLives,

        @In("speedrun/manhunt/variants") @Title("Hunter respawn wait (seconds)") @Range(min = 0, max = 60)
        @Describe("How long a Hunter who died stands still after respawning — no step, no block, no "
                + "hit — before rejoining the chase. 0 sends them straight back.")
        int hunterRespawnDelaySeconds,

        @In("speedrun/manhunt/variants") @Title("Runners glow every (minutes)") @Range(min = 0, max = 60)
        @Describe("Every this many minutes every Runner glows through walls for a few seconds, and "
                + "everybody is warned ten seconds before. 0 never.")
        int glowingRunnersEveryMinutes,

        @In("speedrun/manhunt/variants") @Title("Glow lasts (seconds)") @Range(min = 1, max = 60)
        @Describe("How long each glow lasts.")
        int glowingRunnersSeconds,

        @In("speedrun/manhunt/start") @Title("Extra head start per extra Hunter (seconds)") @Range(min = 0, max = 60)
        @Describe("Added to the head start for every Hunter beyond the number of Runners, so a big pack "
                + "gives the Runners more room. 0 keeps the head start fixed.")
        int headStartPerHunterSeconds,

        @In("speedrun/manhunt/show") @Title("Head-start bar")
        @Describe("Whether a boss bar counts the Runners' head start down for everybody.")
        boolean hudHeadStartBar,

        @In("speedrun/manhunt/show") @Title("Summary in chat")
        @Describe("Whether everybody in a hunt gets its summary in chat at the end: who caught whom, "
                + "the splits and the MVPs, with a button to the full page.")
        boolean summaryInChat,

        @In("speedrun/manhunt/stats") @Title("Keep stats and ratings")
        @Describe("Whether every hunt is added to each player's stats and moves their rating. Off, "
                + "nothing new is remembered; what was is kept.")
        boolean statsEnabled,

        @In("speedrun/manhunt/stats") @Title("Most Runners when balancing") @Range(min = 0, max = 10)
        @Describe("The most Runners auto-balance may pick. 0 lets it choose, up to a third of the lobby.")
        int balanceMaxRunners

) {

    /** Which item the team compass is — see {@code TeamCompassService}. */
    public enum TeamCompassItem { RECOVERY_COMPASS, COMPASS }

    /** What the needle does about a Runner in another dimension. */
    public enum CrossWorldTracking { HIDDEN, NAME_WORLD, LAST_PORTAL }

    /**
     * A fresh install: the needle follows the Runner through the door they took, every Hunter aims
     * their own, distance shown, twice a second, no team compass, a particle trail, no compasses for
     * the Runners, the two sides fixed for the length of a hunt, anybody may run, Hunters may fight
     * each other however they like, no head start, nobody arranged in a circle, a Runner caught after
     * five minutes away, one life, no respawn wait, nobody glowing, the head-start bar and a
     * summary on, and the server's own
     * door left exactly as the owner set it — a plugin that quietly whitelists a server is a plugin
     * that locked somebody out of their own.
     */
    public static final ManhuntSettings DEFAULTS = new ManhuntSettings(
            CrossWorldTracking.LAST_PORTAL, true, true, 10, false, TeamCompassItem.RECOVERY_COMPASS, true,
            false, true, false, true, false, false, 0, false, 300,
            1, 0, 0, 10, 0, true, true, true, 0);

    // Every with… takes its parameter named after the component it replaces, so the parameter shadows
    // exactly that field and a swapped argument does not compile. ManhuntSettingsContractTest walks
    // every component and pins that each of these moves that one and no other — the one failure mode a
    // positional record actually has is two same-typed components swapped in an argument list, which
    // is invisible to the compiler and to every other test.

    public ManhuntSettings withTrackerCrossWorld(CrossWorldTracking trackerCrossWorld) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerHunterMayChoose(boolean trackerHunterMayChoose) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerShowDistance(boolean trackerShowDistance) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerRefreshTicks(int trackerRefreshTicks) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerTeamCompass(boolean trackerTeamCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerTeamCompassItem(TeamCompassItem trackerTeamCompassItem) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withTrackerParticleTrail(boolean trackerParticleTrail) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withRunnerCompass(boolean runnerCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withRunnerStructureCompass(boolean runnerStructureCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withSideSwitchingMidHunt(boolean sideSwitchingMidHunt) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withRunnerSelfJoin(boolean runnerSelfJoin) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withHuntersFistsOnly(boolean huntersFistsOnly) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withCloseWhitelistOnStart(boolean closeWhitelistOnStart) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withHunterHeadStartSeconds(int hunterHeadStartSeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withStartInCircle(boolean startInCircle) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withRunnerOfflineGraceSeconds(int runnerOfflineGraceSeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withRunnerLives(int runnerLives) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withHunterRespawnDelaySeconds(int hunterRespawnDelaySeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withGlowingRunnersEveryMinutes(int glowingRunnersEveryMinutes) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withGlowingRunnersSeconds(int glowingRunnersSeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withHeadStartPerHunterSeconds(int headStartPerHunterSeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withHudHeadStartBar(boolean hudHeadStartBar) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withSummaryInChat(boolean summaryInChat) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withStatsEnabled(boolean statsEnabled) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    public ManhuntSettings withBalanceMaxRunners(int balanceMaxRunners) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle,
                runnerOfflineGraceSeconds, runnerLives, hunterRespawnDelaySeconds,
                glowingRunnersEveryMinutes, glowingRunnersSeconds, headStartPerHunterSeconds,
                hudHeadStartBar, summaryInChat, statsEnabled,
                balanceMaxRunners);
    }

    /** The head start, inside the range the settings screen offers. */
    public int hunterHeadStartSecondsClamped() {
        return Math.max(0, Math.min(600, hunterHeadStartSeconds));
    }

    /**
     * The head start for a hunt of this shape: the base, plus {@link #headStartPerHunterSeconds} for
     * every Hunter beyond the number of Runners — inside the 600 seconds the screen offers.
     */
    public int headStartFor(int runners, int hunters) {
        int extra = Math.max(0, Math.min(60, headStartPerHunterSeconds)) * Math.max(0, hunters - runners);
        return Math.min(600, hunterHeadStartSecondsClamped() + extra);
    }

    public int runnerLivesClamped() {
        return Math.max(1, Math.min(10, runnerLives));
    }

    public int hunterRespawnDelayClamped() {
        return Math.max(0, Math.min(60, hunterRespawnDelaySeconds));
    }

    public int glowEveryMinutesClamped() {
        return Math.max(0, Math.min(60, glowingRunnersEveryMinutes));
    }

    public int glowSecondsClamped() {
        return Math.max(1, Math.min(60, glowingRunnersSeconds));
    }


    /** The grace, inside the range the settings screen offers; 0 (or a hand-edited negative) is never. */
    public int runnerOfflineGraceSecondsClamped() {
        return Math.max(0, Math.min(3600, runnerOfflineGraceSeconds));
    }

    /** The refresh interval, inside the range the settings screen offers — a hand-edited 0 is a busy loop. */
    public int trackerRefreshTicksClamped() {
        return Math.max(2, Math.min(200, trackerRefreshTicks));
    }
}
