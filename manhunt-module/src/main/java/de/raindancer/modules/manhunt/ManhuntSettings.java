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

        @In("manhunt/sides") @Title("Runners may choose themselves")
        @Describe("Whether a player can put themselves on the Runner side. Off, only an admin can, "
                + "through /manhunt assign — everybody else is chasing.")
        boolean runnerSelfJoin,

        @In("manhunt/doors") @Title("Close the whitelist on start")
        @Describe("Whether starting a hunt shuts the server to anybody not already online, and opens "
                + "it again when the hunt ends. Only ever re-opens a whitelist this closed itself.")
        boolean closeWhitelistOnStart

) {

    /** What the needle does about a Runner in another dimension. */
    public enum CrossWorldTracking { HIDDEN, NAME_WORLD, LAST_PORTAL }

    /**
     * A fresh install: the needle follows the Runner through the door they took, every Hunter aims
     * their own, distance shown, twice a second, anybody may run, and the server's own door is left
     * exactly as the owner set it — a plugin that quietly whitelists a server is a plugin that locked
     * somebody out of their own.
     */
    public static final ManhuntSettings DEFAULTS = new ManhuntSettings(
            CrossWorldTracking.LAST_PORTAL, true, true, 10, true, false);

    // Every with… takes its parameter named after the component it replaces, so the parameter shadows
    // exactly that field and a swapped argument does not compile. ManhuntSettingsContractTest walks
    // every component and pins that each of these moves that one and no other — the one failure mode a
    // positional record actually has is two same-typed components swapped in an argument list, which
    // is invisible to the compiler and to every other test.

    public ManhuntSettings withTrackerCrossWorld(CrossWorldTracking trackerCrossWorld) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    public ManhuntSettings withTrackerHunterMayChoose(boolean trackerHunterMayChoose) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    public ManhuntSettings withTrackerShowDistance(boolean trackerShowDistance) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    public ManhuntSettings withTrackerRefreshTicks(int trackerRefreshTicks) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    public ManhuntSettings withRunnerSelfJoin(boolean runnerSelfJoin) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    public ManhuntSettings withCloseWhitelistOnStart(boolean closeWhitelistOnStart) {
        return new ManhuntSettings(trackerCrossWorld, trackerHunterMayChoose, trackerShowDistance,
                trackerRefreshTicks, runnerSelfJoin, closeWhitelistOnStart);
    }

    /** The refresh interval, inside the range the settings screen offers — a hand-edited 0 is a busy loop. */
    public int trackerRefreshTicksClamped() {
        return Math.max(2, Math.min(200, trackerRefreshTicks));
    }
}
