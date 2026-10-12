package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.ArenaAction;

/**
 * What may be done in an arena world: by somebody building, anything; by anybody else, nothing that changes the map
 * or moves an item out of reach of the arena. Nothing in between, because every exception is a way for a kit item to
 * leave — an ender chest in the second map is exactly one.
 */
public final class ProtectionRule implements IArenaRule {

    public boolean allows(ArenaAction action, boolean builder) {
        return builder;
    }

    /** What can tear a map apart and has no place in any fight: never let into an arena world, not even by /summon. */
    private static final java.util.Set<String> NEVER = java.util.Set.of("WITHER", "ENDER_DRAGON");

    public boolean mayExist(String entityType) {
        return entityType == null || !NEVER.contains(entityType.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public String describe() {
        return "nothing in an arena world is changed or taken except by somebody building";
    }
}
