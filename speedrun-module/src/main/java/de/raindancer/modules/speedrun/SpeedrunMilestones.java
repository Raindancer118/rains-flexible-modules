package de.raindancer.modules.speedrun;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The milestones every run is split at, in the order a dragon run passes them.
 *
 * <p>Detected by {@link SpeedrunMilestoneListener}. A game mode adds its own through
 * {@link SpeedrunRun#declareMilestone}; they are listed after these.
 */
public final class SpeedrunMilestones {

    public static final SpeedrunMilestone ENTER_NETHER =
            new SpeedrunMilestone("enter-nether", "Nether", Material.NETHERRACK);
    public static final SpeedrunMilestone BASTION =
            new SpeedrunMilestone("bastion", "Bastion", Material.GILDED_BLACKSTONE);
    public static final SpeedrunMilestone FORTRESS =
            new SpeedrunMilestone("fortress", "Fortress", Material.NETHER_BRICKS);
    public static final SpeedrunMilestone BLAZE_ROD =
            new SpeedrunMilestone("blaze-rod", "Blaze rod", Material.BLAZE_ROD);
    public static final SpeedrunMilestone PEARLS =
            new SpeedrunMilestone("pearls", "Pearls", Material.ENDER_PEARL);
    public static final SpeedrunMilestone LEAVE_NETHER =
            new SpeedrunMilestone("leave-nether", "Out of the nether", Material.OBSIDIAN);
    public static final SpeedrunMilestone STRONGHOLD =
            new SpeedrunMilestone("stronghold", "Stronghold", Material.ENDER_EYE);
    public static final SpeedrunMilestone ENTER_END =
            new SpeedrunMilestone("enter-end", "The End", Material.END_STONE);
    public static final SpeedrunMilestone DRAGON_HALF =
            new SpeedrunMilestone("dragon-half", "Dragon at half", Material.DRAGON_BREATH);
    public static final SpeedrunMilestone DRAGON_KILL =
            new SpeedrunMilestone("dragon-kill", "Dragon killed", Material.DRAGON_HEAD);
    /** The run's own end — the goal reached. Always last. */
    public static final SpeedrunMilestone FINISH =
            new SpeedrunMilestone("finish", "Finish", Material.NETHER_STAR);

    /** In the order a run passes them. */
    public static final List<SpeedrunMilestone> BUILT_IN = List.of(ENTER_NETHER, BASTION, FORTRESS,
            BLAZE_ROD, PEARLS, LEAVE_NETHER, STRONGHOLD, ENTER_END, DRAGON_HALF, DRAGON_KILL, FINISH);

    private static final Map<String, SpeedrunMilestone> BY_ID =
            BUILT_IN.stream().collect(Collectors.toUnmodifiableMap(SpeedrunMilestone::id, Function.identity()));

    private SpeedrunMilestones() {
    }

    public static Optional<SpeedrunMilestone> builtIn(String id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id));
    }

    /** Where {@code id} sorts: the built-ins in their order, a mode's own after them, the finish last. */
    public static int order(String id) {
        if (FINISH.id().equals(id)) {
            return Integer.MAX_VALUE;
        }
        for (int at = 0; at < BUILT_IN.size(); at++) {
            if (BUILT_IN.get(at).id().equals(id)) {
                return at;
            }
        }
        return BUILT_IN.size();
    }
}
