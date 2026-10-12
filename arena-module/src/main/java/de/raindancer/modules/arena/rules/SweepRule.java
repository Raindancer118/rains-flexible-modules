package de.raindancer.modules.arena.rules;

import java.util.Locale;
import java.util.Set;

/**
 * What a pit is cleared of after a fight, before each round, and once a minute while it stands empty: everything a fight
 * can leave behind — arrows and tridents, thrown potions and their clouds, experience, fireworks, fangs, stray mobs —
 * and nothing that is part of the place: players, the gladiators, and whatever the map was built with (stands,
 * paintings, frames).
 *
 * <p>By entity type name, so it is decided without a server. What is kept is the short list; anything new a game version
 * adds is cleared until somebody decides otherwise — the safe side, since the arena never places entities of its own
 * besides the gladiators.
 */
public final class SweepRule implements IArenaRule {

    private static final Set<String> KEPT = Set.of(
            "PLAYER", "MANNEQUIN", "ARMOR_STAND", "PAINTING", "ITEM_FRAME", "GLOW_ITEM_FRAME", "LEASH_KNOT",
            "MARKER", "TEXT_DISPLAY", "ITEM_DISPLAY", "BLOCK_DISPLAY", "INTERACTION");

    public boolean clears(String entityType) {
        return entityType != null && !KEPT.contains(entityType.toUpperCase(Locale.ROOT));
    }

    @Override
    public String describe() {
        return "a pit is cleared of whatever a fight left, never of what belongs to the place";
    }
}
