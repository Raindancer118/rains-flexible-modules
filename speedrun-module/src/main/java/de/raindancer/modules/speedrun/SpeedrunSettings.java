package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
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
 */
@Settings(id = "speedrun", topics = {
        @Topic(path = "config/speedrun", title = "Speedrun", icon = Material.NETHER_STAR,
                description = "Which world races run in, and what ends one."),
})
public record SpeedrunSettings(

        @In("config/speedrun") @Title("Lobby world")
        @Describe("The Bukkit world the lobby, and every run, takes place in.")
        String worldName,

        @In("config/speedrun") @Title("Advancement goal")
        @Describe("The advancement that ends a run, as 'namespace:path'. Empty means none.")
        String advancementKey,

        @In("config/speedrun") @Title("Death policy")
        @Describe("Whether a death ends the run, and whether one death is enough.")
        SpeedrunDeathPolicy deathPolicy,

        @In("config/speedrun") @Title("Require the exit portal")
        @Describe("When the advancement goal is the vanilla dragon kill, whether killing it is only "
                + "the first half — a run does not end until a participant then steps into the exit "
                + "portal, the way an actual dragon-kill speedrun is judged. Has no effect on any other "
                + "advancement goal, or when death alone ends the run.")
        boolean requireExitPortalAfterDragon,

        @In("config/speedrun") @Title("Creeper chance (block break)") @Range(min = 0, max = 100)
        @Describe("Chance, in percent, that a racer breaking a block during a run spawns a creeper "
                + "right where it broke. 0 turns the hazard off; 100 is the old plain on/off toggle's "
                + "'on'.")
        int creeperSpawnChanceOnBreakPercent,

        @In("config/speedrun") @Title("Charged creeper chance (block break)") @Range(min = 0, max = 100)
        @Describe("Of a creeper spawned by breaking a block, the chance in percent that it is charged "
                + "(powered) instead of an ordinary one.")
        int chargedCreeperChanceOnBreakPercent,

        @In("config/speedrun") @Title("Creeper chance (container open)") @Range(min = 0, max = 100)
        @Describe("Chance, in percent, that a racer opening a chest or other container during a run "
                + "spawns a creeper right where it stands — set separately from the block-break chance, "
                + "since opening loot chests is a very different risk than mining.")
        int creeperSpawnChanceOnContainerPercent,

        @In("config/speedrun") @Title("Charged creeper chance (container open)") @Range(min = 0, max = 100)
        @Describe("Of a creeper spawned by opening a container, the chance in percent that it is "
                + "charged (powered) instead of an ordinary one.")
        int chargedCreeperChanceOnContainerPercent,

        @In("config/speedrun") @Title("Start point set")
        @Describe("Whether /starthere has set a start point. Off means nobody is teleported when a "
                + "countdown begins — racers start wherever they were standing when it caught them.")
        boolean startPointSet,

        @In("config/speedrun") @Title("Start X")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startX,

        @In("config/speedrun") @Title("Start Y")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startY,

        @In("config/speedrun") @Title("Start Z")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startZ,

        @In("config/speedrun") @Title("Start yaw")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startYaw,

        @In("config/speedrun") @Title("Start pitch")
        @Describe("Set by /starthere, not meant to be hand-edited.")
        double startPitch

) {

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
     */
    public static final SpeedrunSettings DEFAULTS = new SpeedrunSettings(
            DEFAULT_WORLD_NAME, DRAGON_KILL_ADVANCEMENT, SpeedrunDeathPolicy.OFF, true, 100, 0, 100, 0,
            false, 0, 0, 0, 0, 0);

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
}
