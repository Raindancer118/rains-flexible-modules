package de.raindancer.modules.arena.model;

/** What somebody might try to do in an arena world besides fight. */
public enum ArenaAction {
    BREAK,
    PLACE,
    /** Doors, levers, buttons, beds, crafting tables, anvils — anything right-clicked that is not a container. */
    USE_BLOCK,
    CONTAINER,
    ENDER_CHEST,
    BUCKET,
    DROP,
    PICK_UP,
    FRAME,
    ARMOUR_STAND,
    TRAMPLE
}
