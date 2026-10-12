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

    @Override
    public String describe() {
        return "nothing in an arena world is changed or taken except by somebody building";
    }
}
