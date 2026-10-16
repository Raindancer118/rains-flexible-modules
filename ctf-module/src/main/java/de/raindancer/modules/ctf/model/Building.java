package de.raindancer.modules.ctf.model;

/** Whether players may change the map during a round. Whatever changes is put back after the round. */
public enum Building {
    /** The map stays exactly as it is. */
    NONE,
    /** Place anything; break only blocks placed this round. */
    OWN_BLOCKS,
    /** Place and break anything, except near flags and spawns. */
    FREE
}
