package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;

import java.util.Optional;
import java.util.UUID;

/**
 * Who may go to which of an island's dimensions. The team always, a guest when invited, staff anywhere; nobody but
 * staff while an admin holds the island down; and nobody at all into a dimension that is not unlocked.
 */
public final class EntryRule implements IOneBlockRule {

    /**
     * @param invited a guest invite was accepted for this island
     * @param staff   may enter any island, held down or not
     * @return the refusal's message key, empty when they may go
     */
    public Optional<String> judge(Island island, IslandDimension dimension, UUID player, boolean invited,
                                  boolean staff, long now) {
        if (!island.unlocked(dimension)) {
            return Optional.of("oneblock.go.locked");
        }
        if (staff) {
            return Optional.empty();
        }
        if (island.hold().filter(hold -> hold.keepsDown(now)).isPresent()) {
            return Optional.of("oneblock.go.held-down");
        }
        if (!island.isMember(player) && !invited) {
            return Optional.of("oneblock.go.not-invited");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "who may go to an island";
    }
}
