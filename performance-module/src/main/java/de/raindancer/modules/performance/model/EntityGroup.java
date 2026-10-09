package de.raindancer.modules.performance.model;

/** What kind of thing an entity is, for deciding what may be done about too many of them. */
public enum EntityGroup {
    /** Dropped items and experience orbs: transient, they despawn by themselves. */
    ITEM,
    /** Breedable animals: somebody's farm. */
    ANIMAL,
    /** Hostile mobs. */
    MONSTER,
    VILLAGER,
    /** Minecarts and boats. */
    VEHICLE,
    OTHER
}
