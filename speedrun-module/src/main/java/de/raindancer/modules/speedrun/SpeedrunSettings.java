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

        @In("config/speedrun") @Title("Creeper on block break")
        @Describe("Whether a racer breaking a block during a run spawns a creeper right where it broke.")
        boolean creeperOnBlockBreak,

        @In("config/speedrun") @Title("Charged creeper chance") @Range(min = 0, max = 100)
        @Describe("Chance, in percent, that a creeper spawned this way is charged (powered) instead "
                + "of an ordinary one.")
        int chargedCreeperChancePercent

) {

    /** The advancement key {@link #requireExitPortalAfterDragon} looks for — vanilla's own dragon kill. */
    public static final String DRAGON_KILL_ADVANCEMENT = "minecraft:end/kill_dragon";

    /**
     * What a fresh install ships with: the vanilla dragon kill, nobody's death ends it, the server's
     * own primary world — {@code "world"}, Paper's own {@code level-name} default — and the portal
     * requirement on, since a run that stops timing the instant the dragon dies is not how anybody
     * actually races this goal.
     *
     * <p><b>Found the hard way:</b> shipping a placeholder like {@code "speedrun"} here means a
     * server that never renamed anything, and never will, has a lobby bound to a world that does not
     * exist — {@link SpeedrunLobby#start} then answers {@code WORLD_MISSING} for a reason nobody sees,
     * because the block was clicked in a world whose name does not even match, so
     * {@code SpeedrunLobbyListener} never gets that far. The compass and the block simply do nothing,
     * which is indistinguishable from broken.
     */
    public static final SpeedrunSettings DEFAULTS = new SpeedrunSettings(
            "world", DRAGON_KILL_ADVANCEMENT, SpeedrunDeathPolicy.OFF, true, true, 0);

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
