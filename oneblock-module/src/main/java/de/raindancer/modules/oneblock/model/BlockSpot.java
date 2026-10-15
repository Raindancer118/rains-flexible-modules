package de.raindancer.modules.oneblock.model;

/** Where a one block stands, in its island's world. Migrated islands keep the spot BentoBox gave them. */
public record BlockSpot(int x, int y, int z) {

    public static final BlockSpot START = new BlockSpot(0, 64, 0);

    public boolean is(int x, int y, int z) {
        return this.x == x && this.y == y && this.z == z;
    }
}
