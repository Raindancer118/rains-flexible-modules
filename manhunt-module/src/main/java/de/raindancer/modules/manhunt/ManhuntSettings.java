package de.raindancer.modules.manhunt;

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
 * <h2>Why this is six settings and not forty-five</h2>
 * The module this replaces had forty-five, and most of them existed because it owned things the
 * speedrun lobby already owns: its own world, its own spawn, its own seed, its own countdown, its own
 * map reset. A mode owns none of that any more — the world, the goal, the countdown, the hazard and
 * the reset are {@code speedrun.yml}'s, in one place, for both games. What is left here is what only
 * a hunt has: the compass, who may pick a side, and the door.
 */
@Settings(id = "manhunt", topics = {
        @Topic(path = "manhunt", title = "Manhunt", icon = Material.TARGET,
                description = "Runners against Hunters, played in the speedrun lobby."),
        @Topic(path = "manhunt/tracker", title = "The tracking compass", icon = Material.COMPASS,
                description = "What the Hunters' compass follows, and how closely."),
        @Topic(path = "manhunt/sides", title = "The sides", icon = Material.LIME_BANNER,
                description = "Who may put themselves on the Runner side."),
        @Topic(path = "manhunt/doors", title = "The door", icon = Material.IRON_DOOR,
                description = "What a hunt does to the server whitelist."),
        @Topic(path = "manhunt/start", title = "The start", icon = Material.CLOCK,
                description = "Where everybody stands, and how long the Runners get before the chase."),
})
public record ManhuntSettings(

        @In("manhunt/tracker") @Title("Across dimensions")
        @Describe("What the needle does while the Runner it follows is in another dimension. "
                + "LAST_PORTAL points at the door they went through, which is in the Hunter's own "
                + "world; NAME_WORLD only names where they are; HIDDEN says neither.")
        CrossWorldTracking trackerCrossWorld,

        @In("manhunt/tracker") @Title("Hunters may aim their own")
        @Describe("Whether right-clicking the compass cycles it to the next Runner, or the nearest. "
                + "Off, every needle follows whoever is nearest and nobody may look away.")
        boolean trackerHunterMayChoose,

        @In("manhunt/tracker") @Title("Show the distance")
        @Describe("Whether a Hunter holding the compass is shown how many blocks away the Runner is, "
                + "on the action bar.")
        boolean trackerShowDistance,

        @In("manhunt/tracker") @Title("Re-aim every (ticks)") @Range(min = 2, max = 200)
        @Describe("How often every needle is re-aimed. 20 ticks is a second; 10 is twice a second, "
                + "which is what a chase actually needs.")
        int trackerRefreshTicks,

        @In("manhunt/tracker") @Title("Team compass")
        @Describe("Whether everybody in a hunt also gets a second compass — a recovery compass — that "
                + "points at their own side: Runners at Runners, Hunters at Hunters. It works in the "
                + "Nether and the End too. Right-click cycles; sneak + right-click opens a list.")
        boolean trackerTeamCompass,

        @In("manhunt/tracker") @Title("Team compass item")
        @Describe("RECOVERY_COMPASS (default) aims without the item ever changing and works in every "
                + "dimension. COMPASS looks like a plain compass, but its target is kept in the item, "
                + "so it redraws in your hand whenever the teammate enters a new block.")
        TeamCompassItem trackerTeamCompassItem,

        @In("manhunt/tracker") @Title("Particle trail")
        @Describe("Whether a Hunter holding the compass sees a dotted line of particles leading "
                + "towards whoever — or whichever door — it is pointing at. Only they see it.")
        boolean trackerParticleTrail,

        @In("manhunt/tracker") @Title("Runners get a compass too")
        @Describe("Whether every Runner also carries a tracking compass — pointing at the Hunters: "
                + "the nearest, or one they pick. Off, Runners get no tracking compass at all.")
        boolean runnerCompass,

        @In("manhunt/tracker") @Title("Runners' structure compass")
        @Describe("Whether every Runner gets one compass per hunt that they point at a kind of "
                + "structure of their choice — never a stronghold. It finds the nearest one, shows "
                + "no distance, and disappears when dropped or once they are within 20 blocks. "
                + "Off, nobody is handed one.")
        boolean runnerStructureCompass,

        @In("manhunt/sides") @Title("Sides may change mid-hunt")
        @Describe("Whether a player can move between Runners and Hunters while a hunt is actually "
                + "being played. Off, the two sides are fixed the moment the clock starts and only "
                + "/manhunt assign can move anybody. On, a Runner who gives up becomes a Hunter with "
                + "a compass, and a Hunter who takes up the chase loses theirs.")
        boolean sideSwitchingMidHunt,

        @In("manhunt/sides") @Title("Runners may choose themselves")
        @Describe("Whether a player can put themselves on the Runner side. Off, only an admin can, "
                + "through /manhunt assign — everybody else is chasing.")
        boolean runnerSelfJoin,

        @In("manhunt/sides") @Title("Hunters only punch each other")
        @Describe("Whether a Hunter hitting another Hunter may only do it with a bare fist. On, a "
                + "sword, an axe, a bow or anything else held or thrown does nothing to a teammate.")
        boolean huntersFistsOnly,

        @In("manhunt/doors") @Title("Close the whitelist on start")
        @Describe("Whether starting a hunt shuts the server to anybody not already online, and opens "
                + "it again when the hunt ends. Only ever re-opens a whitelist this closed itself.")
        boolean closeWhitelistOnStart,

        @In("manhunt/start") @Title("Runners' head start (seconds)") @Range(min = 0, max = 600)
        @Describe("How long the Hunters stand still and touch nothing once the run starts, while the "
                + "Runners are already loose. 0 lets everybody go at once.")
        int hunterHeadStartSeconds,

        @In("manhunt/start") @Title("Start in a circle")
        @Describe("Whether everybody is placed evenly around one circle for the countdown, facing "
                + "the middle — Runners together, Hunters together. The circle grows with the "
                + "number of players, around the start point or the world's spawn.")
        boolean startInCircle

) {

    /** Which item the team compass is — see {@code TeamCompassService}. */
    public enum TeamCompassItem { RECOVERY_COMPASS, COMPASS }

    /** What the needle does about a Runner in another dimension. */
    public enum CrossWorldTracking { HIDDEN, NAME_WORLD, LAST_PORTAL }

    /**
     * A fresh install: the needle follows the Runner through the door they took, every Hunter aims
     * their own, distance shown, twice a second, the two sides fixed for the length of a hunt,
     * anybody may run, no team compass, a particle trail, Hunters may fight each other however they like, no head start, nobody arranged in a circle, and the server's own door is left exactly as the owner set it — a plugin that quietly whitelists a server is a plugin that locked
     * somebody out of their own.
     */
    public static final ManhuntSettings DEFAULTS = new ManhuntSettings(
            CrossWorldTracking.LAST_PORTAL, true, true, 10, false, TeamCompassItem.RECOVERY_COMPASS, true,
            false, true, false, true, false, false, 0, false);

    // Every with… takes its parameter named after the component it replaces, so the parameter shadows
    // exactly that field and a swapped argument does not compile. ManhuntSettingsContractTest walks
    // every component and pins that each of these moves that one and no other — the one failure mode a
    // positional record actually has is two same-typed components swapped in an argument list, which
    // is invisible to the compiler and to every other test.

    public ManhuntSettings withTrackerCrossWorld(CrossWorldTracking trackerCrossWorld) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerHunterMayChoose(boolean trackerHunterMayChoose) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerShowDistance(boolean trackerShowDistance) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerRefreshTicks(int trackerRefreshTicks) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerTeamCompass(boolean trackerTeamCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerTeamCompassItem(TeamCompassItem trackerTeamCompassItem) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withTrackerParticleTrail(boolean trackerParticleTrail) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withRunnerCompass(boolean runnerCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withRunnerStructureCompass(boolean runnerStructureCompass) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withSideSwitchingMidHunt(boolean sideSwitchingMidHunt) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withRunnerSelfJoin(boolean runnerSelfJoin) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withHuntersFistsOnly(boolean huntersFistsOnly) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withCloseWhitelistOnStart(boolean closeWhitelistOnStart) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withHunterHeadStartSeconds(int hunterHeadStartSeconds) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    public ManhuntSettings withStartInCircle(boolean startInCircle) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, trackerTeamCompass, trackerTeamCompassItem, trackerParticleTrail,
                runnerCompass, runnerStructureCompass, sideSwitchingMidHunt, runnerSelfJoin,
                huntersFistsOnly, closeWhitelistOnStart, hunterHeadStartSeconds, startInCircle);
    }

    /** The head start, inside the range the settings screen offers. */
    public int hunterHeadStartSecondsClamped() {
        return Math.max(0, Math.min(600, hunterHeadStartSeconds));
    }

    /** The refresh interval, inside the range the settings screen offers — a hand-edited 0 is a busy loop. */
    public int trackerRefreshTicksClamped() {
        return Math.max(2, Math.min(200, trackerRefreshTicks));
    }
}
