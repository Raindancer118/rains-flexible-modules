package de.raindancer.modules.speedrun;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Objects;

/**
 * One point a run passes on its way to the goal — entering the nether, the first blaze rod, the
 * dragon at half health. The first participant to reach it splits the run there; see
 * {@link SpeedrunTimeline#split}.
 *
 * @param id    stable, lower case, the key history and comparisons are kept under
 * @param label what a player reads, without markup
 * @param icon  what it is drawn as on a summary page
 */
public record SpeedrunMilestone(String id, String label, Material icon) {

    public SpeedrunMilestone {
        id = Objects.requireNonNull(id, "id").trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A milestone needs an id.");
        }
        label = label == null || label.isBlank() ? id : label;
        icon = icon == null ? Material.PAPER : icon;
    }
}
