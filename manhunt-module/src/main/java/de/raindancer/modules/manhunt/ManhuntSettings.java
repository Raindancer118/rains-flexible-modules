package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about a Manhunt match.
 *
 * <p>The record <em>is</em> the schema — see {@code ChainedSettings}' own class javadoc for why this
 * shape (a record with a {@code with…} per component, no positional literals scattered elsewhere) is
 * what every settings record in this reactor looks like.
 *
 * <h2>Both win conditions are independent, on purpose</h2>
 * {@link #runnerWin()} and {@link #hunterWin()} are not two branches of one choice — a server can want
 * "Runners win by reaching the exit portal" together with "Hunters win once every Runner is dead" at
 * the same time, which is the classic shape, or swap either half out on its own (a timed objective for
 * the Hunters instead of "wait for the last kill", say). {@code ManhuntService.start} arms whichever
 * {@code SpeedrunEndCondition}s the current choice on each side calls for; {@code SpeedrunSession}
 * itself only ever accepts the first one that actually fires — see its own class javadoc on why that
 * is safe when two conditions could in principle finish the same run at once.
 */
@Settings(id = "manhunt", topics = {
        @Topic(path = "manhunt", title = "Manhunt", icon = Material.COMPASS),
        @Topic(path = "manhunt/win", title = "How it ends", icon = Material.CLOCK),
        @Topic(path = "manhunt/start", title = "Starting a run", icon = Material.NETHER_STAR),
        @Topic(path = "manhunt/world", title = "Resetting the map", icon = Material.TNT),
        @Topic(path = "manhunt/whitelist", title = "The server whitelist", icon = Material.PAPER),
        @Topic(path = "manhunt/chaos", title = "Chaos actions", icon = Material.BLAZE_POWDER),
        @Topic(path = "manhunt/roster", title = "Joining a side", icon = Material.PLAYER_HEAD),
        @Topic(path = "manhunt/lobby", title = "The waiting lobby", icon = Material.ITEM_FRAME),
        @Topic(path = "manhunt/tracker", title = "The tracking compass", icon = Material.COMPASS),
})
public record ManhuntSettings(

        @In("manhunt/win") @Title("How the Runners win")
        @Describe("PORTAL_EXIT: any Runner stepping into the End's exit portal ends the run in "
                + "their favour. ADVANCEMENT: any Runner earning the configured advancement does "
                + "instead — no portal involved.")
        @Key("runner-win")
        RunnerWinCondition runnerWin,

        @In("manhunt/win") @Title("The Runners' advancement")
        @Describe("Only used when the Runners' win condition above is ADVANCEMENT. The vanilla "
                + "dragon kill by default, but any advancement key works.")
        @Key("runner-advancement-key")
        String runnerAdvancementKey,

        @In("manhunt/win") @Title("How the Hunters win")
        @Describe("ALL_RUNNERS_DEAD: the run ends the moment the last living Runner dies. "
                + "TIMEOUT: the Hunters win instead if the clock below runs out before the "
                + "Runners' own condition fires.")
        @Key("hunter-win")
        HunterWinCondition hunterWin,

        @In("manhunt/win") @Title("Minutes on the clock") @Range(min = 1, max = 600)
        @Describe("Only used when the Hunters' win condition above is TIMEOUT.")
        @Key("hunter-timeout-minutes")
        int hunterTimeoutMinutes,

        @In("manhunt/start") @Title("Head start, in seconds") @Range(min = 0, max = 600)
        @Describe("The Hunters are held in a frozen pen this long after the Runners are already "
                + "loose — zero releases everybody together.")
        @Key("hunter-release-delay-seconds")
        int hunterReleaseDelaySeconds,

        @In("manhunt/world") @Title("Reset the map before a run starts")
        @Describe("Whether starting a run throws the configured world away and makes it again "
                + "first, so every attempt begins from the same kind of map rather than one "
                + "already explored by the last attempt.")
        @Key("reset-on-start")
        boolean resetOnStart,

        @In("manhunt/world") @Title("Which world to reset")
        @Describe("The name of the world a run's reset regenerates. Only used when the setting "
                + "above is on, or when the reset command is typed.")
        @Key("world-name")
        String worldName,

        @In("manhunt/world") @Title("Seed policy")
        @Describe("A fixed seed makes every attempt the same map; a random one makes each "
                + "attempt a fresh map.")
        @Key("seed-choice")
        SeedChoice seedChoice,

        @In("manhunt/world") @Title("The fixed seed")
        @Describe("Only used when the seed policy above is fixed.")
        @Key("seed-value")
        long seedValue,

        @In("manhunt/whitelist") @Title("Close the whitelist when a run starts")
        @Describe("Snapshots everybody online as whitelisted and shuts the server to anybody "
                + "else the moment the countdown ends — a Runner or an admin can still do this "
                + "by hand at any other time with /whitelist close.")
        @Key("close-whitelist-on-start")
        boolean closeWhitelistOnStart,

        @In("manhunt/chaos") @Title("Wait between chaos actions") @Range(min = 0, max = 300)
        @Describe("Seconds a console or a menu click has to wait before another chaos action can "
                + "be thrown at the same run — zero switches the cooldown off entirely.")
        @Key("chaos-cooldown-seconds")
        int chaosCooldownSeconds,

        @In("manhunt/roster") @Title("Runners may join themselves")
        @Describe("Off: only somebody with the admin permission can put a player on the Runners. "
                + "Hunters can always join themselves either way — this only locks the Runners.")
        @Key("runner-self-join-enabled")
        boolean runnerSelfJoinEnabled,

        @In("manhunt/lobby") @Title("Waiting lobby set")
        @Describe("Whether an admin has placed a waiting lobby with /manhunt setlobby yet. Nothing "
                + "below does anything until this is on.")
        @Key("lobby-spawn-set")
        boolean lobbySpawnSet,

        @In("manhunt/lobby") @Title("Waiting lobby world")
        @Describe("Set by /manhunt setlobby, not meant to be hand-edited.")
        @Key("lobby-world-name")
        String lobbyWorldName,

        @In("manhunt/lobby") @Title("Waiting lobby X")
        @Describe("Set by /manhunt setlobby, not meant to be hand-edited.")
        @Key("lobby-x")
        double lobbyX,

        @In("manhunt/lobby") @Title("Waiting lobby Y")
        @Describe("Set by /manhunt setlobby, not meant to be hand-edited.")
        @Key("lobby-y")
        double lobbyY,

        @In("manhunt/lobby") @Title("Waiting lobby Z")
        @Describe("Set by /manhunt setlobby, not meant to be hand-edited.")
        @Key("lobby-z")
        double lobbyZ,

        @In("manhunt/lobby") @Title("Waiting lobby facing")
        @Describe("The yaw a player lands facing when relocated into the waiting lobby. Set by "
                + "/manhunt setlobby.")
        @Key("lobby-yaw")
        double lobbyYaw,

        @In("manhunt/lobby") @Title("Waiting lobby radius") @Range(min = 1, max = 200)
        @Describe("Half-width, in blocks, of the protected cube around the waiting lobby's spawn "
                + "point — nobody fights or builds inside it.")
        @Key("lobby-radius")
        int lobbyRadius,

        @In("manhunt/tracker") @Title("Hunters carry a tracking compass")
        @Describe("Off: the Hunters get no compass at all and have to find the Runners the hard "
                + "way. On: every Hunter is handed one when a hunt starts, and it points at a "
                + "Runner for as long as the hunt lasts.")
        @Key("tracker-compass-enabled")
        boolean trackerCompassEnabled,

        @In("manhunt/tracker") @Title("Ticks between compass updates") @Range(min = 1, max = 100)
        @Describe("How often a carried compass re-aims. 20 is once a second; smaller is a needle "
                + "that keeps up with a sprinting Runner, larger is one a Runner can outrun for a "
                + "moment. Every Hunter's compass is re-aimed on the same beat.")
        @Key("tracker-refresh-ticks")
        int trackerRefreshTicks,

        @In("manhunt/tracker") @Title("Who the compass follows")
        @Describe("NEAREST: the needle always swings to whichever Runner is closest. CHOSEN: a "
                + "Hunter right-clicks the compass to pick one Runner, and it stays on them until "
                + "they leave the hunt — the nearest Runner only fills in until somebody is picked.")
        @Key("tracker-targets")
        TrackerTargets trackerTargets,

        @In("manhunt/tracker") @Title("When the Runner is in another dimension")
        @Describe("A compass needle is a direction in one world, so a Runner in the Nether or the "
                + "End has none to give. LAST_PORTAL: it points at the portal they went through, "
                + "so the Hunters can follow them down. NAME_WORLD: it only says which dimension "
                + "they are in. HIDDEN: it says nothing at all, which is the hardest hunt.")
        @Key("tracker-cross-world")
        CrossWorldTracking trackerCrossWorld,

        @In("manhunt/tracker") @Title("Show how far away the Runner is")
        @Describe("Off: the compass points, but never says the distance in blocks — the Hunters "
                + "have a direction and nothing else.")
        @Key("tracker-show-distance")
        boolean trackerShowDistance,

        @In("manhunt/tracker") @Title("Replace a Hunter's compass when they respawn")
        @Describe("A Hunter who dies drops their compass with everything else. On: they are handed "
                + "a fresh one on respawn. Off: losing it is losing it.")
        @Key("tracker-give-on-respawn")
        boolean trackerGiveOnRespawn) {

    /** How a Runner side wins. */
    public enum RunnerWinCondition { PORTAL_EXIT, ADVANCEMENT }

    /** How the Hunter side wins. */
    public enum HunterWinCondition { ALL_RUNNERS_DEAD, TIMEOUT }

    /** How a reset world's terrain is chosen. */
    public enum SeedChoice { FIXED, RANDOM }

    /** Whether the tracking compass follows the nearest Runner or one the Hunter picked. */
    public enum TrackerTargets { NEAREST, CHOSEN }

    /** What the tracking compass does about a Runner who is in another dimension. */
    public enum CrossWorldTracking { HIDDEN, NAME_WORLD, LAST_PORTAL }

    public static final ManhuntSettings DEFAULTS = new ManhuntSettings(
            RunnerWinCondition.PORTAL_EXIT, "minecraft:end/kill_dragon",
            HunterWinCondition.ALL_RUNNERS_DEAD, 60,
            60,
            true, "world", SeedChoice.RANDOM, 0L,
            true,
            10,
            true,
            false, "", 0, 0, 0, 0, 15,
            true, 10, TrackerTargets.CHOSEN, CrossWorldTracking.LAST_PORTAL, true, true);

    // ------------------------------------------------------------------ read back safely

    public int hunterTimeoutMinutesClamped() {
        return Math.max(1, Math.min(600, hunterTimeoutMinutes));
    }

    public int hunterReleaseDelaySecondsClamped() {
        return Math.max(0, Math.min(600, hunterReleaseDelaySeconds));
    }

    public int chaosCooldownSecondsClamped() {
        return Math.max(0, Math.min(300, chaosCooldownSeconds));
    }

    public int trackerRefreshTicksClamped() {
        return Math.max(1, Math.min(100, trackerRefreshTicks));
    }

    // ------------------------------------------------------------------ one component at a time

    public ManhuntSettings withRunnerWin(RunnerWinCondition condition) {
        return new ManhuntSettings(condition, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withRunnerAdvancementKey(String key) {
        return new ManhuntSettings(runnerWin, key, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withHunterWin(HunterWinCondition condition) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, condition, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withHunterTimeoutMinutes(int minutes) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, minutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withHunterReleaseDelaySeconds(int seconds) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                seconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withResetOnStart(boolean reset) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, reset, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withWorldName(String world) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, world, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withSeedChoice(SeedChoice choice) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, choice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withSeedValue(long seed) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seed,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withCloseWhitelistOnStart(boolean close) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                close, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withChaosCooldownSeconds(int seconds) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, seconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withRunnerSelfJoinEnabled(boolean enabled) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                enabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbySpawnSet(boolean set) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, set, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbyWorldName(String world) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, world, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbyX(double x) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, x, lobbyY, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbyY(double y) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, y, lobbyZ, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbyZ(double z) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, z, lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withLobbyYaw(double yaw) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, yaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerCompassEnabled(boolean enabled) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                enabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerRefreshTicks(int ticks) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                trackerCompassEnabled, ticks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerTargets(TrackerTargets targets) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, targets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerCrossWorld(CrossWorldTracking crossWorld) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, crossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerShowDistance(boolean shown) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                shown, trackerGiveOnRespawn);
    }

    public ManhuntSettings withTrackerGiveOnRespawn(boolean given) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ,
                lobbyYaw, lobbyRadius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, given);
    }

    public ManhuntSettings withLobbyRadius(int radius) {
        return new ManhuntSettings(runnerWin, runnerAdvancementKey, hunterWin, hunterTimeoutMinutes,
                hunterReleaseDelaySeconds, resetOnStart, worldName, seedChoice, seedValue,
                closeWhitelistOnStart, chaosCooldownSeconds,
                runnerSelfJoinEnabled, lobbySpawnSet, lobbyWorldName, lobbyX, lobbyY, lobbyZ, lobbyYaw, radius,
                trackerCompassEnabled, trackerRefreshTicks, trackerTargets, trackerCrossWorld,
                trackerShowDistance, trackerGiveOnRespawn);
    }
}
