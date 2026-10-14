package de.raindancer.modules.moderation.store;

import org.bukkit.configuration.ConfigurationSection;

/** Ids like {@code R7} and {@code N12}: the number a section's keys have reached. */
final class NumberedIds {

    private NumberedIds() {
    }

    static long highestIn(ConfigurationSection section, String prefix) {
        long highest = 0;
        if (section == null) {
            return highest;
        }
        for (String id : section.getKeys(false)) {
            if (!id.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            try {
                highest = Math.max(highest, Long.parseLong(id.substring(prefix.length())));
            } catch (NumberFormatException notOneOfOurs) {
                // A hand-written id does not move the count.
            }
        }
        return highest;
    }
}
