package de.raindancer.modules.manhunt.stats;

import java.util.Locale;
import java.util.Optional;

/**
 * The moments of a run worth stopping everybody for — each counted once per hunt, for the first
 * Runner to reach it. The order is the order a run usually meets them in, which is how the splits
 * are listed.
 */
public enum Milestone {
    NETHER,
    FORTRESS,
    BLAZE_ROD,
    STRONGHOLD,
    END,
    DRAGON_HALF,
    DRAGON_KILLED;

    /** Its name in the wording file and on disk: {@code blaze-rod}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<Milestone> byId(String id) {
        for (Milestone milestone : values()) {
            if (milestone.id().equals(id)) {
                return Optional.of(milestone);
            }
        }
        return Optional.empty();
    }
}
