package de.raindancer.modules.moderation.util;

import org.bukkit.Material;

/** Read access to blocks by position — the world on a server, an array in a test. */
public interface BlockGrid {

    Material at(int x, int y, int z);

    /** Whether a block lets sight through: air, liquids, glass, leaves, torches. */
    boolean open(int x, int y, int z);
}
